@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.mdmesh.remote

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.ScreenCapturerAndroid
import java.util.concurrent.TimeUnit

/** One short-lived, video-only WebRTC sender. No audio track, data channel, or ICE server exists here. */
internal class RemoteViewCapture(
    context: Context,
    private val request: com.mdmesh.proto.RemoteStartSessionPayload,
    private val credentials: RemoteViewAgentCredentials,
    private val ended: (String) -> Unit,
    private val signalingOpened: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private val remoteCandidates = RemoteCandidateQueue<IceCandidate>()
    @Volatile private var stopped = false
    @Volatile private var startupStage = "creating capture"

    fun start(resultData: Intent) {
        startupStage = "initializing WebRTC"
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        startupStage = "creating WebRTC factory"
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        startupStage = "creating peer connection"
        val createdPeer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), observer)
            ?: error("WebRTC peer connection could not be created")
        peer = createdPeer
        startupStage = "creating video source"
        val videoSource = factory!!.createVideoSource(true)
        source = videoSource
        startupStage = "creating screen capturer"
        val screen = ScreenCapturerAndroid(resultData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() = stop("projection stopped")
        })
        capturer = screen
        startupStage = "creating capture helper"
        helper = SurfaceTextureHelper.create("mdmesh-remote-capture", egl.eglBaseContext)
        startupStage = "initializing screen capturer"
        screen.initialize(helper, appContext, videoSource.capturerObserver)
        val metrics: DisplayMetrics = appContext.resources.displayMetrics
        startupStage = "starting screen capture"
        screen.startCapture(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), 15)
        startupStage = "creating video track"
        val track = factory!!.createVideoTrack("mdmesh-screen", videoSource)
        startupStage = "adding video track"
        // The one-argument overload supplies no stream IDs. On the S8's WebRTC
        // native runtime that reaches front() on an empty vector and aborts the
        // entire agent process. A video sender must belong to a named stream.
        createdPeer.addTrack(track, listOf(SCREEN_STREAM_ID))
        startupStage = "opening signaling"
        connectSignaling()
    }

    /** Safe operational context for server-side diagnostics; never contains SDP, credentials, or URLs. */
    fun currentStartupStage(): String = startupStage

    private fun connectSignaling() {
        // OkHttp performs the HTTP(S) → WebSocket upgrade itself. Its Request API deliberately
        // accepts only HTTP(S), so changing the scheme to ws(s) throws before the handshake.
        // The device id / bearer secret are sent as request headers; the temporary join credential
        // never enters signaling messages.
        socket = client.newWebSocket(
            Request.Builder().url(request.signaling.endpoint)
                .addHeader("Authorization", "Bearer ${credentials.secret}")
                .addHeader("X-MDMesh-Device", credentials.deviceId)
                .addHeader("X-MDMesh-Remote-Session", request.signaling.credential)
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = signalingOpened()
                override fun onMessage(webSocket: WebSocket, text: String) { handleSignal(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { stop("signaling closed: $reason") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    stop("signaling failed: ${t.javaClass.simpleName}")
                }
            },
        )
    }

    private fun handleSignal(text: String) = runCatching {
        val root = JSONObject(text); val payload = root.getJSONObject("payload")
        when (root.getString("type")) {
            "offer" -> {
                val description = payload.getString("sdp")
                // Do not pass a browser offer without a video media section into
                // native WebRTC. Older Android WebRTC builds can abort the entire
                // process instead of returning a normal negotiation error.
                if (!hasVideoMediaSection(description)) {
                    stop("invalid video offer")
                    return@runCatching
                }
                peer?.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        applyPendingRemoteCandidates()
                        peer?.createAnswer(object : Sdp() {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                peer?.setLocalDescription(object : Sdp() {
                                    override fun onSetSuccess() {
                                        send("answer", JSONObject().put("sdp", sdp.description))
                                    }
                                }, sdp)
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, description))
            }
            "candidate" -> addRemoteCandidate(
                IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate")),
            )
        }
    }.onFailure { stop("invalid signaling") }

    /** Native WebRTC must not receive ICE until the asynchronous remote SDP operation finishes. */
    private fun addRemoteCandidate(candidate: IceCandidate) {
        remoteCandidates.offer(candidate)?.let { peer?.addIceCandidate(it) }
    }

    private fun applyPendingRemoteCandidates() {
        remoteCandidates.open().forEach { candidate -> peer?.addIceCandidate(candidate) }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = send("candidate", JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) { if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED || state == PeerConnection.PeerConnectionState.DISCONNECTED) stop("peer $state") }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) { channel.close() }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
    }

    private fun send(type: String, payload: JSONObject) { if (!stopped) socket?.send(JSONObject().put("type", type).put("payload", payload).toString()) }
    fun stop(reason: String) {
        if (stopped) return
        stopped = true
        runCatching { socket?.close(1000, reason.take(80)) }; socket = null
        remoteCandidates.clear()
        runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        source?.dispose(); source = null; helper?.dispose(); helper = null
        peer?.close(); peer = null; factory?.dispose(); factory = null; egl.release()
        ended(reason)
    }
    private open class Sdp : SdpObserver { override fun onCreateSuccess(sdp: SessionDescription) = Unit; override fun onSetSuccess() = Unit; override fun onCreateFailure(error: String) = Unit; override fun onSetFailure(error: String) = Unit }

    private companion object {
        const val SCREEN_STREAM_ID = "mdmesh-screen-stream"
    }
}

/** Rejects incomplete browser offers before Android's native WebRTC code sees them. */
internal fun hasVideoMediaSection(sdp: String): Boolean = sdp.lineSequence()
    .any { line -> line.trimStart().startsWith("m=video ") }
