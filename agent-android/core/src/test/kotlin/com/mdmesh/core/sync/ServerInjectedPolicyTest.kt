package com.mdmesh.core.sync

import android.content.ComponentName
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.handlers.ConfigApplyHandler
import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.core.state.DeviceStateSource
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.AgentDeviceStateDto
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plays the *server* role for a full check-in cycle: scripts the command batch [FakeMdmApi] returns,
 * runs the real [CheckInCoordinator] over it, and asserts the policy reached the device strategy.
 *
 * This is the closest thing to "the server tells the phone what to do" that runs without a server,
 * without enrollment and without a Device-Owner device. The chain under test is entirely production
 * code — coordinator, dispatcher, `PolicyApplyHandler` / `ConfigApplyHandler`, `ConfigApplier` — with
 * the single DPM-bound leaf (`TogglePolicy`, e.g. `FactoryResetRestrictionPolicy`) faked.
 *
 * What this does NOT cover: that `DevicePolicyManager.addUserRestriction(admin, "no_factory_reset")`
 * mutates the OS. That is platform behaviour, not agent code, and needs a real DO device
 * (`adb shell dpm list user-restrictions`).
 */
class ServerInjectedPolicyTest {

    /**
     * Stands in for a device-bound strategy (`FactoryResetRestrictionPolicy` and friends), recording
     * every `setEnabled` call so the tests can assert both the value and the call count.
     */
    private class RecordingToggle(
        override val capabilityKey: String,
        private val outcome: PolicyOutcome = PolicyOutcome.Applied,
    ) : TogglePolicy {
        val calls = mutableListOf<Boolean>()
        override fun isSupported(): Boolean = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome {
            calls += enabled
            return outcome
        }
    }

