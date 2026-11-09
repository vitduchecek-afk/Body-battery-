package cz.vitd.bodybattery.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Profile shaped like the real COROS data from 2026-11-08/09. Targets Garmin-like values. */
class CalibrationTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toEpochSecond()

    @Test
    fun realisticTwoDays() {
        val now = ZonedDateTime.of(2026, 11, 9, 17, 28, 0, 0, zone)
        val sleep1 = SleepPeriod(at(8, 0, 38), at(8, 7, 51))
        val sleep2 = SleepPeriod(at(8, 23, 38), at(9, 6, 58))
        val samples = mutableListOf<Sample>()
        var t = at(8, 0, 4)
        var i = 0
        while (t < now.toEpochSecond()) {
            val wobble = listOf(-6, 3, -2, 5, 0, -4, 6, -1)[i++ % 8]
            val s = when {
                t < at(8, 0, 38) -> 55
                t < at(8, 3, 0) -> 40          // restless start of night 1
                t < sleep1.end -> 20
                t < sleep2.start -> 44         // day 1 average 44
                t < sleep2.end -> 22
                t < at(9, 15, 0) -> 35
                else -> 65                     // busy afternoon
            } + wobble
            samples += Sample(t, SampleKind.STRESS, s.toDouble())
            t += 300
        }
        val data = HealthData(
            samples = samples, sleeps = listOf(sleep1, sleep2), sleepMinutes = 428.0, sleepScore = 83.0,
            hrvNight = 39.0, hrvBaseline = 44.0, restingHr = 66.0,
        )
        val r = BatteryModel.compute(data, now) { null }
        val startLevel = r.points.first().second
        println("CALIBRATION start=$startLevel wake=${r.wakeLevel} now=${r.level} +${r.charged} -${r.drained}")
        assertTrue("evening level before sleep $startLevel", startLevel in 10..40)
        assertTrue("wake ${r.wakeLevel}", r.wakeLevel in 65..90)
        assertTrue("now ${r.level}", r.level in 25..55)
    }
}
