package com.mdmesh.remote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The ONE place in the whole agent that uses Accessibility.
 *
 * During a future control-mode remote session, the
 * admin's taps/swipes are replayed on the device via
 * [AccessibilityService.dispatchGesture] (enabled by `canPerformGestures="true"`
 * in `res/xml/input_injection_service.xml`).
 *
 * Stub: gesture dispatch is not implemented yet (it pairs with the WebRTC input
 * channel — see README). The service deliberately observes nothing —
 * [onAccessibilityEvent] is a no-op — because it injects, it does not snoop.
 *
 * Its component is declared `android:enabled="false"`. Exposing it later does
 * not enable Accessibility: Android Settings still requires explicit local-user
 * consent, and session state only controls whether an enabled service accepts input.
 */
class InputInjectionService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service injects input, it does not read events.
    }

    override fun onInterrupt() {
        // No long-running feedback to interrupt in the injection-only design.
    }

    // TODO: dispatchGesture(...) wiring lands with the remote-control input channel.
}
