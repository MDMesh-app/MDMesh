package com.mdmesh.core.localapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/**
 * `device.reboot` for [LocalApi]: the Device Owner reboots the device for the pinned app, which has no way of its own
 * to do it (an app's own reboot needs the system's `REBOOT` permission).
 *
 * `DevicePolicyManager.reboot` either refuses at once, with an exception, or goes ahead and blocks its caller until the
 * device goes down. So it runs on a thread of its own, and the reply waits [refusalWindowMs] for a refusal. With none
 * in that time the reboot is under way and the reply is [LocalApiCode.OK]; a call that returns without throwing is too.
 */
internal class RebootBridge(
    private val platform: LocalApiPlatform,
    private val refusalWindowMs: Long,
) {
    suspend fun reboot(): LocalApiReply {
        val thrown = CompletableDeferred<Throwable?>()
        thread(name = THREAD_NAME, isDaemon = true) {
            thrown.complete(runCatching { platform.reboot() }.exceptionOrNull())
        }
        // Null both when the reboot returned and when it is still blocked past the window: either way it goes ahead.
        return when (val failure = withTimeoutOrNull(refusalWindowMs) { thrown.await() }) {
            null -> LocalApiReply.ok("rebooting")
            // The one refusal DevicePolicyManager.reboot raises this way.
            is IllegalStateException -> LocalApiReply.refused(IN_CALL)
            else -> LocalApiReply.error("${LocalApi.DEVICE_REBOOT} failed: ${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        const val THREAD_NAME = "LocalApi-reboot"
        const val IN_CALL = "Android refused to reboot, as it does during a phone call"
    }
}
