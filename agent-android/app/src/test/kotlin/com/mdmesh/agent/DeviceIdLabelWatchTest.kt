package com.mdmesh.agent

import com.mdmesh.agent.DeviceIdLabel.Status.Enrolled
import com.mdmesh.agent.DeviceIdLabel.Status.Enrolling
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

/** The status screen's DEVICE ID area while the screen is open: it follows the stored state as it changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceIdLabelWatchTest {

    private val ids = MutableStateFlow<String?>("dev-1")
    private val marks = MutableStateFlow<Long?>(null)
    private val successes = MutableStateFlow<Long?>(null)
    private val errors = MutableStateFlow<SyncStatus.Failure?>(null)

    /** What the open screen has shown, in order (MainActivity collects the same stream while STARTED). */
    private fun TestScope.openScreen(): List<DeviceIdLabel.Status> {
        val shown = mutableListOf<DeviceIdLabel.Status>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            DeviceIdLabel.watch(ids, marks, successes, errors).collect { shown += it }
        }
        return shown
    }

    @Test
    fun `credentials marked lost in the background show on the open screen`() = runTest {
        val shown = openScreen()
        assertEquals(Enrolled("dev-1", credentialsLost = false), shown.last())

        // A background recovery gets a final answer and marks the credentials lost.
        marks.value = CredentialsLost.markerOf(successes.value)

        assertEquals(Enrolled("dev-1", credentialsLost = true), shown.last())
    }

    @Test
    fun `a check-in that succeeds while the screen is open takes the warning away`() = runTest {
        marks.value = CredentialsLost.NO_SUCCESS
        val shown = openScreen()
        assertEquals(Enrolled("dev-1", credentialsLost = true), shown.last())

        successes.value = 1_000L

        assertEquals(Enrolled("dev-1", credentialsLost = false), shown.last())
    }

    @Test
    fun `an enrollment that completes while the screen is open shows the id`() = runTest {
        ids.value = null
        val shown = openScreen()
        assertEquals(Enrolling(null), shown.last())

        ids.value = "dev-2"

        assertEquals(Enrolled("dev-2", credentialsLost = false), shown.last())
    }

    @Test
    fun `a change that doesn't change what is shown redraws nothing`() = runTest {
        val shown = openScreen()
        errors.value = SyncStatus.Failure("timeout", 5L) // enrolled: the last error isn't shown here
        assertEquals(1, shown.size)
    }
}
