package cz.vitd.bodybattery.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One running workout summary (whole-activity averages). */
data class RunSample(
    val start: Long,
    val durationSec: Long,
    val distanceM: Double,
    val avgHr: Double,
)

enum class Vo2Source { COROS, ESTIMATE_RUNS, ESTIMATE_RESTING_HR }

enum class Vo2Missing { RUNS, RESTING_HR, MAX_HR }

sealed class Vo2Result {
    /** [basedOn] = number of runs used (0 for COROS / resting-HR values). */
    data class Value(val vo2: Double, val source: Vo2Source, val basedOn: Int) : Vo2Result()
    data class Insufficient(val missing: Set<Vo2Missing>) : Vo2Result()
}

/**
 * VO2 max: the value reported by COROS when available, otherwise an own estimate.
 *
 * Estimate from runs (per activity):
 *   speed [m/min] = distance / duration
 *   VO2 at that pace (ACSM running, flat): 0.2 × speed + 3.5
 *   intensity = %HRR = (avgHR − restHR) / (maxHR − restHR)      (≈ %VO2 reserve, Swain 1997)
 *   VO2max ≈ 3.5 + (VO2 − 3.5) / %HRR
 * Only runs ≥ 15 min, 100–400 m/min, with %HRR 0.5–0.95 are used. The newest ≤ 7 values
 * are combined with a median; values more than 15 % away from it are dropped and the median
 * is taken again.
 *
 * Fallback without suitable runs (Uth et al. 2004): 15.3 × maxHR / restHR.
 * maxHR = max(Tanaka 208 − 0.7 × age, highest observed workout max HR).
 */
object Vo2Estimator {
    const val MIN_RUN_SEC = 15 * 60
    const val MIN_SPEED = 100.0
    const val MAX_SPEED = 400.0
    const val MIN_HRR = 0.5
    const val MAX_HRR = 0.95
    const val WINDOW_RUNS = 7
    const val OUTLIER_FRACTION = 0.15
    private val PLAUSIBLE = 15.0..95.0

    fun tanakaMaxHr(age: Int): Double = 208 - 0.7 * age

    fun maxHr(birthDate: LocalDate?, today: LocalDate, observedMax: Double?): Double? {
        val byAge = birthDate?.let { ChronoUnit.YEARS.between(it, today).toInt() }?.takeIf { it in 10..100 }?.let { tanakaMaxHr(it) }
        val observed = observedMax?.takeIf { it in 120.0..230.0 }
        return listOfNotNull(byAge, observed).maxOrNull()
    }

    /** VO2max estimate from one run, or null when the run is not suitable. */
    fun fromRun(run: RunSample, restHr: Double, maxHr: Double): Double? {
        if (run.durationSec < MIN_RUN_SEC || run.distanceM <= 0 || maxHr <= restHr) return null
        val speed = run.distanceM / (run.durationSec / 60.0)
        if (speed !in MIN_SPEED..MAX_SPEED) return null
        val hrr = (run.avgHr - restHr) / (maxHr - restHr)
        if (hrr !in MIN_HRR..MAX_HRR) return null
        val vo2 = 0.2 * speed + 3.5
        return (3.5 + (vo2 - 3.5) / hrr).takeIf { it in PLAUSIBLE }
    }

    fun uth(maxHr: Double, restHr: Double): Double = 15.3 * maxHr / restHr

    fun estimate(
        corosVo2: Double?,
        runs: List<RunSample>,
        restHr: Double?,
        maxHr: Double?,
    ): Vo2Result {
        if (corosVo2 != null && corosVo2 in PLAUSIBLE) return Vo2Result.Value(corosVo2, Vo2Source.COROS, 0)

        val missing = mutableSetOf<Vo2Missing>()
        if (restHr == null) missing += Vo2Missing.RESTING_HR
        if (maxHr == null) missing += Vo2Missing.MAX_HR
        if (restHr == null || maxHr == null) {
            if (runs.isEmpty()) missing += Vo2Missing.RUNS
            return Vo2Result.Insufficient(missing)
        }

        val values = runs.sortedByDescending { it.start }
            .mapNotNull { fromRun(it, restHr, maxHr) }
            .take(WINDOW_RUNS)
        if (values.isNotEmpty()) {
            val m = median(values)
            val kept = values.filter { abs(it - m) <= m * OUTLIER_FRACTION }
            return Vo2Result.Value(median(kept.ifEmpty { values }), Vo2Source.ESTIMATE_RUNS, kept.size)
        }
        val u = uth(maxHr, restHr)
        return if (u in PLAUSIBLE) Vo2Result.Value(u, Vo2Source.ESTIMATE_RESTING_HR, 0)
        else Vo2Result.Insufficient(setOf(Vo2Missing.RUNS))
    }

    /**
     * Change against roughly one month ago: the stored value closest to 30 days back
     * among entries 25–45 days old. Null when there is no such entry.
     */
    fun monthChange(history: List<Pair<LocalDate, Double>>, today: LocalDate, current: Double): Double? {
        val old = history
            .filter { ChronoUnit.DAYS.between(it.first, today) in 25..45 }
            .minByOrNull { abs(ChronoUnit.DAYS.between(it.first, today) - 30) } ?: return null
        return current - old.second
    }

    internal fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
