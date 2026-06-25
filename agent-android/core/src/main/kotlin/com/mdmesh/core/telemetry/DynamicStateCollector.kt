package com.mdmesh.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.mdmesh.core.location.LocationCollector
import com.mdmesh.proto.DynamicState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DynamicStateCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationCollector: LocationCollector,
) {
    @Suppress("DEPRECATION")
    fun collect(): DynamicState {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val healthInt = batt?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val tempTenthsC = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltage = batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val stat = StatFs(Environment.getDataDirectory().path)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val net = networkInfo()
        val lockTask = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

        return DynamicState(
            batteryPct = batteryPercent(level, scale),
            batteryHealth = healthName(healthInt),
            batteryTempC = tempTenthsC / 10.0,
            batteryVoltageMv = voltage,
            chargingSource = chargingSource(plugged),
            freeStorageBytes = stat.availableBlocksLong * stat.blockSizeLong,
            freeRamBytes = mem.availMem,
            networkType = net.type,
            wifiSsid = net.ssid,
            wifiBssid = net.bssid,
            ipAddress = net.ip,
            wifiRssi = net.rssi,
            wifiLinkSpeedMbps = net.linkSpeed,
            cellularOperator = net.operator,
            cellularSignalLevel = if (net.type == "cellular") cellularSignalLevel() else null,
            roaming = net.roaming,
            screenOn = pm.isInteractive,
            locked = lockTask,
            kioskActive = lockTask,
            foregroundApp = foregroundApp(),
            uptimeMs = SystemClock.elapsedRealtime(),
            lastBootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            location = runCatching { locationCollector.collect() }.getOrNull(),
        )
    }

    private data class Net(
        val type: String, val ssid: String?, val bssid: String?, val ip: String?,
        val rssi: Int?, val linkSpeed: Int?, val operator: String?, val roaming: Boolean,
    )

    @Suppress("DEPRECATION")
    private fun networkInfo(): Net {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (isWifi) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ip = (info.ipAddress.takeIf { it != 0 })?.let {
                "%d.%d.%d.%d".format(it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff)
            }
            return Net(
                "wifi",
                info.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" },
                info.bssid, ip, info.rssi, info.linkSpeed, null, false,
            )
        }
        if (isCell) {
            return Net("cellular", null, null, null, null, null, tm?.networkOperatorName, tm?.isNetworkRoaming ?: false)
        }
        return Net("none", null, null, null, null, null, null, false)
    }

    /** Cellular signal level 0–4 (API 28+); null if unavailable. */
    private fun cellularSignalLevel(): Int? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.signalStrength?.level
    }.getOrNull()

    /** Foreground app package via UsageStats (last 60s). Null unless Usage-Access is enabled
     *  (can't be silently DO-granted — special access; enable manually to populate). */
    private fun foregroundApp(): String? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 60_000, now,
        )
        stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }.getOrNull()

    private fun healthName(h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "overVoltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    companion object {
        fun batteryPercent(level: Int, scale: Int): Int =
            if (scale <= 0 || level < 0) -1 else (level * 100 / scale)

        fun chargingSource(plugged: Int): String = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
    }
}
