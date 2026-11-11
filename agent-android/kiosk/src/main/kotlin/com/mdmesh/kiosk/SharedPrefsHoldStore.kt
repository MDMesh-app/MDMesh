package com.mdmesh.kiosk

import android.content.Context

/** SharedPreferences-backed [HoldStore], next to the crash counter; commits at once like [SharedPrefsFaultStore]. */
class SharedPrefsHoldStore(context: Context) : HoldStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(CrashLoopGuard.FAULT_PREFERENCE_NAME, Context.MODE_PRIVATE)

    override val heldBoot: Long?
        get() = prefs.getLong(KEY_HELD_BOOT, NOT_HELD).takeIf { it != NOT_HELD }

    override val heldElapsed: Long
        get() = prefs.getLong(KEY_HELD_ELAPSED, 0L)

    override fun writeHeld(boot: Long?, elapsed: Long) {
        prefs.edit().putLong(KEY_HELD_BOOT, boot ?: NOT_HELD).putLong(KEY_HELD_ELAPSED, elapsed).commit()
    }

    private companion object {
        const val KEY_HELD_BOOT = "held_boot"
        const val KEY_HELD_ELAPSED = "held_elapsed"
        const val NOT_HELD = Long.MIN_VALUE
    }
}
