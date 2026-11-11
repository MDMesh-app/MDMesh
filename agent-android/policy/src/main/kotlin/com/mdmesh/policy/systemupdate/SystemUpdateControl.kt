package com.mdmesh.policy.systemupdate

import android.app.admin.SystemUpdatePolicy
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.PolicyStrategy
import com.mdmesh.policy.ProbeErrorLog
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.ConfigSystemUpdate

/** When the device installs OS updates: what [SystemUpdateControl] asks the platform to set. */
sealed interface SystemUpdateInstall {
    /** As soon as an update is available. */
    data object Automatic : SystemUpdateInstall

    /** Every day between [start] and [end], minutes after local midnight; the window may cross midnight. */
    data class Windowed(val start: Int, val end: Int) : SystemUpdateInstall

    /** Held back for up to 30 days, the platform's limit. */
    data object Postpone : SystemUpdateInstall
}

/** The platform call [SystemUpdateControl] makes, so its decisions are testable without Android. */
fun interface SystemUpdatePlatform {
    /** Set the device's system update policy; null clears it (the device default). Throws when refused. */
    fun set(install: SystemUpdateInstall?)
}

/**
 * `systemUpdatePolicy` (protocol 1.8): how the device installs OS updates, set by the config.apply `systemUpdate`
 * section. Not a toggle, so not routed by `policy.apply` or `policies`: [apply] sets what a section asks for, and
 * [release] clears it once a configuration stops managing it.
 *
 * `DevicePolicyManager.setSystemUpdatePolicy` needs Device Owner, checked on every call because Device Owner may arrive
 * after the process started (#76). Its API level (23) is below this module's floor, so that is the only condition.
 */
class SystemUpdateControl(
    private val platform: SystemUpdatePlatform,
    private val isDeviceOwner: () -> Boolean,
) : PolicyStrategy, ReleasablePolicy {

    override val capabilityKey: String = CAPABILITY_KEY

    /** The last answer of [isSupported], kept through a check that throws ([advertisedKeys]). */
    @Volatile private var supported = false

    override fun isSupported(): Boolean = isDeviceOwner()

    /**
     * [CAPABILITY_KEY] while the policy can be set, for the capability matrix. A check that throws keeps the last
     * answer, as a toggle that resolved before stays advertised: the error is not "unsupported".
     */
    fun advertisedKeys(log: ProbeErrorLog? = null): List<String> {
        runCatching { isSupported() }
            .onSuccess { supported = it; log?.recovered(CAPABILITY_KEY) }
            .onFailure { log?.failed(CAPABILITY_KEY, it) }
        return if (supported) listOf(CAPABILITY_KEY) else emptyList()
    }

    /** Sets what [section] asks for. Unsupported (final) when this agent can't; a check that throws fails. */
    fun apply(section: ConfigSystemUpdate): PolicyOutcome = runCatching {
        val install = installFor(section)
        if (install == null || !isSupported()) {
            PolicyOutcome.Unsupported
        } else {
            platform.set(install)
            PolicyOutcome.Applied
        }
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "system update policy failed") }

    /** Clears the policy, back to the device default. */
    override fun release(): PolicyOutcome = runCatching {
        if (isSupported()) {
            platform.set(null)
            PolicyOutcome.Applied
        } else {
            PolicyOutcome.Unsupported
        }
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "system update policy release failed") }

    companion object {
        const val CAPABILITY_KEY = "systemUpdatePolicy"

        /** Window times are minutes after local midnight, below this. */
        private const val MINUTES_PER_DAY = 1440

        /** What [section] asks for, or null when this agent can't honour it: an unknown policy or a bad window. */
        fun installFor(section: ConfigSystemUpdate): SystemUpdateInstall? = when (section.policy) {
            ConfigSystemUpdate.AUTOMATIC -> SystemUpdateInstall.Automatic
            ConfigSystemUpdate.POSTPONE -> SystemUpdateInstall.Postpone
            ConfigSystemUpdate.WINDOWED -> window(section.windowStart, section.windowEnd)
            else -> null
        }

        private fun window(start: Int?, end: Int?): SystemUpdateInstall.Windowed? = when {
            start == null || end == null -> null
            // Android takes equal times as the whole day, which no one setting a window means.
            start !in 0 until MINUTES_PER_DAY || end !in 0 until MINUTES_PER_DAY || start == end -> null
            else -> SystemUpdateInstall.Windowed(start, end)
        }
    }
}

/** [SystemUpdatePlatform] over `DevicePolicyManager.setSystemUpdatePolicy`. */
class DpmSystemUpdatePlatform(private val handle: DpmHandle) : SystemUpdatePlatform {
    override fun set(install: SystemUpdateInstall?) {
        handle.dpm.setSystemUpdatePolicy(handle.admin, policyFor(install))
    }

    internal companion object {
        /** The platform policy for [install]; null clears it. */
        fun policyFor(install: SystemUpdateInstall?): SystemUpdatePolicy? = when (install) {
            null -> null
            SystemUpdateInstall.Automatic -> SystemUpdatePolicy.createAutomaticInstallPolicy()
            is SystemUpdateInstall.Windowed ->
                SystemUpdatePolicy.createWindowedInstallPolicy(install.start, install.end)
            SystemUpdateInstall.Postpone -> SystemUpdatePolicy.createPostponeInstallPolicy()
        }
    }
}
