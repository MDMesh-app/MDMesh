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
            k.endsWith("enrollment.inProgress") -> "The server is still enrolling this device. Trying again " +
                "automatically."
            else -> "The server refused enrollment."
        }
        return "$text ($k)"
    }

    /**
     * Enrollment could not reach a server answer: [t] is what failed, [host] the server it was for (may be blank),
     * [now] the device clock (for certificate date errors).
     */
    fun forException(t: Throwable, host: String?, now: Long = System.currentTimeMillis()): String {
        val server = host?.takeIf { it.isNotBlank() }?.let { "the server $it" } ?: "the server"
        val serverCap = server.replaceFirstChar { it.uppercase() }
        return when {
            t is InvalidServerAddressException -> "The server address in the QR code (\"${t.address}\") isn't a " +
                "valid https:// address. Make the QR code on the console opened at its public address, then " +
                "factory-reset the device and scan it."
            t is UnknownServiceException && t.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) ->
                "The server address in the QR code uses http://, which Android doesn't allow. Open the console at " +
                    "its https:// address, make the QR code there, then factory-reset the device and scan it."
            t is UnknownHostException -> "Can't find $server. Check that this device is online, and that the " +
                "server address in the QR code is right. Trying again automatically."
            t is SocketTimeoutException -> "$serverCap didn't answer in time. Trying again automatically."
            t is ConnectException || t is NoRouteToHostException -> "Can't connect to $server: it may be down, or " +
                "blocked on this network. Trying again automatically."
            t.hasCause<CertificateExpiredException>() || t.hasCause<CertificateNotYetValidException>() ->
                "Can't open a secure connection to $server. This device's date (${date(now)}) may be wrong: set the " +
                    "date and time, and enrollment continues. Otherwise the server's certificate has expired."
            t is SSLPeerUnverifiedException ||
                (t is SSLException && t.message.orEmpty().contains("Hostname", ignoreCase = true)) ->
                "$serverCap's certificate is for a different name. If this Wi-Fi asks you to sign in, sign in " +
                    "first; otherwise use the address the certificate was issued for."
            t.hasCause<CertPathValidatorException>() || t.message.orEmpty().contains("Trust anchor") ->
                "This device doesn't trust $server's certificate (self-signed, or from a private authority). Give " +
                    "the server a certificate from a public authority such as Let's Encrypt."
            t is SSLException -> "Can't open a secure connection to $server. If this Wi-Fi asks you to sign in, " +
                "sign in first. Trying again automatically."
            t is HttpException && t.code() == 404 -> "$serverCap answers, but isn't an MDMesh server (HTTP 404). " +
                "Check the server address in the QR code."
            t is HttpException && t.code() >= 500 -> "$serverCap has a problem (HTTP ${t.code()}). Trying again " +
                "automatically."
            t is HttpException -> "$serverCap refused the request (HTTP ${t.code()}). Trying again automatically."
            t is SerializationException -> "The answer didn't come from an MDMesh server. If this Wi-Fi asks you to " +
                "sign in, sign in first; otherwise check the server address in the QR code."
            else -> "Enrollment didn't go through (${t.javaClass.simpleName}" +
                (t.message?.let { ": $it" } ?: "") + "). Trying again automatically."
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(8).any { it is T }

    private fun date(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
}
