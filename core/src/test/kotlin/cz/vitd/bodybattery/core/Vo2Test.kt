package cz.vitd.bodybattery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Vo2Test {
    private val day = 86_400L

    private fun run(daysAgo: Int, paceMinPerKm: Double, km: Double, hr: Double) =
        RunSample(start = 1_800_000_000L - daysAgo * day, durationSec = (paceMinPerKm * km * 60).toLong(), distanceM = km * 1000, avgHr = hr)

    @Test
    fun singleRunGivesReasonableValue() {
        // 5:00/km = 200 m/min → VO2 43.5; %HRR = (160-60)/(190-60) = 0.77 → ≈ 55.5
        val v = Vo2Estimator.fromRun(run(0, 5.0, 8.0, 160.0), 60.0, 190.0)!!
        assertEquals(55.5, v, 0.5)
    }

    @Test
    fun unsuitableRunsAreRejected() {
        assertNull("too short", Vo2Estimator.fromRun(run(0, 5.0, 2.0, 160.0), 60.0, 190.0))
        assertNull("walking pace", Vo2Estimator.fromRun(run(0, 12.0, 3.0, 110.0), 60.0, 190.0))
        assertNull("HR too low for a reliable %HRR", Vo2Estimator.fromRun(run(0, 6.0, 5.0, 100.0), 60.0, 190.0))
        assertNull("HR at max", Vo2Estimator.fromRun(run(0, 4.0, 5.0, 189.0), 60.0, 190.0))
    }

    @Test
    fun corosValueWins() {
        val r = Vo2Estimator.estimate(48.0, listOf(run(1, 5.0, 8.0, 160.0)), 60.0, 190.0)
        assertEquals(Vo2Result.Value(48.0, Vo2Source.COROS, 0), r)
    }

    @Test
    fun medianIgnoresOutliers() {
        val runs = listOf(
            run(1, 5.0, 8.0, 160.0),
            run(3, 5.0, 8.0, 158.0),
            run(5, 5.1, 8.0, 160.0),
            run(7, 5.0, 8.0, 135.0), // HR strap glitch → estimate ~73, an outlier
        )
        val r = Vo2Estimator.estimate(null, runs, 60.0, 190.0) as Vo2Result.Value
        assertEquals(Vo2Source.ESTIMATE_RUNS, r.source)
        assertEquals(3, r.basedOn)
        assertTrue("value ${r.vo2}", r.vo2 in 53.0..57.0)
    }

    @Test
    fun uthFallbackWithoutRuns() {
        val r = Vo2Estimator.estimate(null, emptyList(), 55.0, 185.0) as Vo2Result.Value
        assertEquals(Vo2Source.ESTIMATE_RESTING_HR, r.source)
        assertEquals(15.3 * 185 / 55, r.vo2, 0.01)
    }

    @Test
    fun missingDataIsReportedNotInvented() {
        val r = Vo2Estimator.estimate(null, emptyList(), null, null)
        assertEquals(Vo2Result.Insufficient(setOf(Vo2Missing.RUNS, Vo2Missing.RESTING_HR, Vo2Missing.MAX_HR)), r)
        val r2 = Vo2Estimator.estimate(null, listOf(run(1, 5.0, 8.0, 160.0)), 60.0, null)
        assertEquals(Vo2Result.Insufficient(setOf(Vo2Missing.MAX_HR)), r2)
    }

    @Test
    fun implausibleCorosValueIgnored() {
        val r = Vo2Estimator.estimate(3.0, emptyList(), null, null)
        assertTrue(r is Vo2Result.Insufficient)
    }

    @Test
    fun maxHrFromAgeOrObserved() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(208 - 0.7 * 36, Vo2Estimator.maxHr(LocalDate.of(1990, 5, 1), today, null)!!, 0.01)
        assertEquals(195.0, Vo2Estimator.maxHr(LocalDate.of(1990, 5, 1), today, 195.0)!!, 0.01)
        assertNull(Vo2Estimator.maxHr(null, today, null))
    }

    @Test
    fun monthChange() {
        val today = LocalDate.of(2026, 11, 9)
        val hist = listOf(today.minusDays(60) to 40.0, today.minusDays(31) to 50.0, today.minusDays(3) to 51.0)
        assertEquals(2.0, Vo2Estimator.monthChange(hist, today, 52.0)!!, 0.001)
        assertNull(Vo2Estimator.monthChange(listOf(today.minusDays(3) to 51.0), today, 52.0))
    }
}
