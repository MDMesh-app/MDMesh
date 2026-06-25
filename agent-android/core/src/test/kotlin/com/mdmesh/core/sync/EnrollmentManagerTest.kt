package com.mdmesh.core.sync

import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.proto.AgentEnrollResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnrollmentManagerTest {

    private fun manager(
        identity: FakeIdentity,
        api: FakeMdmApi = FakeMdmApi(),
        token: String? = "tok-123",
    ) = EnrollmentManager(api, identity, FakeTokenProvider(token), FakeCapabilitySource(), NoopEventSink)

    @Test
    fun `returns stored id without contacting server when already enrolled`() = runTest {
        val api = FakeMdmApi()
        val id = manager(FakeIdentity(initialId ="existing-1"), api).ensureEnrolled()

        assertEquals("existing-1", id)
        assertTrue("enroll must not be called when already enrolled", api.enrollRequests.isEmpty())
    }

    @Test
    fun `enrolls, stores and returns the server-issued id`() = runTest {
        val api = FakeMdmApi()
        val identity = FakeIdentity(initialId =null)

        val id = manager(identity, api).ensureEnrolled()

        assertEquals("srv-1", id)
        assertEquals(1, api.enrollRequests.size)
        assertEquals("tok-123", api.enrollRequests.first().enrollToken)
        assertEquals("server id must be persisted", "srv-1", identity.current())
        assertEquals("per-device secret must be persisted", "sek-1", identity.secret())
        assertEquals(1, identity.saveCount)
    }

    @Test
    fun `throws when no enrollment token is available`() = runTest {
        try {
            manager(FakeIdentity(initialId =null), token = null).ensureEnrolled()
            fail("expected EnrollmentException")
        } catch (e: EnrollmentException) {
            assertTrue(e.message!!.contains("token"))
        }
    }

    @Test
    fun `throws when the server rejects enrollment`() = runTest {
        val api = FakeMdmApi().apply {
            enrollResponse = ResponseEnvelope(status = "ERROR", message = "error.agent.token.used")
        }
        try {
            manager(FakeIdentity(initialId =null), api).ensureEnrolled()
            fail("expected EnrollmentException")
        } catch (e: EnrollmentException) {
            assertEquals("error.agent.token.used", e.message)
        }
    }
}
