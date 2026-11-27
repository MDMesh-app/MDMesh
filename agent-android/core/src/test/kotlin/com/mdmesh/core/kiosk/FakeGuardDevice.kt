package com.mdmesh.core.kiosk

import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import com.mdmesh.kiosk.KioskGuardControl

/** Thrown by a fault hook to cut the process off between two device calls. */
internal class ProcessCut : RuntimeException("process cut")

/**
 * A device for [KioskGuard]: it holds the state DPM would hold and logs every change in order ([log]), shared with a
 * record store ([LoggedRecords]) so tests see whether the record came first. [beforeChange] runs before each change:
 * a test throws [ProcessCut] from it to cut the process off there.
 */
internal class FakeGuardDevice(
    var policy: Int = PERMISSION_POLICY_PROMPT,
    var sensorsGrantable: Boolean = true,
    /** Installed apps and their runtime permissions. */
    val apps: MutableMap<String, List<String>> = mutableMapOf(),
) : KioskGuardControl {
    val log = mutableListOf<String>()
    /** Fixed-by-policy states (GRANTED / DENIED); a pair absent is DEFAULT. */
    val fixed = mutableMapOf<String, Int>()
    val granted = mutableSetOf<String>()
    val uninstallBlocked = mutableSetOf<String>()
    val restrictions = mutableSetOf<String>()
    var owner = true
    /** Policy setter accepts the call but the device keeps the old value (an OEM that ignores it). */
    var ignorePolicy = false
    var beforeChange: (String) -> Unit = {}
    var installedListings = 0
    /** Called with every policy actually set: a test checks it against lock task's state. */
    var onPolicy: (Int) -> Unit = {}

    private fun change(what: String) { beforeChange(what); log += what }

    override fun isDeviceOwner() = owner
    /** The policy can't be read now. */
    var policyUnreadable = false
    override fun permissionPolicy(): Int? = if (policyUnreadable) null else policy
    override fun setPermissionPolicy(policy: Int): Boolean {
        change("policy:$policy")
        if (!ignorePolicy) { this.policy = policy; onPolicy(policy) }
        return this.policy == policy
    }
    override fun grantState(pkg: String, permission: String): Int? =
        if (KioskGuard.pair(pkg, permission) in unreadableStates) {
            null
        } else {
            fixed[KioskGuard.pair(pkg, permission)] ?: PERMISSION_GRANT_STATE_DEFAULT
        }
    override fun setGrantState(pkg: String, permission: String, state: Int): Boolean {
        val pair = KioskGuard.pair(pkg, permission)
        change("grant:$pair:$state")
        when (state) {
            PERMISSION_GRANT_STATE_DEFAULT -> fixed -= pair // the grant itself stays, as on a device
            PERMISSION_GRANT_STATE_GRANTED -> { fixed[pair] = state; granted += pair }
            PERMISSION_GRANT_STATE_DENIED -> { fixed[pair] = state; granted -= pair }
        }
        return true
    }
    override fun isGranted(pkg: String, permission: String) = KioskGuard.pair(pkg, permission) in granted
    override fun canAdminGrantSensors() = sensorsGrantable
    override fun requestedRuntimePermissions(pkg: String): List<String>? = apps[pkg]
    /** The apps can't be listed now. */
    var unlistable = false
    /** These pairs' grant states can't be read now. */
    val unreadableStates = mutableSetOf<String>()
    override fun runtimePermissionsByApp(): Map<String, List<String>>? {
        installedListings++
        return if (unlistable) null else apps.toMap()
    }
    override fun installedPackages(): List<String>? {
        installedListings++
        return if (unlistable) null else apps.keys.toList()
    }
    /** Apps whose uninstall block the device won't lift. */
    val stuckBlocked = mutableSetOf<String>()
    override fun setUninstallBlocked(pkg: String, blocked: Boolean): Boolean {
        if (!blocked && pkg in stuckBlocked) return false
        change("uninstallBlocked:$pkg:$blocked")
        if (blocked) uninstallBlocked += pkg else uninstallBlocked -= pkg
        return true
    }
    override fun isUninstallBlocked(pkg: String): Boolean? = pkg in uninstallBlocked
    override fun holdsRestriction(key: String): Boolean? = key in restrictions
    override fun setRestriction(key: String, on: Boolean): Boolean {
        if ((key in restrictions) == on) return true
        change("restriction:$key:$on")
        if (on) restrictions += key else restrictions -= key
        return true
    }

    /** What a request answered under the auto-grant does: granted and fixed by policy. */
    fun requestUnderPolicy(pkg: String, permission: String) {
        val pair = KioskGuard.pair(pkg, permission)
        if (policy == PERMISSION_POLICY_AUTO_GRANT && pair !in fixed) {
            fixed[pair] = PERMISSION_GRANT_STATE_GRANTED
            granted += pair
        }
    }
}

/** A [GuardRecordStore] that logs saves and clears into [device]'s log, and can be made unwritable. */
internal class LoggedRecords(private val device: FakeGuardDevice) : GuardRecordStore {
    var record: GuardRecord? = null
    var writable = true
    /** Reads fail (storage that can't be read now). */
    var unreadable = false
    override fun load(): GuardRecord? = if (unreadable) throw java.io.IOException("unreadable") else record
    override fun save(record: GuardRecord): Boolean {
        device.beforeChange("record:save")
        if (!writable) return false
        device.log += "record:save"
        this.record = record
        return true
    }
    override fun clear(): Boolean {
        device.beforeChange("record:clear")
        if (!writable) return false
        device.log += "record:clear"
        record = null
        return true
    }
}
