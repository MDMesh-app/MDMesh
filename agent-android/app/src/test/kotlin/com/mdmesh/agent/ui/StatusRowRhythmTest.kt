package com.mdmesh.agent.ui

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The device card's rows (Device ID, Agent version, Server) share one height and one rhythm: the 48dp copy buttons
 * don't make their rows taller or leave more space under the value than the row without a button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-notnight-420dpi")
class StatusRowRhythmTest {

    private fun card(fontScale: Float): ViewGroup {
        // Real text metrics (native graphics) and the system font scale, as the accessibility checks use them.
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = DialogHost.activity()
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_status, null, false)
        root.findViewById<TextView>(R.id.status_device_id).text = "dev_7f3a9c21e4b84d0e"
        root.findViewById<TextView>(R.id.status_version).text = "0.1.15 (build 16)"
        root.findViewById<TextView>(R.id.status_server).text = "https://mdm.example.com"
        val card = root.findViewById<ViewGroup>(R.id.status_device_card)
        val width = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        return card
    }

    /** Top of [v] relative to [ancestor]. */
    private fun topIn(v: View, ancestor: View): Int {
        var y = 0
        var cur: View = v
        while (cur !== ancestor) {
            y += cur.top
            cur = cur.parent as View
        }
        return y
    }

    private fun check(fontScale: Float) {
        val card = card(fontScale)
        val rows = listOf(R.id.status_row_device_id, R.id.status_row_version, R.id.status_row_server)
            .map { card.findViewById<View>(it) }
        val values = listOf(R.id.status_device_id, R.id.status_version, R.id.status_server)
            .map { card.findViewById<View>(it) }
        val heights = rows.map { it.height }
        // At the largest font a long value wraps (a narrower column beside a copy button), so only the rhythm, not
        // the height, is the same there.
        if (fontScale == 1f) assertEquals("row heights at font $fontScale: $heights", 1, heights.distinct().size)
        val below = rows.zip(values).map { (row, value) -> row.height - (topIn(value, row) + value.height) }
        assertEquals("space under each value at font $fontScale: $below", 1, below.distinct().size)
        val above = rows.zip(values).map { (row, value) -> topIn(value, row) }
        assertEquals("value positions at font $fontScale: $above", 1, above.distinct().size)
        val target = rows.first().resources.getDimensionPixelSize(R.dimen.mdm_touch_target)
        if (fontScale > 1f) assertTrue("the font scale took effect: $heights", heights.all { it > target })
    }

    @Test fun `rows share one height and rhythm`() = check(1.0f)

    @Test fun `rows share one height and rhythm at the largest font`() = check(2.0f)
}
