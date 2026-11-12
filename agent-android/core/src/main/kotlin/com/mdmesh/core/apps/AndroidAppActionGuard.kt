package com.mdmesh.core.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.policy.wifi.DpmHandle

/** Resolve protection from the device on every operation, not from a potentially stale console scan. */
fun androidAppActionGuard(context: Context, handle: DpmHandle, store: KioskStateStore): AppActionGuard =
    AppActionGuard(context.packageName, store, { pkg ->
        val info = context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }, {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
        val keyboard = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        handle.dpm.activeAdmins.orEmpty().map { it.packageName }.toSet() + listOfNotNull(home, keyboard)
    })
