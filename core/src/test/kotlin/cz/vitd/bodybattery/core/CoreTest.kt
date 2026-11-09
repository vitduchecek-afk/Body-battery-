package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MapStore : KeyValueStore {
    val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

class FakeHttp : Http() {
    val log = mutableListOf<String>()
    var claimStatus = "pending"
    var lastState = ""
    var tokenCalls = 0

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): HttpResponse {
        log += "$method $url"
        fun json(s: Int, o: Any, ct: String = "application/json") = HttpResponse(s, mapOf("content-type" to ct), o.toString())
        return when {
            url.endsWith("/.well-known/openid-configuration") -> json(200, JSONObject().put("issuer", "https://mcpeu.coros.com"))
            url.endsWith("/connect/register") -> json(201, JSONObject().put("client_id", "cid"))
            url.endsWith("/api/v1/cli/login-sessions") -> json(200, JSONObject().put("sessionId", "s1").put("pollToken", "p").put("loginUrl", "https://login/x").put("expiresAt", "z"))
            url.contains("/claim") -> {
                assertEquals("p", headers["X-Poll-Token"])
                json(200, JSONObject().put("status", claimStatus).put("loginTicket", "tk"))
            }
            url.contains("/oauth2/authorize") -> {
                assertTrue(url.contains("login_ticket=tk"))
                lastState = queryParams(url)["state"]!!
                HttpResponse(302, mapOf("location" to "http://127.0.0.1:43123/callback?code=C&state=$lastState"), "")
            }
            url.endsWith("/oauth2/token") -> {
                tokenCalls++
                assertTrue(body!!.contains("client_id=cid"))
                json(200, JSONObject().put("access_token", "A$tokenCalls").put("refresh_token", "R").put("expires_in", 3600))
            }
            url.endsWith("/mcp") -> {
                assertEquals("Bearer A1", headers["Authorization"])
                val req = JSONObject(body!!)
                val result = when (req.getString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", "2025-06-18")
                    "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "queryDailyHealth")))
                    else -> JSONObject().put("content", JSONArray())
                }
                val payload = JSONObject().put("jsonrpc", "2.0").put("id", req.get("id")).put("result", result)
                json(200, "event: message\ndata: $payload\n\n", "text/event-stream")
            }
            else -> json(404, JSONObject())
        }
    }
}

class CoreTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val now = ZonedDateTime.of(2026, 11, 9, 15, 0, 0, 0, zone)

    @Test
    fun deviceLoginFlowAndMcp() {
        val store = MapStore()
        val http = FakeHttp()
        val client = CorosClient(store, http, nowEpoch = { 1_000_000L })
        assertEquals("https://login/x", client.startLogin())
        assertTrue(http.log.any { it.startsWith("POST https://mcpeu.coros.com/connect/register") })
        assertTrue(client.pollLogin() is LoginPoll.Pending)
        http.claimStatus = "authorized"
        assertTrue(client.pollLogin() is LoginPoll.Done)
        assertTrue(client.isLoggedIn())
        assertTrue(!client.hasPendingLogin())
        val tools = client.listTools()
        assertEquals("queryDailyHealth", tools.single().getString("name"))
    }

    @Test
    fun plannerPicksReadToolsAndFillsDates() {
        val tools = listOf(
            JSONObject("""{"name":"queryDailyHealth","description":"Query sleep, hrv, stress","inputSchema":{"type":"object","properties":{"date":{"type":"string","description":"date in yyyyMMdd"}},"required":["date"]}}"""),
            JSONObject("""{"name":"queryActivityList","description":"workouts","inputSchema":{"type":"object","properties":{"startDay":{"type":"integer"},"endDay":{"type":"integer"},"pageSize":{"type":"integer"}},"required":["startDay","endDay"]}}"""),
            JSONObject("""{"name":"createTrainingPlan","description":"write plan","inputSchema":{"type":"object","properties":{}}}"""),
        )
        val calls = ToolPlanner.plan(tools, now)
        assertTrue(calls.none { it.tool == "createTrainingPlan" })
        val daily = calls.filter { it.tool == "queryDailyHealth" }
        assertEquals(2, daily.size)
        assertEquals("20261109", daily[0].args.getString("date"))
        assertEquals("2026-11-09", daily[0].altArgs!!.getString("date"))
        val act = calls.single { it.tool == "queryActivityList" }
        assertEquals(20261108, act.args.getInt("startDay"))
        assertEquals(20261109, act.args.getInt("endDay"))
        assertEquals(20, act.args.getInt("pageSize"))
    }

    @Test
    fun extractorUnderstandsSeveralShapes() {
        val ex = DataExtractor(zone)
        val base = ZonedDateTime.of(2026, 11, 9, 8, 0, 0, 0, zone).toEpochSecond()
        val text = """
            Here is the data:
            {"data":{
              "sleep":{"startTime":"2026-11-08 23:10:00","endTime":"2026-11-09 06:50:00","totalSleepMinutes":440,"sleepScore":82},
              "hrv":{"avgHrv":55,"hrvBaseline":50},
              "rhr":52,
              "heartRateList":[{"timestamp":${base * 1000},"hr":70},{"timestamp":${(base + 300) * 1000},"hr":72}],
              "stress":{"startTime":$base,"interval":5,"values":[20,30,40,50]},
              "activities":[{"sportType":"run","startTime":"2026-11-09T12:00:00+01:00","duration":3600,"trainingLoad":80,"avgHr":150}]
            }}
        """.trimIndent()
        ex.addToolResult("queryDailyHealth", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
        val d = ex.result()
        assertEquals(1, d.sleeps.size)
        assertEquals(440.0, d.sleepMinutes!!, 0.1)
        assertEquals(82.0, d.sleepScore!!, 0.1)
        assertEquals(55.0, d.hrvNight!!, 0.1)
        assertEquals(50.0, d.hrvBaseline!!, 0.1)
        assertEquals(52.0, d.restingHr!!, 0.1)
        assertEquals(2, d.samples.count { it.kind == SampleKind.HR })
        assertEquals(4, d.samples.count { it.kind == SampleKind.STRESS })
        assertEquals(base + 900, d.samples.filter { it.kind == SampleKind.STRESS }.last().epoch)
        val a = d.activities.single()
        assertEquals(3600, a.durationSec)
        assertEquals(80.0, a.trainingLoad!!, 0.1)
    }

    @Test
    fun modelChargesAtNightAndDrainsDuringDay() {
        val sleepStart = ZonedDateTime.of(2026, 11, 8, 23, 0, 0, 0, zone).toEpochSecond()
        val wake = ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, zone).toEpochSecond()
        val samples = mutableListOf<Sample>()
        var t = sleepStart
        while (t < now.toEpochSecond()) {
            samples += Sample(t, SampleKind.STRESS, if (t < wake) 12.0 else 40.0)
            t += 600
        }
        val data = HealthData(samples = samples, sleeps = listOf(SleepPeriod(sleepStart, wake)))
        val r = BatteryModel.compute(data, now) { 25 }
        assertTrue("wake level ${r.wakeLevel}", r.wakeLevel in 80..100)
        assertTrue("level ${r.level}", r.level < r.wakeLevel)
        assertTrue(r.detailed)
        assertNotNull(r.points.lastOrNull())
        assertEquals(r.level, BatteryResult.fromJson(r.toJson()).level)
    }

    @Test
    fun modelWorksWithoutAnyData() {
        val r = BatteryModel.compute(HealthData(), now) { null }
        assertTrue(r.level in 1..100)
        assertTrue(r.notes.isNotEmpty())
    }

    @Test
    fun activityDrains() {
        val sleeps = listOf(SleepPeriod(now.minusHours(16).toEpochSecond(), now.minusHours(8).toEpochSecond()))
        val base = BatteryModel.compute(HealthData(sleeps = sleeps, sleepMinutes = 480.0), now) { 30 }
        val withRun = BatteryModel.compute(
            HealthData(sleeps = sleeps, sleepMinutes = 480.0, activities = listOf(Activity(now.minusHours(3).toEpochSecond(), 3600, 100.0, null))),
            now,
        ) { 30 }
        assertTrue("${withRun.level} < ${base.level}", withRun.level <= base.level - 25)
    }
}
