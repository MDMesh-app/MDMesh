package com.mdmesh.agent.ui

import android.app.Dialog
import android.os.Looper
import android.widget.EditText
import androidx.activity.ComponentActivity
import com.mdmesh.agent.R
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/** Robolectric helpers for the dialog tests: an activity on the app theme, and the dialog it shows. */
internal object DialogHost {

    fun activity(): ComponentActivity {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(R.style.Theme_MdmAgent)
        return controller.setup().get()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The dialog showing now (the latest one shown). */
    fun showing(): Dialog = checkNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }) { "no dialog" }

    fun field(dialog: Dialog): EditText = dialog.findViewById(R.id.exit_pin_field)
}
