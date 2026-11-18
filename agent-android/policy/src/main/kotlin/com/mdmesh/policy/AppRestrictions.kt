package com.mdmesh.policy

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Bundle
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Per-app managed configuration ("app restrictions") used only by the additive `config.apply.appRestrictions`
 * section. Values can be secrets: an implementation never logs one or puts one in an outcome reason.
 */
interface AppRestrictions {
    /** Replace [packageName]'s whole bundle with exactly [values], then read it back. */
    fun set(packageName: String, values: Map<String, RestrictionValue>): AppRestrictionOutcome

    /** Remove MDMesh's bundle (an empty one). A package that is not installed has none: Android drops it then. */
    fun clear(packageName: String): AppRestrictionOutcome
}

/** A typed restriction value. [toString] leaves the value out, so it never reaches a log line. */
sealed interface RestrictionValue {
    /** True when [actual], as read back from a bundle, is this value with the same type. */
    fun matches(actual: Any?): Boolean

    class Bool(val value: Boolean) : RestrictionValue {
        override fun matches(actual: Any?) = actual is Boolean && actual == value
        override fun equals(other: Any?) = other is Bool && other.value == value
        override fun hashCode() = value.hashCode()
        override fun toString() = "Bool"
    }

    class Str(val value: String) : RestrictionValue {
        override fun matches(actual: Any?) = actual is String && actual == value
        override fun equals(other: Any?) = other is Str && other.value == value
        override fun hashCode() = value.hashCode()
        override fun toString() = "Str"
    }

    class Int32(val value: Int) : RestrictionValue {
        override fun matches(actual: Any?) = actual is Int && actual == value
        override fun equals(other: Any?) = other is Int32 && other.value == value
        override fun hashCode() = value
        override fun toString() = "Int32"
    }
}

/** True when [actual] (a read-back bundle, key to value) holds exactly [expected]'s keys with the same typed values. */
fun restrictionsMatch(expected: Map<String, RestrictionValue>, actual: Map<String, Any?>): Boolean =
    actual.keys == expected.keys && expected.all { (key, value) -> value.matches(actual[key]) }

sealed interface AppRestrictionOutcome {
    data object Applied : AppRestrictionOutcome
    /** The agent is not Device Owner. */
    data object Unsupported : AppRestrictionOutcome
    /** The configured app is not installed yet; config.apply is retried after app.install. */
    data object NotInstalled : AppRestrictionOutcome
    /** Android refused, or the read-back differs. [reason] never carries a value. */
    data class Failed(val reason: String) : AppRestrictionOutcome
}

/** Safe fallback for JVM wiring/tests before an Android Device Owner implementation is supplied. */
object UnsupportedAppRestrictions : AppRestrictions {
    override fun set(packageName: String, values: Map<String, RestrictionValue>) = AppRestrictionOutcome.Unsupported
    override fun clear(packageName: String) = AppRestrictionOutcome.Unsupported
}

/**
 * Android Device Owner implementation: `setApplicationRestrictions` replaces the package's whole bundle, and
 * `getApplicationRestrictions` reads it back. Platform exception messages are passed on; they name no value.
 */
class DeviceOwnerAppRestrictions(
    private val context: Context,
    private val handle: DpmHandle,
) : AppRestrictions {
    override fun set(packageName: String, values: Map<String, RestrictionValue>): AppRestrictionOutcome =
        preflight(packageName) ?: write(packageName, values)

    override fun clear(packageName: String): AppRestrictionOutcome = when (val pre = preflight(packageName)) {
        AppRestrictionOutcome.NotInstalled -> AppRestrictionOutcome.Applied // removed with the package
        null -> write(packageName, emptyMap())
        else -> pre
    }

    /** Null when the agent may write [packageName]'s bundle now, else the outcome that stops it. */
    private fun preflight(packageName: String): AppRestrictionOutcome? {
        if (!handle.dpm.isDeviceOwnerApp(handle.admin.packageName)) return AppRestrictionOutcome.Unsupported
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            null
        } catch (_: NameNotFoundException) {
            AppRestrictionOutcome.NotInstalled
        } catch (e: SecurityException) {
            AppRestrictionOutcome.Failed(e.message ?: "package lookup failed")
        }
    }

    private fun write(packageName: String, values: Map<String, RestrictionValue>): AppRestrictionOutcome =
        runCatching {
            handle.dpm.setApplicationRestrictions(handle.admin, packageName, values.toBundle())
            val readBack = handle.dpm.getApplicationRestrictions(handle.admin, packageName)
            if (restrictionsMatch(values, readBack.toMap())) {
                AppRestrictionOutcome.Applied
            } else {
                AppRestrictionOutcome.Failed("restrictions read back differ")
            }
        }.getOrElse { AppRestrictionOutcome.Failed(it.message ?: "app restrictions failed") }

    private fun Map<String, RestrictionValue>.toBundle(): Bundle = Bundle().also { bundle ->
        forEach { (key, value) ->
            when (value) {
                is RestrictionValue.Bool -> bundle.putBoolean(key, value.value)
                is RestrictionValue.Str -> bundle.putString(key, value.value)
                is RestrictionValue.Int32 -> bundle.putInt(key, value.value)
            }
        }
    }

    @Suppress("DEPRECATION") // Bundle.get(key): the typed getters can't tell a missing key from a wrong type.
    private fun Bundle.toMap(): Map<String, Any?> = keySet().associateWith { get(it) }
}
