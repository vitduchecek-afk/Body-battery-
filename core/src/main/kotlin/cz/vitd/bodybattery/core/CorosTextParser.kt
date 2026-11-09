package cz.vitd.bodybattery.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The COROS MCP tools answer with human readable text (not JSON). This parser understands
 * the formats returned by the tools we use, e.g.
 *
 *   timestamp=1794092690, timezone=4, stress=59, score=3, stressHrv=0, stressHr=0
 *   Main Sleep Window: 2026-11-08 23:38 - 2026-11-09 06:58
 *   HRV Avg: 39 ms — Normal / Baseline: 44 ms
 *   2026-11-09: 66 bpm
 */
class CorosTextParser(private val zone: ZoneId) {
    val samples = mutableListOf<Sample>()
    val sleeps = mutableListOf<SleepPeriod>()
    var sleepMinutes: Double? = null
    var sleepScore: Double? = null
    var hrvNight: Double? = null
    var hrvBaseline: Double? = null
    var restingHr: Double? = null
    val activities = mutableListOf<Activity>()
    val runs = mutableListOf<RunSample>()
    var corosVo2: Double? = null
    var birthDate: LocalDate? = null
    var observedMaxHr: Double? = null

    private val dayFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val tsLine = Regex("""timestamp=(\d{9,13})""")
    private val stressVal = Regex("""\bstress=(\d+(?:\.\d+)?)""")
    private val hrvVal = Regex("""\bhrv=(\d+(?:\.\d+)?)""")
    private val hrVal = Regex("""\b(?:hr|heartRate|heart_rate)=(\d+(?:\.\d+)?)""")
    private val dateHeader = Regex("""^(?:-+\s*)?(\d{4}-\d{2}-\d{2}|\d{8})(?:\s*-+)?:?\s*$""")

    fun parse(tool: String, text: String) {
        parseTimeSeries(text)
        when (tool) {
            "querySleepOverview" -> parseSleepOverview(text)
            "querySleepHrv" -> parseHrv(text)
            "queryRestingHeartRate" -> parseRestingHr(text)
            "queryDailyHealthData" -> parseDaily(text)
            "querySportRecords" -> parseSportRecords(text)
            "queryFitnessAssessmentOverview" -> parseFitness(text)
            "queryUserInfo" -> parseUserInfo(text)
        }
    }

