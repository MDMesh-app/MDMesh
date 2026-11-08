package com.mdmesh.agent

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.kiosk.AndroidKioskHomeSwitch
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.DataStoreKioskStateStore
import com.mdmesh.core.store.SharedPrefsConfigStateStore
import com.mdmesh.core.store.configuredKiosk
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.LockTaskKioskController
import com.mdmesh.policy.ResolvedToggles
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskFeaturesDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run on a disposable, offline Device Owner emulator (Wi-Fi and mobile data disabled). Exercises the real PIN UI,
 * persisted kiosk state, Settings access and return button against Android lock task, without an MDM server.
 * Saves the return screen to the app's external files directory for visual review.
 * Manual only: CI compiles/lints this source but never runs it. Assumptions skip unsupported test environments.
 */
@RunWith(AndroidJUnit4::class)
class OfflineKioskReturnTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui get() = instrumentation.uiAutomation
    private lateinit var store: DataStoreKioskStateStore
    private lateinit var applier: KioskApplier
    private lateinit var config: SharedPrefsConfigStateStore
    private lateinit var configApplier: ConfigApplier
    private lateinit var oemHomePackage: String
    private val payload = KioskApplyPayload(
        allowedPackages = listOf("com.android.deskclock"), password = "test-staff-pin", exitMode = "visible",
        features = KioskFeaturesDto(home = true),
    )

    @Before
    fun setUp() = runBlocking {
        assumeTrue("Requires an Android emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        assumeTrue("Requires a disposable Device Owner emulator", dpm.isDeviceOwnerApp(context.packageName))
        store = DataStoreKioskStateStore(context)
        assumeTrue("Do not overwrite an existing kiosk", store.load() == null)
        config = SharedPrefsConfigStateStore(context)
        assumeTrue("Do not overwrite an existing configuration", config.load() == null && config.delivered == null)
        oemHomePackage = requireNotNull(
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            ),
        ).activityInfo.packageName
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assumeTrue("Disable emulator Wi-Fi and mobile data before this test", network.activeNetwork == null)
        val home = ComponentName(context.packageName, "com.mdmesh.agent.KioskHomeAlias")
        val controller = LockTaskKioskController(dpm, AdminReceiver.componentName(context))
        applier = KioskApplier(controller, store, AndroidKioskHomeSwitch(context, home), home) {
            controller.isLocked(context)
        }
        configApplier = ConfigApplier({ ResolvedToggles(emptyMap()) }, applier, {}, config, setPowerMode = {})
    }

    @After
    fun tearDown() = runBlocking {
        if (::configApplier.isInitialized) {
            applier.exit(force = true)
            config.delivered = null
            config.clear()
        }
    }

    @Test
    fun staffCanExitWithPinOpenSettingsAndReturnWhileOffline() = runBlocking {
        // Begin from a visible foreground activity, as a staff member would.
        openAgent()
        val result = configApplier.apply(ConfigApplyPayload(revision = "test-on", kiosk = payload))
        assertTrue(ConfigApplier.succeeded(result))
        awaitCondition { locked() && node("Exit kiosk") != null }
        click("Exit kiosk")
        awaitCondition { findNode(ui.rootInActiveWindow) { it.isEditable } != null }
        val input = requireNotNull(findNode(ui.rootInActiveWindow) { it.isEditable })
        input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload.password)
            },
        )
        click("Exit")
        awaitCondition { !locked() && store.load() == null }
        assertNull(store.load())
        // State clears before the exit hands HOME back. Wait for that hand-off before opening Settings.
        awaitCondition { ui.rootInActiveWindow?.packageName?.toString() == oemHomePackage }

        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitCondition { ui.rootInActiveWindow?.packageName == "com.android.settings" }
        openAgent()
        awaitCondition { node("Return to kiosk")?.isEnabled == true }
        captureScreen("offline-kiosk-return.png")
        val returnStartedAt = System.currentTimeMillis()
        click("Return to kiosk")
        awaitCondition { locked() && store.load() == payload && node("Exit kiosk") != null }
        assertEquals(payload, config.configuredKiosk())
        // Use the persistent event buffer's drain/restore API without discarding earlier test events.
        val eventLog = EventLog(context)
        val events = eventLog.drain()
        try {
            assertEquals(1, events.count {
                it.type == "kioskReturn" && it.detail == "returned on-device" && it.ts >= returnStartedAt
            })
        } finally {
            eventLog.restore(events)
        }
    }

    @Test
    fun configurationDisablingKioskRemovesReturnButtonFromAnOpenAgentScreen() = runBlocking {
        openAgent()
        val result = configApplier.apply(ConfigApplyPayload(revision = "test-on", kiosk = payload))
        assertTrue(ConfigApplier.succeeded(result))
        awaitCondition { locked() }
        assertEquals(KioskResult.Ok, applier.exit())
        awaitCondition { ui.rootInActiveWindow?.packageName?.toString() == oemHomePackage }
        openAgent()
        awaitCondition { node("Return to kiosk") != null }
        assertTrue(ConfigApplier.succeeded(configApplier.apply(ConfigApplyPayload(revision = "test-off"))))
        awaitCondition { node("KIOSK") != null && node("Return to kiosk") == null }
        assertNull(config.configuredKiosk())
    }

    @Test
    fun noConfigurationDoesNotOfferReturnOrCreateADefaultKiosk() = runBlocking {
        openAgent()
        awaitCondition { node("KIOSK") != null }
        assertNull(node("Return to kiosk"))
        assertTrue(configApplier.returnToConfiguredKiosk() is KioskResult.Failed)
        assertNull(store.load())
    }

    private fun openAgent() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun locked(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState ==
            ActivityManager.LOCK_TASK_MODE_LOCKED

    private fun node(text: String): AccessibilityNodeInfo? =
        findNode(ui.rootInActiveWindow) { it.text?.toString()?.equals(text, ignoreCase = true) == true }

    private fun captureScreen(name: String) {
        // Accessibility nodes can appear before the activity transition has finished drawing.
        ui.waitForIdle(SCREEN_IDLE_MS, TIMEOUT_MS)
        File(context.getExternalFilesDir(null), name).outputStream().use {
            requireNotNull(ui.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null || matches(root)) return root
        return (0 until root.childCount).firstNotNullOfOrNull { findNode(root.getChild(it), matches) }
    }

    private fun click(text: String) {
        assertTrue("Cannot click $text", requireNotNull(node(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MS)
        }
        captureScreen("offline-kiosk-failure.png")
        error("Timed out waiting for kiosk UI/state")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
        const val SCREEN_IDLE_MS = 500L
    }
}
