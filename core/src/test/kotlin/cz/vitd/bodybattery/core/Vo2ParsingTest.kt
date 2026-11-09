package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The exact text formats of these tools are not known yet; the parser must cope with plausible variants. */
class Vo2ParsingTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun res(t: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject.quote(t))))

    @Test
    fun fitnessOverviewVariants() {
        for ((text, expected) in listOf(
            "Fitness Assessment\nVO2max: 52 ml/kg/min\nRunning Level: 70.1" to 52.0,
            "VO2 Max: 48.5\nThreshold Pace: 4:45 /km" to 48.5,
            "- VO₂max (ml/kg/min): 61" to 61.0,
        )) {
            val ex = DataExtractor(zone)
            ex.addToolResult("queryFitnessAssessmentOverview", res(text))
            assertEquals(text, expected, ex.result().corosVo2!!, 0.01)
        }
        val ex = DataExtractor(zone)
        ex.addToolResult("queryFitnessAssessmentOverview", res("No fitness assessment available."))
        assertNull(ex.result().corosVo2)
    }

    @Test
    fun userInfoBirthday() {
        val ex = DataExtractor(zone)
        ex.addToolResult("queryUserInfo", res("User Info\nHeight: 180 cm\nWeight: 75 kg\nBirthday: 19900512\nGender: Male"))
        assertEquals(LocalDate.of(1990, 5, 12), ex.result().birthDate)
    }

    @Test
    fun sportRecordsRunsAndMaxHr() {
        val text = """
            Sport Records — 2026-09-10 to 2026-11-09

            1. Run
               Start Time: 2026-11-07 07:30
               Distance: 10.02 km | Duration: 0:50:10 | Avg Pace: 5:00 /km
               Avg HR: 158 bpm | Max HR: 176 bpm | Training Load: 85

            2. Strength
               Start Time: 2026-11-05 18:00
               Duration: 45min | Avg HR: 110 bpm
        """.trimIndent()
        val ex = DataExtractor(zone)
        ex.addToolResult("querySportRecords", res(text))
        val d = ex.result()
        assertEquals(2, d.activities.size)
        val r = d.runs.single()
        assertEquals(10020.0, r.distanceM, 0.1)
        assertEquals(3010L, r.durationSec)
        assertEquals(158.0, r.avgHr, 0.1)
        assertEquals(176.0, d.observedMaxHr!!, 0.1)
        assertTrue(d.activities.any { it.trainingLoad == 85.0 })
    }
}
