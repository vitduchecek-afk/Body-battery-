package cz.vitd.bodybattery.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Simple persistent key/value storage (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class CorosException(message: String) : Exception(message)

sealed class LoginPoll {
    object Pending : LoginPoll()
    object Done : LoginPoll()
    data class Failed(val reason: String) : LoginPoll()
}

/**
 * Talks to the official COROS MCP gateway (https://mcp.coros.com).
 *
 * Login mirrors the official `coros-mcp` CLI "device login":
 *  1. dynamic client registration (public client + PKCE)
 *  2. a login session gives a `loginUrl` that the user opens in the browser
 *  3. the app polls the session until it is authorized and receives a `loginTicket`
 *  4. authorize URL + login_ticket returns a redirect carrying the auth code
 *  5. the code is exchanged for access + refresh tokens
 */
class CorosClient(
    private val store: KeyValueStore,
    private val http: Http = Http(),
    private val nowEpoch: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        const val DEFAULT_ISSUER = "https://mcp.coros.com"
        const val SCOPES = "openid offline_access mcp.tools"
        const val REDIRECT_URI = "http://127.0.0.1:43123/callback"
        const val CLIENT_NAME = "Body Battery Android"
        private const val K_TOKEN = "coros.token"
        private const val K_PENDING = "coros.pending"
    }

    private val random = SecureRandom()

    fun isLoggedIn(): Boolean = store.get(K_TOKEN) != null
    fun hasPendingLogin(): Boolean = store.get(K_PENDING) != null

    fun logout() {
        store.put(K_TOKEN, null)
        store.put(K_PENDING, null)
    }

    // ---------------------------------------------------------------- login

    /** Starts the login and returns the URL the user has to open in a browser. */
    fun startLogin(): String {
        val issuer = discoverIssuer()
        val clientId = registerClient(issuer)
        val verifier = b64url(randomBytes(32))
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
        val state = b64url(randomBytes(24))
        val authorizeUrl = "$issuer/oauth2/authorize?" + formEncode(
            linkedMapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to REDIRECT_URI,
                "scope" to SCOPES,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "resource" to "$issuer/mcp",
                "state" to state,
            )
        )
        val resp = http.request(
            "POST", "$issuer/api/v1/cli/login-sessions",
            body = JSONObject().put("clientId", clientId).toString(), contentType = "application/json",
        )
        val payload = parseJsonBody(resp)
        if (resp.status != 200 && resp.status != 201) throw CorosException("login session failed (${resp.status}): ${short(resp.body)}")
        val pending = JSONObject()
            .put("issuer", issuer)
            .put("client_id", clientId)
            .put("verifier", verifier)
            .put("state", state)
            .put("authorize_url", authorizeUrl)
            .put("session_id", payload.requireString("sessionId"))
            .put("poll_token", payload.requireString("pollToken"))
            .put("login_url", payload.requireString("loginUrl"))
            .put("created", nowEpoch())
        store.put(K_PENDING, pending.toString())
        return pending.getString("login_url")
    }

    fun pendingLoginUrl(): String? = store.get(K_PENDING)?.let { JSONObject(it).optString("login_url", null) }

    /** Checks the pending login once. Call repeatedly (every ~3 s) until it is not [LoginPoll.Pending]. */
    fun pollLogin(): LoginPoll {
        val pending = store.get(K_PENDING)?.let { JSONObject(it) } ?: return LoginPoll.Failed("Žádné rozpracované přihlášení")
        val issuer = pending.getString("issuer")
        val resp = http.request(
            "POST", "$issuer/api/v1/cli/login-sessions/${pending.getString("session_id")}/claim",
            headers = mapOf("X-Poll-Token" to pending.getString("poll_token")),
        )
        val payload = parseJsonBody(resp)
        if (resp.status != 200) return fail("claim failed (${resp.status}): ${short(resp.body)}")
        return when (payload.optString("status").lowercase()) {
            "pending" -> LoginPoll.Pending
            "authorized" -> {
                finishLogin(pending, payload.requireString("loginTicket"))
                LoginPoll.Done
            }
            "expired" -> fail("Přihlášení vypršelo, zkuste to znovu")
            "failed" -> fail("Přihlášení selhalo: ${payload.optString("errorCode")}")
            "claimed" -> fail("Přihlášení už bylo použito, zkuste to znovu")
            else -> fail("Neočekávaná odpověď: ${short(resp.body)}")
        }
    }

    private fun fail(reason: String): LoginPoll {
        store.put(K_PENDING, null)
        return LoginPoll.Failed(reason)
    }

    private fun finishLogin(pending: JSONObject, ticket: String) {
        val url = pending.getString("authorize_url") + "&login_ticket=" + enc(ticket)
        val resp = http.request("GET", url)
        val location = resp.header("location")
        if (resp.status !in listOf(301, 302, 303, 307) || location == null) {
            store.put(K_PENDING, null)
            throw CorosException("authorize did not redirect (${resp.status}): ${short(resp.body)}")
        }
        val params = queryParams(location)
        val code = params["code"] ?: throw CorosException("callback without code: ${params["error"] ?: location.take(100)}")
        if (params["state"] != pending.getString("state")) throw CorosException("state mismatch")
        val issuer = pending.getString("issuer")
        val clientId = pending.getString("client_id")
        val tokenResp = http.request(
            "POST", "$issuer/oauth2/token",
            body = formEncode(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to pending.getString("verifier"),
                )
            ),
            contentType = "application/x-www-form-urlencoded",
        )
        saveToken(tokenResp, issuer, clientId, null)
        store.put(K_PENDING, null)
    }

    private fun discoverIssuer(): String {
        return try {
            val resp = http.request("GET", "$DEFAULT_ISSUER/.well-known/openid-configuration")
            val issuer = if (resp.status == 200) JSONObject(resp.body).optString("issuer") else ""
            if (issuer.startsWith("https://")) issuer.trimEnd('/') else DEFAULT_ISSUER
        } catch (e: Exception) {
            DEFAULT_ISSUER
        }
    }

    private fun registerClient(issuer: String): String {
        val body = JSONObject()
            .put("client_name", CLIENT_NAME)
            .put("redirect_uris", JSONArray().put(REDIRECT_URI))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("scope", SCOPES)
            .put("token_endpoint_auth_method", "none")
        val resp = http.request("POST", "$issuer/connect/register", body = body.toString(), contentType = "application/json")
        if (resp.status != 200 && resp.status != 201) throw CorosException("client registration failed (${resp.status}): ${short(resp.body)}")
        return parseJsonBody(resp).requireString("client_id")
    }

    // ---------------------------------------------------------------- tokens

    private fun saveToken(resp: HttpResponse, issuer: String, clientId: String, oldRefresh: String?) {
        val payload = parseJsonBody(resp)
        if (resp.status != 200) throw CorosException("token request failed (${resp.status}): ${short(resp.body)}")
        val access = payload.optString("access_token")
        if (access.isEmpty()) throw CorosException("token response without access_token")
        val refresh = payload.optString("refresh_token").ifEmpty { oldRefresh ?: "" }
        val token = JSONObject()
            .put("issuer", issuer)
            .put("client_id", clientId)
            .put("access_token", access)
            .put("refresh_token", refresh)
            .put("token_type", payload.optString("token_type", "Bearer").ifEmpty { "Bearer" })
            .put("expires_at", nowEpoch() + payload.optLong("expires_in", 3600))
        store.put(K_TOKEN, token.toString())
    }

    private fun token(forceRefresh: Boolean = false): JSONObject {
        val t = store.get(K_TOKEN)?.let { JSONObject(it) } ?: throw CorosException("Nejste připojen ke Korosu")
        if (!forceRefresh && nowEpoch() + 60 < t.getLong("expires_at")) return t
        val refresh = t.optString("refresh_token")
        if (refresh.isEmpty()) {
            store.put(K_TOKEN, null)
            throw CorosException("Přihlášení vypršelo, připojte Koros znovu")
        }
        val resp = http.request(
            "POST", "${t.getString("issuer")}/oauth2/token",
            body = formEncode(
                mapOf("grant_type" to "refresh_token", "client_id" to t.getString("client_id"), "refresh_token" to refresh)
            ),
            contentType = "application/x-www-form-urlencoded",
        )
        if (resp.status == 400 || resp.status == 401) {
            store.put(K_TOKEN, null)
            throw CorosException("Přihlášení vypršelo, připojte Koros znovu")
        }
        saveToken(resp, t.getString("issuer"), t.getString("client_id"), refresh)
        return JSONObject(store.get(K_TOKEN)!!)
    }

    // ---------------------------------------------------------------- MCP

    private var nextId = 1

    private fun mcpRaw(t: JSONObject, method: String, params: JSONObject): HttpResponse {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", method).put("params", params)
        return http.request(
            "POST", "${t.getString("issuer")}/mcp",
            headers = mapOf(
                "Authorization" to "${t.getString("token_type")} ${t.getString("access_token")}",
                "Accept" to "application/json, text/event-stream",
            ),
            body = body.toString(), contentType = "application/json",
        )
    }

    private fun mcp(method: String, params: JSONObject): JSONObject {
        var t = token()
        val init = JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", "1.0.0"))
        var initResp = mcpRaw(t, "initialize", init)
        if (initResp.status == 401) {
            t = token(forceRefresh = true)
            initResp = mcpRaw(t, "initialize", init)
        }
        if (initResp.status != 200) throw CorosException("MCP initialize failed (${initResp.status}): ${short(initResp.body)}")
        val resp = mcpRaw(t, method, params)
        val payload = parseJsonBody(resp)
        if (resp.status != 200) throw CorosException("MCP $method failed (${resp.status}): ${short(resp.body)}")
        if (payload.has("error")) throw CorosException("MCP $method error: ${short(payload.get("error").toString())}")
        return payload.optJSONObject("result") ?: throw CorosException("MCP $method: missing result")
    }

    fun listTools(): List<JSONObject> {
        val tools = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val result = mcp("tools/list", params)
            val arr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { tools.add(it) }
            cursor = result.optString("nextCursor").ifEmpty { null }
        } while (cursor != null)
        return tools
    }

    fun callTool(name: String, args: JSONObject): JSONObject =
        mcp("tools/call", JSONObject().put("name", name).put("arguments", args))

    // ---------------------------------------------------------------- helpers

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

/** Parses a JSON body, also handling `text/event-stream` responses (last `data:` event wins). */
internal fun parseJsonBody(resp: HttpResponse): JSONObject {
    val raw = resp.body.trim()
    if (raw.isEmpty()) return JSONObject()
    val ct = resp.header("content-type") ?: ""
    val text = if (ct.contains("text/event-stream") || raw.startsWith("event:") || raw.startsWith("data:")) {
        val events = mutableListOf<String>()
        val cur = mutableListOf<String>()
        for (line in raw.lines()) {
            if (line.isBlank()) {
                if (cur.isNotEmpty()) { events.add(cur.joinToString("\n")); cur.clear() }
            } else if (line.startsWith("data:")) {
                cur.add(line.removePrefix("data:").trimStart())
            }
        }
        if (cur.isNotEmpty()) events.add(cur.joinToString("\n"))
        events.lastOrNull() ?: return JSONObject()
    } else raw
    return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
}

private fun JSONObject.requireString(key: String): String {
    val v = optString(key)
    if (v.isEmpty()) throw CorosException("response is missing $key")
    return v
}

private fun short(s: String) = s.replace(Regex("\"(access_token|refresh_token|pollToken|loginTicket)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"***\"").take(300)
