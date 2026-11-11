package com.mdmesh.core.sync

import com.mdmesh.core.net.InvalidServerAddressException
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class EnrollmentFailureTest {

    private val finalMarkers = listOf("token.used", "token.invalid", "token.expired", "enrollment.disabled")

    private fun network(t: Throwable) = EnrollmentFailure.forException(t, "mdm.acme.test", now = 0L)

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `server refusals say what to do and end with their key`() {
        val used = EnrollmentFailure.forServer("error.agent.token.used")
        assertTrue(used, used.contains("Make a new code"))
        assertTrue(used.endsWith("(error.agent.token.used)"))
        assertTrue(EnrollmentFailure.forServer("error.agent.token.expired").contains("expired"))
        assertTrue(EnrollmentFailure.forServer("error.agent.token.invalid").contains("doesn't know this enrollment code"))
        assertTrue(EnrollmentFailure.forServer("error.agent.enrollment.disabled").contains("default configuration"))
        assertTrue(EnrollmentFailure.forServer("error.agent.enrollment.failed").contains("Trying again"))
        assertTrue(EnrollmentFailure.forServer(null).startsWith("The server refused enrollment."))
        assertTrue(EnrollmentFailure.forServer("error.new.thing").endsWith("(error.new.thing)"))
    }

    @Test
    fun `a customer at its device limit is told plainly`() {
        val message = EnrollmentFailure.forServer("error.agent.enrollment.deviceLimit")
        assertTrue(message, message.startsWith("This organisation has reached its device limit."))
        assertTrue(message, message.contains("Ask your admin"))
    }

    @Test
    fun `the retryable server errors don't read as final`() {
        for (key in listOf(
            "error.agent.enrollment.failed",
            "error.agent.enrollment.inProgress",
            "error.agent.enrollment.deviceLimit",
        )) {
            val message = EnrollmentFailure.forServer(key)
            assertTrue(message, message.contains("Trying again") || message.contains("by itself"))
            finalMarkers.forEach { assertFalse(message, message.contains(it)) }
        }
    }

    @Test
    fun `network failures say what happened and are never read as final`() {
        val cases = mapOf(
            UnknownHostException("mdm.acme.test") to "Can't find the server mdm.acme.test.",
            SocketTimeoutException("timeout") to "The server mdm.acme.test didn't answer in time.",
            ConnectException("refused") to "Can't connect to the server mdm.acme.test",
            InvalidServerAddressException("mdm.acme.test/x") to "The server address in the QR code (\"mdm.acme.test/x\")",
            UnknownServiceException("CLEARTEXT communication to mdm.acme.test not permitted") to "uses http://",
            SSLPeerUnverifiedException("Hostname mdm.acme.test not verified") to "certificate is for a different name",
            SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification " +
                "path not found.") to "doesn't trust the server mdm.acme.test's certificate",
            http(404) to "isn't an MDMesh server (HTTP 404)",
            http(502) to "has a problem (HTTP 502)",
            SerializationException("Unexpected JSON token") to "didn't come from an MDMesh server",
            IllegalStateException("boom") to "Enrollment didn't go through (IllegalStateException: boom)",
        )
        cases.forEach { (t, expected) ->
            val message = network(t)
            assertTrue("$t -> $message", message.contains(expected))
            finalMarkers.forEach { assertFalse(message, message.contains(it)) }
        }
    }

    @Test
    fun `a certificate date error points at the device clock`() {
        val t = SSLHandshakeException("handshake").apply {
            initCause(CertPathValidatorException("timestamp check failed", CertificateExpiredException("expired")))
        }
        // Noon UTC: the same calendar day in the test machine's time zone.
        val message = EnrollmentFailure.forException(t, "mdm.acme.test", now = 1_699_963_200_000L)
        assertTrue(message, message.contains("This device's date (2023-11-14) may be wrong"))
    }

    @Test
    fun `an untrusted certificate found in the cause chain is named`() {
        val t = SSLHandshakeException("handshake").apply { initCause(CertPathValidatorException("no anchor")) }
        assertTrue(network(t).contains("doesn't trust"))
    }

    @Test
    fun `without a host it still reads`() {
        assertTrue(EnrollmentFailure.forException(SocketTimeoutException(), null).startsWith("The server didn't"))
    }
}
