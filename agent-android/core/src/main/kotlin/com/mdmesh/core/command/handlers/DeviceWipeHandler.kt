package com.mdmesh.core.command.handlers

import android.os.Build
import android.os.UserManager
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
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
 * recoveries enforce them (#222), and put back if the wipe call fails.
 *
 * [exclusive] holds off every configuration apply from the lift until the wipe is under way, so a
 * boot re-apply can't put the block back in between (check-in commands already run one at a time).
 */
class DeviceWipeHandler(
    private val target: WipeTarget,
    private val exclusive: suspend (suspend () -> CommandResult) -> CommandResult = { it() },
) : CommandHandler {

    override val type: String = DeviceAction.WIPE

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        if (!target.isDeviceOwner()) {
            return CommandResults.unsupported(command, "wipe requires Device Owner")
        }
        return exclusive {
            runCatching { wipe(command) }.getOrElse { CommandResults.failed(command, it.message ?: "wipe failed") }
        }
    }

    private fun wipe(command: CommandEnvelope): CommandResult {
        val lifted = when (val lift = WipeRestrictions.lift(target)) {
            is WipeRestrictions.Lift.StillSet -> return CommandResults.failed(
                command,
                "The device still blocks factory reset (${lift.key}) after MDMesh lifted its own block, " +
                    "so the wipe was not started",
            )
            is WipeRestrictions.Lift.Ready -> lift.lifted
        }
        return runCatching {
            target.wipe()
            CommandResults.done(command)
        }.getOrElse { error ->
            val detail = error.message ?: "wipe failed"
            val notRestored = WipeRestrictions.restore(target, lifted)
            CommandResults.failed(
                command,
                if (notRestored.isEmpty()) detail else "$detail; could not restore ${notRestored.joinToString()}",
            )
        }
    }
}

/** The device calls a wipe makes; [DpmWipeTarget] on a device, a fake in tests. */
interface WipeTarget {
    fun isDeviceOwner(): Boolean
    fun hasRestriction(key: String): Boolean
    fun addRestriction(key: String)
    fun clearRestriction(key: String)
    fun wipe()
}

/**
 * On API 34+ a Device Owner must use [android.app.admin.DevicePolicyManager.wipeDevice] for a
 * full factory reset; the legacy [android.app.admin.DevicePolicyManager.wipeData] is treated as
 * "remove the calling user", which fails on the system user ("User 0 ... cannot be removed").
 * [hasRestriction] asks [UserManager], which sees the restriction from every source, not just ours.
 */
class DpmWipeTarget(
    private val handle: DpmHandle,
    private val users: UserManager,
) : WipeTarget {
    override fun isDeviceOwner() = handle.dpm.isDeviceOwnerApp(handle.admin.packageName)
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
