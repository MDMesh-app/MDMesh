package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Looper
import com.mdmesh.agent.KioskLauncherActivity
import com.mdmesh.agent.admin.AdminReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.time.Duration

/**
 * #220, both kiosk files lost, through the real lock-task controller: the allowlist is the evidence of an ad-hoc
 * kiosk. A Device Owner call that throws is "unknown", never "nothing allowed": the read fails and is tried again,
 * with nothing decided or kept from it, and HOME stays up meanwhile.
 */
@Config(shadows = [KioskLockTaskRecoveryTest.ThrowsOnce::class])
class KioskLockTaskRecoveryTest : KioskHarness() {

    /** Reading lock task's allowlist throws [failures] more times (the system service briefly unavailable). */
    @Implements(DevicePolicyManager::class)
    class ThrowsOnce : AdminRestrictionsShadow() {
        @Implementation
        override fun getLockTaskPackages(admin: ComponentName): Array<String> {
            if (failures > 0) {
                failures--
                throw SecurityException("device policy service unavailable")
            }
            return super.getLockTaskPackages(admin)
        }

        companion object {
            @JvmStatic var failures = 0
        }
    }

    private val storage by lazy { StoreCorruption(app) }

    @After fun calm() {
        ThrowsOnce.failures = 0
    }

    private fun allow(vararg packages: String) {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        dpm.setLockTaskPackages(ComponentName(app, AdminReceiver::class.java), arrayOf(*packages))
    }

    /** HOME starts on the damaged files with the next Device Owner call failing; its reads retry on the looper. */
    private fun startHomeWithOneFailure() {
        storage.corruptKiosk(mirror = null)
        ThrowsOnce.failures = 1
        ctl = Robolectric.buildActivity(KioskLauncherActivity::class.java).setup()
    }

    private fun awaitRecoveries(expected: List<String>) = storage.eventually("recoveries $expected") {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)) // past the read's backoff
        storage.recoveries() == expected
    }

    @Test fun anAdHocKioskComesBackFromLockTaskAfterAnUnknownRead() {
        allow(pinned, app.packageName)
        startHomeWithOneFailure()
        awaitRecoveries(listOf("mdm_kiosk from lock task"))
        storage.eventually("lock task") {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            lockTaskState() == ActivityManager.LOCK_TASK_MODE_LOCKED
        }
        val kiosk = storedKiosk()
        assertEquals(listOf(pinned), kiosk?.allowedPackages)
        assertEquals("only the server ends it", "remote", kiosk?.exitMode)
        assertEquals("the failed read decided nothing", 0, ThrowsOnce.failures)
    }

    @Test fun aNeverKioskDeviceIsNotStrandedInAKioskByAnUnknownRead() {
        startHomeWithOneFailure()
        // The event is recorded only once a read delivered the restored value: the launcher's collector got it.
        awaitRecoveries(listOf("mdm_kiosk from empty"))
        settle()
        assertNull(storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        assertEquals("only the one recovery, nothing from the failed read", listOf("mdm_kiosk from empty"),
            storage.recoveries())
    }
}
