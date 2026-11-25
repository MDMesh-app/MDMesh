package com.mdmesh.core.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Every way an install ends releases its download: deleted, and no longer shielded from the sweep. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstallManagerDownloadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val apk = "not really an apk".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun leftovers() =
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(DownloadSweep.PREFIX) }

    private fun manager(interceptor: Interceptor) = InstallManager(
        context,
        InstallResultBus(),
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        InstallIntegrityPolicy(),
    )

    private fun respond(chain: Interceptor.Chain, code: Int, body: ByteArray) = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .body(body.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    private fun request(sha256: String = sha) =
        InstallRequest(url = "http://lan/app.apk", packageName = "com.example.absent", sha256 = sha256)

    @Test
    fun `an install cancelled mid-download releases its file`() = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val manager = manager { chain ->
            entered.countDown()
            proceed.await(10, TimeUnit.SECONDS)
            respond(chain, 200, apk)
        }
        val job = launch(Dispatchers.Default) { manager.install(request()) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        proceed.countDown() // the blocking download finishes and writes its file; then the cancellation lands
        job.join()
        assertEquals(emptyList<java.io.File>(), leftovers())
    }

    @Test
    fun `a download that fails part-way releases its file`() = runBlocking {
        val partial = Buffer().write("half an apk".toByteArray())
        val reset = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long =
                if (partial.size > 0) partial.read(sink, byteCount) else throw IOException("connection reset")
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val outcome = manager { chain ->
            respond(chain, 200, ByteArray(0)).newBuilder()
                .body(reset.buffer().asResponseBody("application/octet-stream".toMediaType()))
                .build()
        }.install(request())
        assertEquals("apk fetch failed: connection reset", (outcome as InstallOutcome.Failure).reason)
        assertEquals(emptyList<java.io.File>(), leftovers())
    }

    @Test
    fun `a checksum mismatch releases its file`() = runBlocking {
        val outcome = manager { chain -> respond(chain, 200, apk) }.install(request(sha256 = "0".repeat(64)))
        assertTrue((outcome as InstallOutcome.Failure).reason.startsWith("checksum mismatch"))
        assertEquals(emptyList<java.io.File>(), leftovers())
    }

    /** Installs from an [https] (else HTTP) address that redirects to the other kind: was that one asked? */
    private fun followsRedirect(https: Boolean): Boolean = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { secure ->
            MockWebServer().use { plain ->
                secure.useHttps(tls.sslSocketFactory(), false)
                val (from, to) = if (https) secure to plain else plain to secure
                to.enqueue(MockResponse().setBody("not the APK"))
                from.enqueue(MockResponse().setResponseCode(302).setHeader("Location", to.url("/app.apk")))
                val client = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
                val outcome = InstallManager(context, InstallResultBus(), client, InstallIntegrityPolicy())
                    .install(request().copy(url = from.url("/app.apk").toString()))
                assertTrue(outcome is InstallOutcome.Failure)
                to.requestCount > 0
            }
        }
    }

    @Test
    fun `an APK named over HTTPS is not fetched over HTTP after a redirect, one named over HTTP may move to HTTPS`() {
        assertFalse(followsRedirect(https = true))
        assertTrue(followsRedirect(https = false))
    }

    @Test
    fun `an install cancelled while it waits for the platform releases its file`() = runBlocking {
        // No result broadcast comes here, so the install waits on the platform until the timeout cancels it (or
        // fails at the session, which also ends it).
        withTimeoutOrNull(3_000L) { manager { chain -> respond(chain, 200, apk) }.install(request()) }
        assertEquals(emptyList<java.io.File>(), leftovers())
    }
}
