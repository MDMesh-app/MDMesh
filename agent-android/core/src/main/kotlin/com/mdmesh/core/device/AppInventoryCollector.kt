package com.mdmesh.core.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import com.mdmesh.proto.AppIcon
import com.mdmesh.proto.AppInfo
import java.io.ByteArrayOutputStream

/**
 * Enumerates installed packages for the console inventory and kiosk app picker. As Device Owner the agent holds
 * `QUERY_ALL_PACKAGES`, so this sees every package including `com.android.*` system binaries.
 *
 * Split into a cheap [scan] (metadata only) and an on-demand, batched [icons] render, so the picker
 * list appears fast and icons are pulled (and client-cached) lazily.
 */
class AppInventoryCollector(private val context: Context) {

    @Suppress("DEPRECATION") // PackageInfoFlags overloads are API 33+; we support minSdk 24
    fun scan(includeAll: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        // Resolve all launchable packages in ONE query instead of getLaunchIntentForPackage per app
        // (that did an intent resolution each — ~hundreds of IPC round-trips, tens of seconds).
        val launchable: Set<String> = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        // MATCH_UNINSTALLED_PACKAGES includes policy-hidden apps, but also retained data from
        // removed packages. FLAG_INSTALLED excludes those entries for the agent's Android user.
        val flags = if (includeAll) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
        return pm.getInstalledPackages(flags).asSequence()
            .filter { pi ->
                if (includeAll) pi.applicationInfo?.let { (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 } == true
                else pi.packageName in launchable
            }
            .mapNotNull { pi ->
                runCatching {
                    val ai = pi.applicationInfo
                    val system = ai != null &&
                        ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
                    AppInfo(
                        pkg = pi.packageName,
                        label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pi.packageName,
                        system = system,
                        launchable = pi.packageName in launchable,
                        versionName = pi.versionName,
                        versionCode = code,
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Render the requested packages' launcher icons as base64 PNGs (missing/failed ones omitted). */
    fun icons(packages: List<String>): List<AppIcon> {
        val pm = context.packageManager
        return packages.distinct().mapNotNull { pkg ->
            runCatching { AppIcon(pkg, encodePng(pm.getApplicationIcon(pkg))) }.getOrNull()
        }
    }

    private fun encodePng(drawable: Drawable, size: Int = ICON_PX): String {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bmp.recycle()
        }
    }

    private companion object {
        const val ICON_PX = 96
    }
}
