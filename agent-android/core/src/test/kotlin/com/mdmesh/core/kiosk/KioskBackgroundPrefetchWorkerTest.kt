package com.mdmesh.core.kiosk

import androidx.work.ListenableWorker.Result
import com.mdmesh.core.kiosk.KioskBackgroundImageStore.Obtained
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class KioskBackgroundPrefetchWorkerTest {
    @Test fun `a cached image or one that can never work ends the prefetch, a failed download retries`() {
        assertEquals(Result.success(), KioskBackgroundPrefetchWorker.outcome(Obtained.Ready(File("x.image"))))
        assertEquals(Result.success(), KioskBackgroundPrefetchWorker.outcome(Obtained.Invalid))
        assertEquals(Result.retry(), KioskBackgroundPrefetchWorker.outcome(Obtained.Retry(1_000L)))
    }
}
