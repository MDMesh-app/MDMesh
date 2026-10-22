package com.mdmesh.kiosk

/**
 * Whether Safe Mode is blocked on this device. The one rule, so every path that changes kiosk or the `safeBoot`
 * policy computes the same answer.
 *
 * Safe Mode starts the phone's own launcher with third-party apps (the agent included) switched off, so it gets
 * around any kiosk. Kiosk therefore blocks it unless the configuration explicitly allows it. Outside kiosk only an
 * explicit Block blocks it, so "Not managed" really means not managed once kiosk ends.
 *
 * @param inKiosk whether the device is in kiosk (a kiosk is saved), however it got there (configuration or
 *   kiosk.enter).
 * @param policy the configuration's `safeBoot` policy: true = Allow, false = Block, null = not managed.
 */
fun safeModeBlocked(inKiosk: Boolean, policy: Boolean?): Boolean = policy == false || (inKiosk && policy != true)
