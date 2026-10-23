package com.mdmesh.core.kiosk

import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.policy.safeboot.SafeBootPolicy

/**
 * The configuration's Safe Mode setting, as [KioskApplier] reads it on `kiosk.enter` and `kiosk.exit`: the `safeBoot`
 * policy, true = Allow, false = Block, null = not managed. A type of its own rather than a lambda, so it can't be
 * passed in the place of another argument by mistake. `config.apply` reads it too ([KioskApplier.syncSafeMode]),
 * after recording its document as delivered, so every path gets the same answer.
 */
interface SafeModeSetting {
    fun current(): Boolean?

    /** No configuration says anything. For tests: production always reads [ConfiguredSafeModeSetting]. */
    object NotManaged : SafeModeSetting {
        override fun current(): Boolean? = null
    }
}

/**
 * Reads the document the server last delivered, applied in full or not, and only then the last saved one. A document
 * with a failed section is never saved, yet it is what the server asked for, and it is already the one being applied
 * while a `config.apply` runs: reading the saved one would follow an old setting (an old Allow) for up to an hour.
 */
class ConfiguredSafeModeSetting(private val store: ConfigStateStore) : SafeModeSetting {
    override fun current(): Boolean? =
        (store.delivered ?: store.load())?.policies?.get(SafeBootPolicy.CAPABILITY_KEY)
}
