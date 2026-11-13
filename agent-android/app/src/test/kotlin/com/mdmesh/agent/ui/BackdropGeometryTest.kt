package com.mdmesh.agent.ui

import com.mdmesh.proto.KioskBackgroundImageDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** The configuration's fit and focal point as the launcher reads them (the maths: the shared geometry vectors). */
class BackdropGeometryTest {
    private val sha = "a".repeat(64)

    @Test fun `servers without fit or focal mean fill around the centre`() {
        assertEquals(Placement.DEFAULT, Placement.of(null))
        assertEquals(Placement.DEFAULT, Placement.of(KioskBackgroundImageDto("https://x/1.png", sha)))
    }

    @Test fun `fit ignores the focal point and fill keeps it`() {
        val fit = KioskBackgroundImageDto("https://x/1.png", sha, fit = "fit", focalX = 0.0, focalY = 1.0)
        assertEquals(Placement(BackdropFit.FIT, Focal.CENTRE_POINT), Placement.of(fit))
        val fill = KioskBackgroundImageDto("https://x/1.png", sha, focalX = 0.2, focalY = 0.9)
        assertEquals(Placement(BackdropFit.FILL, Focal(0.2, 0.9)), Placement.of(fill))
    }

    @Test fun `a focal point moves the crop within the image only`() {
        val left = BackdropGeometry.rect(4000, 3000, 360.0, 780.0, Placement(BackdropFit.FILL, Focal(0.0, 0.5)))
        val right = BackdropGeometry.rect(4000, 3000, 360.0, 780.0, Placement(BackdropFit.FILL, Focal(1.0, 0.5)))
        assertEquals(0.0, left.x, 1e-9)
        assertEquals(360.0 - 1040.0, right.x, 1e-9)
    }
}
