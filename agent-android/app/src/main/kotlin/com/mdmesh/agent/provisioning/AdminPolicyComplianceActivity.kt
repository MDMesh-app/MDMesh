package com.mdmesh.agent.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.os.PersistableBundle
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.policy.PolicyManager
import com.mdmesh.policy.wifi.DpmHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        CheckInWorker.schedule(ctx) // periodic reconcile
        applyBaselinePolicy(ctx)

        val token = extrasString(AdminReceiver.EXTRA_ENROLL_TOKEN)
        if (!token.isNullOrBlank()) {
            // Independent scope so the save completes even though we finish() immediately;
            // the check-in is scheduled only after the token persists (avoids a race).
            CoroutineScope(Dispatchers.IO).launch {
                EnrollTokenStore(ctx).save(token)
                CheckInWorker.scheduleNow(ctx)
            }
        } else {
            CheckInWorker.scheduleNow(ctx)
        }

        // TODO: enforce mandatory baseline policy here before returning RESULT_OK.
        setResult(RESULT_OK)
        finish()
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
            listOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
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
}
