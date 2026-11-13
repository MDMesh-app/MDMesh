package com.mdmesh.agent.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the grid's apps (label and icon) off the main thread. Icons are kept in a cache bounded in bytes and keyed by
 * package, last update time and display density, so redrawing the grid (back to HOME, rotation, a font change)
 * reuses them, an updated app gets its new icon and a display-size change gets icons for the new density. Views
 * never share a cached Drawable: each cell gets its own copy ([KioskViews.bindAppCell]).
 */
class KioskAppLoader(private val context: Context) {

    private val packageManager = context.packageManager

    private val icons = object : LruCache<String, Drawable>(cacheBytes(memoryClassMb())) {
        override fun sizeOf(key: String, value: Drawable): Int = bytesOf(value)
    }
    private val labels = LruCache<String, String>(MAX_LABELS)

    /** The installed ones of [packages], in order, without duplicates. Missing or broken packages are skipped. */
    suspend fun load(packages: List<String>): List<KioskApp> = withContext(Dispatchers.IO) {
        val dpi = context.resources.configuration.densityDpi
        packages.distinct().mapNotNull { loadOne(it, dpi) }
    }

    /** [pkg]'s label if a load already read it. */
    fun cachedLabel(pkg: String): String? = labels.get(pkg)

    suspend fun label(pkg: String): String? = withContext(Dispatchers.IO) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
            ?.also { labels.put(pkg, it) }
    }

    private fun loadOne(pkg: String, densityDpi: Int): KioskApp? {
        val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        return icon(pkg, densityDpi)?.let { icon ->
            val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            labels.put(pkg, label)
            KioskApp(pkg, label, icon)
        }
    }

    private fun icon(pkg: String, densityDpi: Int): Drawable? {
        val updated = runCatching { packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = iconKey(pkg, updated, densityDpi)
        return icons.get(key)
            ?: runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.also { icons.put(key, it) }
    }

    private fun memoryClassMb(): Int =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB

    companion object {
        /** Cache key: a new icon when the app updates or the display density changes. */
        fun iconKey(pkg: String, updated: Long, densityDpi: Int): String = "$pkg@$updated@$densityDpi"

        /** An eighth of the app's heap, at most 16 MB. */
        fun cacheBytes(memoryClassMb: Int): Int = minOf(memoryClassMb * MB / HEAP_SHARE, MAX_CACHE_BYTES)

        /** A package change matters to [screen] only when it is the grid and shows [pkg]. */
        fun affects(screen: KioskScreen, pkg: String): Boolean = screen is KioskScreen.Grid && pkg in screen.packages

        private fun bytesOf(d: Drawable): Int {
            val bitmap = (d as? BitmapDrawable)?.bitmap
            val bytes = bitmap?.allocationByteCount
                ?: (d.intrinsicWidth.coerceAtLeast(1) * d.intrinsicHeight.coerceAtLeast(1) * ARGB_BYTES)
            return bytes.coerceAtLeast(1)
        }

        private const val MB = 1024 * 1024
        private const val HEAP_SHARE = 8
        private const val MAX_CACHE_BYTES = 16 * MB
        private const val DEFAULT_MEMORY_CLASS_MB = 64
        private const val ARGB_BYTES = 4
        private const val MAX_LABELS = 256
    }
}
