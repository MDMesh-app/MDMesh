package com.mdmesh.agent.ui

import android.os.Bundle

/** A dialog [DialogSlot] can show. Showing it takes a [DialogSlot.Key], which only a DialogSlot has. */
interface SlotDialog {
    fun show(key: DialogSlot.Key)

    fun dismiss()

    /** Called once the dialog is gone, however it was closed. */
    fun setOnDismiss(listener: () -> Unit)
}

/**
 * The one dialog an activity shows at a time, and the only way to show one ([SlotDialog.show] needs this slot's
 * [Key]). It keeps the dialog current across what would otherwise lose or freeze it:
 * - **Recreation** (rotation, process death): only which dialog was open is saved, under a caller-chosen tag. The
 *   activity rebuilds it from its current state when [takeRestored] hands the tag back, so a reopened dialog never
 *   shows stale facts (an expired lockout, a kiosk changed meanwhile) and nothing typed (a PIN) is written into
 *   the saved state.
 * - **A config change the activity handles itself** (`configChanges`: dark mode, font size, display size): the
 *   open dialog is rebuilt the same way through [onConfigurationChanged], so its colours and sizes follow.
 *
 * Call [save] from `onSaveInstanceState`, [restore] from `onCreate`, [onConfigurationChanged] from the activity's
 * own, and [dismiss] from `onDestroy` (a dialog left showing on a destroyed activity leaks its window).
 */
class DialogSlot(private val key: String) {

    /** Proof that a DialogSlot is the caller of [SlotDialog.show]. Sealed: nothing outside this file makes one. */
    sealed interface Key

    private data object Token : Key

    private var current: Pair<String, SlotDialog>? = null
    private var restored: String? = null

    /** The dialog showing now, if any. */
    val showing: SlotDialog? get() = current?.second

    /** Show [dialog] under [tag], closing whatever this slot showed before. */
    fun show(tag: String, dialog: SlotDialog) {
        dismiss()
        val entry = tag to dialog
        current = entry
        // Identity check: the old dialog's (asynchronous) dismiss must not clear the new one.
        dialog.setOnDismiss { if (current === entry) current = null }
        dialog.show(Token)
    }

    fun dismiss() {
        val open = current ?: return
        current = null
        open.second.dismiss()
    }

    /**
     * The activity's configuration changed without a recreation: close the open dialog and let [reopen] build and
     * show it again (through [show]) from current state, as after a recreation. What was typed isn't carried over:
     * a PIN stays in no object but the dialog that shows it, and these changes (dark mode, font or display size)
     * are made in Settings, not while someone is typing here.
     */
    fun onConfigurationChanged(reopen: (tag: String) -> Unit) {
        val tag = current?.first ?: return
        dismiss()
        reopen(tag)
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
