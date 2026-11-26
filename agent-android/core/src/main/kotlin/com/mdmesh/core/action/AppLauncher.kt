package com.mdmesh.core.action

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when the agent tried to open an app. */
sealed interface LaunchOutcome {
    data object Launched : LaunchOutcome

    /** The app wasn't opened, and why (shown in the command's result). */
    data class Refused(val reason: String) : LaunchOutcome
}

/** Opens an app's screen on the device, as if the user had tapped its icon. */
fun interface AppLauncher {
    /**
     * [activity] is a class inside [packageName] (`.Orders`, or fully qualified); null opens the app's main screen.
     */
    fun launch(packageName: String, activity: String?): LaunchOutcome
}

/**
 * Opens apps with [Context.startActivity]. The agent is Device Owner, which Android exempts from the limits on starting
 * activities from the background. Checked in this order, so the reason names the real problem:
 *  1. the package is installed (the agent may see every package: QUERY_ALL_PACKAGES);
 *  2. it has the screen asked for (its main screen when none is given);
 *  3. in kiosk (lock task), it is on the kiosk list: Android lets nothing else open there.
 * Nothing keeps the app open afterwards: the user leaves it as usual.
 */
@Singleton
class AndroidAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLauncher {

    override fun launch(packageName: String, activity: String?): LaunchOutcome {
        val isInstalled = installed(packageName)
        val intent = if (isInstalled) screen(packageName, activity) else null
        val refusal = when {
            !isInstalled -> "$packageName is not installed"
            intent == null -> "$packageName has no screen to open"
            inLockTask() && !lockTaskPermitted(packageName) -> "$packageName is not allowed on the kiosk screen"
            else -> null
        }
        return if (intent == null || refusal != null) {
            LaunchOutcome.Refused(refusal ?: "could not open $packageName")
        } else {
            start(intent, packageName)
        }
    }

    /** The screen to open: the given activity, or the app's launcher entry (null when it has none). */
    private fun screen(packageName: String, activity: String?): Intent? =
        if (activity.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } else {
            Intent(Intent.ACTION_MAIN).setClassName(packageName, qualified(packageName, activity))
        }

    private fun start(intent: Intent, packageName: String): LaunchOutcome =
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(intent)
            LaunchOutcome.Launched
        } catch (e: ActivityNotFoundException) {
            LaunchOutcome.Refused("no such screen in $packageName: ${e.message}")
        } catch (e: SecurityException) {
            LaunchOutcome.Refused("$packageName doesn't let other apps open that screen: ${e.message}")
        }

    private fun installed(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun inLockTask(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun lockTaskPermitted(packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isLockTaskPermitted(packageName)
    }

    companion object {
        /** `.Orders` → `com.app.Orders`; a fully qualified name stays as it is. */
        fun qualified(packageName: String, activity: String): String =
            if (activity.startsWith(".")) packageName + activity else activity
    }
}
