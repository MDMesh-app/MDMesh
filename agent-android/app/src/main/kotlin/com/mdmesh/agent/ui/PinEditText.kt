package com.mdmesh.agent.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/**
 * The exit PIN field. Refuses the text actions that would take the PIN out of it (Copy, Cut, Share, Translate,
 * Autofill...), whether they come from a toolbar, a menu or a keyboard shortcut, also while the PIN is shown.
 */
class PinEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    override fun onTextContextMenuItem(id: Int): Boolean =
        if (id == android.R.id.paste || id == android.R.id.selectAll) super.onTextContextMenuItem(id) else true
}
