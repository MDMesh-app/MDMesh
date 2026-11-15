package com.mdmesh.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteViewCaptureTest {

    @Test
    fun `video offers require a video media section`() {
        assertTrue(hasVideoMediaSection("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
        assertTrue(hasVideoMediaSection("v=0\n m=video 9 UDP/TLS/RTP/SAVPF 96\n"))
        assertFalse(hasVideoMediaSection("v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"))
        assertFalse(hasVideoMediaSection("v=0\r\n"))
    }
}
