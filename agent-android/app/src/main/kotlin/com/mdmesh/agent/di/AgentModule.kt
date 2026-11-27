package com.mdmesh.agent.di

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
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
import com.mdmesh.core.command.handlers.ConfigApplyHandler
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
import com.mdmesh.core.command.handlers.DpmWipeTarget
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.location.LocationModeStore
import com.mdmesh.core.power.PowerModeStore
import com.mdmesh.core.command.handlers.KioskClearPinLockoutHandler
import com.mdmesh.core.command.handlers.KioskEnterHandler
import com.mdmesh.core.command.handlers.KioskExitHandler
import com.mdmesh.core.kiosk.AndroidKioskHomeSwitch
import com.mdmesh.core.kiosk.ConfiguredSafeModeSetting
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskBackgroundPrefetchWorker
import com.mdmesh.core.kiosk.KioskGuard
import com.mdmesh.core.kiosk.GuardRecordStore
import com.mdmesh.core.kiosk.SharedPrefsExitJournal
import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.core.device.AppInventoryCollector
import com.mdmesh.core.device.HardwareIdCollector
import com.mdmesh.core.sync.HardwareIdSource
import com.mdmesh.core.sync.LastCheckIn
import com.mdmesh.core.sync.SharedPrefsLastCheckIn
import com.mdmesh.core.sync.WipeHoldExpiryWorker
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.state.DeviceStateCollector
import com.mdmesh.core.state.DeviceStateSource
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.configuredKiosk
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.store.SharedPrefsConfigStateStore
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
import com.mdmesh.proto.EventType
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.Boot
import com.mdmesh.kiosk.HoldStore
import com.mdmesh.kiosk.SharedPrefsHoldStore
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.FaultStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.DpmKioskGuardControl
import com.mdmesh.kiosk.LockTaskKioskController
import com.mdmesh.kiosk.PinAttemptStore
import com.mdmesh.kiosk.SharedPrefsFaultStore
import com.mdmesh.kiosk.SharedPrefsPinAttemptStore
import com.mdmesh.oem.GenericOemAdapter
import com.mdmesh.oem.KnoxAdapter
import com.mdmesh.oem.OemAdapter
import com.mdmesh.policy.CapabilityRegistry
import com.mdmesh.policy.AppRuntimePermissions
import com.mdmesh.policy.DeviceOwnerAppRuntimePermissions
import com.mdmesh.policy.PermissionGrantLock
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.policy.factoryreset.SharedPrefsWipeHoldStore
import com.mdmesh.policy.wifi.AndroidWifiRadio
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.policy.wifi.SharedPrefsWifiRadioOffState
import com.mdmesh.remote.RemoteControlTierDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

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

    private const val WIPE_NOT_STARTED_DETAIL =
        "The device didn't reset within 10 minutes. It is back in use, and its configuration is applied again."

    @Provides
    @Singleton
    fun provideDpmHandle(@ApplicationContext context: Context): DpmHandle {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return DpmHandle(dpm = dpm, admin = AdminReceiver.componentName(context))
    }

    @Provides
    @Singleton
    fun provideCapabilityRegistry(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        wipeHold: FactoryResetWipeHold,
        guard: KioskGuard,
    ): CapabilityRegistry =
        CapabilityRegistry(
            handle, AndroidWifiRadio.from(context), SharedPrefsWifiRadioOffState(context), wipeHold, kioskHold = guard,
        )

    /**
     * The kiosk guard: answers permission requests and protects the kiosk apps while in kiosk ([KioskApplier] drives
     * it). Reads the configuration the server last delivered, as the Safe Mode setting does.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt's explicit bindings keep this wiring auditable.
    fun provideKioskGuard(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        configStore: ConfigStateStore,
        wipeHold: FactoryResetWipeHold,
        grantLock: PermissionGrantLock,
        records: GuardRecordStore,
        controller: KioskController,
    ): KioskGuard {
        fun configured() = configStore.delivered ?: configStore.load()
        return KioskGuard(
            DpmKioskGuardControl(context, handle.dpm, handle.admin), records,
            ownPackage = context.packageName,
            factoryResetSetting = { configured()?.policies?.get(FactoryResetPolicy.CAPABILITY_KEY) },
            permissionRules = {
                (configured()?.appPermissions.orEmpty() + configStore.activeAppPermissions).flatMap { entry ->
                    entry.permissions.keys.map { KioskGuard.pair(entry.packageName, it) }
                }.toSet()
            },
            wiping = { wipeHold.held },
            grantLock = grantLock,
            isLockTaskActive = { controller.isLocked(context) },
        )
    }

    @Provides
    @Singleton
    fun provideFactoryResetWipeHold(
        @ApplicationContext context: Context,
        events: EventSink,
        configStore: ConfigStateStore,
    ): FactoryResetWipeHold =
        FactoryResetWipeHold(
            SharedPrefsWipeHoldStore(context),
            bootId = { Boot.count(context) },
            nowMs = SystemClock::elapsedRealtime,
            // Ends the hold at its deadline even if no command, apply or server comes. A failure to schedule doesn't
            // stop the wipe: each command and apply still reads the hold.
            onHold = {
                runCatching { WipeHoldExpiryWorker.schedule(context) }
                    .onFailure { Log.w("FactoryResetWipeHold", "expiry check not scheduled", it) }
            },
            // The re-apply first, each on its own: a failure to record the event must not cost the block.
            onExpired = {
                // Puts Factory reset: Block back now, and has the server re-send the configuration.
                WipeHoldExpiryWorker.holdEnded(context, configStore)
                runCatching { events.record(EventType.WIPE_NOT_STARTED, WIPE_NOT_STARTED_DETAIL) }
                    .onFailure { Log.w("FactoryResetWipeHold", "wipeNotStarted not recorded", it) }
            },
        )

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
        return CapabilityCollector(
            agentVersion = BuildConfig.VERSION_NAME,
            agentPackage = context.packageName,
            // A lambda, not a value: this collector is a @Singleton and Device-Owner status only
            // flips to true partway through provisioning — see CapabilityCollector.collect().
            isDeviceOwner = { handle.dpm.isDeviceOwnerApp(context.packageName) },
            policyKeys = registry::supportedPolicyKeys,
            remoteControl = remoteTierDetector::capability,
            oem = oemAdapter::capability,
            // Silent install needs Device Owner — advertise app.silentInstall only when we have it, so
            // the server's capability gate won't queue an app.install we can't perform.
            deviceOwnerAppManagementKeys = AppManagement.DEVICE_OWNER_KEYS,
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

    // --- Command handlers (multibound). Add a command == add one @IntoSet provider. ---

    @Provides
    @IntoSet
    fun provideConfigSyncHandler(): CommandHandler = ConfigSyncHandler()

    @Provides
    @IntoSet
    fun providePolicyApplyHandler(registry: CapabilityRegistry): CommandHandler =
        // A function, not a map: the toggles are resolved per command (Device Owner arrives after process start, #76).
        PolicyApplyHandler(registry::togglePolicies)

    @Provides
    @Singleton
    fun provideCommandDispatcher(
        handlers: Set<@JvmSuppressWildcards CommandHandler>,
        wipeHold: FactoryResetWipeHold,
    ): CommandDispatcher = CommandDispatcher(handlers.toList(), wiping = { wipeHold.held })

    // --- Kiosk (lock-task) ---

    @Provides
    @Singleton
    fun provideKioskController(handle: DpmHandle): KioskController =
        LockTaskKioskController(handle.dpm, handle.admin)

    @Provides
    @Singleton
    fun provideFaultStore(@ApplicationContext context: Context): FaultStore =
        SharedPrefsFaultStore(context)

    @Provides
    @Singleton
    fun provideCrashLoopGuard(store: FaultStore): CrashLoopGuard = CrashLoopGuard(store)

    /** The kiosk launcher's crash-loop hold, saved next to the crash counter so it outlives the activity. */
    @Provides
    @Singleton
    fun provideHoldStore(@ApplicationContext context: Context): HoldStore = SharedPrefsHoldStore(context)

    @Provides
    @Singleton
    fun providePinAttemptStore(@ApplicationContext context: Context): PinAttemptStore =
        SharedPrefsPinAttemptStore(context)

    @Provides
    @Singleton
    fun provideExitPinGate(store: PinAttemptStore): ExitPinGate =
        // Uptime, not wall time: it can't be set, so changing the clock can't shorten a lockout.
        ExitPinGate(store) { SystemClock.elapsedRealtime() }

    // --- App-management + kiosk + device command handlers (multibound) ---

    @Provides
    @IntoSet
    fun provideAppInstallHandler(
        installManager: InstallManager,
        applier: ConfigApplier,
        kiosk: KioskApplier,
    ): CommandHandler =
        AppInstallHandler(
            installManager,
            AppInstallHandler.afterInstall({ pkg -> applier.applyAfterInstall(pkg) }, kiosk),
        )

    @Provides
    @IntoSet
    fun provideAppUninstallHandler(installManager: InstallManager, kiosk: KioskApplier): CommandHandler =
        AppUninstallHandler(installManager, inKiosk = kiosk::protectsApp)

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
    @Singleton
    fun provideKioskApplier(
        kiosk: KioskController,
        store: KioskStateStore,
        configStore: ConfigStateStore,
        @ApplicationContext context: Context,
        guard: KioskGuard,
    ): KioskApplier {
        val home = kioskHomeAlias(context)
        return KioskApplier(
            kiosk, store, AndroidKioskHomeSwitch(context, home), home,
            safeMode = ConfiguredSafeModeSetting(configStore),
            journal = SharedPrefsExitJournal(context),
            // An exit cut off midway is never finished while the configuration says kiosk on.
            desiredKioskOn = { configStore.configuredKiosk() != null },
            io = Dispatchers.IO,
            onEntered = { p -> KioskBackgroundPrefetchWorker.follow(context, p.theme.backgroundImage) },
            onExited = { KioskBackgroundPrefetchWorker.cancel(context) },
            bootId = { Boot.count(context) },
            guard = guard,
            isLockTaskActive = { kiosk.isLocked(context) },
        )
    }

    @Provides @IntoSet
    fun provideKioskEnterHandler(applier: KioskApplier): CommandHandler = KioskEnterHandler(applier)

    @Provides @IntoSet
    fun provideKioskExitHandler(applier: KioskApplier): CommandHandler = KioskExitHandler(applier)

    @Provides @IntoSet
    fun provideKioskClearPinLockoutHandler(gate: ExitPinGate): CommandHandler = KioskClearPinLockoutHandler(gate)

    @Provides
    @IntoSet
    fun provideDeviceRebootHandler(handle: DpmHandle): CommandHandler =
        DeviceRebootHandler(handle)

    @Provides
    @IntoSet
    fun provideDeviceLockHandler(@ApplicationContext context: Context, handle: DpmHandle): CommandHandler {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return DeviceLockHandler(lockNow = { handle.dpm.lockNow() }, isDeviceSecure = { keyguard.isDeviceSecure })
    }

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
    @Suppress("LongParameterList") // Hilt's explicit bindings keep this wiring auditable.
    fun provideWipeHandler(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        applier: ConfigApplier,
        wipeHold: FactoryResetWipeHold,
        configStore: ConfigStateStore,
        kiosk: KioskApplier,
    ): CommandHandler =
        DeviceWipeHandler(
            DpmWipeTarget(handle, context.getSystemService(UserManager::class.java)),
            wipeHold,
            markConfigStale = configStore::markStale,
            // No apply and no kiosk guard step (which sets Factory reset: Block in kiosk) between the lift and the wipe
            // call. The configuration's lock first, as every apply takes them.
        ) { wipe -> applier.exclusive { kiosk.exclusive(wipe) } }

    @Provides
    @IntoSet
    fun providePowerModeHandler(store: PowerModeStore): CommandHandler =
        DevicePowerModeHandler(store)

    @Provides
    @IntoSet
    fun provideLocationModeHandler(store: LocationModeStore): CommandHandler =
        DeviceLocationModeHandler(store)

    // --- Desired-state configuration (config.apply) ---

    @Provides
    @Singleton
    fun provideConfigStateStore(@ApplicationContext context: Context): ConfigStateStore =
        SharedPrefsConfigStateStore(context)

    @Provides
    @Singleton
    fun provideLastCheckIn(@ApplicationContext context: Context): LastCheckIn = SharedPrefsLastCheckIn(context)

    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt's explicit configuration-section bindings make this wiring auditable.
    fun provideConfigApplier(
        registry: CapabilityRegistry,
        kiosk: KioskApplier,
        location: LocationModeStore,
        store: ConfigStateStore,
        power: PowerModeStore,
        appPermissions: AppRuntimePermissions,
        grantLock: PermissionGrantLock,
    ): ConfigApplier = ConfigApplier(registry::togglePolicies, kiosk, location::set, store,
        setPowerMode = power::set, appPermissions = appPermissions, grantLock = grantLock)

    /** The one lock for both writers of per-app grant states: the configuration's rules and the kiosk guard. */
    @Provides
    @Singleton
    fun providePermissionGrantLock(): PermissionGrantLock = PermissionGrantLock()

    @Provides
    @Singleton
    fun provideAppRuntimePermissions(@ApplicationContext context: Context, handle: DpmHandle): AppRuntimePermissions =
        DeviceOwnerAppRuntimePermissions(context, handle)

    @Provides
    @IntoSet
    fun provideConfigApplyHandler(applier: ConfigApplier): CommandHandler = ConfigApplyHandler(applier)
}
