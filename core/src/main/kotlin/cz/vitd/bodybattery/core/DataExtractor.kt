package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SampleKind { HR, STRESS, HRV }

data class Sample(val epoch: Long, val kind: SampleKind, val value: Double)

data class SleepPeriod(val start: Long, val end: Long)

data class Activity(
    val start: Long,
    val durationSec: Long,
    val trainingLoad: Double?,
    val avgHr: Double?,
)

data class HealthData(
    val samples: List<Sample> = emptyList(),
    val sleeps: List<SleepPeriod> = emptyList(),
    val sleepMinutes: Double? = null,
    val sleepScore: Double? = null,
    val hrvNight: Double? = null,
    val hrvBaseline: Double? = null,
    val restingHr: Double? = null,
    val maxHr: Double? = null,
    val activities: List<Activity> = emptyList(),
) {
    fun describe(): String = buildString {
        val byKind = samples.groupBy { it.kind }
        append("Tep: ${byKind[SampleKind.HR]?.size ?: 0} záznamů, ")
        append("stres: ${byKind[SampleKind.STRESS]?.size ?: 0} záznamů, ")
        append("HRV: ${byKind[SampleKind.HRV]?.size ?: 0} záznamů\n")
        append("Spánek: ${sleeps.size} úseků")
        sleepMinutes?.let { append(", délka ${"%.0f".format(it)} min") }
        sleepScore?.let { append(", skóre ${"%.0f".format(it)}") }
        append("\n")
        hrvNight?.let { append("HRV noc: ${"%.0f".format(it)}") }
        hrvBaseline?.let { append(" (normál ${"%.0f".format(it)})") }
        restingHr?.let { append("  Klidový tep: ${"%.0f".format(it)}") }
        maxHr?.let { append("  Max tep: ${"%.0f".format(it)}") }
        append("\nAktivity: ${activities.size}")
    }
}

/**
 * Heuristic extractor: walks any JSON returned by the COROS MCP tools and pulls out
 * heart rate / stress / HRV samples, sleep, resting HR and activities. Field names are
 * matched loosely because the exact response format is not documented.
 */
class DataExtractor(private val zone: ZoneId) {
    private val samples = linkedMapOf<Pair<SampleKind, Long>, Sample>()
    private val sleeps = mutableListOf<SleepPeriod>()
    private val activities = linkedMapOf<Long, Activity>()
    private var sleepMinutes: Double? = null
    private var sleepScore: Double? = null
    private var hrvNight: Double? = null
    private var hrvBaseline: Double? = null
    private var restingHr: Double? = null
    private var maxHr: Double? = null

