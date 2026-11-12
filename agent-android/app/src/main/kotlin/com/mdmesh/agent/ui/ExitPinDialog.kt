package com.mdmesh.agent.ui

import android.content.Context
import android.os.SystemClock
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.os.Build
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mdmesh.agent.R

/**
 * The Exit kiosk dialog: PIN field with a show/hide toggle and an error line under it. It doesn't check PINs: Exit
 * (or the keyboard's Done) hands the text to `onSubmit` (the launcher's `onPinEntered`), which renders the result
 * back with [render]. A wrong PIN keeps the dialog open; it closes only on [ExitPinUiState.Close] or Cancel.
 *
 * The typed PIN is never saved with the activity's state: after a recreation (a locale change, process death) the
 * launcher reopens the dialog empty; an in-place rebuild (rotation, dark mode, font or display size) hands it over
 * in memory, with the idle deadline. It goes with the dialog when the screen goes off (the launcher
 * stops), after [IDLE_TIMEOUT_MS] without input (or the user's longer accessibility "Time to take action"), and once
 * a PIN is accepted: the prompt then settles, and a second "done" for the same press does nothing. Shown or not,
 * the PIN can't be copied, shared, autofilled or content-captured.
 * The wrong-PIN count and lockout live in ExitPinGate's persisted store, so neither resets with the dialog.
 */
