package com.mdmesh.policy

import android.util.Log
import com.mdmesh.policy.bluetooth.BluetoothPolicy
import com.mdmesh.policy.bluetooth.BluetoothPolicyFactory
import com.mdmesh.policy.camera.CameraPolicy
import com.mdmesh.policy.camera.CameraPolicyFactory
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.FactoryResetPolicyFactory
import com.mdmesh.policy.safeboot.SafeBootPolicy
import com.mdmesh.policy.safeboot.SafeBootPolicyFactory
import com.mdmesh.policy.screenshots.ScreenshotsPolicy
import com.mdmesh.policy.screenshots.ScreenshotsPolicyFactory
import com.mdmesh.policy.systemupdate.DpmSystemUpdatePlatform
import com.mdmesh.policy.systemupdate.SystemUpdateControl
import com.mdmesh.policy.usb.UsbStoragePolicy
import com.mdmesh.policy.usb.UsbStoragePolicyFactory
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.policy.wifi.WifiPolicy
import com.mdmesh.policy.wifi.WifiPolicyFactory
import com.mdmesh.policy.wifi.InMemoryWifiRadioOffState
import com.mdmesh.policy.wifi.WifiRadio
import com.mdmesh.policy.wifi.WifiRadioOffState
import com.mdmesh.policy.wifi.WifiRadioPolicy
import com.mdmesh.policy.wifi.WifiRadioPolicyFactory

/**
 * Reports which policy capability keys (from `proto/registry.md`) *this* device
 * actually supports, by probing each policy's strategy factory.
 *
 * This is the bridge between the capability-abstraction layer and the protocol:
 * [supportedPolicyKeys] feeds directly into the `capabilities.policy` array of the
 * [com.mdmesh.proto.CapabilityMatrix]. A capability only appears here if a
 * usable strategy exists, which is exactly what guarantees the server never sends
 * a command the device can't honour.
 *
 * As more policy surfaces are added (bluetooth, camera, kiosk, ...) each registers
 * its factory probe here. The `:core` module wraps this together with app-mgmt,
 * remote-control, and OEM probes to build the full matrix.
 */
class CapabilityRegistry(
    private val handle: DpmHandle,
    /** The Wi-Fi radio surface for `wifiRadio`; null (e.g. in instrumented tests) leaves that key unadvertised. */
    private val wifiRadio: WifiRadio? = null,
    /** Persisted `wifiRadio` off state (self-heal across reboots); in memory when not given. */
    private val wifiRadioState: WifiRadioOffState = InMemoryWifiRadioOffState(),
) {

    /** Probe errors, logged once per key and error class per interval, plus the recovery. */
    private val probeLog = ProbeErrorLog { message, error ->
        if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
    }

    /**
     * Each toggle's factory probe, keyed by capability key. This is the single extension point for on/off policies:
     * add a probe here and both the advertised capabilities ([supportedPolicyKeys]) and the generic `policy.apply` /
     * `config.apply` routing pick it up automatically — no switch to update. Each probe returns null on a device
     * where the policy is unsupported, so a key only appears when a usable strategy exists.
     * Absence == "not advertised" == "never commanded".
     */
    private val toggles = TogglePolicyResolver(
        linkedMapOf<String, () -> TogglePolicy?>(
            WifiPolicy.CAPABILITY_KEY to { WifiPolicyFactory.create(handle) },
            CameraPolicy.CAPABILITY_KEY to { CameraPolicyFactory.create(handle) },
            ScreenshotsPolicy.CAPABILITY_KEY to { ScreenshotsPolicyFactory.create(handle) },
            BluetoothPolicy.CAPABILITY_KEY to { BluetoothPolicyFactory.create(handle) },
            UsbStoragePolicy.CAPABILITY_KEY to { UsbStoragePolicyFactory.create(handle) },
            FactoryResetPolicy.CAPABILITY_KEY to { FactoryResetPolicyFactory.create(handle) },
            SafeBootPolicy.CAPABILITY_KEY to { SafeBootPolicyFactory.create(handle) },
            WifiRadioPolicy.CAPABILITY_KEY to { WifiRadioPolicyFactory.create(handle, wifiRadio, wifiRadioState) },
        ),
        onProbeError = probeLog::failed,
        onProbeOk = probeLog::recovered,
    )

    /**
     * The toggle policies with a working strategy on this device right now, keyed by capability key, plus the keys
     * whose probe threw on this read ([ResolvedToggles.errors]: fail closed, never treat them as unsupported).
     *
     * Resolve per operation; never inject or store the result. Device-Owner status flips to true partway through
     * QR provisioning, after this process may have started (#76), so a stored map misses every Device-Owner policy
     * for the life of the process. Pass `registry::togglePolicies` instead. Each policy is created once and the
     * same instance is returned on every later call (see [TogglePolicyResolver]). TogglePolicyWiringTest fails
     * the build when production code stores or injects the result.
     */
    fun togglePolicies(): ResolvedToggles = toggles.current()

    /**
     * `systemUpdatePolicy` (1.6). Not a toggle: only the config.apply `systemUpdate` section sets it, so it is not in
     * [toggles]. Its support is checked on every call, as theirs is.
     */
    val systemUpdate: SystemUpdateControl = SystemUpdateControl(DpmSystemUpdatePlatform(handle)) {
        handle.dpm.isDeviceOwnerApp(handle.admin.packageName)
    }

    /**
     * The set of policy keys with a working strategy on this device — derived from the
     * registered strategies, so it can never drift from what can actually be applied.
     * A key whose check threw on this read stays advertised if it resolved before (so the
     * server keeps sending it and the agent keeps retrying); see [ResolvedToggles.advertisedKeys]
     * and [SystemUpdateControl.advertisedKeys].
     * Each entry corresponds to a row in `proto/registry.md` § policy.
     */
    fun supportedPolicyKeys(): List<String> = policyKeys(togglePolicies(), systemUpdate, probeLog)

    /** Convenience: resolve the live [DeviceControl] facade for this device. */
    fun deviceControl(): DeviceControl? {
        val wifi = WifiPolicyFactory.create(handle) ?: return null
        return DefaultDeviceControl(wifi)
    }

    internal companion object {
        private const val TAG = "CapabilityRegistry"

        /** Every advertised policy key: the toggles', then the controls' that aren't toggles. */
        fun policyKeys(toggles: ResolvedToggles, systemUpdate: SystemUpdateControl, log: ProbeErrorLog?): List<String> =
            toggles.advertisedKeys + systemUpdate.advertisedKeys(log)
    }
}

/** Default [DeviceControl] facade wiring the selected strategies together. */
internal class DefaultDeviceControl(
    override val wifi: WifiPolicy,
) : DeviceControl
