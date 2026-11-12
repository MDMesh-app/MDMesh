package com.mdmesh.agent

import com.mdmesh.agent.ui.StatusUiState.DeviceIdUi
import com.mdmesh.core.sync.CredentialsLost
import com.mdmesh.core.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The status screen's Device ID while it is open (#188's DeviceIdLabelWatchTest, on the view model's stream): it
 * follows the stored id, the lost-credentials mark, the last success and the last error as they change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusDeviceIdTest {

    private val ids = MutableStateFlow<String?>("dev-1")
    private val marks = MutableStateFlow<Long?>(null)
    private val syncStatus = SyncStatus()

    private fun TestScope.openScreen(): List<DeviceIdUi> {
        val shown = mutableListOf<DeviceIdUi>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            StatusViewModel.deviceIds(ids, marks, syncStatus.lastSuccessAtMsChanges, syncStatus.lastError)
                .collect { shown += it }
        }
        return shown
    }

    @Test fun `credentials marked lost in the background show on the open screen`() = runTest {
        val shown = openScreen()
        assertEquals(DeviceIdUi.Known("dev-1", credentialsLost = false), shown.last())
        marks.value = CredentialsLost.markerOf(syncStatus.lastSuccessAtMs)
        assertEquals(DeviceIdUi.Known("dev-1", credentialsLost = true), shown.last())
    }

    @Test fun `a check-in that succeeds while the screen is open takes the warning away`() = runTest {
        marks.value = CredentialsLost.NO_SUCCESS
        val shown = openScreen()
        assertEquals(DeviceIdUi.Known("dev-1", credentialsLost = true), shown.last())
        syncStatus.recordSuccess()
        assertEquals(DeviceIdUi.Known("dev-1", credentialsLost = false), shown.last())
    }

    @Test fun `an enrollment that completes while the screen is open shows the id`() = runTest {
        ids.value = null
        val shown = openScreen()
        assertEquals(DeviceIdUi.Enrolling(null, null), shown.last())
        ids.value = "dev-2"
        assertEquals(DeviceIdUi.Known("dev-2", credentialsLost = false), shown.last())
    }

    @Test fun `while enrolling the last error shows, and a change that shows nothing new redraws nothing`() = runTest {
        ids.value = null
        val shown = openScreen()
        syncStatus.recordFailure(IOException("timeout"))
        val enrolling = shown.last() as DeviceIdUi.Enrolling
        assertEquals("timeout", enrolling.lastError)
        ids.value = "dev-1"
        val count = shown.size
        syncStatus.recordFailure(IOException("again")) // enrolled: the last error isn't shown
        assertEquals(count, shown.size)
    }

    @Test fun `copying the device id copies the id only, not the warning`() {
        val state = com.mdmesh.agent.ui.StatusUiState.of(
            deviceOwner = true, locked = false, configured = false, deviceId = "dev-1", deviceIdLoaded = true,
            lastError = null, lastErrorAtMs = null, serverUrl = "https://m", returning = false, returnFailure = null,
            credentialsLost = true,
        )
        var copied: String? = null
        val target = com.mdmesh.agent.ui.StatusCopy.Target.DEVICE_ID
        com.mdmesh.agent.ui.StatusCopy.copy(state, target) { _, t -> copied = t }
        assertEquals("dev-1", copied)
    }
}
