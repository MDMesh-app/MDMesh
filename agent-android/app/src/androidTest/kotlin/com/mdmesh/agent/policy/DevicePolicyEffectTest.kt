package com.mdmesh.agent.policy

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.policy.CapabilityRegistry
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.camera.CameraPolicy
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.FactoryResetPolicyFactory
import com.mdmesh.policy.safeboot.SafeBootPolicy
import com.mdmesh.policy.safeboot.SafeBootPolicyFactory
import com.mdmesh.policy.screenshots.ScreenshotsPolicy
import com.mdmesh.policy.systemupdate.SystemUpdateControl
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.ConfigSystemUpdate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Closes the one gap the JVM suite structurally cannot: that the agent's `DevicePolicyManager` calls
 * actually move real OS state. The JVM tests fake `TogglePolicy` and can only prove the agent computes
 * the *right DPM call*; these prove Android honours it, and — more importantly — that the polarity
 * survives the trip through the platform, where the DPM APIs speak the opposite language
 * (`setCameraDisabled(admin, !enabled)` vs. the protocol's `true = allowed`).
 *
 * Everything here needs the app to be **Device Owner**, which the test cannot grant itself. On a
 * non-DO device these tests report *skipped*, never *passed*: a skip means the DPM contract is
 * UNVERIFIED. Provision first, then run:
 *
 * ```
 * adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 *
 * (the admin component is `com.mdmesh.agent.admin.AdminReceiver`; only the package carries the
 * `.debug` suffix). `set-device-owner` fails if the device has accounts — factory reset it first.
 *
 * Non-destructive by construction: the original device state is captured in [setUp] and restored in
 * [tearDown], which runs even when a test fails, so a red run never leaves a phone with its camera
 * disabled, its factory reset blocked or its Safe Mode blocked.
 */
@RunWith(AndroidJUnit4::class)
class DevicePolicyEffectTest {

    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var handle: DpmHandle

    /** Pre-test device state, restored unconditionally so a failure cannot brick the device's UX. */
    private lateinit var original: Map<String, Boolean>

    /** The system update policy before the test; null when none was set. */
    private var originalUpdatePolicy: SystemUpdatePolicy? = null

    private val doProvisioningHint: String
        get() = "This device is not Device Owner, so no DPM policy can be enforced and nothing can be " +
            "verified. Provision it first: adb shell dpm set-device-owner " +
            "${context.packageName}/${AdminReceiver::class.java.name}"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        handle = DpmHandle(dpm, AdminReceiver.componentName(context))

        assumeTrue(doProvisioningHint, dpm.isDeviceOwnerApp(context.packageName))

        original = captureDeviceState()
        originalUpdatePolicy = dpm.systemUpdatePolicy
    }

    @After
    fun tearDown() {
        if (::original.isInitialized) {
            restoreDeviceState(original)
            runCatching { dpm.setSystemUpdatePolicy(handle.admin, originalUpdatePolicy) }
        }
    }

    /**
     * The probe chain against a live DPM: `isSupported()` -> factory -> [CapabilityRegistry].
     * This is precisely what a JVM test cannot assert, because the mockable `android.jar` makes
     * `isDeviceOwnerApp` report false and every factory return null.
     */
    @Test
    fun deviceOwnerRegistersTheDoGatedPoliciesInTheCapabilityRegistry() {
        val toggles = CapabilityRegistry(handle).togglePolicies()

        assertTrue(
            "a Device Owner must advertise factoryReset; the server gates on this token",
            FactoryResetPolicy.CAPABILITY_KEY in toggles,
        )
        assertTrue(
            "a Device Owner must advertise safeBoot; the server gates on this token",
            SafeBootPolicy.CAPABILITY_KEY in toggles,
        )
        assertTrue(
            "a Device Owner must advertise the other DO-gated toggles too",
            "camera" in toggles && "screenshots" in toggles,
        )
    }

    @Test
    fun deviceOwnerAdvertisesTheSystemUpdatePolicy() {
        assertTrue(
            "a Device Owner must advertise systemUpdatePolicy; the capability matrix carries it to the server",
            SystemUpdateControl.CAPABILITY_KEY in CapabilityRegistry(handle).supportedPolicyKeys(),
        )
    }

    /**
     * Reads the window back from the OS: swapped arguments would turn a night window into a working-day one and still
     * report applied. Only the windowed policy is used: automatic would install an update waiting on the test phone.
     */
    @Test
    fun aDailyWindowReachesTheOsAsSentAndReleaseClearsIt() {
        val control = CapabilityRegistry(handle).systemUpdate
        val window = ConfigSystemUpdate(ConfigSystemUpdate.WINDOWED, windowStart = 1320, windowEnd = 240)

        assertEquals(PolicyOutcome.Applied, control.apply(window))
        val policy = requireNotNull(dpm.systemUpdatePolicy) { "the window left no policy on the device" }
        assertEquals(SystemUpdatePolicy.TYPE_INSTALL_WINDOWED, policy.policyType)
        assertEquals(1320, policy.installWindowStart)
        assertEquals(240, policy.installWindowEnd)

        assertEquals(PolicyOutcome.Applied, control.release())
        assertNull("release must clear the policy", dpm.systemUpdatePolicy)
    }

    @Test
    fun factoryResetFalseBlocksTheWipeAndTrueReleasesIt() {
        val policy = requireNotNull(FactoryResetPolicyFactory.create(handle)) {
            "factoryReset resolved no strategy on a Device Owner — the probe chain is broken"
        }

        // Read back the real OS state, not the agent's own report of it.
        assertTogglesDeviceState(
            toggle = policy,
            describe = "factoryReset",
            isBlockedOnDevice = { restrictionActive(UserRestrictions.DISALLOW_FACTORY_RESET) },
        )
    }

    @Test
    fun safeBootFalseBlocksSafeModeAndTrueReleasesIt() {
        val policy = requireNotNull(SafeBootPolicyFactory.create(handle)) {
            "safeBoot resolved no strategy on a Device Owner — the probe chain is broken"
        }

        // Read back the real OS state, not the agent's own report of it.
        assertTogglesDeviceState(
            toggle = policy,
            describe = "safeBoot",
            isBlockedOnDevice = { restrictionActive(UserRestrictions.DISALLOW_SAFE_BOOT) },
        )
    }

    @Test
    fun cameraFalseDisablesTheCameraAndTrueRestoresIt() {
        val toggle = toggleFor(CameraPolicy.CAPABILITY_KEY)
        assertTogglesDeviceState(
            toggle = toggle,
            describe = CameraPolicy.CAPABILITY_KEY,
            isBlockedOnDevice = { dpm.getCameraDisabled(handle.admin) },
        )
    }

    @Test
    fun screenshotsFalseDisablesCaptureAndTrueRestoresIt() {
        val toggle = toggleFor(ScreenshotsPolicy.CAPABILITY_KEY)
        assertTogglesDeviceState(
            toggle = toggle,
            describe = ScreenshotsPolicy.CAPABILITY_KEY,
            isBlockedOnDevice = { dpm.getScreenCaptureDisabled(handle.admin) },
        )
    }

    /**
     * The polarity contract, asserted against the platform rather than a fake: `setEnabled(false)`
     * must leave the device *blocked*, `setEnabled(true)` must leave it *usable*. An inversion here
     * is the failure mode that reports `done` to the server while doing the exact opposite.
     */
    private fun assertTogglesDeviceState(
        toggle: TogglePolicy,
        describe: String,
        isBlockedOnDevice: () -> Boolean,
    ) {
        assertEquals("$describe(false) must be accepted by the DPM", PolicyOutcome.Applied, toggle.setEnabled(false))
        assertTrue("$describe(false) must block the action on the real device", isBlockedOnDevice())

        assertEquals("$describe(true) must be accepted by the DPM", PolicyOutcome.Applied, toggle.setEnabled(true))
        assertFalse("$describe(true) must unblock the action on the real device", isBlockedOnDevice())
    }

    private fun toggleFor(capabilityKey: String): TogglePolicy =
        requireNotNull(CapabilityRegistry(handle).togglePolicies()[capabilityKey]) {
            "no strategy registered for '$capabilityKey' on a Device Owner"
        }

    private fun restrictionActive(key: String): Boolean =
        readRestrictions().getBoolean(key, false)

    private fun readRestrictions(): Bundle = dpm.getUserRestrictions(handle.admin)

    /** Snapshot of everything these tests touch, keyed so [restoreDeviceState] can invert it back. */
    private fun captureDeviceState(): Map<String, Boolean> = buildMap {
        put(
            restrictionKey(UserRestrictions.DISALLOW_FACTORY_RESET),
            restrictionActive(UserRestrictions.DISALLOW_FACTORY_RESET),
        )
        put(
            restrictionKey(UserRestrictions.DISALLOW_SAFE_BOOT),
            restrictionActive(UserRestrictions.DISALLOW_SAFE_BOOT),
        )
        put("cameraDisabled", dpm.getCameraDisabled(handle.admin))
        put("screenCaptureDisabled", dpm.getScreenCaptureDisabled(handle.admin))
    }

    /** Restores the pre-test state through the same public strategies the agent uses. */
    private fun restoreDeviceState(state: Map<String, Boolean>) {
        runCatching {
            val toggles = CapabilityRegistry(handle).togglePolicies()
            // The strategies speak "allowed", the DPM speaks "blocked": invert on the way back.
            toggles[FactoryResetPolicy.CAPABILITY_KEY]
                ?.setEnabled(state.getValue(restrictionKey(UserRestrictions.DISALLOW_FACTORY_RESET)).not())
            toggles[SafeBootPolicy.CAPABILITY_KEY]
                ?.setEnabled(state.getValue(restrictionKey(UserRestrictions.DISALLOW_SAFE_BOOT)).not())
            toggles[CameraPolicy.CAPABILITY_KEY]?.setEnabled(state.getValue("cameraDisabled").not())
            toggles[ScreenshotsPolicy.CAPABILITY_KEY]?.setEnabled(state.getValue("screenCaptureDisabled").not())
        }
    }

    private fun restrictionKey(restriction: String) = "restriction:$restriction"
}
