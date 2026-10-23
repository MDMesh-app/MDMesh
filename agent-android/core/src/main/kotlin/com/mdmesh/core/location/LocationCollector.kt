package com.mdmesh.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.LocationDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device location for telemetry. Passive mode (default) returns the OS's freshest
 * last-known fix across providers — near-zero battery, no active GPS. Active mode requests one fresh
 * fix per call (API 30+) with a short timeout, falling back to last-known. Off mode ("Do not track")
 * returns null without touching [LocationManager] at all. Never throws; returns null without a
 * location permission, with location services off, or when no fix is available.
 */
@Singleton
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeStore: LocationModeStore,
    private val permissionAccess: LocationPermissionAccess,
) {
    /** What one collection reads, per location mode. */
    enum class Plan { NONE, LAST_KNOWN, FRESH_FIX }

    fun collect(): LocationDto? {
        val plan = plan(modeStore.get())
        if (plan == Plan.NONE) return null
        permissionAccess.ensure()
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val cached = lastKnown(lm)
        val loc = if (plan == Plan.FRESH_FIX) {
            currentFix(lm, fixTimeoutSeconds(cached != null)) ?: cached
        } else cached
        return loc?.let {
            LocationDto(
                lat = it.latitude,
                lon = it.longitude,
                accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                provider = it.provider,
                capturedAt = it.time,
            )
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Freshest last-known location across all enabled providers (cheap, no active GPS). */
    @SuppressLint("MissingPermission") // gated by collect()'s hasPermission() check
    private fun lastKnown(lm: LocationManager): Location? = runCatching {
        lm.allProviders
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    /** A single fresh fix with a short timeout (API 30+ only); null on timeout/failure. */
    @SuppressLint("MissingPermission") // gated by collect()'s hasPermission() check
    private fun currentFix(lm: LocationManager, timeoutSeconds: Long): Location? {
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { lm.isProviderEnabled(it) }
        } else null
        if (provider == null) return null
        return runCatching {
            val latch = CountDownLatch(1)
            val ref = AtomicReference<Location?>()
            val cancel = CancellationSignal()
            lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc ->
                ref.set(loc); latch.countDown()
            }
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                cancel.cancel()
                null
            } else {
                ref.get()
            }
        }.getOrNull()
    }

    companion object {
        private const val FIX_TIMEOUT_SEC = 5L
        private const val FIRST_FIX_TIMEOUT_SEC = 30L

        /** Freshly enrolled/reset devices have no cached fix: allow a GPS cold start. */
        fun fixTimeoutSeconds(hasCachedFix: Boolean): Long =
            if (hasCachedFix) FIX_TIMEOUT_SEC else FIRST_FIX_TIMEOUT_SEC

        fun plan(mode: String): Plan = when (LocationModeStore.normalize(mode)) {
            DeviceAction.LOCATION_OFF -> Plan.NONE
            DeviceAction.LOCATION_ACTIVE -> Plan.FRESH_FIX
            else -> Plan.LAST_KNOWN
        }
    }
}
