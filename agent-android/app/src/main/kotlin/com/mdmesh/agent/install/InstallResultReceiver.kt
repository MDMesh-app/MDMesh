package com.mdmesh.agent.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.mdmesh.core.install.InstallResultBus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Manifest-registered, non-exported receiver for [PackageInstaller] commit results.
 *
 * `InstallManager` commits each session with an explicit PendingIntent targeting this
 * receiver (action [InstallResultBus.ACTION]); we forward the outcome to the singleton
 * [InstallResultBus], which the suspending install/uninstall call is awaiting. A
 * manifest receiver (not a context-registered one) is the Android-14-safe pattern and
 * survives process death between commit and callback.
 */
@AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {

    @Inject lateinit var bus: InstallResultBus

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != InstallResultBus.ACTION) return
        val sessionId = intent.getIntExtra(InstallResultBus.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(InstallResultBus.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(InstallResultBus.EXTRA_STATUS_MESSAGE)
        bus.publish(sessionId, status, message)
    }
}
