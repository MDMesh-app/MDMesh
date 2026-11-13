package com.mdmesh.agent

/**
 * Return to kiosk's progress on the status screen, and why the last try failed. The failure shows until the next
 * refresh that matters: a new try, a change in the device's state (the kiosk or lock task), or the person leaving
 * the screen. A rotation keeps it.
 */
data class ReturnProgress(val running: Boolean = false, val failure: String? = null) {

    fun started(): ReturnProgress = ReturnProgress(running = true)

    fun finished(failure: String?): ReturnProgress = ReturnProgress(running = false, failure = failure)

    fun refreshed(factsChanged: Boolean): ReturnProgress = if (factsChanged && !running) copy(failure = null) else this

    fun left(): ReturnProgress = if (running) this else copy(failure = null)
}
