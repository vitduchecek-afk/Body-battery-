package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class BatteryResult(
    val level: Int,
    val wakeLevel: Int,
    val charged: Int,
    val drained: Int,
    val sleepStart: Long,
    val wakeTime: Long,
    val computedAt: Long,
    /** (epochSeconds, level) every 15 minutes from sleep start to now. */
    val points: List<Pair<Long, Int>>,
    val notes: List<String>,
    val detailed: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("level", level).put("wakeLevel", wakeLevel).put("charged", charged).put("drained", drained)
        .put("sleepStart", sleepStart).put("wakeTime", wakeTime).put("computedAt", computedAt)
        .put("detailed", detailed)
        .put("notes", JSONArray(notes))
        .put("points", JSONArray().also { a -> points.forEach { a.put(JSONArray().put(it.first).put(it.second)) } })

    companion object {
        fun fromJson(o: JSONObject): BatteryResult {
            val pts = o.optJSONArray("points") ?: JSONArray()
            val notes = o.optJSONArray("notes") ?: JSONArray()
            return BatteryResult(
                level = o.getInt("level"), wakeLevel = o.optInt("wakeLevel"), charged = o.optInt("charged"),
                drained = o.optInt("drained"), sleepStart = o.optLong("sleepStart"), wakeTime = o.optLong("wakeTime"),
                computedAt = o.optLong("computedAt"),
                points = (0 until pts.length()).map { pts.getJSONArray(it).let { p -> p.getLong(0) to p.getInt(1) } },
                notes = (0 until notes.length()).map { notes.getString(it) },
                detailed = o.optBoolean("detailed"),
            )
        }
    }
}

/**
 * Body-battery approximation (Garmin/Firstbeat-like idea, own formulas):
 *  - during sleep the battery charges, faster when stress / heart rate is low and HRV is good
 *  - when awake, low stress (rest) charges slightly, higher stress drains, activities drain by load
 *  - with no data at all the battery drains slowly over the day
 */
object BatteryModel {
    private const val STEP = 300L // 5 min
    private const val DEFAULT_START = 30.0
    private const val DEFAULT_AWAKE_DRAIN_PER_H = 3.0

