package com.mdmesh.agent.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.UserManager
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.core.sync.CheckInWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device-admin / Device-Owner receiver — the component named when binding the DPC
 * (`dpm set-device-owner com.mdmesh.agent/.admin.AdminReceiver`).
 *
 * Keep this thin: it reacts to admin lifecycle events and delegates real work to the
 * injected graph. The signing certificate of the app that owns this receiver is what
 * the DO binding is tied to — see the release signing note in `app/build.gradle.kts`.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Admin (and, when provisioned this way, Device Owner) is now active.
        setStableOrganizationId(context)
        grantLocationAccess(context)
        CheckInWorker.schedule(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        // Fully-managed provisioning finished. Set the org id FIRST so getEnrollmentSpecificId()
        // is populated before the first check-in reports the hardware id (see HardwareIdCollector).
        setStableOrganizationId(context)
        grantLocationAccess(context)
        // Capture the server URL from the QR bundle BEFORE any check-in, so one prebuilt APK can
        // serve any deployment (it falls back to the baked URL only when absent — dev/ADB).
        ServerConfigStore(context.applicationContext).save(extrasString(intent, EXTRA_SERVER_URL))
        // Capture the single-use enroll token handed in via the QR provisioning bundle, then
        // trigger an immediate check-in so the device enrolls within seconds rather than on the
        // periodic cycle.
        CheckInWorker.schedule(context)

        val token = extrasToken(intent)
        if (token.isNullOrBlank()) {
            CheckInWorker.scheduleNow(context)
            return
        }
        // EnrollTokenStore.save is suspend; keep the receiver alive while it persists.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EnrollTokenStore(context.applicationContext).save(token)
            } finally {
                CheckInWorker.scheduleNow(context)
                pending.finish()
            }
        }
    }

    /** Entering kiosk: block other apps/services from drawing toasts/dialogs over the kiosk. */
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        setCreateWindowsRestriction(context, restrict = true)
    }

    /** Leaving kiosk: allow overlays again. */
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        setCreateWindowsRestriction(context, restrict = false)
    }

    /**
     * Set a fleet-wide, constant organization id so [DevicePolicyManager.getEnrollmentSpecificId]
     * returns a stable per-device id. The enrollment-specific id is derived from this org id + our
     * DPC package + a hardware identifier, and — unlike `ANDROID_ID` — is **stable across factory
     * resets**, which is what lets the server de-duplicate a wiped-and-re-enrolled device. Using one
     * constant id for the whole fleet keeps each physical device's id stable while still differing
     * between devices. Idempotent: it can only be set once per owner session, so a repeat call
     * throws and is swallowed. API 31+.
     */
    private fun setStableOrganizationId(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            dpm.setOrganizationId(ORGANIZATION_ID)
        }
    }

    /**
     * As Device Owner, silently grant location permission (incl. background) and turn location
     * services on, so the agent can report device location for telemetry without any user prompt.
     */
    private fun grantLocationAccess(context: Context) {
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return
            val pkg = context.packageName
            if (!dpm.isDeviceOwnerApp(pkg)) return
            val admin = componentName(context)
            val perms = mutableListOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                perms.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            perms.forEach { perm ->
                runCatching {
                    dpm.setPermissionGrantState(
                        admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { dpm.setLocationEnabled(admin, true) }
            }
        }
    }

    private fun setCreateWindowsRestriction(context: Context, restrict: Boolean) {
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = componentName(context)
            if (restrict) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            }
        }
    }

    private fun extrasToken(intent: Intent): String? = extrasString(intent, EXTRA_ENROLL_TOKEN)

    @Suppress("DEPRECATION") // typed getParcelableExtra is API 33+; we support minSdk 24
    private fun extrasString(intent: Intent, key: String): String? {
        val bundle = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        return bundle?.getString(key)
    }

    companion object {
        /** Key in the QR `PROVISIONING_ADMIN_EXTRAS_BUNDLE` carrying the enroll token. */
        const val EXTRA_ENROLL_TOKEN = "com.mdmesh.ENROLL_TOKEN"

        /** Key carrying the server base URL, so one prebuilt APK serves any deployment. */
        const val EXTRA_SERVER_URL = "com.mdmesh.SERVER_URL"

        /** Constant fleet org id feeding the factory-reset-stable enrollment-specific id. */
        const val ORGANIZATION_ID = "mdmesh-fleet"

        /** This agent's admin component, used wherever a [ComponentName] is needed. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }
}
