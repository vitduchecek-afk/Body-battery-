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
import cz.vitd.bodybattery.core.KeyValueStore
import cz.vitd.bodybattery.core.Refresher
import cz.vitd.bodybattery.core.asSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("bb", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** App-wide state shared by the activity, the widget and the background worker. */
object Repo {
    private const val K_RESULT = "result"
    private const val K_HISTORY = "history"
    private const val K_DIAG = "diag"
    private const val K_ERROR = "error"
    private val mutex = Mutex()

    private val _version = MutableStateFlow(0)
    /** Bumped whenever stored state changes, so the UI can re-render. */
    val version: StateFlow<Int> = _version

    fun store(ctx: Context) = PrefsStore(ctx.applicationContext)
    fun client(ctx: Context) = CorosClient(store(ctx))

    fun lastResult(ctx: Context): BatteryResult? =
        store(ctx).get(K_RESULT)?.let { runCatching { BatteryResult.fromJson(org.json.JSONObject(it)) }.getOrNull() }

    fun diagnostics(ctx: Context): String = store(ctx).get(K_DIAG) ?: "Zatím žádná data."
    fun lastError(ctx: Context): String? = store(ctx).get(K_ERROR)

    fun setError(ctx: Context, msg: String?) {
        store(ctx).put(K_ERROR, msg)
        _version.value++
    }

    fun notifyChanged() { _version.value++ }

    /** Blocking – call from a background thread / Dispatchers.IO. */
    suspend fun refresh(ctx: Context): BatteryResult? = mutex.withLock {
        val app = ctx.applicationContext
        val st = store(app)
        val client = CorosClient(st)
        if (!client.isLoggedIn()) {
            setError(app, "Nejste připojen ke Korosu")
            return null
        }
        try {
            val history = loadHistory(st)
            val outcome = Refresher.refresh(client.asSource(), ZonedDateTime.now()) { t ->
                // level stored closest before time t (within 8 h)
                history.filter { it.first <= t && t - it.first <= 8 * 3600 }.maxByOrNull { it.first }?.second
            }
            st.put(K_RESULT, outcome.result.toJson().toString())
            st.put(K_DIAG, outcome.diagnostics)
            st.put(K_ERROR, null)
            saveHistory(st, history, outcome.result)
            _version.value++
            BatteryWidget.updateAll(app)
            return outcome.result
        } catch (e: Exception) {
            setError(app, e.message ?: e.toString())
            BatteryWidget.updateAll(app)
            return null
        }
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
