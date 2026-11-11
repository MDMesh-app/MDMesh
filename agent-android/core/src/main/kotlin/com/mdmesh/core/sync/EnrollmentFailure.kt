package com.mdmesh.core.sync

import com.mdmesh.core.net.InvalidServerAddressException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What the person holding the device reads when enrollment does not go through: what happened and what to do, in
 * place of a server key or an exception's text. Shown on the agent's screen ("Enrolling… (last error: …)").
 *
 * A server refusal keeps its key at the end in brackets: it is what an admin searches the server log for, and what
 * [CheckInWorker] matches to stop retrying a code that can never work. Network messages never contain those keys.
 *
 * Pure (no Android) so every case is unit-tested.
 */
object EnrollmentFailure {

    /** Shown when the device has no enrollment code at all. */
    const val NO_CODE = "This device has no enrollment code: it wasn't set up with an MDMesh QR code. " +
        "Factory-reset it and scan a QR code from the console's Enroll page."

    /** The server refused enrollment with [key] (`error.agent.…`). */
    fun forServer(key: String?): String {
        val k = key ?: "error.agent.enrollment.refused"
        val text = when {
            k.endsWith("token.invalid") -> "The server doesn't know this enrollment code. Make sure the QR code was " +
                "made on this server's console, or make a new code, factory-reset the device and scan it."
            k.endsWith("token.used") -> "This enrollment code has already enrolled all the devices it was made for, " +
                "or was stopped. Make a new code on the Enroll page, factory-reset the device and scan it."
            k.endsWith("token.expired") -> "This enrollment code has expired. Make a new code on the Enroll page, " +
                "factory-reset the device and scan it."
            k.endsWith("enrollment.disabled") -> "The server isn't accepting new devices: it has no configuration " +
                "for them. An admin can choose a default configuration in Settings, or make the code with one."
            k.endsWith("enrollment.failed") -> "The server had a problem enrolling this device. Trying again " +
                "automatically."
            k.endsWith("enrollment.deviceLimit") -> "This organisation has reached its device limit. Ask your " +
                "admin to make room; this device then enrolls by itself."
            k.endsWith("enrollment.inProgress") -> "The server is still enrolling this device. Trying again " +
                "automatically."
            else -> "The server refused enrollment."
        }
        return "$text ($k)"
    }

    /**
     * Enrollment could not reach a server answer: [t] is what failed, [host] the server it was for (may be blank),
     * [now] the device clock (for certificate date errors). The first rule that matches [t] words it; the order
     * matters (an address or clock problem is named before the TLS failure it causes).
     */
    fun forException(t: Throwable, host: String?, now: Long = System.currentTimeMillis()): String {
        val failure = Failure(t, host?.takeIf { it.isNotBlank() }?.let { "the server $it" } ?: "the server", now)
        return rules.firstOrNull { it.matches(failure) }?.words?.invoke(failure) ?: unexpected(t)
    }

    /** What failed, for the rules: [server] is "the server <host>" or "the server". */
    private class Failure(val t: Throwable, val server: String, val now: Long) {
        val serverCap: String get() = server.replaceFirstChar { it.uppercase() }
        val message: String get() = t.message.orEmpty()
    }

    /** One kind of failure: how to recognise it, and what to say. */
    private class Rule(val matches: (Failure) -> Boolean, val words: (Failure) -> String)

    private val rules: List<Rule> = listOf(
        Rule({ it.t is InvalidServerAddressException }) {
            "The server address in the QR code (\"${(it.t as InvalidServerAddressException).address}\") isn't a " +
                "valid https:// address. Make the QR code on the console opened at its public address, then " +
                "factory-reset the device and scan it."
        },
        Rule({ it.t is UnknownServiceException && it.message.contains("CLEARTEXT", ignoreCase = true) }) {
            "The server address in the QR code uses http://, which Android doesn't allow. Open the console at its " +
                "https:// address, make the QR code there, then factory-reset the device and scan it."
        },
        Rule({ it.t is UnknownHostException }) {
            "Can't find ${it.server}. Check that this device is online, and that the server address in the QR code " +
                "is right. Trying again automatically."
        },
        Rule({ it.t is SocketTimeoutException }) {
            "${it.serverCap} didn't answer in time. Trying again automatically."
        },
        Rule({ it.t is ConnectException || it.t is NoRouteToHostException }) {
            "Can't connect to ${it.server}: it may be down, or blocked on this network. Trying again automatically."
        },
        Rule({ it.t.hasCause<CertificateExpiredException>() || it.t.hasCause<CertificateNotYetValidException>() }) {
            "Can't open a secure connection to ${it.server}. This device's date (${date(it.now)}) may be wrong: set " +
                "the date and time, and enrollment continues. Otherwise the server's certificate has expired."
        },
        Rule({
            it.t is SSLPeerUnverifiedException ||
                (it.t is SSLException && it.message.contains("Hostname", ignoreCase = true))
        }) {
            "${it.serverCap}'s certificate is for a different name. If this Wi-Fi asks you to sign in, sign in " +
                "first; otherwise use the address the certificate was issued for."
        },
        Rule({ it.t.hasCause<CertPathValidatorException>() || it.message.contains("Trust anchor") }) {
            "This device doesn't trust ${it.server}'s certificate (self-signed, or from a private authority). Give " +
                "the server a certificate from a public authority such as Let's Encrypt."
        },
        Rule({ it.t is SSLException }) {
            "Can't open a secure connection to ${it.server}. If this Wi-Fi asks you to sign in, sign in first. " +
                "Trying again automatically."
        },
        Rule({ it.t is HttpException }) { httpWords(it, (it.t as HttpException).code()) },
        Rule({ it.t is SerializationException }) {
            "The answer didn't come from an MDMesh server. If this Wi-Fi asks you to sign in, sign in first; " +
                "otherwise check the server address in the QR code."
        },
    )

    private fun httpWords(f: Failure, code: Int): String = when {
        code == HTTP_NOT_FOUND -> "${f.serverCap} answers, but isn't an MDMesh server (HTTP $code). Check the " +
            "server address in the QR code."
        code >= HTTP_SERVER_ERROR -> "${f.serverCap} has a problem (HTTP $code). Trying again automatically."
        else -> "${f.serverCap} refused the request (HTTP $code). Trying again automatically."
    }

    private fun unexpected(t: Throwable): String =
        "Enrollment didn't go through (${t.javaClass.simpleName}" + (t.message?.let { ": $it" } ?: "") +
            "). Trying again automatically."

    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_SERVER_ERROR = 500

    /** How far down a cause chain to look (TLS failures nest the real cause a few levels deep). */
    private const val MAX_CAUSE_DEPTH = 8

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH).any { it is T }

    private fun date(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
}
