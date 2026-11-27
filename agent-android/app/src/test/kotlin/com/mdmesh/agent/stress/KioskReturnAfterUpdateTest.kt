package com.mdmesh.agent.stress

import android.content.Intent
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.mdmesh.agent.service.BootReceiver
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Replacing the agent ends the lock task its process held: the package-replaced broadcast itself starts the kiosk
 * again (the pinned app afresh, then the kiosk HOME), without waiting for the re-apply or a HOME press.
 */
class KioskReturnAfterUpdateTest : KioskHarness() {

    @Test fun agentUpdateInKioskShowsTheKioskAgain() {
        install(pinned)
        saveKiosk(single) // in kiosk, lock task not running (the update ended it)
        // Queued work never runs here: the start-time re-apply would bring the kiosk back too, later. This is the
        // broadcast's own, immediate return.
        val idle = Configuration.Builder().setExecutor { }.build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, idle, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        app.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setClass(app, BootReceiver::class.java))
        settle()
        val started = generateSequence { shadowOf(app).nextStartedActivity }.toList()
        assertTrue("the kiosk HOME: $started", started.any { it.component?.className == HOME_ALIAS })
        assertTrue(
            "the pinned app afresh: $started",
            started.any { it.isAfresh(pinned) },
        )
    }

    private fun Intent.isAfresh(pkg: String) =
        (component?.packageName ?: `package`) == pkg && flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0

    private companion object {
        const val HOME_ALIAS = "com.mdmesh.agent.KioskHomeAlias"
    }
}
