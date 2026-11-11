package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The backdrop's one geometry function: FILL around a focal point (centre by default) and FIT's letterbox. */
class BackdropGeometryTest {
    private val eps = 1e-9

    @Test fun `fill around the centre is CENTER_CROP`() {
        val p = BackdropGeometry.place(40 to 16, 80.0 to 160.0)
        // scale = max(2, 10) = 10: crop 8 x 16 at (16, 0), 0.1 image px per unit.
        assertEquals(16.0, p.left, eps)
        assertEquals(0.0, p.top, eps)
        assertEquals(0.1, p.perUnit, eps)
    }

    @Test fun `fill keeps the focal point in view, never past the image's edges`() {
        assertEquals(0.0, BackdropGeometry.place(40 to 16, 80.0 to 160.0, focal = Focal(0.0, 0.5)).left, eps)
        assertEquals(32.0, BackdropGeometry.place(40 to 16, 80.0 to 160.0, focal = Focal(1.0, 0.5)).left, eps)
        assertEquals(26.0, BackdropGeometry.place(40 to 16, 80.0 to 160.0, focal = Focal(0.75, 0.5)).left, eps)
    }

    @Test fun `fit shows the whole image, letterboxed`() {
        val p = BackdropGeometry.place(40 to 16, 80.0 to 160.0, BackdropFit.FIT)
        // scale = min(2, 10) = 2: 0.5 image px per unit; the window is 40 x 80 image px, the image 16 high.
        assertEquals(0.5, p.perUnit, eps)
        assertEquals(0.0, p.left, eps)
        assertEquals(-32.0, p.top, eps)
    }
}
