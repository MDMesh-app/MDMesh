// WifiConfiguration (the class, its key fields and its SECURITY_TYPE constants) is deprecated for apps, but it is
// still how a Device Owner saves a network, and this file does nothing else.
@file:Suppress("DEPRECATION")

package com.mdmesh.policy.wifi

import android.net.wifi.WifiConfiguration
import android.os.Build
import androidx.annotation.RequiresApi

/** Building the [WifiConfiguration] for [WifiNetworks.add]; the string rules are plain JVM-testable functions. */
internal object WifiKeys {

    private const val WEP_64_HEX = 10
    private const val WEP_128_HEX = 26
    private const val WEP_152_HEX = 32
    private const val WEP_256_HEX = 58

    /** WEP key lengths written as hex digits; a key of any other length is ASCII. */
    private val WEP_HEX_LENGTHS = setOf(WEP_64_HEX, WEP_128_HEX, WEP_152_HEX, WEP_256_HEX)

    fun quote(value: String): String = "\"$value\""

    /** A saved SSID without its quotes. One that is not quoted (a non-UTF-8 SSID, in hex) is kept as it is. */
    fun unquote(raw: String?): String {
        val s = raw.orEmpty()
        return if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
    }

    fun isHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** A WPA passphrase is quoted; a 64-digit raw key is not. */
    fun pskKey(password: String): String =
        if (isHex(password, WifiSecurity.PSK_HEX)) password else quote(password)

    /** An ASCII WEP key is quoted; a hex one is not. */
    fun wepKey(password: String): String =
        if (WEP_HEX_LENGTHS.any { isHex(password, it) }) password else quote(password)

    fun configuration(ssid: String, password: String, security: WifiSecurity): WifiConfiguration =
        WifiConfiguration().apply {
            SSID = quote(ssid)
            when (security) {
                WifiSecurity.OPEN -> Unit
                WifiSecurity.WEP -> {
                    wepKeys[0] = wepKey(password)
                    wepTxKeyIndex = 0
                }
                WifiSecurity.PSK -> preSharedKey = pskKey(password)
                WifiSecurity.SAE -> preSharedKey = quote(password)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setSecurityParams(securityType(security))
            } else {
                legacyKeyManagement(security)
            }
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun securityType(security: WifiSecurity): Int = when (security) {
        WifiSecurity.OPEN -> WifiConfiguration.SECURITY_TYPE_OPEN
        WifiSecurity.WEP -> WifiConfiguration.SECURITY_TYPE_WEP
        WifiSecurity.PSK -> WifiConfiguration.SECURITY_TYPE_PSK
        WifiSecurity.SAE -> WifiConfiguration.SECURITY_TYPE_SAE
    }

    /** API 24-29: the key-management bits [WifiConfiguration.setSecurityParams] sets from API 30. */
    private fun WifiConfiguration.legacyKeyManagement(security: WifiSecurity) {
        when (security) {
            WifiSecurity.OPEN -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            WifiSecurity.WEP -> {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
            }
            WifiSecurity.PSK -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            // WifiNetworks.supports keeps SAE off API 24-28; the check here is for lint.
            WifiSecurity.SAE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
            }
        }
    }
}
