package com.mdmesh.remote

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.os.Handler
import android.os.Looper
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-support primitive which isolates Android WebRTC host-candidate
 * gathering from screen capture, signaling, and the MDMesh server.
 */
object RemoteViewCandidateProbe {
    private const val WAIT_MS = 5_000L
    private const val CAPTURE_FRAME_RATE = 15

    fun run(context: Context, completed: (String) -> Unit) = run(context, videoOnly = false, completed)

    /**
     * Creates a video sender through the explicit Unified-Plan transceiver API
     * with default offer constraints. It isolates the legacy addTrack sender
     * path from screen capture, signaling, and the MDMesh server.
     */
    fun runVideo(context: Context, completed: (String) -> Unit) = run(context, videoOnly = true, completed)

    /**
     * The final diagnostic variation: starts an approved MediaProjection before
     * constructing the local video offer. It has no server or browser path.
     */
    fun runCapturedVideo(
        context: Context,
        resultData: Intent,
        completed: (String) -> Unit,
    ) {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mdmesh-captured-candidate-probe") }.execute {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            val egl = EglBase.create()
            // A generic factory handles a data channel, but this Android 9
            // runtime needs explicit video codecs before it will initialize a
            // usable video media section and ICE gathering for that section.
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            val candidates = AtomicInteger()
            val peer = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()),
                observer(candidates),
            )
            if (peer == null) {
                egl.release()
                factory.dispose()
                report(completed, "Captured video candidate probe could not create a peer connection.")
                return@execute
            }
            val resources = CapturedProbeResources()
            resources.peer = peer
            resources.factory = factory
            resources.egl = egl
            resources.source = factory.createVideoSource(true)
            resources.track = factory.createVideoTrack("captured-candidate-probe-video", resources.source)
            peer.addTransceiver(
                resources.track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
            )
            resources.capturer = ScreenCapturerAndroid(
                resultData,
                object : android.media.projection.MediaProjection.Callback() {},
            )
            resources.helper = SurfaceTextureHelper.create(
                "mdmesh-captured-candidate-probe",
                egl.eglBaseContext,
            )
            resources.capturer.initialize(
                resources.helper,
                context.applicationContext,
                resources.source.capturerObserver,
            )
            val metrics: DisplayMetrics = context.resources.displayMetrics
            resources.capturer.startCapture(
                metrics.widthPixels.coerceAtLeast(1),
                metrics.heightPixels.coerceAtLeast(1),
                CAPTURE_FRAME_RATE,
            )
            peer.createOffer(object : ProbeSdp() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peer.setLocalDescription(object : ProbeSdp() {
                        override fun onSetSuccess() {
                            Handler(Looper.getMainLooper()).postDelayed({
                                val embedded = iceCandidateCount(peer.localDescription?.description.orEmpty())
                                resources.dispose()
                                report(
                                    completed,
                                    "Captured video candidate probe: callbacks=${candidates.get()}, SDP=$embedded.",
                                )
                            }, WAIT_MS)
                        }

                        override fun onSetFailure(error: String) {
                            resources.dispose()
                            report(completed, "Captured video candidate probe rejected local SDP: $error")
                        }
                    }, sdp)
                }

                override fun onCreateFailure(error: String) {
                    resources.dispose()
                    report(completed, "Captured video candidate probe could not create SDP: $error")
                }
            }, org.webrtc.MediaConstraints())
        }
    }

    private fun run(context: Context, videoOnly: Boolean, completed: (String) -> Unit) {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mdmesh-candidate-probe") }.execute {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            val candidates = AtomicInteger()
            val peer = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()),
                observer(candidates),
            )
            if (peer == null) {
                factory.dispose()
                report(completed, "Candidate probe could not create a peer connection.")
                return@execute
            }
            val source: VideoSource?
            val track: VideoTrack?
            if (videoOnly) {
                // Mirror the real screen-share source. The source still has no
                // capturer here, so this remains independent of consent and
                // MediaProjection frame delivery.
                source = factory.createVideoSource(true)
                track = factory.createVideoTrack("candidate-probe-video", source)
                peer.addTransceiver(
                    track,
                    RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    ),
                )
            } else {
                source = null
                track = null
                peer.createDataChannel("candidate-probe", DataChannel.Init())
            }
            peer.createOffer(object : ProbeSdp() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peer.setLocalDescription(object : ProbeSdp() {
                        override fun onSetSuccess() {
                            Handler(Looper.getMainLooper()).postDelayed({
                                val embedded = iceCandidateCount(peer.localDescription?.description.orEmpty())
                                peer.close()
                                track?.dispose()
                                source?.dispose()
                                factory.dispose()
                                val kind = if (videoOnly) "Video candidate probe" else "Candidate probe"
                                report(completed, "$kind: callbacks=${candidates.get()}, SDP=$embedded.")
                            }, WAIT_MS)
                        }

                        override fun onSetFailure(error: String) {
                            peer.close()
                            track?.dispose()
                            source?.dispose()
                            factory.dispose()
                            report(completed, "Candidate probe rejected local SDP: $error")
                        }
                    }, sdp)
                }

                override fun onCreateFailure(error: String) {
                    peer.close()
                    track?.dispose()
                    source?.dispose()
                    factory.dispose()
                    report(completed, "Candidate probe could not create SDP: $error")
                }
            }, org.webrtc.MediaConstraints())
        }
    }

    private fun observer(candidates: AtomicInteger) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) { candidates.incrementAndGet() }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) { channel.close() }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver,
            mediaStreams: Array<out org.webrtc.MediaStream>,
        ) = Unit
    }

    private fun report(completed: (String) -> Unit, message: String) {
        Handler(Looper.getMainLooper()).post { completed(message) }
    }

    private class CapturedProbeResources {
        lateinit var peer: PeerConnection
        lateinit var capturer: ScreenCapturerAndroid
        lateinit var track: VideoTrack
        lateinit var source: VideoSource
        lateinit var helper: SurfaceTextureHelper
        lateinit var factory: PeerConnectionFactory
        lateinit var egl: EglBase

        fun dispose() {
            runCatching { capturer.stopCapture() }
            capturer.dispose()
            peer.close()
            track.dispose()
            source.dispose()
            helper.dispose()
            factory.dispose()
            egl.release()
        }
    }

    private open class ProbeSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
