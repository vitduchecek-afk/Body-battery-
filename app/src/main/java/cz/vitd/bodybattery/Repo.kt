package cz.vitd.bodybattery

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cz.vitd.bodybattery.core.BatteryResult
import cz.vitd.bodybattery.core.CorosClient
import cz.vitd.bodybattery.core.CorosException
import cz.vitd.bodybattery.core.KeyValueStore
import cz.vitd.bodybattery.core.Problem
import cz.vitd.bodybattery.core.RefreshOutcome
import cz.vitd.bodybattery.core.Refresher
import cz.vitd.bodybattery.core.Vo2Missing
import cz.vitd.bodybattery.core.Vo2Result
import cz.vitd.bodybattery.core.Vo2Source
import cz.vitd.bodybattery.core.asSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("bb", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** App-wide state shared by the activity, the widget, the notification and the background worker. */
object Repo {
    private const val K_RESULT = "result"
    private const val K_HISTORY = "history.v3" // bump whenever the model changes (old levels are not comparable)
    private const val K_DIAG = "diag.v2"
    private const val K_ERROR = "error.v2"
    private const val K_VO2 = "vo2"
    private const val K_VO2_HISTORY = "vo2.history"
    private const val K_NOTIF_ENABLED = "notif.enabled"
    private const val K_NOTIF_TIME = "notif.time"
    private const val RAW_LIMIT = 6000
    private val mutex = Mutex()

    private val _version = MutableStateFlow(0)
    /** Bumped whenever stored state changes, so the UI can re-render. */
    val version: StateFlow<Int> = _version

    fun store(ctx: Context) = PrefsStore(ctx.applicationContext)
    fun client(ctx: Context) = CorosClient(store(ctx))

    fun lastResult(ctx: Context): BatteryResult? =
        store(ctx).get(K_RESULT)?.let { runCatching { BatteryResult.fromJson(JSONObject(it)) }.getOrNull() }

    /** For tests and previews. */
    fun storeResult(ctx: Context, r: BatteryResult) {
        store(ctx).put(K_RESULT, r.toJson().toString())
        _version.value++
    }

    fun diagnosticsJson(ctx: Context): JSONObject? = store(ctx).get(K_DIAG)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Localized error message, or null. */
    fun lastError(ctx: Context): String? = store(ctx).get(K_ERROR)?.let {
        runCatching { JSONObject(it) }.getOrNull()?.let { o -> Texts.error(ctx, o.optString("code"), o.optString("detail").ifEmpty { null }) }
    }

    fun setError(ctx: Context, code: String?, detail: String? = null) {
        store(ctx).put(K_ERROR, code?.let { JSONObject().put("code", it).put("detail", detail ?: "").toString() })
        _version.value++
    }

    fun notifyChanged() { _version.value++ }

    // ------------------------------------------------------------------ VO2 max

    fun vo2(ctx: Context): Vo2Result? = store(ctx).get(K_VO2)?.let { s ->
        runCatching {
            val o = JSONObject(s)
            if (o.has("vo2")) Vo2Result.Value(o.getDouble("vo2"), Vo2Source.valueOf(o.getString("source")), o.optInt("basedOn"))
            else {
                val m = o.optJSONArray("missing") ?: JSONArray()
                Vo2Result.Insufficient((0 until m.length()).map { Vo2Missing.valueOf(m.getString(it)) }.toSet())
            }
        }.getOrNull()
    }

    fun vo2History(ctx: Context): List<Pair<LocalDate, Double>> {
        val arr = store(ctx).get(K_VO2_HISTORY)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONArray(i)?.let { runCatching { LocalDate.parse(it.getString(0)) to it.getDouble(1) }.getOrNull() }
        }
    }

    private fun saveVo2(st: KeyValueStore, ctx: Context, r: Vo2Result) {
        val o = JSONObject()
        when (r) {
            is Vo2Result.Value -> o.put("vo2", r.vo2).put("source", r.source.name).put("basedOn", r.basedOn)
            is Vo2Result.Insufficient -> o.put("missing", JSONArray(r.missing.map { it.name }))
        }
        st.put(K_VO2, o.toString())
        if (r is Vo2Result.Value) {
            val today = LocalDate.now()
            // one value per day (latest wins), keep ~100 days
            val hist = vo2History(ctx).filter { it.first != today && it.first.isAfter(today.minusDays(100)) } + (today to r.vo2)
            st.put(K_VO2_HISTORY, JSONArray().also { a -> hist.sortedBy { it.first }.forEach { a.put(JSONArray().put(it.first.toString()).put(it.second)) } }.toString())
        }
    }

    // ------------------------------------------------------------------ notification settings

    fun notificationEnabled(ctx: Context): Boolean = store(ctx).get(K_NOTIF_ENABLED) == "1"
    fun setNotificationEnabled(ctx: Context, on: Boolean) = store(ctx).put(K_NOTIF_ENABLED, if (on) "1" else null)

    /** (hour, minute), default 8:00. */
    fun notificationTime(ctx: Context): Pair<Int, Int> {
        val v = store(ctx).get(K_NOTIF_TIME)?.split(':')?.mapNotNull { it.toIntOrNull() }
        return if (v != null && v.size == 2) v[0] to v[1] else 8 to 0
    }

    fun setNotificationTime(ctx: Context, hour: Int, minute: Int) = store(ctx).put(K_NOTIF_TIME, "$hour:$minute")

    // ------------------------------------------------------------------ refresh

    /** Blocking network work – call from Dispatchers.IO. */
    suspend fun refresh(ctx: Context): BatteryResult? = mutex.withLock {
        val app = ctx.applicationContext
        val st = store(app)
        val client = CorosClient(st)
        if (!client.isLoggedIn()) {
            setError(app, Problem.NOT_CONNECTED.name)
            return null
        }
        try {
            val history = loadHistory(st)
            val outcome = Refresher.refresh(client.asSource(), ZonedDateTime.now()) { t ->
                // level stored closest before time t (within 8 h)
                history.filter { it.first <= t && t - it.first <= 8 * 3600 }.maxByOrNull { it.first }?.second
            }
            st.put(K_RESULT, outcome.result.toJson().toString())
            saveVo2(st, app, outcome.vo2)
            st.put(K_DIAG, diagJson(app, outcome).toString())
            st.put(K_ERROR, null)
            saveHistory(st, history, outcome.result)
            _version.value++
            BatteryWidget.updateAll(app)
            return outcome.result
        } catch (e: Throwable) {
            st.put(K_DIAG, JSONObject().put("time", System.currentTimeMillis() / 1000).put("version", appVersion(app))
                .put("error", e.stackTraceToString().take(4000)).toString())
            when (e) {
                is CorosException -> setError(app, e.problem.name, e.detail)
                is IOException -> setError(app, Texts.ERR_NETWORK)
                else -> setError(app, Texts.ERR_UNKNOWN, e.message ?: e.javaClass.simpleName)
            }
            BatteryWidget.updateAll(app)
            return null
        }
    }

    private fun diagJson(ctx: Context, o: RefreshOutcome): JSONObject {
        val d = o.data
        return JSONObject()
            .put("time", o.result.computedAt)
            .put("version", appVersion(ctx))
            .put("hr", d.samples.count { it.kind.name == "HR" })
            .put("stress", d.samples.count { it.kind.name == "STRESS" })
            .put("hrv", d.samples.count { it.kind.name == "HRV" })
            .put("sleeps", d.sleeps.size)
            .put("tools", JSONArray().also { a -> o.tools.forEach { t -> a.put(JSONArray().put(t.name).put(t.description.take(200)).put(t.params?.take(400) ?: "")) } })
            .put("calls", JSONArray().also { a ->
                o.calls.forEach { c ->
                    val raw = c.text
                    val cut = if (raw.length <= RAW_LIMIT) raw else raw.take(RAW_LIMIT / 2) + "\u0000" + raw.length + "\u0000" + raw.takeLast(RAW_LIMIT / 2)
                    a.put(JSONArray().put(c.tool).put(c.args).put(cut).put(c.isError))
                }
            })
    }

    private fun loadHistory(st: KeyValueStore): List<Pair<Long, Int>> {
        val arr = st.get(K_HISTORY)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { it.getLong(0) to it.getInt(1) } }
    }

    private fun saveHistory(st: KeyValueStore, old: List<Pair<Long, Int>>, r: BatteryResult) {
        val cutoff = r.computedAt - 3 * 86400
        // Keep older history, replace everything from this computation's start with the fresh timeline.
        val merged = (old.filter { it.first >= cutoff && it.first < r.sleepStart } + r.points).sortedBy { it.first }
        val arr = JSONArray()
        merged.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        st.put(K_HISTORY, arr.toString())
    }

    fun appVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    fun schedule(ctx: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("bb-refresh", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    fun refreshSoon(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("bb-refresh-now", ExistingWorkPolicy.KEEP, req)
    }

    fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BatteryWidget::class.java))
}
