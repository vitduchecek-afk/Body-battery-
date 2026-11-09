package cz.vitd.bodybattery.core

import org.json.JSONObject
import java.time.ZonedDateTime

interface McpSource {
    fun listTools(): List<JSONObject>
    fun callTool(name: String, args: JSONObject): JSONObject
}

fun CorosClient.asSource(): McpSource = object : McpSource {
    override fun listTools() = this@asSource.listTools()
    override fun callTool(name: String, args: JSONObject) = this@asSource.callTool(name, args)
}

data class RefreshOutcome(val result: BatteryResult, val data: HealthData, val diagnostics: String)

/** Fetch data from COROS, extract it and compute the battery. Runs on a background thread. */
object Refresher {
    private const val RAW_LIMIT = 6000

    fun refresh(source: McpSource, now: ZonedDateTime, previousLevelAt: (Long) -> Int?): RefreshOutcome {
        val diag = StringBuilder()
        diag.append("Čas: $now\n\n")
        val tools = source.listTools()
        diag.append("=== Dostupné nástroje COROS MCP (${tools.size}) ===\n")
        for (t in tools) {
            diag.append("• ${t.optString("name")}: ${t.optString("description").take(200)}\n")
            t.optJSONObject("inputSchema")?.optJSONObject("properties")?.let { diag.append("   parametry: ${it.toString().take(400)}\n") }
        }

        val calls = ToolPlanner.plan(tools, now)
        diag.append("\n=== Volání (${calls.size}) ===\n")
        val extractor = DataExtractor(now.zone)
        for (c in calls) {
            var args = c.args
            var res = safeCall(source, c.tool, args)
            if ((res == null || res.optBoolean("isError")) && c.altArgs != null) {
                args = c.altArgs
                res = safeCall(source, c.tool, args) ?: res
            }
            diag.append("\n--- ${c.tool} $args\n")
            if (res == null) {
                diag.append("(chyba volání)\n")
                continue
            }
            diag.append(rawText(res).take(RAW_LIMIT)).append('\n')
            if (!res.optBoolean("isError")) extractor.addToolResult(c.tool, res)
        }
        val data = extractor.result()
        val result = BatteryModel.compute(data, now, previousLevelAt)
        diag.insert(0, "=== Rozpoznaná data ===\n${data.describe()}\n\n")
        return RefreshOutcome(result, data, diag.toString())
    }

    private fun safeCall(source: McpSource, tool: String, args: JSONObject): JSONObject? =
        try {
            source.callTool(tool, args)
        } catch (e: Exception) {
            JSONObject().put("isError", true).put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "EXCEPTION: ${e.message}")))
        }

    private fun rawText(res: JSONObject): String {
        val content = res.optJSONArray("content") ?: return res.toString()
        val sb = StringBuilder()
        if (res.optBoolean("isError")) sb.append("[isError] ")
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            sb.append(c.optString("text", c.toString())).append('\n')
        }
        res.optJSONObject("structuredContent")?.let { if (sb.isBlank()) sb.append(it.toString()) }
        return sb.toString()
    }
}
