package com.mdmesh.agent.ui

import android.os.Bundle

/**
 * The one dialog an activity shows at a time, reopened after the activity is recreated (rotation, a config change,
 * process death). Only which dialog was open is saved, under a caller-chosen tag; the activity rebuilds it from its
 * current state when [takeRestored] hands the tag back, so a reopened dialog never shows stale facts (an expired
 * lockout, a kiosk changed meanwhile) and nothing typed (a PIN) is written into the saved state.
 *
 * Call [save] from `onSaveInstanceState`, [restore] from `onCreate`, and [dismiss] from `onDestroy` (a dialog left
 * showing on a destroyed activity leaks its window).
 */
class DialogSlot(private val key: String) {

    private var current: Pair<String, MdmDialog>? = null
    private var restored: String? = null

    /** The dialog showing now, if any. */
    val showing: MdmDialog? get() = current?.second

    /** Show [dialog] under [tag], closing whatever this slot showed before. */
    fun show(tag: String, dialog: MdmDialog) {
        dismiss()
        val entry = tag to dialog
        current = entry
        dialog.alert.setOnDismissListener { if (current === entry) current = null }
        dialog.show()
    }

    fun dismiss() {
        current?.second?.dismiss()
        current = null
    }

    fun save(outState: Bundle) {
        savedTag()?.let { outState.putString(key, it) }
    }

    fun restore(savedInstanceState: Bundle?) = restoreTag(savedInstanceState?.getString(key))

    /**
     * The tag of the dialog that was open before the activity was recreated, handed back once; null if none. Once
     * taken it's gone: the activity either reopens the dialog (then it's [showing] again) or drops it for good.
     */
    fun takeRestored(): String? = restored.also { restored = null }

    /**
     * What [save] writes: the dialog showing, or one restored but not yet taken (recreated again before the
     * activity got to it, so still open as far as the user is concerned).
     */
    internal fun savedTag(): String? = current?.first ?: restored

    internal fun restoreTag(tag: String?) {
        restored = tag
    }
}
