package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.AppInstallHandler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The after-install steps (the app's permission rules, the kiosk guard) each run whatever the other does. */
class AppInstallAfterStepsTest {
    @Test fun `a step that throws never skips the next`() = runTest {
        val ran = mutableListOf<String>()
        AppInstallHandler.each(
            { ran += "rules:$it"; error("rules failed") },
            { ran += "guard:$it" },
        )("com.kiosk")
        assertEquals(listOf("rules:com.kiosk", "guard:com.kiosk"), ran)
    }
}
