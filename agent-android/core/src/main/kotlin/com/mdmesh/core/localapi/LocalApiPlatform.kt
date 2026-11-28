package com.mdmesh.core.localapi

import android.content.Context
import android.os.Build
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.KioskApplyPayload

/** What authorizing a [LocalApi] caller needs to know about this device, so the rule is testable without Android. */
interface LocalApiPlatform {
    /** This agent's package: the installer of record an authorized caller must have. */
    val ownPackage: String

    fun isDeviceOwner(): Boolean

    /** The kiosk persisted on the device, which needs no network; null when not in kiosk. */
    suspend fun kiosk(): KioskApplyPayload?

    /** [packageName]'s installer of record; null when unknown, not installed or not visible. */
    fun installerOf(packageName: String): String?
}

/**
 * [LocalApiPlatform] over DevicePolicyManager, PackageManager and the [KioskStateStore].
 *
 * The installer lookup needs no package-visibility declaration: an app that calls this agent's content provider is
 * visible to the agent for that call.
 */
class AndroidLocalApiPlatform(
    context: Context,
    private val handle: DpmHandle,
    private val kioskStore: KioskStateStore,
) : LocalApiPlatform {

    private val packages = context.applicationContext.packageManager

    override val ownPackage: String = handle.admin.packageName

    override fun isDeviceOwner(): Boolean = handle.dpm.isDeviceOwnerApp(ownPackage)

    override suspend fun kiosk(): KioskApplyPayload? = kioskStore.load()

    override fun installerOf(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") // getInstallSourceInfo is API 30+
            packages.getInstallerPackageName(packageName)
        }
    }.getOrNull()
}
