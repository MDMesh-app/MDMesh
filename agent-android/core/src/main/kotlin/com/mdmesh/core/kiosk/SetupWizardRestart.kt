package com.mdmesh.core.kiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Restarts the setup wizard from scratch once enrollment is done, so it takes its short "already provisioned" path:
 * a welcome check, then straight to its exit, which records setup as complete. Seen twice on a Lenovo TB311XU
 * (Android 16): started fresh after enrollment, the wizard skipped every remaining page, Google's included, in
 * about three seconds.
 *
 * Without it, the wizard goes on to pages that need a tap. With an OEM's setup app hidden ([EnrollmentHiddenApps]) it
 * crashes instead, and each crash only closes its top page, so it reopens its history page by page until it reaches
 * the QR scanner, which waits for someone to press Back.
 *
 * A restart opens the wizard's entry activity in a new task that replaces its own, which closes that history. Never by
 * hiding its package: Android then treats the wizard as uninstalled, and the Wi-Fi service deletes every network it
 * saved (WifiServiceImpl's package-removed receiver, `WifiConfigManager.removeNetworksForApp`), the Wi-Fi joined during
 * setup included, so the device is offline the moment setup ends. It is repeated a few times while setup has still
 * not completed.
 */
class SetupWizardRestart(
    context: Context,
    private val userSetup: UserSetup,
) {
    private val context = context.applicationContext

    /** The setup wizard's entry: the HOME activity that also declares [CATEGORY_SETUP_WIZARD], or null without one. */
    fun wizard(): ComponentName? = runCatching {
        context.packageManager.queryIntentActivities(home().addCategory(CATEGORY_SETUP_WIZARD), 0)
            .firstOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** Restarts [wizard] after each of [DELAYS_MS] while setup has not completed. Suspends for up to a minute. */
    suspend fun untilComplete(wizard: ComponentName) {
        for (wait in DELAYS_MS) {
            delay(wait)
            if (userSetup.isComplete()) return
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            runCatching { context.startActivity(home().setComponent(wizard).addFlags(flags)) }
                .recoverCatching { context.startActivity(home().addFlags(flags)) } // its entry is not exported
                .onSuccess { Log.i(TAG, "restarted the setup wizard") }
                .onFailure { Log.w(TAG, "could not restart the setup wizard", it) }
        }
    }

    private fun home() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private companion object {
        const val TAG = "SetupWizardRestart"

        /** `Intent.CATEGORY_SETUP_WIZARD`, which is a system API. */
        const val CATEGORY_SETUP_WIZARD = "android.intent.category.SETUP_WIZARD"

        /**
         * The first after its post-enrollment Google check-in (about 3.5 s after `ADMIN_POLICY_COMPLIANCE` on the
         * Lenovo), then twice more should setup still not be complete.
         */
        val DELAYS_MS = listOf(5_000L, 15_000L, 15_000L)
    }
}
