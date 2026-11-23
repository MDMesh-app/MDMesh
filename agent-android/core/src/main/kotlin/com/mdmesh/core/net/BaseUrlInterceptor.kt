package com.mdmesh.core.net

import com.mdmesh.core.config.ServerConfigStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Rewrites each request's scheme/host/port to the currently-configured server
 * ([ServerConfigStore.baseUrl]) so the Retrofit base URL can be a static placeholder and the real
 * target is resolved per-request — the server URL is only known after provisioning. Paths/queries
 * are preserved (deployments are hosted at the domain root).
 *
 * An address that can't be parsed fails the request with [InvalidServerAddressException] naming it. It used to fall
 * through to the placeholder, which sent a device provisioned with a broken address to the baked default host.
 */
class BaseUrlInterceptor(private val baseUrl: () -> String) : Interceptor {

    constructor(serverConfig: ServerConfigStore) : this({ serverConfig.baseUrl() })

    override fun intercept(chain: Interceptor.Chain): Response {
        val raw = baseUrl()
        val configured = raw.toHttpUrlOrNull() ?: throw InvalidServerAddressException(raw)
        val req = chain.request()
        val newUrl = req.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()
        return chain.proceed(req.newBuilder().url(newUrl).build())
    }
}

/** The configured server address isn't a usable http(s) address. */
class InvalidServerAddressException(val address: String) :
    IOException("server address \"$address\" is not a valid https:// address")
