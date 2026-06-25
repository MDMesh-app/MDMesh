package com.mdmesh.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeSignalTest {
    @Test fun parsesCommandsWake() {
        assertEquals("commands", WakeSignal.parse("""{"wake":"commands"}""")?.kind)
    }

    @Test fun parsesInteractiveTtl() {
        val w = WakeSignal.parse("""{"wake":"interactive","ttlSec":120}""")
        assertEquals("interactive", w?.kind)
        assertEquals(120, w?.ttlSec)
    }

    @Test fun rejectsGarbage() {
        assertNull(WakeSignal.parse("not json"))
        assertNull(WakeSignal.parse("""{"nope":1}"""))
    }
}
