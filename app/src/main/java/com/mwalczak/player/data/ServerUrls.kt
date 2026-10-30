package com.mwalczak.player.data

import java.net.URI
import java.net.URLDecoder

/** Pure helpers for the Xtream Codes URL scheme, kept free of Android types so they are unit-testable. */
object ServerUrls {

    /**
     * Accepts whatever the user typed ("host:8080", "http://host:8080/", ".../player_api.php", an M3U link)
     * and returns the bare base URL, e.g. "http://host:8080".
     */
    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "http://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: return s.trimEnd('/')
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: return s.trimEnd('/')
        val port = if (uri.port != -1) ":${uri.port}" else ""
        // Keep any sub-path the panel lives under, minus the known Xtream endpoints.
        val path = (uri.path ?: "")
            .replace(Regex("/(player_api\\.php|get\\.php|xmltv\\.php|panel_api\\.php).*$"), "")
            .trimEnd('/')
        return "$scheme://$host$port$path"
    }

    /**
     * Extracts credentials from an M3U playlist link such as
     * `http://host:8080/get.php?username=U&password=P&type=m3u_plus&output=ts`.
     */
    fun parseM3uLink(link: String): Credentials? {
        val trimmed = link.trim()
        val uri = runCatching { URI(if (trimmed.contains("://")) trimmed else "http://$trimmed") }.getOrNull()
            ?: return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()
        val user = params["username"] ?: return null
        val pass = params["password"] ?: return null
        return Credentials(normalizeServer(trimmed), user, pass)
    }

    fun playerApi(creds: Credentials, action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("${creds.server}/player_api.php?username=${enc(creds.username)}&password=${enc(creds.password)}")
        if (action != null) sb.append("&action=").append(action)
        extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
        return sb.toString()
    }

    fun liveStream(creds: Credentials, streamId: Int, format: StreamFormat): String =
        "${creds.server}/live/${enc(creds.username)}/${enc(creds.password)}/$streamId.${format.extension}"

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
