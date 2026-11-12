package com.mdmesh.agent.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mdmesh.core.kiosk.KioskBackgroundPrefetchWorker
import com.mdmesh.proto.KioskBackgroundImageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR #192 S4: the prefetch follows the kiosk's image. A kiosk that drops its image, or a kiosk that ends, cancels the
 * prefetch of the old one, which would otherwise retry an unreachable image for up to 6 h at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KioskPrefetchFollowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val image = KioskBackgroundImageDto("https://127.0.0.1:1/kiosk.png", "a".repeat(64))

    private fun states(): List<WorkInfo.State> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork("kiosk-background").get().map { it.state }

    @Test fun anImageIsPrefetchedAndNoImageCancelsIt() {
        KioskBackgroundPrefetchWorker.follow(app, image)
        assertTrue("queued: ${states()}", states().any { !it.isFinished })
        KioskBackgroundPrefetchWorker.follow(app, null)
        assertEquals(listOf(WorkInfo.State.CANCELLED), states())
    }

    @Test fun leavingTheKioskCancelsIt() {
        KioskBackgroundPrefetchWorker.follow(app, image)
        KioskBackgroundPrefetchWorker.cancel(app)
        assertEquals(listOf(WorkInfo.State.CANCELLED), states())
    }
}
