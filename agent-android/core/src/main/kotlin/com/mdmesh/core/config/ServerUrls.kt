package com.mdmesh.core.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one rule for what a server address is. The QR's `com.mdmesh.SERVER_URL` is typed or derived by people and
 * consoles, so it can come with spaces, a trailing slash, no scheme, or a path. The agent talks to the server at the
 * root of its origin (REST under `/rest/…`, the wake socket under `/agent/ws/…`), so the address is reduced to
 * `scheme://host[:port]`; both channels then always reach the same place.
 */
object ServerUrls {

    /**
     * The server origin for [raw], without a trailing slash, or null when it isn't an http(s) address with a host.
     * A missing scheme means https (the agent speaks HTTPS; Android blocks cleartext for it).
     */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in s) s else "https://$s"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        return origin(url)
    }

    /**
     * What to store when provisioning hands over [provided], given what is [stored]; null: keep what is stored.
     * A valid address is stored normalised. An invalid one never replaces a valid one, but is kept as given when there
     * is nothing valid yet, so requests fail naming it instead of quietly going to the baked default host.
     */
    fun toStore(provided: String?, stored: String?): String? {
        if (provided.isNullOrBlank()) return null
        normalize(provided)?.let { return it }
        return if (normalize(stored) == null) provided.trim() else null
    }

    private fun origin(url: HttpUrl): String {
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        val host = if (':' in url.host) "[${url.host}]" else url.host // IPv6 literal
        return if (url.port == defaultPort) "${url.scheme}://$host" else "${url.scheme}://$host:${url.port}"
    }
}
