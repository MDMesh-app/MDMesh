package com.mdmesh.agent.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the grid's apps (label and icon) off the main thread. Icons are kept in a bounded cache keyed by package and
 * last update time, so redrawing the grid (back to HOME, rotation, a font change) reuses them and an updated app gets
 * its new icon.
 */
class KioskAppLoader(private val packageManager: PackageManager) {

    private val icons = LruCache<String, Drawable>(MAX_ICONS)
    private val labels = LruCache<String, String>(MAX_ICONS)

    /** The installed ones of [packages], in order, without duplicates. Missing or broken packages are skipped. */
    suspend fun load(packages: List<String>): List<KioskApp> = withContext(Dispatchers.IO) {
        packages.distinct().mapNotNull(::loadOne)
    }

    /** [pkg]'s label if a load already read it. */
    fun cachedLabel(pkg: String): String? = labels.get(pkg)

    suspend fun label(pkg: String): String? = withContext(Dispatchers.IO) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
            ?.also { labels.put(pkg, it) }
    }

    private fun loadOne(pkg: String): KioskApp? {
        val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        return icon(pkg)?.let { icon ->
            val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            labels.put(pkg, label)
            KioskApp(pkg, label, icon)
        }
    }

    private fun icon(pkg: String): Drawable? {
        val updated = runCatching { packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "$pkg@$updated"
        return icons.get(key)
            ?: runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.also { icons.put(key, it) }
    }

    private companion object {
        /** A kiosk allowlist is a few dozen apps at most. */
        const val MAX_ICONS = 96
    }
}
