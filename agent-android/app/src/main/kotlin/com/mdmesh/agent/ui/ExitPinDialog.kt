package com.mdmesh.agent.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
 * The typed PIN is never saved with the activity's state: after a rotation the launcher reopens the dialog empty.
 * The wrong-PIN count and lockout live in ExitPinGate's persisted store, so neither resets with the dialog.
 */
class ExitPinDialog private constructor(
    /** The dialog itself, for the activity's [DialogSlot]. */
    val dialog: MdmDialog,
    private val field: EditText,
    private val toggle: ImageButton,
    private val error: TextView,
) {

    private var shown = false

    /** The error shown under the field, also given to accessibility services as the field's error. */
    private var errorText: String? = null

    fun render(state: ExitPinUiState) {
        when (state) {
            ExitPinUiState.Close -> dialog.dismiss()
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
        field.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
        if (selection.first >= 0) field.setSelection(selection.first, selection.second)
        toggle.setImageResource(if (shown) R.drawable.ic_v2_eye_off else R.drawable.ic_v2_eye)
        toggle.contentDescription =
            field.context.getString(if (shown) R.string.kiosk_exit_pin_hide else R.string.kiosk_exit_pin_show)
    }

    /** Exit or the keyboard's Done. */
    private fun submit(onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit) {
        // An empty field is never a try: after a wrong PIN the field is cleared and the dialog stays open, so a second
        // tap on Exit (or Done, then Exit) would otherwise use up tries without anything being typed.
        if (isSubmittable(field.text)) onSubmit(field.text.toString(), this)
    }

    private fun wire(onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit) {
        val submit = { submit(onSubmit) }
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
        // Keyboard and focus in the field as the dialog opens.
        dialog.alert.setOnShowListener { field.requestFocus() }
        field.setOnEditorActionListener { _, actionId, event ->
            when (editorAction(actionId, event?.action, event?.keyCode)) {
                EditorAction.SUBMIT -> true.also { submit() }
                EditorAction.CONSUME -> true
                EditorAction.IGNORE -> false
            }
        }
        toggle.setOnClickListener { toggleShown() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Typing again clears the error; clearing the field for a retry happens before the error is shown.
                if (error.visibility == View.VISIBLE && !s.isNullOrEmpty()) showError(null)
            }
        })
    }

    /** What a key or IME action in the PIN field does. */
    internal enum class EditorAction { SUBMIT, CONSUME, IGNORE }

    companion object {
        /**
         * The soft keyboard's Done submits, and so does Enter on a hardware keyboard or a scanner, which arrives as
         * IME_NULL with its key event: on the press, once; the release is swallowed so it can't count again.
         */
        internal fun editorAction(actionId: Int, keyAction: Int?, keyCode: Int?): EditorAction {
            val enter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            return when {
                actionId == EditorInfo.IME_ACTION_DONE -> EditorAction.SUBMIT
                actionId != EditorInfo.IME_NULL || !enter -> EditorAction.IGNORE
                keyAction == KeyEvent.ACTION_DOWN -> EditorAction.SUBMIT
                else -> EditorAction.CONSUME
            }
        }

        /** Whether the field's text is worth handing to the PIN check: an empty string can never match a PIN. */
        internal fun isSubmittable(text: CharSequence?): Boolean = !text.isNullOrEmpty()

        /** Build (not show) the dialog on [context]: an activity, never the application context (the theme). */
        fun create(context: Context, onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit): ExitPinDialog {
            var pin: ExitPinDialog? = null
            val spec = MdmDialog.Spec(
                title = context.getString(R.string.kiosk_exit_title),
                message = context.getString(R.string.kiosk_exit_message),
                confirm = context.getString(R.string.kiosk_exit_confirm),
                cancel = context.getString(R.string.action_cancel),
                body = R.layout.dialog_exit_pin,
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
            mdm.alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
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
