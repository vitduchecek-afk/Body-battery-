package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Uses text in the exact shape the COROS MCP returned (2026-11-09). */
class RealFormatTest {
    private val zone = ZoneId.of("Europe/Prague")

    /** COROS wraps the text in an extra JSON string literal. */
    private fun result(text: String) = JSONObject().put(
        "content", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject.quote(text)))
    )

    private val sleepText = """
        Sleep Overview
        ========================
        Note: each record below is dated by its wake-up day.

        2026-11-08
        Sleep Score: 77
        Main Sleep (asleep): 7h 5min
        Main Sleep Window: 2026-11-08 00:38 - 2026-11-08 07:51

        2026-11-09
        Sleep Score: 83
        Daily Sleep: 7h 8min (incl. naps)
        Main Sleep (asleep): 7h 8min
        Main Sleep Window: 2026-11-08 23:38 - 2026-11-09 06:58
        Naps Total: 0 min
    """.trimIndent()

    private val hrvText = """
        Sleep HRV — 2026-11-08 to 2026-11-09
        HRV Assessment — Last 2 days
        ========================

        2026-11-09:
          HRV Avg: 39 ms — Normal
          Normal Range: 38 - 50 ms
          Baseline: 44 ms
        2026-11-08:
          HRV Avg: 37 ms — Below normal
          Baseline: 44 ms

        Sleep HRV Time Series — Last 2 days
        2026-11-08:
          timestamp=1794095090, timezone=4, hrv=67 ms, status=4, confidence=83855
        2026-11-09:
          timestamp=1794179090, timezone=4, hrv=24 ms, status=4, confidence=100000
    """.trimIndent()

    private val rhrText = "Resting Heart Rate — Last 2 days\n========================\n\n2026-11-09: 66 bpm\n2026-11-08: 68 bpm"

    /** Stress every 5 min from 2026-11-08 00:04:50: ~45 awake-ish, ~20 asleep, ~40 during the day. */
    private fun stressText(nowS: Long): String {
        val sb = StringBuilder("Stress Time Series — 2026-11-08 to 2026-11-09\n========================\n\n2026-11-08:\n")
        var t = 1794092690L
        val sleep1 = 1794094680L..1794120660L // 00:38 - 07:51
        val sleep2 = 1794176280L..1794202680L // 23:38 - 06:58
        while (t < nowS) {
            val s = if (t in sleep1 || t in sleep2) 20 else 40
            sb.append("  timestamp=$t, timezone=4, stress=$s, score=2, stressHrv=0, stressHr=0\n")
            t += 300
        }
        return sb.toString()
    }

    @Test
    fun parsesRealCorosTextAndComputes() {
        val now = ZonedDateTime.of(2026, 11, 9, 17, 9, 0, 0, zone)
        val ex = DataExtractor(zone)
        ex.addToolResult("querySleepOverview", result(sleepText))
        ex.addToolResult("querySleepHrv", result(hrvText))
        ex.addToolResult("queryRestingHeartRate", result(rhrText))
        ex.addToolResult("queryStressTimeSeries", result(stressText(now.toEpochSecond())))
        ex.addToolResult("querySportRecords", result("No sport records found from 2026-11-08 to 2026-11-09."))
        val d = ex.result()

        assertEquals(2, d.sleeps.size)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 23, 38, 0, 0, zone).toEpochSecond(), d.sleeps.last().start)
        assertEquals(83.0, d.sleepScore!!, 0.1)
        assertEquals(428.0, d.sleepMinutes!!, 0.1)
        assertEquals(39.0, d.hrvNight!!, 0.1)
        assertEquals(44.0, d.hrvBaseline!!, 0.1)
        assertEquals(66.0, d.restingHr!!, 0.1)
        assertTrue(d.samples.count { it.kind == SampleKind.STRESS } > 400)
        assertEquals(2, d.samples.count { it.kind == SampleKind.HRV })
        assertTrue(d.activities.isEmpty())

        val r = BatteryModel.compute(d, now) { null }
        println("level=${r.level} wake=${r.wakeLevel} +${r.charged} -${r.drained} notes=${r.notes}")
        assertTrue(r.detailed)
        assertTrue(r.notes.any { it.contains("předchozího dne") })
        assertTrue("wake ${r.wakeLevel}", r.wakeLevel in 60..100)
        assertTrue("level ${r.level}", r.level in 20 until r.wakeLevel)
    }

    @Test
    fun plannerUsesKnownCorosTools() {
        val names = listOf("querySleepOverview", "queryStressTimeSeries", "querySleepHrv", "queryRestingHeartRate", "querySportRecords", "queryMenstruationCycles")
        val calls = ToolPlanner.plan(names.map { JSONObject().put("name", it) }, ZonedDateTime.of(2026, 11, 9, 17, 0, 0, 0, zone))
        assertEquals(listOf("querySleepOverview", "queryStressTimeSeries", "querySleepHrv", "queryRestingHeartRate", "querySportRecords"), calls.map { it.tool })
        assertEquals("20261108", calls[1].args.getString("startDate"))
        assertEquals("20261109", calls[1].args.getString("endDate"))
    }

    @Test
    fun durations() {
        assertEquals(428.0, CorosTextParser.durationMinutes("7h 8min")!!, 0.01)
        assertEquals(62.05, CorosTextParser.durationMinutes("1:02:03")!!, 0.01)
        assertEquals(45.0, CorosTextParser.durationMinutes("45 min")!!, 0.01)
    }
}
