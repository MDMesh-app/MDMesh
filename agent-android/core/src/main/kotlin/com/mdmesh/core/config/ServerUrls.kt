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
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val url = (if ("://" in s) s else "https://$s").toHttpUrlOrNull()
        return url?.takeIf { it.host.isNotEmpty() }?.let(::origin)
    }

    /**
     * What to store when provisioning hands over [provided], given what is [stored]; null: keep what is stored.
     * A valid address is stored normalised. An invalid one never replaces a valid one, but is kept as given when there
     * is nothing valid yet, so requests fail naming it instead of quietly going to the baked default host.
     */
    fun toStore(provided: String?, stored: String?): String? {
        val normalized = normalize(provided)
        return when {
            provided.isNullOrBlank() -> null
            normalized != null -> normalized
            normalize(stored) == null -> provided.trim()
            else -> null
        }
    }

    /**
     * The server the agent uses. [stored] is what provisioning saved; [legacy] says an agent before these rules saved
     * it (as given, unchecked). For such a value the old meaning is kept, so an update never moves an enrolled device
     * to another server: a value it could not parse sent requests to the baked [fallback]. Values saved under these
     * rules are normalised, and one that isn't an address is returned as given so requests fail naming it.
     */
    fun effective(stored: String?, legacy: Boolean, fallback: String): String {
        val baked = normalize(fallback) ?: fallback.trim().trimEnd('/')
        val value = stored?.takeIf { it.isNotBlank() }
        return when {
            value == null -> baked
            legacy && value.toHttpUrlOrNull() == null -> baked
            else -> normalize(value) ?: value.trim().trimEnd('/')
        }
    }

    private fun origin(url: HttpUrl): String {
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        val host = if (':' in url.host) "[${url.host}]" else url.host // IPv6 literal
        return if (url.port == defaultPort) "${url.scheme}://$host" else "${url.scheme}://$host:${url.port}"
    }
}
