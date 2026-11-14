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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    @Volatile private var stopped = false

    fun start(resultData: Intent) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val createdPeer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), observer)
            ?: error("WebRTC peer connection could not be created")
        peer = createdPeer
        val videoSource = factory!!.createVideoSource(true)
        source = videoSource
        val screen = ScreenCapturerAndroid(resultData, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() = stop("projection stopped")
        })
        capturer = screen
        helper = SurfaceTextureHelper.create("mdmesh-remote-capture", egl.eglBaseContext)
        screen.initialize(helper, appContext, videoSource.capturerObserver)
        val metrics: DisplayMetrics = appContext.resources.displayMetrics
        screen.startCapture(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), 15)
        val track = factory!!.createVideoTrack("mdmesh-screen", videoSource)
        createdPeer.addTrack(track)
        connectSignaling()
    }

    private fun connectSignaling() {
        val parsed = request.signaling.endpoint.toHttpUrl()
        val wsUrl = parsed.newBuilder().scheme(if (parsed.isHttps) "wss" else "ws").build()
        // The device id / bearer secret are appended by the session endpoint's auth layer through
        // request headers. The temporary join credential never enters signaling messages.
        socket = client.newWebSocket(
            Request.Builder().url(wsUrl)
                .addHeader("Authorization", "Bearer ${credentials.secret}")
                .addHeader("X-MDMesh-Device", credentials.deviceId)
                .addHeader("X-MDMesh-Remote-Session", request.signaling.credential)
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                override fun onMessage(webSocket: WebSocket, text: String) { handleSignal(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { stop("signaling closed: $reason") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stop("signaling failed") }
            },
        )
    }

    private fun handleSignal(text: String) = runCatching {
        val root = JSONObject(text); val payload = root.getJSONObject("payload")
        when (root.getString("type")) {
            "offer" -> peer?.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
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
            }, SessionDescription(SessionDescription.Type.OFFER, payload.getString("sdp")))
            "candidate" -> peer?.addIceCandidate(IceCandidate(payload.getString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate")))
        }
    }.onFailure { stop("invalid signaling") }

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
        runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        source?.dispose(); source = null; helper?.dispose(); helper = null
        peer?.close(); peer = null; factory?.dispose(); factory = null; egl.release()
        ended(reason)
    }
    private open class Sdp : SdpObserver { override fun onCreateSuccess(sdp: SessionDescription) = Unit; override fun onSetSuccess() = Unit; override fun onCreateFailure(error: String) = Unit; override fun onSetFailure(error: String) = Unit }
}
