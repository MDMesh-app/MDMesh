package com.mdmesh.core.command.handlers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult

/**
 * `kiosk.exit` — release COSU lock-task (clear allowlist + persistent-HOME claim). On success the
 * persisted [KioskStateStore] payload is cleared so the agent does not re-enter kiosk on next boot,
 * and the launcher is brought forward so it unpins and drops to its idle screen immediately.
 */
class KioskExitHandler(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val homeComponent: ComponentName,
    private val context: Context,
) : CommandHandler {

    override val type: String = "kiosk.exit"

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        when (val r = kiosk.exit()) {
            KioskResult.Ok -> {
                store.save(null)
                // Drop our HOME claim so HOME falls back to the OEM launcher, then send the device
                // there — otherwise the user is stuck on our (now-unpinned) launcher surface.
                disableHomeAlias()
                goToOemHome()
                CommandResults.done(command)
            }
            KioskResult.Unsupported -> CommandResults.unsupported(command, "kiosk unsupported on this device")
            is KioskResult.Failed -> CommandResults.failed(command, r.reason)
        }

    private fun disableHomeAlias() {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                homeComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun goToOemHome() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