    /**
     * @param previousLevelAt returns the level stored earlier for a given time (used as the
     *        starting point before last night's sleep), or null if unknown.
     */
    fun compute(data: HealthData, now: ZonedDateTime, previousLevelAt: (Long) -> Int?): BatteryResult {
        val nowS = now.toEpochSecond()
        val zone = now.zone
        val notes = mutableListOf<String>()
        val today = now.toLocalDate()

        // ---- last night's sleep
        val windowStart = today.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
        val sleep = data.sleeps.filter { it.end in windowStart..nowS }.maxByOrNull { it.end }
        val (sleepStart, wake) = when {
            sleep != null -> sleep.start to sleep.end
            data.sleepMinutes != null -> {
                val w = today.atTime(7, 0).atZone(zone).toEpochSecond().coerceAtMost(nowS)
                notes += "Čas probuzení neznámý – počítám s 7:00"
                (w - (data.sleepMinutes * 60).toLong()) to w
            }
            else -> {
                notes += "Chybí data o spánku – počítám se spánkem 23:00–7:00"
                today.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond() to
                    today.atTime(7, 0).atZone(zone).toEpochSecond().coerceAtMost(nowS)
            }
        }

        val rhr = data.restingHr ?: percentile(data.samples.filter { it.kind == SampleKind.HR }.map { it.value }, 0.05) ?: 60.0
        val maxHr = data.maxHr ?: 190.0
        val stressSamples = data.samples.filter { it.kind == SampleKind.STRESS }
        val hrSamples = data.samples.filter { it.kind == SampleKind.HR }
        val detailed = stressSamples.size >= 12 || hrSamples.size >= 12
        if (!detailed) notes += "Málo průběžných dat (tep/stres) – průběh dne je odhad"

        fun stressAt(t: Long): Double? {
            nearest(stressSamples, t, 20 * 60)?.let { return it }
            val hr = nearest(hrSamples, t, 20 * 60) ?: return null
            return ((hr - rhr - 5) / (0.45 * (maxHr - rhr)) * 100).coerceIn(0.0, 100.0)
        }

        // ---- sleep quality multiplier for nights without detailed samples
        var quality = 1.0
        data.sleepScore?.let { quality *= (it / 80.0).coerceIn(0.6, 1.2) }
        if (data.hrvNight != null && data.hrvBaseline != null && data.hrvBaseline > 0) {
            quality *= (data.hrvNight / data.hrvBaseline).coerceIn(0.75, 1.2)
        }

        var level = previousLevelAt(sleepStart)?.toDouble() ?: run {
            notes += "Neznám stav před spánkem – začínám na ${DEFAULT_START.toInt()}"
            DEFAULT_START
        }
        val points = mutableListOf<Pair<Long, Int>>()
        var charged = 0.0
        var drained = 0.0
        var wakeLevel = level

        val acts = data.activities.map { a ->
            val end = a.start + a.durationSec
            val total = when {
                a.trainingLoad != null -> (a.trainingLoad * 0.35).coerceIn(4.0, 50.0)
                a.avgHr != null -> {
                    val hrr = ((a.avgHr - rhr) / (maxHr - rhr)).coerceIn(0.1, 1.0)
                    (a.durationSec / 60.0) * (0.08 + 0.45 * hrr.pow(2))
                }
                else -> a.durationSec / 60.0 * 0.25
            }
            Triple(a.start, end, total / max(1.0, a.durationSec / STEP.toDouble()))
        }

        var t = sleepStart
        var lastPoint = sleepStart
        points += sleepStart to level.toInt()
        while (t < nowS) {
            val dt = min(STEP, nowS - t)
            val h = dt / 3600.0
            val asleep = t < wake
            val act = acts.firstOrNull { t >= it.first && t < it.second }
            val delta: Double = when {
                act != null -> -act.third * (dt.toDouble() / STEP)
                asleep -> {
                    val s = stressAt(t)
                    if (s != null) max(0.0, 0.32 * (35 - s)) * h * (if (data.hrvNight != null && data.hrvBaseline != null) quality else 1.0)
                    else 7.5 * quality * h
                }
                else -> {
                    val s = stressAt(t)
                    when {
                        s == null -> -DEFAULT_AWAKE_DRAIN_PER_H * h
                        s <= 25 -> (25 - s) * 0.06 * h
                        else -> -(1.0 + (s - 25) * 0.12) * h
                    }
                }
            }
            val before = level
            level = (level + delta).coerceIn(1.0, 100.0)
            val real = level - before
            if (real > 0) charged += real else drained -= real
            t += dt
            if (asleep && t >= wake) wakeLevel = level
            if (t - lastPoint >= 900 || t >= nowS) {
                points += t to level.toInt()
                lastPoint = t
            }
        }
        if (wake > nowS) wakeLevel = level

        return BatteryResult(
            level = level.toInt().coerceIn(1, 100),
            wakeLevel = wakeLevel.toInt(),
            charged = charged.toInt(),
            drained = drained.toInt(),
            sleepStart = sleepStart,
            wakeTime = wake,
            computedAt = nowS,
            points = points,
            notes = notes,
            detailed = detailed,
        )
    }

    private fun nearest(samples: List<Sample>, t: Long, maxGap: Long): Double? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].epoch < t) lo = mid + 1 else hi = mid
        }
        val cands = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1))
        val best = cands.minByOrNull { kotlin.math.abs(it.epoch - t) } ?: return null
        return if (kotlin.math.abs(best.epoch - t) <= maxGap) best.value else null
    }

    private fun percentile(values: List<Double>, p: Double): Double? {
        if (values.size < 10) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}
