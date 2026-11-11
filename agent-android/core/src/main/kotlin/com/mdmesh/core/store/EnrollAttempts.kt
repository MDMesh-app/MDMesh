package com.mdmesh.core.store

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The id of one enrollment attempt (protocol 1.6 `enrollAttempt`): random, generated before the first enroll request
 * and kept with the token it was made for, so every retry of that enrollment, including after the process died,
 * sends the same id and a retry whose response was lost gets the device the first request created.
 *
 * Pure (no Android) so the keep-or-replace rule is unit-tested; [EnrollTokenStore] persists the result.
 */
object EnrollAttempts {

    /** What ties a stored attempt to its token, without storing the token a second time. */
    fun bindingOf(token: String): String = hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /** 128 random bits as 32 hex characters (matches the server's `[A-Za-z0-9_-]{16,128}`). */
    fun newId(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).let(::hex)

    /**
     * The attempt id to send with [token]: the stored one when it was made for this token, else a new one (a new
     * token is a new enrollment).
     *
     * @return the id and whether it must be stored (it is new)
     */
    fun resolve(storedBinding: String?, storedId: String?, token: String, newId: () -> String): Pair<String, Boolean> {
        if (!storedId.isNullOrBlank() && storedBinding == bindingOf(token)) return storedId to false
        return newId() to true
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