    private class SilentKioskController : KioskController {
        var enters = 0
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
            enters++
            return KioskResult.Ok
        }
        override fun exit(): KioskResult = KioskResult.Ok
        override fun isLocked(context: android.content.Context): Boolean = false
        override fun allowedPackages(): List<String> = emptyList()
    }

    private object NoHome : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) {}
        override fun showLauncher() {}
        override fun showOemHome() {}
    }

    /**
     * The real production graph, minus the network and the DPM leaf. Mirrors `AgentModule`:
     * `togglePolicies()` -> `PolicyApplyHandler` + `ConfigApplyHandler` -> `CommandDispatcher`.
     */
    private class Harness(toggles: Map<String, TogglePolicy>) {
        val api = FakeMdmApi()
        val pending = PendingResults()
        val configStore = InMemoryConfigStateStore()

        private val kiosk = KioskApplier(
            kiosk = SilentKioskController(),
            store = InMemoryKioskStateStore(),
            home = NoHome,
            homeComponent = ComponentName("com.mdmesh.agent", "com.mdmesh.agent.KioskHomeAlias"),
        )

        private val applier = ConfigApplier(toggles, kiosk, setLocationMode = {}, store = configStore)

        private val identity = FakeIdentity(initialId = "srv-1", initialSecret = "sek-1")

        val coordinator = CheckInCoordinator(
            api = api,
            enrollment = EnrollmentManager(
                api = api,
                identity = identity,
                tokenProvider = FakeTokenProvider("t"),
                capabilitySource = FakeCapabilitySource(),
                eventSink = NoopEventSink,
            ),
            identity = identity,
            capabilitySource = FakeCapabilitySource(),
            dispatcher = CommandDispatcher(
                listOf(PolicyApplyHandler(toggles), ConfigApplyHandler(applier))
            ),
            pending = pending,
            // Same wiring as DeviceStateCollector: the applied revision is what the server reads back.
            stateSource = DeviceStateSource { snapshot(configStore) },
            telemetrySource = { null },
            eventSink = NoopEventSink,
        )

        /** Script the next check-in response: this is the "server" handing down a command batch. */
        fun serverSays(vararg commands: CommandEnvelope) {
            api.checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(commands = commands.toList()),
            )
        }

        private fun snapshot(store: ConfigStateStore) = AgentDeviceStateDto(
            battery = 50,
            charging = false,
            locked = false,
            kioskActive = false,
            androidRelease = "14",
            lastBootAt = 0L,
            appliedConfigRevision = store.revision(),
        )
    }

    /**
     * [id] is the dispatcher's idempotency key: the same id dispatched twice in one process runs the
     * handler once. Every test that sends more than one command must vary it, exactly as the server
     * would when minting a batch.
     */
    private fun command(id: String, type: String, payload: JsonObject? = null) = CommandEnvelope(
        commandId = id,
        issuedAt = "2026-01-01T00:00:00Z",
        type = type,
        payload = payload,
    )

    private fun policyCommand(id: String, key: String, enabled: Boolean) = command(
        id = id,
        type = POLICY_APPLY,
        payload = buildJsonObject {
            put("policy", key)
            put("value", enabled)
        },
    )

    private fun configCommand(doc: ConfigApplyPayload) = command(
        id = "c1",
        type = DeviceAction.CONFIG_APPLY,
        payload = ProtocolJson.json.encodeToJsonElement(ConfigApplyPayload.serializer(), doc) as JsonObject,
    )

    @Test
    fun `server-injected policy reaches the device strategy and is acked done`() = runTest {
        val factoryReset = RecordingToggle("factoryReset")
        val harness = Harness(mapOf("factoryReset" to factoryReset))
        harness.serverSays(policyCommand("c1", "factoryReset", enabled = false))

        harness.coordinator.runOnce()

        // The command travelled the whole chain: server -> coordinator -> dispatcher -> handler -> strategy.
        assertEquals(listOf(false), factoryReset.calls)
        val ack = harness.pending.drain().single()
        assertEquals(CommandStatus.DONE, ack.status)
    }

    /**
     * Pins the polarity contract from SPEC.md through the full loop: `true` = the action is ALLOWED
     * (restriction cleared), `false` = BLOCKED (restriction added). A silent inversion here is the
     * kind of bug that only shows up on a device in Settings.
     */
    @Test
    fun `true allows the action and false blocks it`() = runTest {
        val factoryReset = RecordingToggle("factoryReset")
        val harness = Harness(mapOf("factoryReset" to factoryReset))

        harness.serverSays(policyCommand("c1", "factoryReset", enabled = false))
        harness.coordinator.runOnce()
        harness.serverSays(policyCommand("c2", "factoryReset", enabled = true))
        harness.coordinator.runOnce()

        assertEquals(listOf(false, true), factoryReset.calls)
    }

    @Test
    fun `the outcome is delivered back to the server on the following cycle`() = runTest {
        val harness = Harness(mapOf("factoryReset" to RecordingToggle("factoryReset")))
        harness.serverSays(policyCommand("c1", "factoryReset", enabled = false))
        harness.coordinator.runOnce()

        assertTrue("first cycle acks nothing", harness.api.checkInRequests.first().results.isEmpty())

        // The server marks the command delivered and returns an empty batch.
        harness.serverSays()
        harness.coordinator.runOnce()

        val delivered = harness.api.checkInRequests[1].results.single()
        assertEquals("c1", delivered.commandId)
        assertEquals(CommandStatus.DONE, delivered.status)
        assertTrue("buffer emptied after delivery", harness.pending.drain().isEmpty())
    }

    /**
     * The open-registry guarantee (proto/endpoints.md): an unknown policy key degrades to
     * `unsupported` instead of failing the cycle, and the rest of the batch still lands.
     */
    @Test
    fun `an unregistered policy key is unsupported and does not abort the batch`() = runTest {
        val camera = RecordingToggle("camera")
        val harness = Harness(mapOf("camera" to camera))
        harness.serverSays(
            policyCommand("c1", "factoryResetProtection", enabled = false), // not implemented
            policyCommand("c2", "camera", enabled = false),
        )

        harness.coordinator.runOnce()

        val acks = harness.pending.drain().associateBy { it.commandId }
        assertEquals(
            CommandStatus.UNSUPPORTED,
            acks.getValue("c1").status,
        )
        assertEquals("the good command in the same batch still applied", listOf(false), camera.calls)
    }

    /**
     * The declarative path an admin console actually drives: one `config.apply` document converges
     * every policy it names, persists the revision, and that revision is reported back on the next
     * check-in so the server can distinguish "drift" from "converged".
     */
    @Test
    fun `config apply document converges every named policy and reports the revision back`() = runTest {
        val factoryReset = RecordingToggle("factoryReset")
        val camera = RecordingToggle("camera")
        val harness = Harness(mapOf("factoryReset" to factoryReset, "camera" to camera))
        harness.serverSays(
            configCommand(
                ConfigApplyPayload(
                    revision = "dev-7",
                    policies = mapOf("factoryReset" to false, "camera" to false),
                )
            )
        )

        harness.coordinator.runOnce()

        assertEquals(listOf(false), factoryReset.calls)
        assertEquals(listOf(false), camera.calls)
        assertEquals("dev-7", harness.configStore.revision())

        val ack = harness.pending.drain().single()
        assertEquals(CommandStatus.DONE, ack.status)
        val result = ProtocolJson.json.decodeFromString(ConfigApplyResult.serializer(), ack.detail!!)
        assertEquals(ConfigOutcome.APPLIED, result.outcomes["policies.factoryReset"])
        assertEquals(ConfigOutcome.APPLIED, result.outcomes["policies.camera"])

        // Next cycle: the server learns which revision the device is actually running.
        harness.serverSays()
        harness.coordinator.runOnce()
        assertEquals("dev-7", harness.api.checkInRequests[1].state?.appliedConfigRevision)
    }

    /**
     * A document whose strategy fails must NOT be persisted: the revision is what the server reads
     * back as "converged", so persisting a partially-applied document would report a lie.
     */
    @Test
    fun `a failing strategy blocks the revision from being persisted and reported`() = runTest {
        val wifi = RecordingToggle("wifi", PolicyOutcome.Failed("dpm rejected"))
        val harness = Harness(mapOf("wifi" to wifi))
        harness.serverSays(
            configCommand(
                ConfigApplyPayload(revision = "dev-8", policies = mapOf("wifi" to true))
            )
        )

        harness.coordinator.runOnce()

        val ack = harness.pending.drain().single()
        assertEquals(CommandStatus.FAILED, ack.status)
        assertNull("a failed document must not become the applied revision", harness.configStore.revision())
    }

    private companion object {
        /** `PolicyApplyHandler.type` is a literal, not a `DeviceAction` constant (it is not a device action). */
        const val POLICY_APPLY = "policy.apply"
    }
}
