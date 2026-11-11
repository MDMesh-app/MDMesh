package com.mdmesh.core.sync

import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.proto.AgentEnrollResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        tokens: FakeTokenProvider = FakeTokenProvider(token, attempt = "attempt-0123456789abcdef"),
    ) = EnrollmentManager(api, identity, tokens, FakeCapabilitySource(), NoopEventSink)

    @Test
    fun `sends the stored attempt id for the token`() = runTest {
        val api = FakeMdmApi()
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")

        manager(FakeIdentity(), api, tokens = tokens).ensureEnrolled()

        assertEquals(listOf("tok-123"), tokens.attemptRequests)
        assertEquals("attempt-0123456789abcdef", api.enrollRequests.single().enrollAttempt)
    }

    @Test
    fun `the attempt id is cleared once the credentials are stored, not before`() = runTest {
        val identity = FakeIdentity()
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val api = FakeMdmApi().apply { enrollThrows = java.net.SocketTimeoutException("timeout") }
        val manager = manager(identity, api, tokens = tokens)

        runCatching { manager.ensureEnrolled() }
        assertEquals("kept while the enrollment can still be retried", 0, tokens.attemptCleared)

        api.enrollThrows = null
        manager.ensureEnrolled()
        assertEquals("sek-1", identity.secret())
        assertEquals(1, tokens.attemptCleared)
    }

    @Test
    fun `a retry after a lost response sends the same attempt id`() = runTest {
        val api = FakeMdmApi().apply { enrollThrows = java.net.SocketTimeoutException("timeout") }
        val identity = FakeIdentity()
        val manager = manager(identity, api)

        runCatching { manager.ensureEnrolled() }
        api.enrollThrows = null
        assertEquals("srv-1", manager.ensureEnrolled())

        assertEquals(2, api.enrollRequests.size)
        assertEquals(api.enrollRequests[0].enrollAttempt, api.enrollRequests[1].enrollAttempt)
        assertEquals("sek-1", identity.secret())
    }

    @Test
    fun `enrolls without an attempt id when it can't be kept`() = runTest {
        val api = FakeMdmApi()
        val tokens = object : com.mdmesh.core.store.EnrollTokenProvider {
            override suspend fun token() = "tok-123"
            override suspend fun enrollAttempt(token: String): String = throw java.io.IOException("disk full")
        }

        val id = EnrollmentManager(api, FakeIdentity(), tokens, FakeCapabilitySource(), NoopEventSink).ensureEnrolled()

        assertEquals("srv-1", id)
        assertEquals(null, api.enrollRequests.single().enrollAttempt)
    }

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
    fun `concurrent callers share one enroll - the single-use token is posted exactly once`() = runTest {
        val api = FakeMdmApi().apply { enrollGate = CompletableDeferred() }
        val identity = FakeIdentity(initialId = null)
        val manager = manager(identity, api)

        val racers = (1..3).map { async { manager.ensureEnrolled() } }
        testScheduler.advanceUntilIdle() // all racers now parked on the gate or the mutex
        api.enrollGate!!.complete(Unit)

        assertEquals(listOf("srv-1", "srv-1", "srv-1"), racers.awaitAll())
        assertEquals("the single-use token must be POSTed once", 1, api.enrollRequests.size)
        assertEquals("credentials must be persisted once", 1, identity.saveCount)
    }

    @Test
    fun `throws when no enrollment token is available`() = runTest {
        try {
            manager(FakeIdentity(initialId =null), token = null).ensureEnrolled()
            fail("expected EnrollmentException")
        } catch (e: EnrollmentException) {
            assertEquals(EnrollmentFailure.NO_CODE, e.message)
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
            assertTrue(e.message, e.message!!.startsWith("This enrollment code has already enrolled"))
            assertTrue("the key stays for the log and the retry rule", e.message!!.endsWith("(error.agent.token.used)"))
        }
    }

    @Test
    fun `a network failure says what happened and names the server`() = runTest {
        val api = FakeMdmApi().apply { enrollThrows = java.net.UnknownHostException("mdm.acme.test") }
        val manager = EnrollmentManager(
            api, FakeIdentity(), FakeTokenProvider("tok-123"), FakeCapabilitySource(), NoopEventSink,
            serverAddress = { "https://mdm.acme.test" },
        )
        try {
            manager.ensureEnrolled()
            fail("expected EnrollmentException")
        } catch (e: EnrollmentException) {
            assertTrue(e.message, e.message!!.startsWith("Can't find the server mdm.acme.test."))
            assertTrue(e.cause is java.net.UnknownHostException)
        }
    }
}
