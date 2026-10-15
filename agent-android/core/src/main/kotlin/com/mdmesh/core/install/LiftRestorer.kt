package com.mdmesh.core.install

/**
 * Puts back an app install/uninstall restriction left lifted by an interrupted managed operation
 * ([InstallManager.restoreInterruptedLifts]). Run by [com.mdmesh.core.sync.CheckInCoordinator] at the start of every
 * cycle, where no managed operation can be in flight. Never throws.
 */
fun interface LiftRestorer {
    fun restore()
}
