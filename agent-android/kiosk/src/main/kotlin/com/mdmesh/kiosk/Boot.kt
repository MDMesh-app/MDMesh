package com.mdmesh.kiosk

import android.content.Context
import android.provider.Settings

/** Which boot the device is in. */
object Boot {

    /** Settings.Global.BOOT_COUNT (API 24+, no permission), or 0 when the ROM doesn't keep it or it can't be read. */
    fun count(context: Context): Long =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong() }
            .getOrDefault(0L)
}
