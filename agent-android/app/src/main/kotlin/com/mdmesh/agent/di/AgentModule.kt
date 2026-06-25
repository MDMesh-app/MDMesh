package com.mdmesh.agent.di

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.mdmesh.agent.BuildConfig
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.capability.CapabilityCollector
import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.action.AlertNotifier
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.core.action.RingController
import com.mdmesh.core.command.handlers.AppIconsHandler
import com.mdmesh.core.command.handlers.AppInstallHandler
import com.mdmesh.core.command.handlers.AppScanHandler
import com.mdmesh.core.command.handlers.AppUninstallHandler
import com.mdmesh.core.command.handlers.ConfigSyncHandler
import com.mdmesh.core.command.handlers.DeviceAlertHandler
import com.mdmesh.core.command.handlers.DeviceLockHandler
import com.mdmesh.core.command.handlers.DeviceLockscreenMessageHandler
import com.mdmesh.core.command.handlers.DevicePasscodeResetHandler
import com.mdmesh.core.command.handlers.DeviceRebootHandler
import com.mdmesh.core.command.handlers.DeviceRingHandler
import com.mdmesh.core.command.handlers.DeviceLocationModeHandler
import com.mdmesh.core.command.handlers.DevicePowerModeHandler
import com.mdmesh.core.command.handlers.DeviceRingStopHandler
import com.mdmesh.core.command.handlers.DeviceWipeHandler
import com.mdmesh.core.location.LocationModeStore
import com.mdmesh.core.power.PowerModeStore
import com.mdmesh.core.command.handlers.KioskEnterHandler
import com.mdmesh.core.command.handlers.KioskExitHandler
import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.core.device.AppInventoryCollector
import com.mdmesh.core.device.HardwareIdCollector
import com.mdmesh.core.sync.HardwareIdSource
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.state.DeviceStateCollector
import com.mdmesh.core.state.DeviceStateSource
import com.mdmesh.core.store.DataStoreKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.telemetry.DeviceInfoCollector
import com.mdmesh.core.telemetry.DynamicStateCollector
import com.mdmesh.core.telemetry.IdentityCollector
import com.mdmesh.core.telemetry.SecurityCollector
import com.mdmesh.core.telemetry.TelemetryAssembler
import com.mdmesh.core.telemetry.TelemetrySource
import com.mdmesh.proto.AppManagement
import com.mdmesh.proto.DeviceAction
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.FaultStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.LockTaskKioskController
import com.mdmesh.kiosk.SharedPrefsFaultStore
import com.mdmesh.oem.GenericOemAdapter
import com.mdmesh.oem.KnoxAdapter
import com.mdmesh.oem.OemAdapter
import com.mdmesh.policy.CapabilityRegistry
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.remote.RemoteControlTierDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Assembles the device-specific capability graph and binds it to the `:core`
 * orchestration types. This is the one place that knows about *this* app's admin
 * component and concrete adapters; everything downstream depends only on interfaces.
 *
 * Command handlers are contributed via multibindings (`@IntoSet`), so adding a new
 * command is a single `@Provides @IntoSet` line — the dispatcher never changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideDpmHandle(@ApplicationContext context: Context): DpmHandle {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return DpmHandle(dpm = dpm, admin = AdminReceiver.componentName(context))
    }

    @Provides
    @Singleton
    fun provideCapabilityRegistry(handle: DpmHandle): CapabilityRegistry =
        CapabilityRegistry(handle)

    @Provides
    @Singleton
    fun provideOemAdapter(): OemAdapter {
        // Most-capable-first; GenericOemAdapter is always available as the fallback.
        val candidates = listOf(KnoxAdapter(), GenericOemAdapter())
        return candidates.first { it.isAvailable() }
    }

    @Provides
    @Singleton
    fun provideRemoteTierDetector(): RemoteControlTierDetector =
        // MediaProjection + accessibility probes land with :remote's real impl;
        // until then advertise tier=none honestly.
        RemoteControlTierDetector(
            screenCaptureAvailable = false,
            inputInjectionAvailable = false,
        )

    @Provides
    @Singleton
    fun provideCapabilityCollector(
        @ApplicationContext context: Context,
        registry: CapabilityRegistry,
        remoteTierDetector: RemoteControlTierDetector,
        oemAdapter: OemAdapter,
        handle: DpmHandle,
    ): CapabilityCollector {
        val deviceOwner = handle.dpm.isDeviceOwnerApp(context.packageName)
        return CapabilityCollector(
            agentVersion = BuildConfig.VERSION_NAME,
            agentPackage = context.packageName,
            isDeviceOwner = deviceOwner,
            capabilityRegistry = registry,
            remoteTierDetector = remoteTierDetector,
            oemAdapter = oemAdapter,
            // Silent install needs Device Owner — advertise app.silentInstall only when we have it, so
            // the server's capability gate won't queue an app.install we can't perform.
            appManagementKeys = if (deviceOwner) AppManagement.DEVICE_OWNER_KEYS else emptyList(),
            deviceActionKeys = DeviceAction.ADVERTISED_KEYS,
        )
    }

    /** Expose the collector behind its interface for the Android-free sync/enroll logic. */
    @Provides
    fun provideCapabilitySource(collector: CapabilityCollector): CapabilitySource = collector

    /** Stable, permission-free device id for enrollment de-duplication. */
    @Provides
    fun provideHardwareIdSource(collector: HardwareIdCollector): HardwareIdSource = collector

    /** Expose the device-state collector behind its interface (keeps :core sync Android-free). */
    @Provides
    fun provideDeviceStateSource(collector: DeviceStateCollector): DeviceStateSource = collector

    /** Expose the persistent event buffer behind its interface (keeps :core sync Android-free). */
    @Provides
    fun provideEventSink(eventLog: EventLog): EventSink = eventLog

    /** Compose the telemetry collectors into the census source (graceful degradation per collector). */
    @Provides
    @Singleton
    fun provideTelemetrySource(
        deviceInfo: DeviceInfoCollector,
        identity: IdentityCollector,
        dynamic: DynamicStateCollector,
        security: SecurityCollector,
    ): TelemetrySource = TelemetryAssembler(
        hardware = { deviceInfo.collect() },
        identity = { identity.collect() },
        dynamic = { dynamic.collect() },
        security = { security.collect() },
    )

    /** The supported toggle policies, keyed by capability key (data-driven routing). */
    @Provides
    fun providePolicyToggles(registry: CapabilityRegistry): Map<String, TogglePolicy> =
        registry.togglePolicies()

    // --- Command handlers (multibound). Add a command == add one @IntoSet provider. ---

    @Provides
    @IntoSet
    fun provideConfigSyncHandler(): CommandHandler = ConfigSyncHandler()

    @Provides
    @IntoSet
    fun providePolicyApplyHandler(
        toggles: Map<String, @JvmSuppressWildcards TogglePolicy>,
    ): CommandHandler = PolicyApplyHandler(toggles)

    @Provides
    @Singleton
    fun provideCommandDispatcher(
        handlers: Set<@JvmSuppressWildcards CommandHandler>,
    ): CommandDispatcher = CommandDispatcher(handlers.toList())

    // --- Kiosk (lock-task) ---

    @Provides
    @Singleton
    fun provideKioskController(handle: DpmHandle): KioskController =
        LockTaskKioskController(handle.dpm, handle.admin)

    @Provides
    @Singleton
    fun provideKioskStateStore(@ApplicationContext context: Context): KioskStateStore =
        DataStoreKioskStateStore(context)

    @Provides
    @Singleton
    fun provideFaultStore(@ApplicationContext context: Context): FaultStore =
        SharedPrefsFaultStore(context)

    @Provides
    @Singleton
    fun provideCrashLoopGuard(store: FaultStore): CrashLoopGuard = CrashLoopGuard(store)

    // --- App-management + kiosk + device command handlers (multibound) ---

    @Provides
    @IntoSet
    fun provideAppInstallHandler(installManager: InstallManager): CommandHandler =
        AppInstallHandler(installManager)

    @Provides
    @IntoSet
    fun provideAppUninstallHandler(installManager: InstallManager): CommandHandler =
        AppUninstallHandler(installManager)

    @Provides
    @Singleton
    fun provideAppInventoryCollector(@ApplicationContext context: Context): AppInventoryCollector =
        AppInventoryCollector(context)

    @Provides
    @IntoSet
    fun provideAppScanHandler(inventory: AppInventoryCollector): CommandHandler =
        AppScanHandler(inventory)

    @Provides
    @IntoSet
    fun provideAppIconsHandler(inventory: AppInventoryCollector): CommandHandler =
        AppIconsHandler(inventory)

    // The HOME claim is the activity-alias (toggled on only during kiosk), not the launcher
    // activity itself — see AndroidManifest `.KioskHomeAlias`.
    private fun kioskHomeAlias(context: Context): ComponentName =
        ComponentName(context.packageName, "com.mdmesh.agent.KioskHomeAlias")

    @Provides
    @IntoSet
    fun provideKioskEnterHandler(
        kiosk: KioskController,
        store: KioskStateStore,
        @ApplicationContext context: Context,
    ): CommandHandler = KioskEnterHandler(kiosk, store, kioskHomeAlias(context), context)

    @Provides
    @IntoSet
    fun provideKioskExitHandler(
        kiosk: KioskController,
        store: KioskStateStore,
        @ApplicationContext context: Context,
    ): CommandHandler = KioskExitHandler(kiosk, store, kioskHomeAlias(context), context)

    @Provides
    @IntoSet
    fun provideDeviceRebootHandler(handle: DpmHandle): CommandHandler =
        DeviceRebootHandler(handle)

    @Provides
    @IntoSet
    fun provideDeviceLockHandler(handle: DpmHandle): CommandHandler =
        DeviceLockHandler(handle)

    // --- Remote action handlers (multibound) ---

    @Provides
    @IntoSet
    fun provideLockscreenMessageHandler(handle: DpmHandle): CommandHandler =
        DeviceLockscreenMessageHandler(handle)

    @Provides
    @IntoSet
    fun provideAlertHandler(notifier: AlertNotifier): CommandHandler =
        DeviceAlertHandler(notifier)

    @Provides
    @IntoSet
    fun provideRingHandler(ring: RingController): CommandHandler =
        DeviceRingHandler(ring)

    @Provides
    @IntoSet
    fun provideRingStopHandler(ring: RingController): CommandHandler =
        DeviceRingStopHandler(ring)

    @Provides
    @IntoSet
    fun providePasscodeResetHandler(handle: DpmHandle, store: ResetPasswordTokenStore): CommandHandler =
        DevicePasscodeResetHandler(handle, store)

    @Provides
    @IntoSet
    fun provideWipeHandler(handle: DpmHandle): CommandHandler =
        DeviceWipeHandler(handle)

    @Provides
    @IntoSet
    fun providePowerModeHandler(store: PowerModeStore): CommandHandler =
        DevicePowerModeHandler(store)

    @Provides
    @IntoSet
    fun provideLocationModeHandler(store: LocationModeStore): CommandHandler =
        DeviceLocationModeHandler(store)
}
