package com.mdmesh.core.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BaseUrlInterceptorTest {

    /** Runs a request through the interceptor and returns the URL it would have been sent to (no network). */
    private fun sentTo(base: String): String {
        var seen = ""
        val client = OkHttpClient.Builder()
            .addInterceptor(BaseUrlInterceptor { base })
            .addInterceptor { chain ->
                seen = chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url("https://mdm.example.com/rest/public/agent/v1/enroll").build()).execute()
            .close()
        return seen
    }

    @Test
    fun `requests go to the configured server, path kept`() {
        assertEquals("https://mdm.acme.test:8443/rest/public/agent/v1/enroll", sentTo("https://mdm.acme.test:8443"))
    }

    @Test
    fun `an unusable address fails the request naming it instead of using the placeholder`() {
        try {
            sentTo("mdm.acme.test/oops")
            fail("expected InvalidServerAddressException")
        } catch (e: InvalidServerAddressException) {
            assertEquals("mdm.acme.test/oops", e.address)
            assertTrue(e.message!!.contains("mdm.acme.test/oops"))
        }
    }
}
