package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

interface McpSource {
    fun listTools(): List<JSONObject>
    fun callTool(name: String, args: JSONObject): JSONObject
}

fun CorosClient.asSource(): McpSource = object : McpSource {
    override fun listTools() = this@asSource.listTools()
    override fun callTool(name: String, args: JSONObject) = this@asSource.callTool(name, args)
}

/** Raw record of one MCP call, used for the (localized) diagnostics screen. */
data class CallLog(val tool: String, val args: String, val text: String, val isError: Boolean)

data class ToolInfo(val name: String, val description: String, val params: String?)

data class RefreshOutcome(
    val result: BatteryResult,
    val data: HealthData,
    val vo2: Vo2Result,
    val tools: List<ToolInfo>,
    val calls: List<CallLog>,
)

/** Fetch data from COROS, extract it and compute the charge + VO2 max. Runs on a background thread. */
object Refresher {
    const val FITNESS_TOOL = "queryFitnessAssessmentOverview"
    const val USER_TOOL = "queryUserInfo"
    const val SPORT_TOOL = "querySportRecords"
    private const val RUN_HISTORY_DAYS = 60L

    fun refresh(source: McpSource, now: ZonedDateTime, previousLevelAt: (Long) -> Int?): RefreshOutcome {
        val tools = source.listTools()
        val names = tools.map { it.optString("name") }.toSet()
        val extractor = DataExtractor(now.zone)
        val calls = mutableListOf<CallLog>()

        fun run(tool: String, args: JSONObject, alt: JSONObject? = null) {
            var a = args
            var res = safeCall(source, tool, a)
            if (res.optBoolean("isError") && alt != null) {
                a = alt
                res = safeCall(source, tool, a)
            }
            calls += CallLog(tool, a.toString(), rawText(res), res.optBoolean("isError"))
            if (!res.optBoolean("isError")) extractor.addToolResult(tool, res)
        }

        // 1) charge inputs
        for (c in ToolPlanner.plan(tools, now)) run(c.tool, c.args, c.altArgs)

        // 2) VO2 max: COROS value first; only if missing fetch what the own estimate needs
        if (FITNESS_TOOL in names) run(FITNESS_TOOL, JSONObject())
        if (extractor.result().corosVo2 == null) {
            if (SPORT_TOOL in names) {
                val f = DateTimeFormatter.BASIC_ISO_DATE
                run(SPORT_TOOL, JSONObject()
                    .put("startDate", now.toLocalDate().minusDays(RUN_HISTORY_DAYS).format(f))
                    .put("endDate", now.toLocalDate().format(f)))
            }
            if (USER_TOOL in names) run(USER_TOOL, JSONObject())
        }

        val data = extractor.result()
        val result = BatteryModel.compute(data, now, previousLevelAt)
        val maxHr = Vo2Estimator.maxHr(data.birthDate, now.toLocalDate(), data.observedMaxHr)
        val vo2 = Vo2Estimator.estimate(data.corosVo2, data.runs, data.restingHr, maxHr)
        val infos = tools.map {
            ToolInfo(it.optString("name"), it.optString("description"), it.optJSONObject("inputSchema")?.optJSONObject("properties")?.toString())
        }
        return RefreshOutcome(result, data, vo2, infos, calls)
    }

    private fun safeCall(source: McpSource, tool: String, args: JSONObject): JSONObject =
        try {
            source.callTool(tool, args)
        } catch (e: Exception) {
            JSONObject().put("isError", true)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "EXCEPTION: ${e.message}")))
        }

    private fun rawText(res: JSONObject): String {
        val content = res.optJSONArray("content") ?: return res.toString()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            sb.append(DataExtractor.unwrapText(c.optString("text", c.toString()))).append('\n')
        }
        res.optJSONObject("structuredContent")?.let { if (sb.isBlank()) sb.append(it.toString()) }
        return sb.toString()
    }
}
