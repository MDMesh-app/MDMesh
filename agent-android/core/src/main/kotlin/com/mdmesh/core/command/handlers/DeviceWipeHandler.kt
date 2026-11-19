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
 * recoveries enforce them (#222), and put back if the wipe call fails. Nothing in the lift stops the
 * wipe of a lost device: a block another admin or source keeps (stock Android wipes past it) and a
 * restriction call that threw are reported in the result, and the wipe is still made.
 *
 * [hold] is set before anything is lifted and released when the wipe call throws, so no apply can
 * put the block back once it is lifted: the wipe call can return before the reboot, and the process
 * can die between the lift and the call. A repeat wipe that throws keeps the hold an earlier accepted
 * wipe set: that one may still be heading into recovery. If no reboot follows, the hold expires and the
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
        val wasHeld = hold.held
        hold.hold()
        val lift = WipeRestrictions.lift(target, beforeClear = markConfigStale)
        return runCatching {
            target.wipe()
            val notes = listOfNotNull(
                liftError(lift)?.let { "$it; wiped anyway" },
                lift.setElsewhere.takeIf { it.isNotEmpty() }?.let(::elsewhere),
            )
            CommandResults.done(command, notes.joinToString("; ").ifEmpty { null })
        }.getOrElse { error ->
            if (!wasHeld) hold.release()
            val notRestored = WipeRestrictions.restore(target, lift.lifted)
            val notes = listOfNotNull(
                error.message ?: "wipe failed",
                liftError(lift),
                notRestored.takeIf { it.isNotEmpty() }?.let { "could not restore ${it.joinToString()}" },
            )
            CommandResults.failed(command, notes.joinToString("; "))
        }
    }

    private fun liftError(lift: WipeRestrictions.Lift) =
        lift.error?.let { "could not lift the factory reset block (${it.message})" }

    private fun elsewhere(keys: List<String>) =
        "another admin or source also blocks factory reset (${keys.joinToString()}); " +
            "some vendor recoveries may refuse it"
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
