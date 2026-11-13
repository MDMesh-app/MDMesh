package com.mdmesh.agent.ui

import android.content.res.Configuration
import android.os.Bundle

/** A dialog [DialogSlot] can show. Showing it takes a [DialogSlot.Key], which only a DialogSlot has. */
interface SlotDialog {
    fun show(key: DialogSlot.Key)

    fun dismiss()

    /** Called once the dialog is gone, however it was closed. */
    fun setOnDismiss(listener: () -> Unit)

    /** The configuration the dialog was built for, or null when it doesn't depend on one. */
    val builtFor: Configuration? get() = null

    /** Text typed into the dialog, handed to its rebuild after a config change. Never written to saved state. */
    fun typedText(): CharSequence? = null

    /** When the dialog closes itself for lack of input (SystemClock.uptimeMillis), or null for never. */
    fun idleDeadline(): Long? = null

    /** A rebuild keeps the deadline of the dialog it replaces ([idleDeadline]), set before [show]. */
    fun keepIdleDeadline(uptimeMs: Long) = Unit
}

/**
 * The one dialog an activity shows at a time, and the only way to show one ([SlotDialog.show] needs this slot's
 * [Key]). It keeps the dialog current across what would otherwise lose or freeze it:
 * - **Recreation** (rotation, process death): only which dialog was open is saved, under a caller-chosen tag. The
 *   activity rebuilds it from its current state when [takeRestored] hands the tag back, so a reopened dialog never
 *   shows stale facts (an expired lockout, a kiosk changed meanwhile) and nothing typed (a PIN) is written into
 *   the saved state.
 * - **A config change the activity handles itself** (what it declares in `configChanges`; the kiosk launcher:
 *   dark mode, rotation, window size, font size, display size and the keyboard set): through
 *   [onConfigurationChanged] the open dialog is rebuilt the same way when the change alters how it looks, with what
 *   was typed and its idle deadline carried over in memory, and left as it is otherwise (a keyboard or scanner
 *   connecting, a dock).
 *
 * Call [save] from `onSaveInstanceState`, [restore] from `onCreate`, [onConfigurationChanged] from the activity's
 * own, and [dismiss] from `onDestroy` (a dialog left showing on a destroyed activity leaks its window).
 */
class DialogSlot(private val key: String) {

    /**
     * Proof that a DialogSlot is the caller of [SlotDialog.show]. Sealed, so only this package of this module could
     * declare another; the one instance is private here, so code elsewhere can't show a dialog outside a slot.
     */
    sealed interface Key

    private data object Token : Key

    private var current: Pair<String, SlotDialog>? = null

    /** The idle deadline of a dialog being rebuilt, handed to its replacement in [show]. */
    private var carriedDeadline: Long? = null
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
        carriedDeadline?.let(dialog::keepIdleDeadline)
        dialog.show(Token)
    }

    /** Close the dialog showing under [tag], if that's the one showing. */
    fun dismiss(tag: String) {
        if (current?.first == tag) dismiss()
    }

    fun dismiss() {
        val open = current ?: return
        current = null
        open.second.dismiss()
    }

    /**
     * The activity's configuration changed without a recreation: only what it declares in `configChanges` arrives
     * here (the kiosk launcher: uiMode, orientation, screen size and layout, font scale, density and the keyboard
     * set; anything else, a locale say, recreates it and goes through [restore]). If the change alters how the open
     * dialog looks (dark mode, font size, density or window size), close it and let [reopen]
     * build and show it again (through [show]) from current state, as after a recreation, with what was typed in
     * it: kept in memory for this hand-over only, never in saved state. Other changes (a desk or car dock switching
     * the UI mode type) leave it open as it is.
     */
    fun onConfigurationChanged(newConfig: Configuration, reopen: (tag: String, typed: CharSequence?) -> Unit) {
        val (tag, dialog) = current ?: return
        val old = dialog.builtFor
        if (old != null && !changesLook(old, newConfig)) return
        val typed = dialog.typedText()?.toString()
        val deadline = dialog.idleDeadline()
        dismiss()
        // The rebuilt dialog closes when the old one would have: a look change doesn't buy an abandoned PIN time.
        carriedDeadline = deadline
        try {
            reopen(tag, typed)
        } finally {
            carriedDeadline = null
        }
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

    private companion object {
        /** Whether a dialog built for [old] looks different in [new]: colours, text size, density or space. */
        fun changesLook(old: Configuration, new: Configuration): Boolean =
            (old.uiMode and Configuration.UI_MODE_NIGHT_MASK) != (new.uiMode and Configuration.UI_MODE_NIGHT_MASK) ||
                old.fontScale != new.fontScale ||
                old.densityDpi != new.densityDpi ||
                old.screenWidthDp != new.screenWidthDp ||
                old.screenHeightDp != new.screenHeightDp
    }
}
