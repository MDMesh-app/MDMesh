package com.mdmesh.remote

/** Holds remote ICE candidates until WebRTC confirms that the remote SDP is installed. */
internal class RemoteCandidateQueue<T> {
    private val pending = mutableListOf<T>()
    private var open = false

    @Synchronized
    fun offer(candidate: T): T? {
        if (open) return candidate
        pending += candidate
        return null
    }

    @Synchronized
    fun open(): List<T> {
        open = true
        return pending.toList().also { pending.clear() }
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }
}
