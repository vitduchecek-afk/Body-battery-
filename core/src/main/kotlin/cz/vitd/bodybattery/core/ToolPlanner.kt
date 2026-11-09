package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class PlannedCall(
    val tool: String,
    val args: JSONObject,
    /** Same call with the other date format, tried when the first attempt returns an error. */
    val altArgs: JSONObject?,
)

/**
 * We do not know the exact COROS MCP tool names in advance, so we pick the read-only
 * tools that look health/activity related and fill their arguments from the JSON schema.
 */
object ToolPlanner {
    private val WRITE = Regex("(create|add|delete|remove|update|save|write|set|sync|upload|push|schedule|plan|edit|modify|bind)")
    private val RELEVANT = Regex("(health|sleep|hrv|stress|heart|\\bhr\\b|rest|daily|day|activit|workout|sport|training|load|recovery|body|metric|summary|record|user|profile|wellness|energy)")
    private const val MAX_TOOLS = 10

    fun plan(tools: List<JSONObject>, now: ZonedDateTime): List<PlannedCall> {
        val names = tools.map { it.optString("name") }.toSet()
        if ("queryStressTimeSeries" in names && "querySleepOverview" in names) return corosPlan(names, now)
        return genericPlan(tools, now)
    }

    /** Known COROS MCP tools (as of 2026-11): exact calls, yesterday..today (dates are wake-up days). */
    private fun corosPlan(names: Set<String>, now: ZonedDateTime): List<PlannedCall> {
        val today = now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
        val yday = now.toLocalDate().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
        fun range() = JSONObject().put("startDate", yday).put("endDate", today)
        val calls = mutableListOf(
            PlannedCall("querySleepOverview", range(), null),
            PlannedCall("queryStressTimeSeries", range(), null),
            PlannedCall("querySleepHrv", range(), null),
            PlannedCall("queryRestingHeartRate", JSONObject().put("days", 2), null),
            PlannedCall("querySportRecords", range(), null),
        )
        return calls.filter { it.tool in names }
    }

    private fun genericPlan(tools: List<JSONObject>, now: ZonedDateTime): List<PlannedCall> {
        val scored = tools.mapNotNull { t ->
            val name = t.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            val lname = splitCamel(name)
            if (WRITE.containsMatchIn(lname)) return@mapNotNull null
            if (t.optJSONObject("annotations")?.optBoolean("readOnlyHint", true) == false) return@mapNotNull null
            val text = lname + " " + t.optString("description").lowercase()
            val score = RELEVANT.findAll(text).count()
            if (score == 0) null else t to score
        }.sortedByDescending { it.second }.take(MAX_TOOLS).map { it.first }

        val calls = mutableListOf<PlannedCall>()
        for (t in scored) {
            val schema = t.optJSONObject("inputSchema") ?: JSONObject()
            val singleDate = hasSingleDateParam(schema)
            val days = if (singleDate) listOf(now.toLocalDate(), now.toLocalDate().minusDays(1)) else listOf(now.toLocalDate())
            for (day in days) {
                val primary = buildArgs(schema, now, day, altFormat = false) ?: continue
                val alt = buildArgs(schema, now, day, altFormat = true)
                calls.add(PlannedCall(t.getString("name"), primary, alt?.takeIf { it.toString() != primary.toString() }))
            }
        }
        return calls
    }

    private fun splitCamel(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()

    private fun hasSingleDateParam(schema: JSONObject): Boolean {
        val props = schema.optJSONObject("properties") ?: return false
        val names = props.keys().asSequence().map { it.lowercase() }.toList()
        val dateLike = names.filter { isDateName(it) || isTimeName(it) }
        return dateLike.size == 1 && !isStart(dateLike[0]) && !isEnd(dateLike[0])
    }

    private fun isDateName(n: String) = n.contains("date") || n.contains("day") && !n.contains("days")
    private fun isTimeName(n: String) = n.contains("time") || n == "ts" || n.contains("timestamp")
    private fun isStart(n: String) = n.contains("start") || n.contains("begin") || n.contains("from")
    private fun isEnd(n: String) = n.contains("end") || n.contains("to") && !n.contains("total") || n.contains("until")

    /** Returns null when a required argument cannot be filled. */
    internal fun buildArgs(schema: JSONObject, now: ZonedDateTime, day: LocalDate, altFormat: Boolean): JSONObject? {
        val props = schema.optJSONObject("properties") ?: return JSONObject()
        val required = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val zone: ZoneId = now.zone
        val args = JSONObject()
        for (key in props.keys()) {
            val p = props.optJSONObject(key) ?: JSONObject()
            val n = key.lowercase()
            val type = schemaType(p)
            val desc = p.optString("description").lowercase()
            val isReq = key in required
            val value: Any? = when {
                p.has("default") -> p.get("default")
                p.has("enum") -> if (isReq) p.getJSONArray("enum").opt(0) else null
                n.contains("days") && type in listOf("integer", "number") -> 2
                isTimeName(n) && !n.contains("zone") -> {
                    val at = when {
                        isStart(n) -> day.minusDays(1).atStartOfDay(zone)
                        isEnd(n) -> if (day == now.toLocalDate()) now else day.plusDays(1).atStartOfDay(zone)
                        else -> now
                    }
                    if (type == "string") {
                        at.format(DateTimeFormatter.ofPattern(if (altFormat) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd HH:mm:ss"))
                    } else {
                        val ms = n.contains("ms") || n.contains("milli") || desc.contains("millisecond") || desc.contains("毫秒")
                        if (ms != altFormat) at.toInstant().toEpochMilli() else at.toEpochSecond()
                    }
                }
                isDateName(n) || desc.contains("yyyy") -> {
                    val d = when {
                        isStart(n) -> day.minusDays(1)
                        else -> day
                    }
                    if (type == "integer" || type == "number") {
                        d.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
                    } else {
                        val compactHint = desc.contains("yyyymmdd") || p.optString("pattern").contains("\\d{8}")
                        val compact = compactHint != altFormat
                        d.format(if (compact) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                }
                n.contains("timezone") || n.contains("tz") -> zone.id
                (n.contains("size") || n.contains("limit") || n.contains("count")) && type in listOf("integer", "number") -> 20
                (n == "page" || n.contains("pageno") || n.contains("pagenum") || n.contains("pageindex")) -> 1
                n.contains("offset") -> 0
                isReq && type == "boolean" -> false
                else -> null
            }
            if (value != null) args.put(key, value)
            else if (isReq) return null
        }
        return args
    }

    private fun schemaType(p: JSONObject): String {
        val t = p.opt("type")
        return when (t) {
            is String -> t
            is JSONArray -> (0 until t.length()).map { t.optString(it) }.firstOrNull { it != "null" } ?: "string"
            else -> "string"
        }
    }
}