    /** Feed a full `tools/call` result object. */
    fun addToolResult(toolName: String, result: JSONObject) {
        val ctx = norm(toolName)
        result.optJSONObject("structuredContent")?.let { walk(it, ctx) }
        val content = result.optJSONArray("content") ?: return
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            val text = c.optString("text")
            if (text.isEmpty()) continue
            parseLoose(text)?.let { walk(it, ctx) }
        }
    }

    fun addJson(root: Any, context: String = "") = walk(root, norm(context))

    fun result(): HealthData = HealthData(
        samples = samples.values.sortedBy { it.epoch },
        sleeps = sleeps.distinct().sortedBy { it.start },
        sleepMinutes = sleepMinutes,
        sleepScore = sleepScore,
        hrvNight = hrvNight,
        hrvBaseline = hrvBaseline,
        restingHr = restingHr,
        maxHr = maxHr,
        activities = activities.values.sortedBy { it.start },
    )

    // ------------------------------------------------------------------ walking

    private fun walk(node: Any?, ctx: String) {
        when (node) {
            is JSONObject -> {
                inspectObject(node, ctx)
                for (k in node.keys()) walk(node.opt(k), ctx + "/" + norm(k))
            }
            is JSONArray -> {
                inspectArray(node, ctx)
                for (i in 0 until node.length()) walk(node.opt(i), ctx)
            }
            is String -> if (node.length > 2 && (node.trimStart().startsWith("{") || node.trimStart().startsWith("["))) {
                parseLoose(node)?.let { walk(it, ctx) }
            }
        }
    }

    private fun inspectObject(o: JSONObject, ctx: String) {
        val keys = o.keys().asSequence().toList()
        val nk = keys.associateBy { norm(it) } // normalized -> original
        val time = findTime(o, nk)
        val leaf = ctx.substringAfterLast('/')

        // --- point samples: {time, hr|stress|hrv|value}
        if (time != null) {
            var found = false
            for ((n, orig) in nk) {
                val v = num(o.opt(orig)) ?: continue
                val kind = sampleKind(n, ctx) ?: continue
                addSample(time, kind, v)
                found = true
            }
            if (found) return
        }

        // --- compact series: {startTime, interval, hrList:[...]}
        for ((n, orig) in nk) {
            val arr = o.optJSONArray(orig) ?: continue
            if (arr.length() < 2 || num(arr.opt(0)) == null) continue
            val kind = seriesKind(n, ctx) ?: continue
            val start = time ?: continue
            val intervalSec = findInterval(o, nk) ?: continue
            for (i in 0 until arr.length()) {
                val v = num(arr.opt(i)) ?: continue
                addSample(start + i * intervalSec, kind, v)
            }
        }

        // --- scalar daily metrics
        for ((n, orig) in nk) {
            val v = num(o.opt(orig)) ?: continue
            val full = "$ctx/$n"
            when {
                isRestingHrKey(n) && v in 30.0..120.0 -> restingHr = v
                (n == "maxhr" || n == "maxheartrate" || n == "hrmax") && !isActivityCtx(full) && v in 140.0..230.0 -> maxHr = v
                n.contains("hrv") && (n.contains("base") || n.contains("normal") || n.contains("7d") || n.contains("week")) && v in 5.0..250.0 -> hrvBaseline = v
                n.contains("hrv") && !n.contains("base") && !n.contains("normal") && time == null && v in 5.0..250.0 && hrvNight == null -> hrvNight = v
                n.contains("rmssd") && time == null && v in 5.0..250.0 && hrvNight == null -> hrvNight = v
                full.contains("sleep") && (n.contains("score") || n.contains("quality")) && v in 1.0..100.0 -> sleepScore = v
                full.contains("sleep") && isDurationKey(n) && !n.contains("deep") && !n.contains("light") && !n.contains("rem") && !n.contains("awake") && !n.contains("wake") -> {
                    val minutes = toMinutes(n, v)
                    if (minutes in 60.0..960.0) sleepMinutes = maxOf(sleepMinutes ?: 0.0, minutes)
                }
            }
        }

        // --- sleep period
        if (ctx.contains("sleep") || keys.any { norm(it).contains("sleep") }) {
            val s = findTimeBy(o, nk) { it.contains("start") || it.contains("bed") || it.contains("begin") || it.contains("fallasleep") }
            val e = findTimeBy(o, nk) { it.contains("end") || it.contains("wake") || it.contains("getup") }
            if (s != null && e != null && e > s && e - s in 3600L..16 * 3600L) {
                sleeps.add(SleepPeriod(s, e))
            }
        }

        // --- activity
        if (isActivityCtx(ctx) || isActivityCtx(leaf) || nk.keys.any { it == "sporttype" || it == "sportname" || it == "mode" && nk.keys.any { k -> k.contains("load") } }) {
            val start = findTimeBy(o, nk) { it.contains("start") || it.contains("begin") } ?: time
            val end = findTimeBy(o, nk) { it.contains("end") || it.contains("finish") }
            val durEntry = nk.entries.firstOrNull { isDurationKey(it.key) && !it.key.contains("moving") }
            var dur = durEntry?.let { e -> num(o.opt(e.value))?.let { toSeconds(e.key, it) } }
            if (dur == null && start != null && end != null && end > start) dur = end - start
            val load = nk.entries.firstOrNull { it.key == "trainingload" || it.key == "load" || it.key == "tl" || it.key == "trainload" || it.key == "exerciseload" }
                ?.let { num(o.opt(it.value)) }
            val avgHr = nk.entries.firstOrNull { it.key == "avghr" || it.key == "averagehr" || it.key == "avgheartrate" || it.key == "averageheartrate" }
                ?.let { num(o.opt(it.value)) }
            if (start != null && dur != null && dur in 120L..12 * 3600L && (load != null || avgHr != null || isActivityCtx(ctx))) {
                activities[start] = Activity(start, dur, load, avgHr)
            }
        }
    }

    /** Arrays of pairs: [[t, v], ...] under an hr/stress/hrv key. */
    private fun inspectArray(a: JSONArray, ctx: String) {
        if (a.length() < 2) return
        val first = a.optJSONArray(0) ?: return
        if (first.length() != 2) return
        val kind = seriesKind(ctx.substringAfterLast('/'), ctx) ?: return
        for (i in 0 until a.length()) {
            val pair = a.optJSONArray(i) ?: continue
            val t = parseTime(pair.opt(0)) ?: continue
            val v = num(pair.opt(1)) ?: continue
            addSample(t, kind, v)
        }
    }

    private fun addSample(t: Long, kind: SampleKind, v: Double) {
        val ok = when (kind) {
            SampleKind.HR -> v in 25.0..230.0
            SampleKind.STRESS -> v in 0.0..100.0
            SampleKind.HRV -> v in 3.0..300.0
        }
        if (ok) samples[kind to t] = Sample(t, kind, v)
    }

    // ------------------------------------------------------------------ classification

    private fun sampleKind(n: String, ctx: String): SampleKind? = when {
        n in setOf("hr", "heartrate", "bpm", "heartratevalue", "hrvalue", "heart") -> SampleKind.HR
        n in setOf("stress", "stresslevel", "stressvalue", "stressscore", "pressure") -> SampleKind.STRESS
        n in setOf("hrv", "rmssd", "hrvvalue") -> SampleKind.HRV
        n in setOf("value", "v", "val", "avg", "average") -> contextKind(ctx)
        else -> null
    }

    private fun seriesKind(n: String, ctx: String): SampleKind? {
        val s = n + " " + ctx.substringAfterLast('/')
        return when {
            s.contains("rest") || s.contains("max") || s.contains("min") -> null
            s.contains("stress") || s.contains("pressure") -> SampleKind.STRESS
            s.contains("hrv") || s.contains("rmssd") -> SampleKind.HRV
            s.contains("heart") || Regex("(^|[^a-z])hr").containsMatchIn(n) || n.startsWith("hr") -> SampleKind.HR
            n in setOf("values", "data", "list", "points", "samples") -> contextKind(ctx)
            else -> null
        }
    }

    private fun contextKind(ctx: String): SampleKind? {
        val last = ctx.split('/').takeLast(3).joinToString("/")
        return when {
            last.contains("stress") || last.contains("pressure") -> SampleKind.STRESS
            last.contains("hrv") -> SampleKind.HRV
            last.contains("heart") || Regex("(^|/)hr").containsMatchIn(last) -> SampleKind.HR
            else -> null
        }
    }

    private fun isRestingHrKey(n: String) =
        n == "rhr" || n.contains("restinghr") || n.contains("restheartrate") || n.contains("restinghear") || n == "resthr" || n.contains("resthr")

    private fun isActivityCtx(s: String) =
        s.contains("activit") || s.contains("workout") || s.contains("sport") || s.contains("exercise") || s.contains("training") && !s.contains("plan")

    private fun isDurationKey(n: String) =
        n.contains("duration") || n.contains("totaltime") || n.contains("sleeptime") || n.endsWith("minutes") || n.endsWith("seconds") || n.endsWith("mins") || n.endsWith("secs") || n == "total"

    private fun toMinutes(n: String, v: Double): Double = when {
        n.contains("sec") -> v / 60
        n.contains("hour") -> v * 60
        n.contains("min") -> v
        v > 1500 -> v / 60 // probably seconds
        v < 24 -> v * 60 // probably hours
        else -> v
    }

    private fun toSeconds(n: String, v: Double): Long = when {
        n.contains("ms") || n.contains("milli") -> (v / 1000).toLong()
        n.contains("min") -> (v * 60).toLong()
        n.contains("hour") -> (v * 3600).toLong()
        v > 100_000 -> (v / 1000).toLong()
        else -> v.toLong()
    }

    // ------------------------------------------------------------------ time parsing

    private fun findTime(o: JSONObject, nk: Map<String, String>): Long? {
        val priority = listOf("timestamp", "time", "ts", "t", "datetime", "starttime", "start", "begintime", "happentime", "timepoint", "date")
        for (p in priority) nk[p]?.let { k -> parseTime(o.opt(k))?.let { return it } }
        for ((n, orig) in nk) if (n.endsWith("time") || n.endsWith("timestamp")) parseTime(o.opt(orig))?.let { return it }
        return null
    }

    private fun findTimeBy(o: JSONObject, nk: Map<String, String>, pred: (String) -> Boolean): Long? {
        for ((n, orig) in nk) {
            if (!pred(n)) continue
            if (!(n.contains("time") || n.contains("date") || n.contains("ts") || n.endsWith("at") || n == "start" || n == "end")) continue
            parseTime(o.opt(orig))?.let { return it }
        }
        return null
    }

    private fun findInterval(o: JSONObject, nk: Map<String, String>): Long? {
        for ((n, orig) in nk) {
            if (!(n.contains("interval") || n.contains("step") || n.contains("period") || n.contains("frequency") || n == "gap")) continue
            val v = num(o.opt(orig)) ?: continue
            return when {
                n.contains("ms") || v >= 10_000 -> (v / 1000).toLong()
                n.contains("min") || v <= 30 -> (v * 60).toLong()
                else -> v.toLong()
            }.takeIf { it > 0 }
        }
        return null
    }

    internal fun parseTime(v: Any?): Long? {
        when (v) {
            is Number -> {
                val d = v.toDouble()
                return when {
                    d > 1e12 && d < 1e14 -> (d / 1000).toLong()
                    d > 1e9 && d < 1e11 -> d.toLong()
                    else -> null
                }
            }
            is String -> {
                val s = v.trim()
                if (s.isEmpty()) return null
                s.toDoubleOrNull()?.let { return parseTime(it) }
                runCatching { return Instant.parse(s).epochSecond }
                runCatching { return OffsetDateTime.parse(s).toEpochSecond() }
                for (f in LOCAL_FORMATS) {
                    runCatching { return LocalDateTime.parse(s, f).atZone(zone).toEpochSecond() }
                }
                return null
            }
            else -> return null
        }
    }

    internal fun parseDate(v: Any?): LocalDate? {
        val s = v?.toString()?.trim() ?: return null
        return runCatching { LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            ?: runCatching { LocalDate.parse(s) }.getOrNull()
    }

    companion object {
        private val LOCAL_FORMATS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm"),
        )

        fun norm(s: String) = s.lowercase().replace("_", "").replace("-", "").replace(" ", "")

        fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble().takeIf { !it.isNaN() }
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }

        /** Parses JSON from a text that may contain extra prose around it. */
        fun parseLoose(text: String): Any? {
            val t = text.trim()
            runCatching { return if (t.startsWith("[")) JSONArray(t) else JSONObject(t) }
            val o = t.indexOf('{')
            val a = t.indexOf('[')
            val start = listOf(o, a).filter { it >= 0 }.minOrNull() ?: return null
            val end = maxOf(t.lastIndexOf('}'), t.lastIndexOf(']'))
            if (end <= start) return null
            val sub = t.substring(start, end + 1)
            return runCatching { if (sub.startsWith("[")) JSONArray(sub) else JSONObject(sub) }.getOrNull()
        }
    }
}
