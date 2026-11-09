package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import kotlin.math.abs
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
    /** (epochSeconds, level) every 15 minutes from last night's sleep start to now. */
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
 * Body-battery approximation (Garmin/Firstbeat-like idea, own formulas), driven mainly by the
 * COROS stress value (0–100) sampled every 5 minutes:
 *  - asleep: charges, faster with lower stress (COROS sleep stress ~15–25 → ~6–10 points/hour),
 *    scaled by last night's HRV relative to baseline
 *  - awake, stress ≤ 25 (rest): slow charge
 *  - awake, stress > 25: drain growing with stress (40 → ~3/h, 60 → ~5/h, 80 → ~8/h)
 *  - workouts: drain by training load (or duration × intensity)
 *  - no data: slow linear drain while awake
 */
object BatteryModel {
    private const val STEP = 300L // 5 min
    private const val DEFAULT_START = 30.0
    private const val DEFAULT_AWAKE_DRAIN_PER_H = 3.0

    fun compute(data: HealthData, now: ZonedDateTime, previousLevelAt: (Long) -> Int?): BatteryResult {
        val nowS = now.toEpochSecond()
        val zone = now.zone
        val notes = mutableListOf<String>()
        val today = now.toLocalDate()

        // ---- last night's sleep
        val windowStart = today.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
        val sleeps = data.sleeps.toMutableList()
        var lastSleep = sleeps.filter { it.end in windowStart..nowS }.maxByOrNull { it.end }
        if (lastSleep == null) {
            val w = today.atTime(7, 0).atZone(zone).toEpochSecond().coerceAtMost(nowS)
            val minutes = data.sleepMinutes ?: 480.0
            notes += if (data.sleepMinutes != null) "Čas probuzení neznámý – počítám s 7:00" else "Chybí data o spánku – počítám se spánkem do 7:00"
            lastSleep = SleepPeriod(w - (minutes * 60).toLong(), w)
            sleeps += lastSleep
        }
        val sleepStart = lastSleep.start
        val wake = lastSleep.end

        val hrSamples = data.samples.filter { it.kind == SampleKind.HR }
        val stressSamples = data.samples.filter { it.kind == SampleKind.STRESS }
        val rhr = data.restingHr ?: percentile(hrSamples.map { it.value }, 0.05) ?: 60.0
        val maxHr = data.maxHr ?: 190.0
        val todaysStress = stressSamples.count { it.epoch >= sleepStart }
        val detailed = todaysStress >= 12 || hrSamples.count { it.epoch >= sleepStart } >= 12
        if (!detailed) notes += "Málo průběžných dat (stres) – průběh je odhad"

        fun stressAt(t: Long): Double? {
            nearest(stressSamples, t, 20 * 60)?.let { return it }
            val hr = nearest(hrSamples, t, 20 * 60) ?: return null
            return ((hr - rhr - 5) / (0.45 * (maxHr - rhr)) * 100).coerceIn(0.0, 100.0)
        }

        // ---- sleep quality factor (HRV vs baseline, sleep score when no samples)
        var hrvFactor = 1.0
        if (data.hrvNight != null && data.hrvBaseline != null && data.hrvBaseline > 0) {
            hrvFactor = (data.hrvNight / data.hrvBaseline).coerceIn(0.8, 1.15)
        }
        val scoreFactor = data.sleepScore?.let { (it / 80.0).coerceIn(0.6, 1.2) } ?: 1.0

        val acts = data.activities.map { a ->
            val total = when {
                a.trainingLoad != null && a.trainingLoad > 0 -> (a.trainingLoad * 0.35).coerceIn(4.0, 50.0)
                a.avgHr != null -> {
                    val hrr = ((a.avgHr - rhr) / (maxHr - rhr)).coerceIn(0.1, 1.0)
                    (a.durationSec / 60.0) * (0.08 + 0.45 * hrr.pow(2))
                }
                else -> a.durationSec / 60.0 * 0.25
            }
            Triple(a.start, a.start + a.durationSec, total / max(1.0, a.durationSec / STEP.toDouble()))
        }

        fun delta(t: Long, dt: Long): Double {
            val h = dt / 3600.0
            val act = acts.firstOrNull { t >= it.first && t < it.second }
            if (act != null) return -act.third * (dt.toDouble() / STEP)
            val asleep = sleeps.any { t >= it.start && t < it.end }
            val s = stressAt(t)
            return if (asleep) {
                if (s != null) max(0.0, 0.4 * (40 - s)) * h * hrvFactor
                else 7.5 * scoreFactor * hrvFactor * h
            } else when {
                s == null -> -DEFAULT_AWAKE_DRAIN_PER_H * h
                s <= 25 -> (25 - s) * 0.06 * h
                else -> -(1.0 + (s - 25) * 0.12) * h
            }
        }

        fun simulate(from: Long, to: Long, start: Double, onStep: (Long, Double, Double) -> Unit = { _, _, _ -> }): Double {
            var level = start
            var t = from
            while (t < to) {
                val dt = min(STEP, to - t)
                val before = level
                level = (level + delta(t, dt)).coerceIn(1.0, 100.0)
                t += dt
                onStep(t, before, level)
            }
            return level
        }

        // ---- level when last night's sleep began
        val startLevel: Double = previousLevelAt(sleepStart)?.toDouble() ?: run {
            // No stored history: replay the previous day from the sleep before (if we have its data).
            val earlier = sleeps.filter { it.end <= sleepStart - 6 * 3600 && it.start >= sleepStart - 40 * 3600 }.maxByOrNull { it.end }
            if (earlier != null && stressSamples.any { it.epoch in earlier.end..sleepStart }) {
                notes += "Stav před spánkem dopočítán z předchozího dne"
                simulate(earlier.start, sleepStart, DEFAULT_START)
            } else {
                notes += "Neznám stav před spánkem – začínám na ${DEFAULT_START.toInt()}"
                DEFAULT_START
            }
        }

        // ---- main timeline: last night's sleep start → now
        val points = mutableListOf(sleepStart to startLevel.toInt())
        var charged = 0.0
        var drained = 0.0
        var wakeLevel = startLevel
        var lastPoint = sleepStart
        val level = simulate(sleepStart, nowS, startLevel) { t, before, after ->
            val d = after - before
            if (d > 0) charged += d else drained -= d
            if (t <= wake) wakeLevel = after
            if (t - lastPoint >= 900 || t >= nowS) {
                points += t to after.toInt()
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
        val best = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1)).minByOrNull { abs(it.epoch - t) } ?: return null
        return if (abs(best.epoch - t) <= maxGap) best.value else null
    }

    private fun percentile(values: List<Double>, p: Double): Double? {
        if (values.size < 10) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}
