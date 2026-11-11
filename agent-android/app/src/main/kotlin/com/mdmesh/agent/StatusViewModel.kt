package com.mdmesh.agent

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdmesh.agent.ui.StatusUiState
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.store.configuredKiosk
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.sync.SyncStatus
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.KioskApplyPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The status screen's state and its one action, Return to kiosk. Lives across rotation, so a return in progress
 * keeps the button disabled ("Returning…") and its failure stays shown, where the activity alone lost both.
 *
 * Device Owner and lock-task state are system facts with no change callback: they are read again whenever the
 * kiosk or its configuration changes (as before) and when the screen resumes ([refresh]).
 */
@HiltViewModel
class StatusViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    sources: StatusSources,
    private val dpmHandle: DpmHandle,
    private val configApplier: ConfigApplier,
    private val events: EventLog,
) : ViewModel() {

    private val serverConfig = sources.serverConfig
    private val configStore = sources.configStore

    private data class Facts(val deviceOwner: Boolean, val locked: Boolean, val configured: Boolean)

    private data class Return(val running: Boolean = false, val failure: String? = null)

    /** Bumped by [refresh]: the system facts are read again. */
    private val refreshes = MutableStateFlow(0)
    private val returning = MutableStateFlow(Return())

    /**
     * Device Owner, lock task and the configured kiosk, read again whenever the kiosk or its configuration changes
     * (entering or leaving a kiosk changes the lock-task state) and on [refresh]. Off the main thread.
     */
    private val facts = combine(configStore.configuredKioskFlow(), sources.kioskStore.flow(), refreshes) { _, _, _ ->
        readFacts()
    }.flowOn(Dispatchers.Default)

    /** Collected only while the screen is started (WhileSubscribed), kept through a rotation. */
    val state: StateFlow<StatusUiState> = combine(
        facts,
        sources.deviceIdStore.deviceId.map { Loaded(it) },
        sources.syncStatus.lastError,
        returning,
    ) { f, id, error, r -> stateOf(f, id, error, r) }
        // The first frame is drawn from the facts read now, as before; the device id follows from storage.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), stateOf(readFacts()))

    /** The stored device id, once storage has answered ([loaded]). */
    private data class Loaded(val id: String?, val loaded: Boolean = true)

    private fun stateOf(
        f: Facts,
        id: Loaded = Loaded(null, loaded = false),
        error: SyncStatus.Failure? = null,
        r: Return = returning.value,
    ) = StatusUiState.of(
        deviceOwner = f.deviceOwner,
        locked = f.locked,
        configured = f.configured,
        deviceId = id.id,
        deviceIdLoaded = id.loaded,
        lastError = error?.message,
        lastErrorAtMs = error?.atMillis,
        serverUrl = serverConfig.baseUrl(),
        returning = r.running,
        returnFailure = r.failure,
    )

    /** Reads the system facts again (the screen resumed, or a kiosk change may have locked or unlocked it). */
    fun refresh() {
        refreshes.value++
    }

    /** The kiosk Return to kiosk would enter now; null when there is none. */
    fun configuredKiosk(): KioskApplyPayload? = configStore.configuredKiosk()

    /** Enters [confirmed] (the kiosk the person confirmed); ignored while a return is already running. */
    fun returnToKiosk(confirmed: KioskApplyPayload) {
        if (returning.value.running) return
        returning.value = Return(running = true)
        viewModelScope.launch {
            try {
                // Not cancelled with the screen: stopping mid-entry could leave a partial kiosk, HOME claimed and the
                // allowlist set with nothing saved. The entry, its event and the check-in finish.
                val failure = withContext(NonCancellable) { enterConfiguredKiosk(confirmed) }
                returning.value = Return(failure = failure)
            } finally {
                if (returning.value.running) returning.value = Return()
                refresh()
            }
        }
    }

    /** Enters the confirmed kiosk; null when it worked, else what to tell the person. */
    private suspend fun enterConfiguredKiosk(confirmed: KioskApplyPayload): String? {
        val result = runCatching { configApplier.returnToConfiguredKiosk(expected = confirmed) }.getOrElse {
            if (it is CancellationException) throw it
            KioskResult.Failed(app.getString(R.string.kiosk_return_failed))
        }
        return when (result) {
            KioskResult.Ok -> {
                events.record("kioskReturn", "returned on-device")
                // Tell the server now, so the console shows the device in kiosk without waiting for the next
                // heartbeat. After any check-in already running: that one may carry the state from before.
                CheckInWorker.scheduleAfterCurrent(app)
                null
            }
            KioskResult.Unsupported -> app.getString(R.string.kiosk_return_unsupported)
            is KioskResult.Failed -> result.reason
        }
    }

    private fun readFacts(): Facts {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return Facts(
            deviceOwner = dpmHandle.dpm.isDeviceOwnerApp(app.packageName),
            locked = am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED,
            configured = configStore.configuredKiosk() != null,
        )
    }

    /** Where the status screen reads from. */
    class StatusSources @Inject constructor(
        val deviceIdStore: DeviceIdStore,
        val serverConfig: ServerConfigStore,
        val syncStatus: SyncStatus,
        val configStore: ConfigStateStore,
        val kioskStore: KioskStateStore,
    )

    private companion object {
        /** Keep collecting through a rotation, stop soon after the screen leaves. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
