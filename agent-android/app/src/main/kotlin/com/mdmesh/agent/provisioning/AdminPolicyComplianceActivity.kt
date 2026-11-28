package com.mdmesh.agent.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.os.PersistableBundle
import com.mdmesh.agent.MdmApplication
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.kiosk.EnrollmentHiddenApps
import com.mdmesh.core.kiosk.SetupWizardRestart
import com.mdmesh.core.location.LocationPermissions
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.policy.PolicyManager
import com.mdmesh.policy.wifi.DpmHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handles `ACTION_ADMIN_POLICY_COMPLIANCE`, launched right after provisioning.
 *
 * On Android 12+ (the modern provisioning contract) the `PROVISIONING_ADMIN_EXTRAS_BUNDLE`
 * — which carries our single-use enroll token and the deployment's server URL — is delivered
 * to THIS activity's intent, not to [AdminReceiver.onProfileProvisioningComplete]. So this is
 * where we capture both, persist them, and kick an immediate check-in so the device enrolls
 * within seconds — against the server the QR named, not the baked fallback (which in release
 * builds is a placeholder).
 */
class AdminPolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext
        // Server URL first (synchronous SharedPreferences), so no check-in scheduled below can
        // ever run against the baked fallback. save() is a no-op for an absent/blank extra.
        ServerConfigStore(ctx).save(extrasString(AdminReceiver.EXTRA_SERVER_URL))
        // Before the first enroll: it reports the enrollment-specific id, which needs the org id. On Android 12+ this
        // activity can run before the admin receiver's callbacks that also set it.
        AdminReceiver.setStableOrganizationId(ctx)
        CheckInWorker.schedule(ctx) // periodic reconcile
        applyBaselinePolicy(ctx)

        val token = extrasString(AdminReceiver.EXTRA_ENROLL_TOKEN)
        if (!token.isNullOrBlank()) {
            // Independent scope so the save completes even though we finish() immediately;
            // the check-in is scheduled only after the token persists (avoids a race).
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AgentDataStores.stores(ctx).enrollTokenStore().save(token)
                } finally {
                    // Even if the save failed: the check-in then reports "no enrollment code" on the device's screen
                    // instead of nothing happening at all.
                    CheckInWorker.scheduleNow(ctx)
                    // Open the instant channel now that the token is saved (its first sync enrolls); otherwise it
                    // only started from the app's screens or a reboot, and commands waited for the periodic check-in.
                    CheckInService.start(ctx)
                }
            }
        } else {
            CheckInWorker.scheduleNow(ctx)
            CheckInService.start(ctx)
        }

        hideEnrollmentApps(ctx)

        // TODO: enforce mandatory baseline policy here before returning RESULT_OK.
        setResult(RESULT_OK)
        finish()
    }

    /**
     * Before RESULT_OK, so the setup wizard has not yet reached the pages these apps add ([EnrollmentHiddenApps]).
     * Device-Owner status can lag this activity (see [applyBaselinePolicy]), so what could not be hidden is retried
     * for a few seconds in the background: the wizard still has Google's own pages to show first.
     */
    private fun hideEnrollmentApps(ctx: Context) {
        val packages = EnrollmentHiddenApps.parse(extrasString(EnrollmentHiddenApps.EXTRA), ctx.packageName)
        if (packages.isEmpty()) return
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.componentName(ctx)
        val hide = { p: String -> dpm.setApplicationHidden(admin, p, true); dpm.isApplicationHidden(admin, p) }
        val userSetup = (application as MdmApplication).userSetup
        // Before anything is hidden: the setup wizard may crash over a hidden app's pages, and must not leave a dialog.
        val quiet = { userSetup.quietUntilComplete(admin) }
        quiet()
        // Then started fresh, it ends on its short path, without the hidden app's pages or a tap (SetupWizardRestart).
        val restart = SetupWizardRestart(ctx, userSetup)
        restart.wizard()?.let { wizard ->
            CoroutineScope(Dispatchers.IO).launch { restart.untilComplete(wizard) }
        }
        // One QR serves several brands, so some of its apps are not on this device: they are left out, and logged as
        // such. Hidden ones still count as installed (MATCH_UNINSTALLED_PACKAGES).
        val (here, absent) = packages.partition {
            runCatching { packageManager.getPackageInfo(it, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.isSuccess
        }
        val missed = EnrollmentHiddenApps.hideAll(here, hide)
        Log.i(TAG, "hid ${here.size - missed.size} of ${here.size} apps at enrollment; not on this device: $absent")
        if (missed.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            var left = missed
            repeat(HIDE_RETRIES) {
                delay(HIDE_RETRY_MS)
                quiet()
                left = EnrollmentHiddenApps.hideAll(left, hide)
                if (left.isEmpty()) return@launch
            }
            Log.w(TAG, "not hidden at enrollment (refused): $left")
        }
    }

    /** Benign Device-Owner baseline: auto-grant runtime permissions to managed apps so they
     *  never prompt. Restrictive policies are pushed by the admin via commands, not here. */
    private fun applyBaselinePolicy(ctx: android.content.Context) {
        runCatching {
            val dpm = ctx.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val handle = DpmHandle(dpm, AdminReceiver.componentName(ctx))
            PolicyManager(handle).setPermissionAutoGrant()
            // Provision the DO reset-password token once, so device.passcodeReset works later.
            ResetPasswordTokenStore(ctx, handle).ensureToken()
            // Silently grant the telemetry runtime permissions as Device Owner.
            // Location includes background (Android 10+): on this QR path nothing else grants it while the agent is
            // Device Owner. The collector repairs it later too (LocationPermissionAccess).
            // POST_NOTIFICATIONS (Android 13+) likewise, so alerts and the "MDMesh active" notification show. Best
            // effort: this can run before the agent reads as Device Owner (see CapabilityCollector). If it doesn't
            // take, NotificationPermissionAccess grants it when the next alert is shown or CheckInService next starts.
            (
                listOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_PHONE_NUMBERS) +
                    LocationPermissions.forThisDevice() +
                    listOfNotNull(
                        android.Manifest.permission.POST_NOTIFICATIONS
                            .takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU },
                    )
            ).forEach { perm ->
                runCatching {
                    dpm.setPermissionGrantState(
                        handle.admin, ctx.packageName, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }
            }
            // Enable location services (DO) so Wi-Fi SSID telemetry is readable on Android 10+.
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    dpm.setLocationEnabled(handle.admin, true)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // typed getParcelableExtra is API 33+; we support minSdk 24
    private fun extrasString(key: String): String? =
        intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )?.getString(key)

    private companion object {
        const val TAG = "AdminPolicyCompliance"
        const val HIDE_RETRIES = 10
        const val HIDE_RETRY_MS = 1_000L
    }
}
