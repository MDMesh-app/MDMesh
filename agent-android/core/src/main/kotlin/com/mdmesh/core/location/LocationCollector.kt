package com.mdmesh.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.RequiresApi
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
    /** A cold-start wait (no cached fix) delays its check-in, so it happens at most once per 15 minutes. */
    private val coldStarts = Throttle(COLD_START_EVERY_MS) { SystemClock.elapsedRealtime() }

    /** What one collection reads, per location mode. */
    enum class Plan { NONE, LAST_KNOWN, FRESH_FIX }

    fun collect(): LocationDto? {
        val plan = plan(modeStore.get())
        if (plan == Plan.NONE) return null
        // Before the check: a QR-enrolled agent can hold only the foreground grants (see LocationPermissionAccess).
        permissionAccess.ensure()
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val cached = lastKnown(lm)
        val loc = if (plan == Plan.FRESH_FIX) (freshFix(lm, hasCachedFix = cached != null) ?: cached) else cached
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

    /**
     * A fresh fix (API 30+) from the first enabled of GPS and network, or null when there is none to wait for. The
     * cold-start budget is only spent when there is a provider to wait on.
     */
    private fun freshFix(lm: LocationManager, hasCachedFix: Boolean): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = FRESH_FIX_PROVIDERS.firstOrNull { lm.isProviderEnabled(it) }
        val coldStart = provider != null && !hasCachedFix && coldStarts.tryUse()
        return provider?.let { currentFix(lm, it, fixTimeoutSeconds(coldStart)) }
    }

    /** One fix from [provider] within [timeoutSeconds]; null on timeout or failure. */
    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission") // gated by collect()'s hasPermission() check
    private fun currentFix(lm: LocationManager, provider: String, timeoutSeconds: Long): Location? = runCatching {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Location?>()
        val cancel = CancellationSignal()
        // Delivered on the binder thread, not the main executor: the caller may itself be on Main (CheckInService).
        lm.getCurrentLocation(provider, cancel, Runnable::run) { loc ->
            ref.set(loc); latch.countDown()
        }
        try {
            if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) ref.get() else null
        } finally {
            if (latch.count > 0) cancel.cancel() // timed out or interrupted: stop the request
        }
    }.getOrNull()

    companion object {
        private const val FIX_TIMEOUT_SEC = 5L
        private const val COLD_START_TIMEOUT_SEC = 30L
        private const val COLD_START_EVERY_MS = 15 * 60_000L
        private val FRESH_FIX_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** A device with no cached fix gets a GPS cold start, at most every 15 minutes (it delays that check-in). */
        fun fixTimeoutSeconds(coldStart: Boolean): Long = if (coldStart) COLD_START_TIMEOUT_SEC else FIX_TIMEOUT_SEC

        fun plan(mode: String): Plan = when (LocationModeStore.normalize(mode)) {
            DeviceAction.LOCATION_OFF -> Plan.NONE
            DeviceAction.LOCATION_ACTIVE -> Plan.FRESH_FIX
            else -> Plan.LAST_KNOWN
        }
    }
}
