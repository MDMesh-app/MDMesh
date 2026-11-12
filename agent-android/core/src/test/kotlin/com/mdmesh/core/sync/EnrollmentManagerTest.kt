package com.mdmesh.core.sync

import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.proto.AgentEnrollResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** What a server that resumes enrollments (protocol 1.9) answers. */
    private fun resumableAnswer(secret: String) = com.mdmesh.core.net.ResponseEnvelope(
        status = "OK",
        data = com.mdmesh.proto.AgentEnrollResponse(deviceId = "srv-1", deviceSecret = secret, resumeSupported = true),
    )

    private fun recovering(
        api: FakeMdmApi,
        tokens: FakeTokenProvider,
        status: SyncStatus,
        identity: FakeIdentity = FakeIdentity(),
    ) =
        EnrollmentManager(api = api, identity = identity, tokenProvider = tokens,
            capabilitySource = FakeCapabilitySource(), eventSink = NoopEventSink,
            environment = EnrollmentEnvironment(syncStatus = status))

    @Test
    fun `sends the stored attempt id for the token`() = runTest {
        val api = FakeMdmApi()
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")

        manager(FakeIdentity(), api, tokens = tokens).ensureEnrolled()

        assertEquals(listOf("tok-123"), tokens.attemptRequests)
        assertEquals("attempt-0123456789abcdef", api.enrollRequests.single().enrollAttempt)
    }

    @Test
    fun `the attempt id is kept until a check-in succeeds, then forgotten`() = runTest {
        val identity = FakeIdentity()
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val manager = EnrollmentManager(api = FakeMdmApi(), identity = identity, tokenProvider = tokens,
            capabilitySource = FakeCapabilitySource(), eventSink = NoopEventSink,
            environment = EnrollmentEnvironment(syncStatus = status))

        manager.ensureEnrolled()
        assertEquals("kept: the credentials may still be rejected", 0, tokens.attemptCleared)

        status.recordSuccess()
        manager.ensureEnrolled()
        assertEquals(1, tokens.attemptCleared)
    }

    @Test
    fun `credentials rejected before the first check-in are recovered by resuming the same attempt`() = runTest {
        val identity = FakeIdentity()
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
        val manager = recovering(api, tokens, status, identity)
        manager.ensureEnrolled()

        // The server no longer accepts the saved secret, and the device has never checked in.
        status.recordFailure(CheckInException("error.agent.unauthorized"))
        api.enrollResponse = resumableAnswer("sek-2")
        assertEquals("srv-1", manager.ensureEnrolled())

        assertEquals("enrolled again", 2, api.enrollRequests.size)
        val recovery = api.enrollRequests[1]
        assertEquals("with the same attempt", api.enrollRequests[0].enrollAttempt, recovery.enrollAttempt)
        assertEquals("resume only: never a new device", true, recovery.resumeOnly)
        assertEquals(null, api.enrollRequests[0].resumeOnly)
        assertEquals("sek-2", identity.secret())
    }

    @Test
    fun `no recovery against a server that doesn't resume enrollments`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val api = FakeMdmApi() // an older server: no resumeSupported in its answer
        val manager = recovering(api, tokens, status)
        manager.ensureEnrolled()
        status.recordFailure(CheckInException("error.agent.unauthorized"))

        manager.ensureEnrolled()

        assertEquals("an ordinary enroll there could make a new device", 1, api.enrollRequests.size)
    }

    @Test
    fun `when nothing can be resumed the agent stops recovering and remembers the credentials are lost`() = runTest {
        val finals = listOf(
            "error.agent.enrollment.notResumable",
            "error.agent.token.used",
            "error.agent.token.invalid",
        )
        for (final in finals) {
            val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
            val status = SyncStatus()
            val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
            val manager = recovering(api, tokens, status)
            manager.ensureEnrolled()
            api.enrollResponse = com.mdmesh.core.net.ResponseEnvelope(status = "ERROR", message = final)

            status.recordFailure(CheckInException("error.agent.unauthorized"))
            manager.ensureEnrolled()
            // The next check-in is refused again, which replaces the last error; the lost state stays.
            status.recordFailure(CheckInException("error.agent.unauthorized (next check-in)"))

            assertTrue(final, tokens.credentialsLost)
            assertEquals("$final: the attempt is dropped", 1, tokens.attemptCleared)
            manager.ensureEnrolled()
            assertEquals("$final: no further recovery", 2, api.enrollRequests.size)
        }
    }

    @Test
    fun `a retryable answer to a recovery keeps the attempt for the next refused check-in`() = runTest {
        for (retryable in listOf("error.agent.enrollment.inProgress", "error.agent.enrollment.failed")) {
            val identity = FakeIdentity()
            val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
            val status = SyncStatus()
            val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
            val manager = recovering(api, tokens, status, identity)
            manager.ensureEnrolled()

            api.enrollResponse = com.mdmesh.core.net.ResponseEnvelope(status = "ERROR", message = retryable)
            status.recordFailure(CheckInException("error.agent.unauthorized (1)"))
            manager.ensureEnrolled()
            assertEquals("$retryable: attempt kept", 0, tokens.attemptCleared)
            assertTrue(retryable, !tokens.credentialsLost)

            api.enrollResponse = resumableAnswer("sek-2")
            status.recordFailure(CheckInException("error.agent.unauthorized (2)"))
            manager.ensureEnrolled()
            assertEquals("$retryable: the first enroll and two recoveries", 3, api.enrollRequests.size)
            assertEquals("sek-2", identity.secret())
        }
    }

    @Test
    fun `a successful check-in clears a lost-credentials state`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
        val manager = recovering(api, tokens, status)
        manager.ensureEnrolled()
        api.enrollResponse = com.mdmesh.core.net.ResponseEnvelope(status = "ERROR", message = "error.agent.token.used")
        status.recordFailure(CheckInException("error.agent.unauthorized"))
        manager.ensureEnrolled()
        assertTrue("marked lost", tokens.credentialsLost)

        // Whatever refused the credentials (a proxy stripping the header, say) is fixed: a check-in succeeds.
        status.recordSuccess()
        manager.ensureEnrolled()
        assertTrue("cleared", !tokens.credentialsLost)

        val writes = tokens.lostWrites
        manager.ensureEnrolled()
        assertEquals("no write when it is already clear", writes, tokens.lostWrites)
    }

    @Test
    fun `a successful enrollment clears a lost-credentials state`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef").apply { credentialsLostAt = 1L }
        manager(FakeIdentity(), tokens = tokens).ensureEnrolled()
        assertTrue(!tokens.credentialsLost)
    }

    @Test
    fun `the resume flag follows the latest answer`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef").apply { resumable = true }
        manager(FakeIdentity(), FakeMdmApi(), tokens = tokens).ensureEnrolled() // an older server: no flag
        assertTrue("an older server's answer clears it", !tokens.resumable)
    }

    @Test
    fun `one recovery per refused check-in, even when several cycles run`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
        val manager = recovering(api, tokens, status)
        manager.ensureEnrolled()
        status.recordFailure(CheckInException("error.agent.unauthorized"))

        // The worker, the heartbeat and a wake all reach enrollment before the next check-in reports anything.
        (1..3).map { async { manager.ensureEnrolled() } }.awaitAll()
        manager.ensureEnrolled()

        assertEquals("the first enroll and one recovery", 2, api.enrollRequests.size)
    }

    @Test
    fun `no recovery once the device has checked in`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
        val manager = recovering(api, tokens, status)
        manager.ensureEnrolled()
        status.recordSuccess()
        status.recordFailure(CheckInException("error.agent.unauthorized"))

        manager.ensureEnrolled()

        assertEquals(1, api.enrollRequests.size)
    }

    @Test
    fun `recovery is bounded, so a device can't loop on enroll`() = runTest {
        val tokens = FakeTokenProvider("tok-123", attempt = "attempt-0123456789abcdef")
        val status = SyncStatus()
        val api = FakeMdmApi().apply { enrollResponse = resumableAnswer("sek-1") }
        val manager = recovering(api, tokens, status)
        manager.ensureEnrolled()
        repeat(10) {
            // Distinct refusals (a StateFlow keeps the earlier one when an equal failure is recorded the same ms).
            status.recordFailure(CheckInException("error.agent.unauthorized (check-in $it)"))
            manager.ensureEnrolled()
        }
        assertEquals("the first enroll and at most 3 recoveries", 4, api.enrollRequests.size)
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
    fun `an unexpected failure is a retryable enrollment failure with a generic message`() = runTest {
        val bug = IllegalStateException("bug")
        val api = FakeMdmApi().apply { enrollThrows = bug }
        try {
            manager(FakeIdentity(), api).ensureEnrolled()
            fail("expected EnrollmentException")
        } catch (e: EnrollmentException) {
            assertEquals("Enrollment didn't go through. Trying again automatically.", e.message)
            assertFalse("no internal detail on screen", e.message!!.contains("bug"))
            assertTrue("the cause is kept for the log", e.cause === bug)
            listOf("token.used", "token.invalid", "token.expired", "enrollment.disabled").forEach {
                assertFalse("retryable: $it", e.message!!.contains(it))
            }
        }
    }

    @Test
    fun `a failure outside the request (reading capabilities) is worded the same way`() = runTest {
        val broken = object : com.mdmesh.core.capability.CapabilitySource {
            override fun matrix(deviceId: String) = throw IllegalArgumentException("no matrix")
        }
        try {
            EnrollmentManager(FakeMdmApi(), FakeIdentity(), FakeTokenProvider("tok"), broken, NoopEventSink)
                .ensureEnrolled()
            fail("expected EnrollmentException")
        } catch (e: EnrollmentException) {
            assertEquals(EnrollmentFailure.UNEXPECTED, e.message)
            assertTrue("kept for the log", e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun `cancellation is never turned into an enrollment failure`() = runTest {
        val api = FakeMdmApi().apply { enrollThrows = kotlinx.coroutines.CancellationException("stopped") }
        try {
            manager(FakeIdentity(), api).ensureEnrolled()
            fail("expected CancellationException")
        } catch (e: EnrollmentException) {
            fail("cancellation was wrapped: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("stopped", e.message)
        }
    }

    @Test
    fun `an HTTP error and an unreadable answer are enrollment problems`() = runTest {
        val notFound = retrofit2.HttpException(
            retrofit2.Response.error<Any>(404, "".toResponseBody()),
        )
        for (failure in listOf(notFound, kotlinx.serialization.SerializationException("html"))) {
            val api = FakeMdmApi().apply { enrollThrows = failure }
            try {
                manager(FakeIdentity(), api).ensureEnrolled()
                fail("expected EnrollmentException")
            } catch (e: EnrollmentException) {
                assertTrue(e.cause === failure)
            }
        }
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
            environment = EnrollmentEnvironment(serverAddress = { "https://mdm.acme.test" }),
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
