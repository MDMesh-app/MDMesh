package com.mdmesh.agent.di

import android.app.ActivityManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.agent.stress.AdminRestrictionsShadow
import com.mdmesh.core.kiosk.GuardRecord
import com.mdmesh.core.kiosk.InMemoryGuardRecordStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.reportedRevision
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PermissionGrantLock
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The production kiosk guard follows the device's own lock task, through the production kiosk controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KioskGuardWiringTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `a record marked releasing holds factory reset exactly while the device is locked`() {
        shadowOf(app.getSystemService(DevicePolicyManager::class.java))
            .setDeviceOwner(AdminReceiver.componentName(app))
        val handle = AgentModule.provideDpmHandle(app)
        val releasing = GuardRecord(
            priorPolicy = DevicePolicyManager.PERMISSION_POLICY_PROMPT,
            packages = setOf("com.a"),
            fixedBefore = null,
            releasing = true,
        )
        val guard = AgentModule.provideKioskGuard(
            app, handle, InMemoryConfigStateStore(), FactoryResetWipeHold(), PermissionGrantLock(),
            InMemoryGuardRecordStore(releasing), AgentModule.provideKioskController(handle),
        )
        val am = shadowOf(app.getSystemService(ActivityManager::class.java))
        for (state in listOf(
            ActivityManager.LOCK_TASK_MODE_NONE,
            ActivityManager.LOCK_TASK_MODE_LOCKED,
            ActivityManager.LOCK_TASK_MODE_PINNED,
            ActivityManager.LOCK_TASK_MODE_NONE,
        )) {
            am.setLockTaskModeState(state)
            assertEquals("lock task $state", state == ActivityManager.LOCK_TASK_MODE_LOCKED, guard.held())
        }
    }

    @Test
    @Config(shadows = [AdminRestrictionsShadow::class])
    fun `kiosk entered and left never changes the revision the device reports`() {
        shadowOf(app.getSystemService(DevicePolicyManager::class.java))
            .setDeviceOwner(AdminReceiver.componentName(app))
        val handle = AgentModule.provideDpmHandle(app)
        val users = app.getSystemService(UserManager::class.java)
        for (allow in listOf(true, null)) {
            val policies = listOfNotNull(allow?.let { FactoryResetPolicy.CAPABILITY_KEY to it }).toMap()
            val store = InMemoryConfigStateStore()
            store.save(ConfigApplyPayload(revision = "s1", policies = policies, kiosk = KioskApplyPayload()))
            val guard = AgentModule.provideKioskGuard(
                app, handle, store, FactoryResetWipeHold(), PermissionGrantLock(),
                InMemoryGuardRecordStore(), AgentModule.provideKioskController(handle),
            )
            guard.arm(setOf("com.a"))
            assertEquals("blocked in kiosk", true, users.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET))
            assertEquals("entered, allow=$allow", "s1", store.reportedRevision())
            assertEquals(KioskResult.Ok, guard.disarm())
            assertEquals("lifted", false, users.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET))
            assertEquals("left, allow=$allow", "s1", store.reportedRevision())
        }
    }
}
