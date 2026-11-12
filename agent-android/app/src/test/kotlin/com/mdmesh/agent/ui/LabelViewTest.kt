package com.mdmesh.agent.ui

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A label is laid out once per width: a scroll or relayout at the same width does no layout work. */
@RunWith(RobolectricTestRunner::class)
class LabelViewTest {
    private fun measure(v: View, width: Int) =
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)

    @Test fun layingOutIsCachedPerWidthAndBoundedForHugeLabels() {
        val v = LabelView(ApplicationProvider.getApplicationContext(), 2)
        v.layoutParams = android.view.ViewGroup.LayoutParams(300, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        v.label = "W".repeat(100_000)
        val start = System.nanoTime()
        measure(v, 300)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("a 100k-character label: $ms ms", ms < 500)
        assertEquals(1, v.layouts)
        repeat(5) { measure(v, 300) }
        assertEquals("the same width again: no work", 1, v.layouts)
        measure(v, 200)
        assertEquals("a new width: laid out again", 2, v.layouts)
        assertTrue(v.text.endsWith(LabelLines.ELLIPSIS))
    }
}
