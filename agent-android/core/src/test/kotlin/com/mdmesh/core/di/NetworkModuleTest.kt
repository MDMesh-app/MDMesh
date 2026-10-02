package com.mdmesh.core.di

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.net.BaseUrlInterceptor
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The agent has two OkHttp clients, and they must stay different. The API client rewrites every
 * request to the provisioned server (BaseUrlInterceptor). The download client must not: an APK URL
 * on another host has to reach that host, not our server. Each client gets one extra interceptor
 * appended last, which records the URL the client would have sent and answers without a network.
 */
class NetworkModuleTest {

    private val provisioned = "https://mdm.provisioned.test"

    /** A ServerConfigStore whose stored server URL is [url], with no Android runtime behind it. */
    private fun serverConfig(url: String): ServerConfigStore {
        val prefs = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, _ -> if (method.name == "getString") url else null } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        return ServerConfigStore(context)
    }

    /** The URL [client] sends for a GET of [url], after all of its own interceptors ran. */
    private fun sentUrl(client: OkHttpClient, url: String): HttpUrl {
        var seen: HttpUrl? = null
        val terminal = Interceptor { chain ->
            seen = chain.request().url
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()
        }
        client.newBuilder().addInterceptor(terminal).build()
            .newCall(Request.Builder().url(url).build())
            .execute()
            .close()
        return checkNotNull(seen)
    }

    @Test
    fun `download client keeps the host of an absolute APK URL`() {
        val apk = "https://cdn.vendor.test:8443/apps/app.apk?token=abc"

        assertEquals(apk, sentUrl(NetworkModule.provideDownloadOkHttp(), apk).toString())
    }

    @Test
    fun `download client carries no BaseUrlInterceptor`() {
        val client = NetworkModule.provideDownloadOkHttp()

        assertFalse((client.interceptors + client.networkInterceptors).any { it is BaseUrlInterceptor })
    }

    @Test
    fun `API client is still rewritten to the provisioned server`() {
        val client = NetworkModule.provideApiOkHttp(serverConfig(provisioned))

        val sent = sentUrl(client, "https://mdm.example.com/agent/v1/checkin?x=1")

        assertEquals("$provisioned/agent/v1/checkin?x=1", sent.toString())
    }
}
