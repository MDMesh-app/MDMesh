package com.mdmesh.agent.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.mdmesh.agent.R

/**
 * The Exit kiosk dialog: PIN field with a show/hide toggle and an error line under it. It doesn't check PINs: Exit
 * (or the keyboard's Done) hands the text to [onSubmit] (the launcher's `onPinEntered`), which renders the result
 * back with [render]. A wrong PIN keeps the dialog open; it closes only on [ExitPinUiState.Close] or Cancel.
 */
class ExitPinDialog private constructor(
    private val dialog: AlertDialog,
    private val field: EditText,
    private val toggle: ImageButton,
    private val error: TextView,
) {

    private var shown = false

    fun render(state: ExitPinUiState) {
        when (state) {
            ExitPinUiState.Close -> dialog.dismiss()
            is ExitPinUiState.Error -> {
                if (state.clearField) field.text = null
                showError(state.message.resolve(dialog.context.resources))
                field.requestFocus()
            }
        }
    }

    private fun showError(message: String?) {
        error.text = message
        error.visibility = if (message == null) View.GONE else View.VISIBLE
        val background = if (message == null) R.drawable.mdm_field_bg else R.drawable.mdm_field_bg_error
        keepingPadding(field) { field.background = AppCompatResources.getDrawable(dialog.context, background) }
    }

    private fun toggleShown() {
        shown = !shown
        val selection = field.selectionStart to field.selectionEnd
        field.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
        if (selection.first >= 0) field.setSelection(selection.first, selection.second)
        toggle.setImageResource(if (shown) R.drawable.ic_v2_eye_off else R.drawable.ic_v2_eye)
        toggle.contentDescription =
            dialog.context.getString(if (shown) R.string.kiosk_exit_pin_hide else R.string.kiosk_exit_pin_show)
    }

    private fun wire(onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit) {
        val submit = { onSubmit(field.text.toString(), this) }
        // Replaces the default click, which would close the dialog on any answer.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit() }
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

    companion object {
        /** Show the dialog on [context] (an activity, never the application context: the theme matters). */
        fun show(context: Context, onSubmit: (entered: String, dialog: ExitPinDialog) -> Unit): ExitPinDialog {
            val builder = AlertDialog.Builder(context)
            // Inflated from the dialog's themed context, so the field takes the dialog theme's v2 field style.
            val body = LayoutInflater.from(builder.context).inflate(R.layout.dialog_exit_pin, null, false)
            val alert = builder
                .setTitle(R.string.kiosk_exit_title)
                .setMessage(R.string.kiosk_exit_message)
                .setView(body)
                .setPositiveButton(R.string.kiosk_exit_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create()
            // Keyboard up with the dialog, focus in the field.
            alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            alert.show()
            val dialog = ExitPinDialog(
                alert,
                body.findViewById(R.id.exit_pin_field),
                body.findViewById(R.id.exit_pin_toggle),
                body.findViewById(R.id.exit_pin_error),
            )
            dialog.wire(onSubmit)
            dialog.field.requestFocus()
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
