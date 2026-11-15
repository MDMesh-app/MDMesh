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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    /**
     * AppRTC serializes every PeerConnection operation on one executor. Native
     * WebRTC objects are not safe to mutate from the OkHttp, callback, capture,
     * and Android-main threads that all participate in this feature.
     */
    private val peerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mdmesh-remote-webrtc")
    }
    @Volatile private var socket: WebSocket? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var captureWidth = 1
    private var captureHeight = 1
    private var captureStarted = false
    private val remoteCandidates = RemoteCandidateQueue<IceCandidate>()
    @Volatile private var stopped = false
    @Volatile private var startupStage = "creating capture"

    fun start(resultData: Intent) = runOnPeerThread {
        runCatching { startOnPeerThread(resultData) }
            .onFailure { failure -> stop("capture startup failed: ${failure.javaClass.simpleName}") }
    }

    private fun startOnPeerThread(resultData: Intent) {
        startupStage = "initializing WebRTC"
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        egl = EglBase.create()
        startupStage = "creating WebRTC factory"
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        startupStage = "creating peer connection"
        // STUN discovers the agent's reachable candidate address. It does not
        // relay video or control traffic; TURN remains explicitly out of scope
        // for this LAN-first POC.
        val createdPeer = factory!!.createPeerConnection(
            PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(STUN_SERVER).createIceServer())), observer,
        )
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
        helper = SurfaceTextureHelper.create("mdmesh-remote-capture", requireNotNull(egl).eglBaseContext)
        startupStage = "initializing screen capturer"
        screen.initialize(helper, appContext, videoSource.capturerObserver)
        val metrics: DisplayMetrics = appContext.resources.displayMetrics
        // Do not start producing frames before WebRTC has a negotiated sender.
        // An Android 9 native WebRTC runtime can be reached with a track whose
        // sender graph is not negotiated yet, so defer frame production until
        // the answer has installed it.
        captureWidth = metrics.widthPixels.coerceAtLeast(1)
        captureHeight = metrics.heightPixels.coerceAtLeast(1)
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
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "remote signaling opened")
                    signalingOpened()
                    runOnPeerThread(::createAndSendOffer)
                }
                override fun onMessage(webSocket: WebSocket, text: String) { runOnPeerThread { handleSignal(text) } }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { stop("signaling closed: $reason") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    stop("signaling failed: ${t.javaClass.simpleName}")
                }
            },
        )
    }

    /** Runs only on [peerExecutor], including JSON-to-native candidate handoff. */
    private fun handleSignal(text: String) = runCatching {
        val root = JSONObject(text); val payload = root.getJSONObject("payload")
        when (root.getString("type")) {
            "answer" -> {
                val description = payload.getString("sdp")
                // Do not pass a browser answer without a video media section into
                // native WebRTC. Older Android WebRTC builds can abort the entire
                // process instead of returning a normal negotiation error.
                if (!hasVideoMediaSection(description)) {
                    stop("invalid video answer")
                    return@runCatching
                }
                startupStage = "setting remote description"
                Log.i(TAG, "remote answer accepted")
                peer?.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        runOnPeerThread {
                            startupStage = "remote answer installed"
                            Log.i(TAG, "remote description accepted")
                            applyPendingRemoteCandidates()
                            startScreenCapture()
                        }
                    }
                    override fun onSetFailure(error: String) = stop("remote answer rejected: $error")
                }, SessionDescription(SessionDescription.Type.ANSWER, description))
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

    /** The screen-sharing agent is the offerer; Firefox receives a constrained local offer. */
    private fun createAndSendOffer() {
        startupStage = "creating local offer"
        peer?.createOffer(object : Sdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                runOnPeerThread {
                    startupStage = "setting local offer"
                    peer?.setLocalDescription(object : Sdp() {
                        override fun onSetSuccess() {
                            startupStage = "sending local offer"
                            Log.i(TAG, "local offer accepted")
                            send("offer", JSONObject().put("sdp", sdp.description))
                        }
                        override fun onSetFailure(error: String) = stop("local offer rejected: $error")
                    }, sdp)
                }
            }
            override fun onCreateFailure(error: String) = stop("local offer creation failed: $error")
        }, MediaConstraints())
    }

    /** Starts frames only after the local answer has installed a usable video sender. */
    private fun startScreenCapture() {
        if (captureStarted || stopped) return
        startupStage = "starting negotiated screen capture"
        capturer?.startCapture(captureWidth, captureHeight, 15)
        captureStarted = true
        Log.i(TAG, "negotiated screen capture started")
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.i(TAG, "remote local ICE candidate generated: mid=${candidate.sdpMid}, index=${candidate.sdpMLineIndex}")
            send("candidate", JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            startupStage = "peer $state"
            Log.i(TAG, "remote peer state=$state")
            // DISCONNECTED is explicitly a transient ICE state. Ending the
            // capture and disposing native objects from this callback caused a
            // re-entrant native teardown on the S8. FAILED and CLOSED are the
            // only terminal states for this short-lived POC session.
            if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                stop("peer $state")
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            startupStage = "ICE $state"
            Log.i(TAG, "remote ICE state=$state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.i(TAG, "remote ICE gathering=$state")
        }
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) { runOnPeerThread { channel.close() } }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
    }

    private fun send(type: String, payload: JSONObject) { if (!stopped) socket?.send(JSONObject().put("type", type).put("payload", payload).toString()) }
    @Synchronized
    fun stop(reason: String) {
        if (stopped) return
        stopped = true
        Log.i(TAG, "stopping remote capture: $reason")
        peerExecutor.execute {
            runCatching { socket?.close(1000, reason.take(80)) }; socket = null
            remoteCandidates.clear()
            if (captureStarted) runCatching { capturer?.stopCapture() }
            captureStarted = false
            capturer?.dispose(); capturer = null
            source?.dispose(); source = null; helper?.dispose(); helper = null
            peer?.close(); peer = null; factory?.dispose(); factory = null; egl?.release(); egl = null
            ended(reason)
            peerExecutor.shutdown()
        }
    }

    private fun runOnPeerThread(action: () -> Unit) {
        if (stopped) return
        runCatching {
            peerExecutor.execute { if (!stopped) action() }
        }.onFailure { failure -> stop("WebRTC executor failed: ${failure.javaClass.simpleName}") }
    }
    private open class Sdp : SdpObserver { override fun onCreateSuccess(sdp: SessionDescription) = Unit; override fun onSetSuccess() = Unit; override fun onCreateFailure(error: String) = Unit; override fun onSetFailure(error: String) = Unit }

    private companion object {
        const val TAG = "MDMeshRemoteView"
        const val SCREEN_STREAM_ID = "mdmesh-screen-stream"
        const val STUN_SERVER = "stun:stun.l.google.com:19302"
    }
}

/** Rejects incomplete browser offers before Android's native WebRTC code sees them. */
internal fun hasVideoMediaSection(sdp: String): Boolean = sdp.lineSequence()
    .any { line -> line.trimStart().startsWith("m=video ") }