    /** Lines with `timestamp=...` and a stress/hrv/hr value. */
    private fun parseTimeSeries(text: String) {
        for (line in text.lineSequence()) {
            val ts = tsLine.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val epoch = if (ts > 100_000_000_000L) ts / 1000 else ts
            stressVal.find(line)?.let { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { samples += Sample(epoch, SampleKind.STRESS, it) } }
            hrvVal.find(line)?.let { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it in 3.0..300.0 }?.let { samples += Sample(epoch, SampleKind.HRV, it) } }
            hrVal.find(line)?.let { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it in 25.0..230.0 }?.let { samples += Sample(epoch, SampleKind.HR, it) } }
        }
    }

    /** Splits text into blocks keyed by a date header line (e.g. "2026-11-09" or "--- 20261109 ---"). */
    private fun blocksByDate(text: String): List<Pair<LocalDate, String>> {
        val out = mutableListOf<Pair<LocalDate, StringBuilder>>()
        for (line in text.lines()) {
            val m = dateHeader.find(line.trim())
            val d = m?.let { parseDay(it.groupValues[1]) }
            if (d != null) out += d to StringBuilder() else out.lastOrNull()?.second?.append(line)?.append('\n')
        }
        return out.map { it.first to it.second.toString() }
    }

    private fun parseDay(s: String): LocalDate? =
        runCatching { if (s.length == 8) LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE) else LocalDate.parse(s, dayFmt) }.getOrNull()

    private fun parseSleepOverview(text: String) {
        val window = Regex("""Main Sleep Window:\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s*-\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2})""")
        for (m in window.findAll(text)) {
            val s = parseLocal(m.groupValues[1]) ?: continue
            val e = parseLocal(m.groupValues[2]) ?: continue
            if (e > s) sleeps += SleepPeriod(s, e)
        }
        val latest = blocksByDate(text).maxByOrNull { it.first } ?: return
        Regex("""Sleep Score:\s*(\d+)""").find(latest.second)?.let { sleepScore = it.groupValues[1].toDouble() }
        Regex("""Main Sleep \(asleep\):\s*(.+)""").find(latest.second)?.let { m -> durationMinutes(m.groupValues[1])?.let { sleepMinutes = it } }
    }

    private fun parseHrv(text: String) {
        // Only the assessment part (before the time series) has per-day "HRV Avg" lines.
        val latest = blocksByDate(text).filter { it.second.contains("HRV Avg") }.maxByOrNull { it.first } ?: return
        Regex("""HRV Avg:\s*(\d+(?:\.\d+)?)""").find(latest.second)?.let { hrvNight = it.groupValues[1].toDouble() }
        Regex("""Baseline:\s*(\d+(?:\.\d+)?)""").find(latest.second)?.let { hrvBaseline = it.groupValues[1].toDouble() }
    }

    private fun parseRestingHr(text: String) {
        val rows = Regex("""(\d{4}-\d{2}-\d{2}):\s*(\d+)\s*bpm""").findAll(text)
            .mapNotNull { m -> parseDay(m.groupValues[1])?.let { it to m.groupValues[2].toDouble() } }.toList()
        rows.maxByOrNull { it.first }?.let { restingHr = it.second }
    }

    private fun parseDaily(text: String) {
        if (restingHr == null) Regex("""Resting HR:\s*(\d+)""").find(text)?.let { restingHr = it.groupValues[1].toDouble() }
        if (hrvBaseline == null) Regex("""HRV Baseline:\s*(\d+)""").find(text)?.let { hrvBaseline = it.groupValues[1].toDouble() }
    }

    /**
     * Workout list. The exact format is not known yet, so every block (separated by blank lines
     * or numbered items) with a start time and a duration becomes an activity.
     */
    private fun parseSportRecords(text: String) {
        val blocks = text.split(Regex("""\n\s*\n|\n(?=\s*\d+[.)]\s)|\n(?=-{2,})"""))
        val startRe = Regex("""(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2})""")
        val durRe = Regex("""(?i)\b(?:duration|total\s*time|elapsed\s*time|moving\s*time|workout\s*time)\s*[:=]\s*([^\n|,]+)""")
        val loadRe = Regex("""(?i)(?:training\s*load|load)\s*[:=]\s*(\d+(?:\.\d+)?)""")
        val avgHrRe = Regex("""(?i)(?:avg|average)\.?\s*(?:hr|heart\s*rate)\s*[:=]\s*(\d+)""")
        val maxHrRe = Regex("""(?i)max\.?\s*(?:hr|heart\s*rate)\s*[:=]\s*(\d+)""")
        val distRe = Regex("""(?i)distance\s*[:=]\s*([\d.,]+)\s*(km|mi|m)\b""")
        val runRe = Regex("""(?i)(\brun\b|\brunning\b|sport\s*type\s*[:=]\s*10[0-3]\b|sportType\s*=\s*10[0-3]\b)""")
        for (b in blocks) {
            maxHrRe.find(b)?.groupValues?.get(1)?.toDoubleOrNull()?.let { observedMaxHr = maxOf(observedMaxHr ?: 0.0, it) }
            val start = startRe.find(b)?.groupValues?.get(1)?.replace('T', ' ')?.let { parseLocal(it) } ?: continue
            val dur = durRe.find(b)?.groupValues?.get(1)?.let { durationMinutes(it) } ?: continue
            if (dur < 2 || dur > 12 * 60) continue
            val avgHr = avgHrRe.find(b)?.groupValues?.get(1)?.toDoubleOrNull()
            activities += Activity(
                start = start,
                durationSec = (dur * 60).toLong(),
                trainingLoad = loadRe.find(b)?.groupValues?.get(1)?.toDoubleOrNull(),
                avgHr = avgHr,
            )
            val dist = distRe.find(b)?.let { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
                when (m.groupValues[2].lowercase()) { "km" -> v * 1000; "mi" -> v * 1609.344; else -> v }
            }
            if (runRe.containsMatchIn(b) && dist != null && avgHr != null) {
                runs += RunSample(start, (dur * 60).toLong(), dist, avgHr)
            }
        }
    }

    /** queryFitnessAssessmentOverview: "VO2max: 52" (any similar wording). */
    private fun parseFitness(text: String) {
        Regex("""(?i)VO\s*[2₂]\s*[-_ ]?max[^0-9\n]{0,24}(\d{2}(?:[.,]\d+)?)""").find(text)?.let {
            corosVo2 = it.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
    }

    /** queryUserInfo: birthday as yyyy-MM-dd or yyyyMMdd. */
    private fun parseUserInfo(text: String) {
        Regex("""(?i)birth\w*\s*[:=]\s*(\d{4})[-/.]?(\d{2})[-/.]?(\d{2})""").find(text)?.let { m ->
            birthDate = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
    }

    private fun parseLocal(s: String): Long? =
        runCatching { LocalDateTime.parse(s, dtFmt).atZone(zone).toEpochSecond() }.getOrNull()

    companion object {
        /** "7h 8min", "45 min", "1:02:03", "62:10", "3720 s" -> minutes */
        fun durationMinutes(raw: String): Double? {
            val s = raw.trim()
            Regex("""^(\d+):(\d{2}):(\d{2})""").find(s)?.let { m ->
                val (h, mi, se) = m.destructured
                return h.toDouble() * 60 + mi.toDouble() + se.toDouble() / 60
            }
            Regex("""^(\d+):(\d{2})\b""").find(s)?.let { m ->
                val (mi, se) = m.destructured
                return mi.toDouble() + se.toDouble() / 60
            }
            var total = 0.0
            var any = false
            Regex("""(\d+(?:\.\d+)?)\s*h""").find(s)?.let { total += it.groupValues[1].toDouble() * 60; any = true }
            Regex("""(\d+(?:\.\d+)?)\s*min""").find(s)?.let { total += it.groupValues[1].toDouble(); any = true }
            if (!any) Regex("""(\d+(?:\.\d+)?)\s*s(ec)?\b""").find(s)?.let { total += it.groupValues[1].toDouble() / 60; any = true }
            return if (any) total else null
        }
    }
}
