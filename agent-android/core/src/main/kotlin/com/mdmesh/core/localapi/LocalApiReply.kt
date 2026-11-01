package com.mdmesh.core.localapi

/** The `code` of every [LocalApiReply]. */
object LocalApiCode {
    const val OK = "OK"

    /** A required extra is missing, has the wrong type or an impossible value. */
    const val BAD_REQUEST = "BAD_REQUEST"

    /** The caller may not use this method; `reason` says why ([LocalApi.Denial]). */
    const val UNAUTHORIZED = "UNAUTHORIZED"

    /** No saved network has that SSID. */
    const val NOT_FOUND = "NOT_FOUND"

    /** The platform said no (`WifiManager` returned false or -1). */
    const val REFUSED = "REFUSED"

    /** Unknown method, or not possible on this device or API level. */
    const val UNSUPPORTED = "UNSUPPORTED"

    /** Something threw; the message names the exception class only. */
    const val ERROR = "ERROR"
}

/** Keys of the call extras and of the reply. */
object LocalApiKeys {
    const val OK = "ok"
    const val CODE = "code"
    const val MESSAGE = "message"
    const val API_VERSION = "apiVersion"
    const val METHODS = "methods"
    const val AUTHORIZED = "authorized"
    const val REASON = "reason"
    const val NETWORK_ID = "networkId"
    const val SSIDS = "ssids"

    const val SSID = "ssid"
    const val PASSWORD = "password"
    const val SECURITY = "security"
    const val ENABLED = "enabled"
}

/** A call's extras, Android-free: the provider copies the Bundle in. A value of the wrong type reads as absent. */
class LocalApiArgs(private val values: Map<String, Any?> = emptyMap()) {
    fun string(key: String): String? = values[key] as? String

    fun boolean(key: String): Boolean? = values[key] as? Boolean
}

/**
 * One reply of the [LocalApi]; the provider turns [fields] into the result Bundle. [message] is for people and logs:
 * it never carries a password or an SSID.
 */
data class LocalApiReply(
    val code: String,
    val message: String,
    /** The method's own keys: Int, Boolean, String or List<String> values. */
    val values: Map<String, Any> = emptyMap(),
) {
    val ok: Boolean get() = code == LocalApiCode.OK

    /** Every key of the reply: `ok`, `code`, `message` and `apiVersion`, then [values]. */
    fun fields(): Map<String, Any> = linkedMapOf<String, Any>(
        LocalApiKeys.OK to ok,
        LocalApiKeys.CODE to code,
        LocalApiKeys.MESSAGE to message,
        LocalApiKeys.API_VERSION to LocalApi.API_VERSION,
    ) + values

    companion object {
        fun ok(message: String, values: Map<String, Any> = emptyMap()) = LocalApiReply(LocalApiCode.OK, message, values)

        fun badRequest(message: String) = LocalApiReply(LocalApiCode.BAD_REQUEST, message)

        fun notFound() = LocalApiReply(LocalApiCode.NOT_FOUND, "no saved network has that SSID")

        fun refused(message: String, values: Map<String, Any> = emptyMap()) =
            LocalApiReply(LocalApiCode.REFUSED, message, values)

        fun unsupported(message: String) = LocalApiReply(LocalApiCode.UNSUPPORTED, message)

        fun error(message: String) = LocalApiReply(LocalApiCode.ERROR, message)
    }
}
