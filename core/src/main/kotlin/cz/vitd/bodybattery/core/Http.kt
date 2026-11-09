package cz.vitd.bodybattery.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** Minimal HTTP client. Redirects are never followed (the OAuth flow needs the Location header). */
open class Http(private val timeoutMs: Int = 30_000) {
    private val cookies = linkedMapOf<String, String>()

    open fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "BodyBattery-Android/1.0")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            synchronized(cookies) {
                if (cookies.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hdrs = mutableMapOf<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k == null || v.isNullOrEmpty()) continue
                if (k.equals("Set-Cookie", ignoreCase = true)) {
                    for (raw in v) captureCookie(raw)
                }
                hdrs[k.lowercase()] = v.last()
            }
            return HttpResponse(status, hdrs, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun captureCookie(raw: String) {
        val nameValue = raw.substringBefore(';')
        val idx = nameValue.indexOf('=')
        if (idx > 0) synchronized(cookies) {
            cookies[nameValue.substring(0, idx).trim()] = nameValue.substring(idx + 1).trim()
        }
    }
}

internal fun formEncode(params: Map<String, String>): String =
    params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

internal fun queryParams(url: String): Map<String, String> {
    val q = url.substringAfter('?', "").substringBefore('#')
    if (q.isEmpty()) return emptyMap()
    return q.split('&').filter { it.isNotEmpty() }.associate {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}