class ExitPinDialog private constructor(
    /** The dialog itself, for the activity's [DialogSlot]. */
    val dialog: MdmDialog,
    private val field: PinEditText,
    private val toggle: ImageButton,
    private val error: TextView,
) {

    private var shown = false

    /** The error shown under the field, also given to accessibility services as the field's error. */
    private var errorText: String? = null

    /**
     * The prompt is done: a PIN was accepted (right, or locked out) or the dialog closed. Every later submit is a
     * no-op, so two "done" signals for one press (a scanner's CR plus its Enter key, an IME's Done plus an Enter
     * key) can't run the exit twice.
     */
    private var settled = false

    fun render(state: ExitPinUiState) {
        when (state) {
            ExitPinUiState.Close -> {
                settled = true
                field.text = null
                dialog.dismiss()
            }
            is ExitPinUiState.Error -> {
                if (state.clearField) field.text = null
                showError(state.message.resolve(field.resources))
                field.requestFocus()
            }
        }
    }

    private fun showError(message: String?) {
        errorText = message
        error.text = message
        error.visibility = if (message == null) View.GONE else View.VISIBLE
        val background = if (message == null) R.drawable.mdm_field_bg else R.drawable.mdm_field_bg_error
        keepingPadding(field) { field.background = AppCompatResources.getDrawable(field.context, background) }
    }

    private fun toggleShown() {
        shown = !shown
        val selection = field.selectionStart to field.selectionEnd
        // Shown is still a password transformation (one that changes nothing): TextView keeps treating the field as a
        // password, so it offers no copy, cut or share and no suggestions while the PIN is readable.
        field.transformationMethod = if (shown) ShownPin else PasswordTransformationMethod.getInstance()
        if (selection.first >= 0) field.setSelection(selection.first, selection.second)
        toggle.setImageResource(if (shown) R.drawable.ic_v2_eye_off else R.drawable.ic_v2_eye)
        toggle.contentDescription =
            field.context.getString(if (shown) R.string.kiosk_exit_pin_hide else R.string.kiosk_exit_pin_show)
    }

    /** Exit or the keyboard's Done. */
    private fun submit(onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit) {
        // An empty field is never a try: after a wrong PIN the field is cleared and the dialog stays open, so a second
        // tap on Exit (or Done, then Exit) would otherwise use up tries without anything being typed.
        if (!settled && isSubmittable(field.text)) onSubmit(field.text.toString(), this)
    }

    private fun wire(onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit) {
        val submit = { submit(onSubmit) }
        submitHandler = submit
        // inputType textPassword switches the field to monospace, hint included; keep the dialog's sans.
        field.typeface = Typeface.DEFAULT
        // TalkBack: the error line is a polite live region (announced when it appears), and the field reports it as
        // its error, so focusing the field reads "Exit PIN, error: Wrong PIN ...".
        ViewCompat.setAccessibilityDelegate(field, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                errorText?.let {
                    info.error = it
                    info.isContentInvalid = true
                }
            }
        })
        // Gone from the screen (dismissed, cancelled, rebuilt, activity destroyed): settled, and no timer left behind
        // holding the dialog and its activity.
        field.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                settled = true
                field.removeCallbacks(idle)
            }
        })
        // Keyboard and focus in the field as the dialog opens; from then on a minute without input closes it.
        dialog.setOnShown {
            field.requestFocus()
            // A rebuild keeps the deadline of the prompt it replaces; a new prompt starts the minute.
            val kept = keptDeadline
            keptDeadline = null
            if (kept == null) restartIdle() else idleUntil(kept)
        }
        dialog.idle = object : MdmDialog.IdleTimer {
            // Until the rebuilt prompt is shown (its OnShow is posted), the deadline handed to it is the one that
            // counts: a second rebuild in that window must carry it on, not a fresh minute from setText(typed).
            override fun deadline(): Long? = keptDeadline ?: idleDeadline
            override fun keep(deadline: Long) {
                keptDeadline = deadline
            }
        }
        // Nothing to select, copy, share, translate or autofill: no selection or insertion toolbar at all.
        field.isLongClickable = false
        field.customSelectionActionModeCallback = NoActionMode
        field.customInsertionActionModeCallback = NoActionMode
        // One rule for every way to say "done", one try per press:
        // - A hardware keyboard's or scanner's Enter is a key event. It's taken here, before TextView turns it into
        //   an editor action (IME_NULL before API 34, the field's Done from 34; numpad Enter not at all on 24):
        //   the first press submits, repeats and the release are swallowed.
        // - The soft keyboard's Done is an editor action without a key event: it submits.
        // - A scanner that types its Enter as text: the newline never reaches the PIN, and a trailing one submits.
        field.setOnKeyListener { _, keyCode, event ->
            restartIdle()
            act(keyAction(keyCode, event.action, event.repeatCount))
        }
        field.setOnEditorActionListener { _, actionId, event ->
            act(if (event == null && actionId == EditorInfo.IME_ACTION_DONE) Input.SUBMIT else Input.IGNORE)
        }
        // Posted: the filter runs before the text it returns is in the field.
        field.filters = field.filters + NewlineFilter { field.post { submit() } }
        toggle.setOnClickListener { toggleShown() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                restartIdle()
                // Typing again clears the error; clearing the field for a retry happens before the error is shown.
                if (error.visibility == View.VISIBLE && !s.isNullOrEmpty()) showError(null)
            }
        })
    }

    /** Closes the prompt (and with it whatever was typed) a while after the last input. */
    private val idle = Runnable { dialog.dismiss() }

    private fun idleTimeoutMs(): Long = idleTimeoutMs(field.context)

    /** When [idle] runs (SystemClock.uptimeMillis); null until the prompt is shown. */
    private var idleDeadline: Long? = null

    /** The deadline handed over from the prompt this one replaces, for the first show. */
    private var keptDeadline: Long? = null

    /** One idle mechanism: the timer runs to a deadline, which a rebuild hands to the new prompt. */
    private fun restartIdle() = idleUntil(SystemClock.uptimeMillis() + idleTimeoutMs())

    private fun idleUntil(deadline: Long) {
        idleDeadline = deadline
        field.removeCallbacks(idle)
        field.postDelayed(idle, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    /** What a key or editor action in the PIN field does. */
    internal enum class Input { SUBMIT, CONSUME, IGNORE }

    private var submitHandler: (() -> Unit)? = null

    /** Handles [input]; true when the field shouldn't process it further. */
    private fun act(input: Input): Boolean = when (input) {
        Input.SUBMIT -> true.also { submitHandler?.invoke() }
        Input.CONSUME -> true
        Input.IGNORE -> false
    }

    /**
     * Drops CR and LF from what goes into the field: a PIN never has them, and a scanner that types its Enter as text
     * would otherwise put one in the PIN. A newline at the end of the inserted text is that Enter: [onEnter], once.
     */
    private class NewlineFilter(private val onEnter: () -> Unit) : InputFilter {
        override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int):
            CharSequence? {
            val inserted = source.subSequence(start, end)
            if (inserted.none { it == '\n' || it == '\r' }) return null
            if (inserted.last() == '\n' || inserted.last() == '\r') onEnter()
            return inserted.filterNot { it == '\n' || it == '\r' }
        }
    }

    /** A password transformation that hides nothing: the shown PIN, still a password to TextView. */
    private object ShownPin : PasswordTransformationMethod() {
        override fun getTransformation(source: CharSequence, view: View): CharSequence = source
    }

    /** Refuses every selection or insertion toolbar (Copy, Cut, Share, Translate, Autofill, Paste...). */
    private object NoActionMode : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
        override fun onDestroyActionMode(mode: ActionMode?) = Unit
    }

    companion object {
        /**
         * An open prompt with no key or text input for this long closes, taking what was typed with it. A PIN is
         * typed in seconds; a minute covers looking it up or a short interruption, and an abandoned prompt is gone
         * long before anyone else is likely at the device, also where the kiosk keeps the screen on (no screen-off
         * to clear it, see KioskLauncherActivity.onStop).
         */
        const val IDLE_TIMEOUT_MS = 60_000L

        /** [IDLE_TIMEOUT_MS], or longer if the user set a longer "Time to take action" (accessibility, API 29+). */
        fun idleTimeoutMs(context: Context): Long {
            val a11y = context.getSystemService(AccessibilityManager::class.java)
            val recommended = if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val flags = AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT
                a11y.getRecommendedTimeoutMillis(IDLE_TIMEOUT_MS.toInt(), flags).toLong()
            } else {
                IDLE_TIMEOUT_MS
            }
            return recommended.coerceAtLeast(IDLE_TIMEOUT_MS)
        }

        /** A hardware key in the field: Enter submits on its first press; its repeats and release do nothing. */
        internal fun keyAction(keyCode: Int, action: Int, repeatCount: Int): Input = when {
            keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER -> Input.IGNORE
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> Input.SUBMIT
            else -> Input.CONSUME
        }

        /** Whether the field's text is worth handing to the PIN check: an empty string can never match a PIN. */
        internal fun isSubmittable(text: CharSequence?): Boolean = !text.isNullOrEmpty()

        /** Build (not show) the dialog on [context]: an activity, never the application context (the theme). */
        fun create(
            context: Context,
            /** Text typed into the dialog this one replaces after a config change (memory only). */
            typed: CharSequence? = null,
            onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit,
        ): ExitPinDialog {
            var pin: ExitPinDialog? = null
            val spec = MdmDialog.Spec(
                title = context.getString(R.string.kiosk_exit_title),
                message = context.getString(R.string.kiosk_exit_message),
                confirm = context.getString(R.string.kiosk_exit_confirm),
                cancel = context.getString(R.string.action_cancel),
                body = R.layout.dialog_exit_pin,
                keyboard = true,
            )
            // Exit checks the PIN and doesn't close the dialog: render() closes it, or shows the error.
            val mdm = MdmDialog.create(context, spec) { pin?.submit(onSubmit) }
            val body = checkNotNull(mdm.body)
            val dialog = ExitPinDialog(
                mdm,
                body.findViewById(R.id.exit_pin_field),
                body.findViewById(R.id.exit_pin_toggle),
                body.findViewById(R.id.exit_pin_error),
            )
            pin = dialog
            dialog.wire(onSubmit)
            typed?.let {
                dialog.field.setText(it)
                dialog.field.setSelection(dialog.field.length())
            }
            return dialog
        }

        /** A new background resets the padding to the drawable's own; keep the style's. */
        private inline fun keepingPadding(view: View, change: () -> Unit) {
            val start = view.paddingStart
            val top = view.paddingTop
            val end = view.paddingEnd
            val bottom = view.paddingBottom
            change()
            view.setPaddingRelative(start, top, end, bottom)
        }
    }
}
