package com.mdmesh.core.command.handlers

import android.os.Build
import android.os.UserManager
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction

/**
 * `device.wipe` — factory-reset the whole device. Irreversible. Gated by the `device.wipe`
 * capability token AND UI type-to-confirm. Reports `done` best-effort, though the process is
 * usually torn down before the ack is sent.
 *
 * The restrictions in [WipeRestrictions] (Factory reset: Block) are lifted first, since some vendor
 * recoveries enforce them (#222), and put back if the wipe call fails. A block another admin or
 * source keeps doesn't stop the wipe (stock Android wipes past it); the result says so.
 *
 * [hold] is set before anything is lifted and released on every path that doesn't reach a wipe, so
 * no apply can put the block back once it is lifted: the wipe call can return before the reboot, and
 * the process can die between the lift and the call. If no reboot follows, the hold expires and the
 * agent re-applies its configuration ([FactoryResetWipeHold]). [exclusive] keeps applies out from the
 * hold to the wipe call (check-in commands already run one at a time).
 *
 * [markConfigStale] (`ConfigStateStore.markStale`) runs before anything is lifted, so the server
 * re-sends the configuration too.
 */
class DeviceWipeHandler(
    private val target: WipeTarget,
    private val hold: FactoryResetWipeHold = FactoryResetWipeHold(),
    private val markConfigStale: () -> Unit = {},
    private val exclusive: suspend (suspend () -> CommandResult) -> CommandResult = { it() },
) : CommandHandler {

    override val type: String = DeviceAction.WIPE

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        if (!target.isDeviceOwner()) {
            return CommandResults.unsupported(command, "wipe requires Device Owner")
        }
        return exclusive { wipe(command) }
    }

    private fun wipe(command: CommandEnvelope): CommandResult {
        hold.hold()
        return when (val lift = WipeRestrictions.lift(target, beforeClear = markConfigStale)) {
            is WipeRestrictions.Lift.Ready -> wipeLifted(command, lift)
            is WipeRestrictions.Lift.Error -> {
                hold.release()
                failed(command, lift.cause.message, lift.notRestored)
            }
        }
    }

    private fun wipeLifted(command: CommandEnvelope, lift: WipeRestrictions.Lift.Ready): CommandResult =
        runCatching {
            target.wipe()
            CommandResults.done(command, lift.setElsewhere.takeIf { it.isNotEmpty() }?.let { elsewhere(it) })
        }.getOrElse {
            hold.release()
            failed(command, it.message, WipeRestrictions.restore(target, lift.lifted))
        }

    private fun elsewhere(keys: List<String>) =
        "another admin or source also blocks factory reset (${keys.joinToString()}); " +
            "some vendor recoveries may refuse it"

    private fun failed(command: CommandEnvelope, detail: String?, notRestored: List<String>): CommandResult {
        val reason = detail ?: "wipe failed"
        return CommandResults.failed(
            command,
            if (notRestored.isEmpty()) reason else "$reason; could not restore ${notRestored.joinToString()}",
        )
    }
}

/** The device calls a wipe makes; [DpmWipeTarget] on a device, a fake in tests. */
interface WipeTarget {
    fun isDeviceOwner(): Boolean
    /** Whether our admin sets [key]. */
    fun holdsRestriction(key: String): Boolean

    /** Whether [key] is in force from any source (another admin, the system), ours included. */
    fun hasRestriction(key: String): Boolean
    fun addRestriction(key: String)
    fun clearRestriction(key: String)
    fun wipe()
}

/**
 * On API 34+ a Device Owner must use [android.app.admin.DevicePolicyManager.wipeDevice] for a
 * full factory reset; the legacy [android.app.admin.DevicePolicyManager.wipeData] is treated as
 * "remove the calling user", which fails on the system user ("User 0 ... cannot be removed").
 * [holdsRestriction] reads our admin's own restrictions; [hasRestriction] asks [UserManager], which
 * sees every source.
 */
class DpmWipeTarget(
    private val handle: DpmHandle,
    private val users: UserManager,
) : WipeTarget {
    override fun isDeviceOwner() = handle.dpm.isDeviceOwnerApp(handle.admin.packageName)
    override fun holdsRestriction(key: String) = handle.dpm.getUserRestrictions(handle.admin).getBoolean(key)
    override fun hasRestriction(key: String) = users.hasUserRestriction(key)
    override fun addRestriction(key: String) = handle.dpm.addUserRestriction(handle.admin, key)
    override fun clearRestriction(key: String) = handle.dpm.clearUserRestriction(handle.admin, key)

    override fun wipe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            handle.dpm.wipeDevice(0)
        } else {
            @Suppress("DEPRECATION")
            handle.dpm.wipeData(0)
        }
    }
}
