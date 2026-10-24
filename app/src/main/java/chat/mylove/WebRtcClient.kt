
package chat.mylove

import android.content.Context
import android.os.Build
import java.util.UUID
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.MediaStream
import org.webrtc.RtpReceiver
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.CameraVideoCapturer
import org.webrtc.Camera1Enumerator
import org.webrtc.SurfaceTextureHelper
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DefaultVideoDecoderFactory

class WebRtcClient(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, Boolean) -> Unit,
    private val onPairingEvent: (JSONObject) -> Unit = {},
    private val onFeatureEvent: (JSONObject) -> Unit = {},
    private val onFileReceived: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    private val onRemoteVideo: (VideoTrack?) -> Unit = {},
    private val onLocalVideo: (VideoTrack?) -> Unit = {}
) {
    companion object {
        private const val SIGNALING_URL =
            "wss://ghostlink-signaling-dwaq.onrender.com"

        // IMPORTANT:
        // Replace these with newly rotated Metered credentials.
        // Never commit real TURN credentials to a public repository.
        private const val TURN_USERNAME = "8d71da47e2d503774e0a8735"
        private const val TURN_CREDENTIAL = "GfQ29hBP/KlqNlbO"
    }

    private val appContext = context.applicationContext
    private val identityPrefs = appContext.getSharedPreferences("ghostlink_identity_v1", Context.MODE_PRIVATE)
    private val deviceId: String by lazy {
        identityPrefs.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            identityPrefs.edit().putString("device_id", it).apply()
        }
    }
    private val deviceLabel: String get() = (Build.MODEL ?: "GhostLink device").take(40)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteRenderer: org.webrtc.SurfaceViewRenderer? = null
    private var localRenderer: org.webrtc.SurfaceViewRenderer? = null
    private val eglBase: org.webrtc.EglBase by lazy { org.webrtc.EglBase.create() }
    private var currentCallVideo = false
    // One call at a time. Prevent duplicate incoming-call prompts and call collisions.
    private var callState: String = "idle" // idle, outgoing, incoming, active
    private var microphoneMuted = false
    private val incomingTransfers = mutableMapOf<String, IncomingTransfer>()
    private data class IncomingTransfer(val name: String, val mime: String, val total: Int, val chunks: Array<String?>, var received: Int = 0)

    private var initiator = false
    private var remoteDescriptionSet = false
    private var closed = false
    private var factoryInitialized = false
    @Volatile private var reconnectDelayMs = 2_000L

    private val iceLock = Any()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val pendingMessages = mutableListOf<JSONObject>()

    private val sendLock = Any()

    private fun status(text: String) {
        mainHandler.post {
            onStatus(text)
        }
    }

    private fun pairingEvent(event: JSONObject) {
        mainHandler.post {
            onPairingEvent(event)
        }
    }

    private fun messageReceived(text: String, outgoing: Boolean) {
        mainHandler.post {
            onMessage(text, outgoing)
        }
    }

    /**
     * Opens the signaling WebSocket.
     * Calling this more than once does not open duplicate connections.
     */

    @Synchronized
    fun connect() {
        if (socket != null || closed) return

        try {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(appContext)
                        .createInitializationOptions()
                )

                factory = PeerConnectionFactory
                    .builder()
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(
                            eglBase.eglBaseContext,
                            true,
                            true
                        )
                    )
                    .setVideoDecoderFactory(
                        DefaultVideoDecoderFactory(
                            eglBase.eglBaseContext
                        )
                    )
                    .createPeerConnectionFactory()

                if (factory == null) {
                    status("Failed to initialize WebRTC")
                    return
                }

                factoryInitialized = true
            }

            status("Connecting to GhostLink signaling server...")

            val request = Request.Builder()
                .url(SIGNALING_URL)
                .build()

            socket = http.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {
                        reconnectDelayMs = 2_000L

                        status("Signaling server connected")

                        // Register every WebSocket connection because the
                        // signaling server's device registry is in memory.
                        webSocket.send(
                            JSONObject()
                                .put("type", "register-device")
                                .put("deviceId", deviceId)
                                .put("deviceLabel", deviceLabel)
                                .toString()
                        )

                        val queued: List<JSONObject>

                        synchronized(sendLock) {
                            queued = pendingMessages.toList()
                            pendingMessages.clear()
                        }

                        queued.forEach { message ->
                            webSocket.send(message.toString())
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {
                        try {
                            handleSignal(JSONObject(text))
                        } catch (e: Exception) {
                            status(
                                "Signaling message error: ${e.message}"
                            )
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        synchronized(this@WebRtcClient) {
                            if (socket === webSocket) {
                                socket = null
                            }
                        }

                        status("Signaling failed: ${t.message}")
                        scheduleReconnect()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        synchronized(this@WebRtcClient) {
                            if (socket === webSocket) {
                                socket = null
                            }
                        }

                        status("Signaling closed: $reason")
                        scheduleReconnect()
                    }
                }
            )
        } catch (e: Exception) {
            status("Initialization failed: ${e.message}")
        }
    }


    private fun scheduleReconnect() {
        if (closed) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
        mainHandler.postDelayed({
            if (!closed && socket == null) connect()
        }, delay)
    }

    /**
     * Creates a new one-time invitation.
     * The returned invitation-created event contains roomId and token.
     */
    fun createInvite() {
        connect()
        sendOrQueue(JSONObject().put("type", "create-invite"))
        status("Requesting a one-time pairing invitation...")
    }

    /**
     * Joins an invitation obtained by scanning a QR code.
     */
    fun joinInvite(
        roomId: String,
        token: String,
        deviceLabel: String
    ) {
        if (roomId.isBlank() || token.isBlank()) {
            status("Invalid QR invitation")
            return
        }

        connect()

        sendOrQueue(
            JSONObject()
                .put("type", "join-invite")
                .put("roomId", roomId)
                .put("token", token)
                .put("deviceLabel", deviceLabel.take(40))
        )

        status("Sending pairing request...")
    }

    /**
     * Only the invitation creator should call this after seeing
     * the pairing approval screen.
     */
    fun respondToPairing(
        requestId: String,
        approved: Boolean
    ) {
        if (requestId.isBlank()) {
            status("Missing pairing request ID")
            return
        }

        sendOrQueue(
            JSONObject()
                .put("type", "pairing-response")
                .put("requestId", requestId)
                .put("approved", approved)
        )

        status(
            if (approved) "Approving pairing..."
            else "Rejecting pairing..."
        )
    }

    /** Starts a fresh session with a previously paired device, without another QR scan. */
    fun requestReconnect(peerDeviceId: String, pairToken: String) {
        if (peerDeviceId.isBlank() || pairToken.length < 32) {
            status("Saved trusted-device details are invalid")
            return
        }
        // If the old DataChannel is still open, reuse it instead of opening another room.
        if (channel?.state() == DataChannel.State.OPEN) {
            status("Already connected to this peer")
            return
        }
        connect()
        // The server may still associate this socket with a stale room after ICE/WebRTC
        // failed. WebSocket ordering guarantees leave-session is processed before the request.
        sendOrQueue(JSONObject().put("type", "leave-session"))
        sendOrQueue(JSONObject()
            .put("type", "reconnect-request")
            .put("peerDeviceId", peerDeviceId)
            .put("pairToken", pairToken)
            .put("deviceLabel", deviceLabel))
        status("Requesting trusted-device reconnection...")
    }

    /** Responds to a reconnect prompt shown only after the local pair-token check. */
    fun respondToReconnect(requestId: String, approved: Boolean) {
        if (requestId.isBlank()) return
        sendOrQueue(JSONObject()
            .put("type", "reconnect-response")
            .put("requestId", requestId)
            .put("approved", approved))
        status(if (approved) "Reconnect approved; negotiating WebRTC..." else "Reconnect rejected")
    }

    /** Cancels an invitation or pending pairing request. */
    fun cancelInvite() {
        sendOrQueue(JSONObject().put("type", "cancel-invite"))
    }

    private fun sendOrQueue(message: JSONObject) {
        val currentSocket: WebSocket?

        synchronized(sendLock) {
            currentSocket = socket

            if (currentSocket == null) {
                pendingMessages.add(message)
                return
            }
        }

        if (currentSocket != null && currentSocket.send(message.toString())) {
            return
        }

        synchronized(sendLock) {
            pendingMessages.add(message)
        }

        status("Signaling message queued for retry")
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {

            "invite-created" -> {
                // The UI should encode these fields into its QR.
                pairingEvent(message)
                status("Pairing invitation created")
            }

            "pairing-request" -> {
                pairingEvent(message)
                status("A device is requesting to pair")
            }

            "trusted-device", "reconnect-request", "reconnect-pending", "reconnect-approved",
            "reconnect-rejected", "reconnect-failed", "reconnect-request-cancelled", "device-registered",
            "session-left" -> {
                pairingEvent(message)
                when (message.optString("type")) {
                    "trusted-device" -> status("Trusted device saved")
                    "reconnect-request" -> status("Trusted device requests reconnection")
                    "reconnect-pending" -> status("Waiting for reconnection approval")
                    "reconnect-approved" -> status("Reconnection approved")
                    "reconnect-rejected" -> status("Reconnection rejected")
                    "reconnect-failed" -> status("Reconnection failed: ${message.optString("reason")}")
                }
            }

            "pairing-pending" -> {
                pairingEvent(message)
                status("Waiting for the creator to approve pairing")
            }

            "pairing-request-cancelled" -> {
                pairingEvent(message)
                status("Pairing request cancelled")
            }

            "pairing-approved" -> {
                pairingEvent(message)
                status("Pairing approved; preparing secure connection")
            }

            "pairing-rejected" -> {
                pairingEvent(message)
                status("Pairing was rejected")
            }

            "joined" -> {
                initiator = !message.optBoolean("peerPresent", false)
                status(
                    if (initiator) "Session joined; waiting for peer"
                    else "Session joined; peer is present"
                )
                pairingEvent(message)
            }

            "peer-joined" -> {
                status("Peer joined; preparing WebRTC")
                pairingEvent(message)
                if (initiator) createPeerAndOffer() else createPeer()
            }

            "offer" -> handleOffer(message)

            "answer" -> handleAnswer(message)

            "ice" -> handleIce(message)

            "peer-left" -> {
                pairingEvent(message)
                status("Peer left the session")
            }

            "session-ended", "session-expired" -> {
                pairingEvent(message)
                status("Session ended: ${message.optString("reason", "expired")}")
                closePeerConnection()
            }

            "error" -> {
                pairingEvent(message)
                status(
                    "Server error (${message.optString("code")}): " +
                            message.optString("message")
                )
            }

            else -> {
                status("Unknown signaling event: ${message.optString("type")}")
            }
        }
    }

    private fun createPeer() {
        if (peer != null || closed) return

        synchronized(iceLock) {
            remoteDescriptionSet = false
            pendingIceCandidates.clear()
        }

        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.relay.metered.ca:80")
                .createIceServer(),

            PeerConnection.IceServer
                .builder("turn:global.relay.metered.ca:80")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_CREDENTIAL)
                .createIceServer(),

            PeerConnection.IceServer
                .builder("turn:global.relay.metered.ca:80?transport=tcp")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_CREDENTIAL)
                .createIceServer(),

            PeerConnection.IceServer
                .builder("turn:global.relay.metered.ca:443")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_CREDENTIAL)
                .createIceServer(),

            PeerConnection.IceServer
                .builder("turns:global.relay.metered.ca:443?transport=tcp")
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_CREDENTIAL)
                .createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers)

        peer = factory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onSignalingChange(
                    state: PeerConnection.SignalingState
                ) {
                    status("WebRTC signaling: $state")
                }

                override fun onIceConnectionReceivingChange(
                    receiving: Boolean
                ) = Unit

                override fun onIceConnectionChange(
                    state: PeerConnection.IceConnectionState
                ) {
                    status("ICE connection: $state")
                }

                override fun onIceGatheringChange(
                    state: PeerConnection.IceGatheringState
                ) {
                    status("ICE gathering: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    val candidateType = when {
                        candidate.sdp.contains(" typ relay") -> "relay"
                        candidate.sdp.contains(" typ srflx") -> "srflx"
                        candidate.sdp.contains(" typ host") -> "host"
                        else -> "unknown"
                    }

                    status("Local ICE candidate: $candidateType")

                    val data = JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)

                    sendSignal("ice", data)
                }

                override fun onIceCandidatesRemoved(
                    candidates: Array<out IceCandidate>
                ) = Unit

                override fun onAddStream(stream: MediaStream) = Unit

                override fun onRemoveStream(stream: MediaStream) = Unit

                override fun onDataChannel(dataChannel: DataChannel) {
                    attachChannel(dataChannel)
                }

                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(
                    receiver: RtpReceiver,
                    streams: Array<out MediaStream>
                ) {
                    val track = receiver.track()
                    if (track is VideoTrack) {
                        // Always enable the received track. A transient call-state race must
                        // not leave the remote camera permanently disabled.
                        remoteVideoTrack?.let { previous ->
                            try { remoteRenderer?.let { previous.removeSink(it) } } catch (_: Exception) {}
                        }
                        remoteVideoTrack = track
                        track.setEnabled(true)
                        try { remoteRenderer?.let { track.addSink(it) } } catch (e: Exception) {
                            status("Could not attach remote video renderer: ${e.message}")
                        }
                        status("Remote video track received")
                        mainHandler.post { onRemoteVideo(track) }
                    }
                }

                override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack) {
                        // Always enable the received track. A transient call-state race must
                        // not leave the remote camera permanently disabled.
                        remoteVideoTrack?.let { previous ->
                            try { remoteRenderer?.let { previous.removeSink(it) } } catch (_: Exception) {}
                        }
                        remoteVideoTrack = track
                        track.setEnabled(true)
                        try { remoteRenderer?.let { track.addSink(it) } } catch (e: Exception) {
                            status("Could not attach remote video renderer: ${e.message}")
                        }
                        status("Remote video track received")
                        mainHandler.post { onRemoteVideo(track) }
                    }
                }
            }
        )

        status(
            if (peer == null) "Failed to create PeerConnection"
            else "PeerConnection created"
        )
    }

    private fun createPeerAndOffer() {
        createPeer()

        val currentPeer = peer ?: return

        if (channel == null) {
            val dataChannel = currentPeer.createDataChannel(
                "chat",
                DataChannel.Init()
            )

            if (dataChannel == null) {
                status("Failed to create DataChannel")
                return
            }

            attachChannel(dataChannel)
        }

        currentPeer.createOffer(
            object : SdpObserver {

                override fun onCreateSuccess(sdp: SessionDescription) {
                    currentPeer.setLocalDescription(
                        object : SdpObserver {

                            override fun onSetSuccess() {
                                status("Offer created; sending to peer")

                                sendSignal(
                                    "offer",
                                    JSONObject().put("sdp", sdp.description)
                                )
                            }

                            override fun onSetFailure(error: String) {
                                status("Set local offer failed: $error")
                            }

                            override fun onCreateSuccess(
                                sdp: SessionDescription
                            ) = Unit

                            override fun onCreateFailure(error: String) = Unit
                        },
                        sdp
                    )
                }

                override fun onCreateFailure(error: String) {
                    status("Create offer failed: $error")
                }

                override fun onSetSuccess() = Unit

                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints()
        )
    }

    private fun handleOffer(message: JSONObject) {
        createPeer()

        val currentPeer = peer ?: run {
            status("Cannot receive offer: PeerConnection missing")
            return
        }

        val offerSdp = message.getJSONObject("data").getString("sdp")
        status("Remote offer received: audio=${offerSdp.contains("m=audio")} video=${offerSdp.contains("m=video")}")
        val sdp = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        currentPeer.setRemoteDescription(
            object : SdpObserver {

                override fun onSetSuccess() {
                    flushPendingIceCandidates()
                    status("Offer received; creating answer")

                    currentPeer.createAnswer(
                        object : SdpObserver {

                            override fun onCreateSuccess(
                                answer: SessionDescription
                            ) {
                                status("Call answer created: audio=${answer.description.contains("m=audio")} video=${answer.description.contains("m=video")}")
                                currentPeer.setLocalDescription(
                                    object : SdpObserver {

                                        override fun onSetSuccess() {
                                            sendSignal(
                                                "answer",
                                                JSONObject().put(
                                                    "sdp",
                                                    answer.description
                                                )
                                            )

                                            status("Answer sent")
                                        }

                                        override fun onSetFailure(error: String) {
                                            status("Set local answer failed: $error")
                                        }

                                        override fun onCreateSuccess(
                                            sdp: SessionDescription
                                        ) = Unit

                                        override fun onCreateFailure(
                                            error: String
                                        ) = Unit
                                    },
                                    answer
                                )
                            }

                            override fun onCreateFailure(error: String) {
                                status("Create answer failed: $error")
                            }

                            override fun onSetSuccess() = Unit

                            override fun onSetFailure(error: String) = Unit
                        },
                        MediaConstraints()
                    )
                }

                override fun onSetFailure(error: String) {
                    status("Set remote offer failed: $error")
                }

                override fun onCreateSuccess(sdp: SessionDescription) = Unit

                override fun onCreateFailure(error: String) = Unit
            },
            sdp
        )
    }

    private fun handleAnswer(message: JSONObject) {
        val currentPeer = peer ?: run {
            status("Answer received, but PeerConnection is missing")
            return
        }

        val sdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            message.getJSONObject("data").getString("sdp")
        )

        currentPeer.setRemoteDescription(
            object : SdpObserver {

                override fun onSetSuccess() {
                    flushPendingIceCandidates()
                    status("Remote answer accepted")
                }

                override fun onSetFailure(error: String) {
                    status("Set remote answer failed: $error")
                }

                override fun onCreateSuccess(sdp: SessionDescription) = Unit

                override fun onCreateFailure(error: String) = Unit
            },
            sdp
        )
    }

    private fun handleIce(message: JSONObject) {
        val data = message.getJSONObject("data")

        val candidate = IceCandidate(
            data.getString("sdpMid"),
            data.getInt("sdpMLineIndex"),
            data.getString("candidate")
        )

        addRemoteIceCandidate(candidate)
    }

    private fun addRemoteIceCandidate(candidate: IceCandidate) {
        synchronized(iceLock) {
            if (!remoteDescriptionSet) {
                pendingIceCandidates.add(candidate)
                return
            }

            val accepted = peer?.addIceCandidate(candidate) ?: false
            status("Remote ICE candidate accepted: $accepted")
        }
    }

    private fun flushPendingIceCandidates() {
        synchronized(iceLock) {
            remoteDescriptionSet = true

            val candidates = pendingIceCandidates.toList()
            pendingIceCandidates.clear()

            candidates.forEach { candidate ->
                val accepted = peer?.addIceCandidate(candidate) ?: false
                status("Queued ICE candidate accepted: $accepted")
            }
        }
    }

    private fun attachChannel(dataChannel: DataChannel) {
        channel?.unregisterObserver()
        channel = dataChannel

        dataChannel.registerObserver(
            object : DataChannel.Observer {

                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    status("DataChannel: ${dataChannel.state()}")
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (!buffer.binary) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)

                        val text = String(
                            bytes,
                            StandardCharsets.UTF_8
                        )

                        if (text.startsWith("GLSYS:")) handleControl(text)
                        else messageReceived(text, false)
                    }
                }
            }
        )

        status("DataChannel created")
    }

    private fun featureEvent(event: JSONObject) { mainHandler.post { onFeatureEvent(event) } }

    private fun sendControl(event: JSONObject): Boolean {
        val active = channel ?: return false
        if (active.state() != DataChannel.State.OPEN) return false
        val bytes = ByteBuffer.wrap(("GLSYS:" + event.toString()).toByteArray(StandardCharsets.UTF_8))
        return active.send(DataChannel.Buffer(bytes, false))
    }

    fun startCall(video: Boolean): Boolean {
        if (callState != "idle") {
            status("A call is already in progress")
            return false
        }
        if (channel?.state() != DataChannel.State.OPEN) {
            status("Connect to the paired device before calling")
            return false
        }
        currentCallVideo = video
        callState = "outgoing"
        status("Call state: outgoing; video=$video")
        val sent = sendControl(JSONObject().put("kind", "call-invite").put("video", video))
        if (!sent) callState = "idle"
        return sent
    }

    fun acceptCall(video: Boolean) {
        if (callState != "incoming") {
            status("There is no pending incoming call")
            return
        }
        currentCallVideo = video
        status("Call state: accepting incoming call; video=$video")
        if (!createLocalMedia(video)) {
            callState = "idle"
            sendControl(JSONObject().put("kind", "call-rejected"))
            featureEvent(JSONObject().put("kind", "call-error").put("message", "Could not start microphone/camera"))
            return
        }
        callState = "active"
        status("Call state: active on accepting device; video=$video")
        try { peer?.receivers?.forEach { receiver -> receiver.track()?.setEnabled(true) } } catch (_: Exception) {}
        sendControl(JSONObject().put("kind", "call-accepted").put("video", video))
        featureEvent(JSONObject().put("kind", "call-active").put("video", video))
    }

    fun rejectCall() {
        sendControl(JSONObject().put("kind", "call-rejected"))
        cleanupCall(notifyPeer = false)
        featureEvent(JSONObject().put("kind", "call-ended"))
    }

    fun endCall() {
        if (callState == "idle") return
        // Notify the peer before removing media, then immediately disable/remove local tracks.
        sendControl(JSONObject().put("kind", "call-ended"))
        cleanupCall(notifyPeer = false)
        featureEvent(JSONObject().put("kind", "call-ended"))
    }

    private fun cleanupCall(notifyPeer: Boolean) {
        if (notifyPeer) sendControl(JSONObject().put("kind", "call-ended"))
        status("Call state: cleanup -> idle (previous state=$callState)")
        callState = "idle"
        stopLocalMedia()
        // Silence inbound media immediately as well. This prevents audio/video from
        // continuing to render during the short interval before the peer processes hang-up.
        try {
            peer?.receivers?.forEach { receiver -> receiver.track()?.setEnabled(false) }
        } catch (_: Exception) {}
        remoteVideoTrack?.let { track ->
            try { remoteRenderer?.let { track.removeSink(it) } } catch (_: Exception) {}
            mainHandler.post {
                try { onRemoteVideo(null) } catch (_: Exception) {}
            }
        }
        remoteVideoTrack = null
        currentCallVideo = false
        microphoneMuted = false
    }
    fun setMicrophoneMuted(muted: Boolean) { microphoneMuted = muted; audioTrack?.setEnabled(!muted) }
    fun setCameraEnabled(enabled: Boolean) { videoTrack?.setEnabled(enabled) }
    fun switchCamera() { cameraCapturer?.switchCamera(null) }

    private fun createLocalMedia(video: Boolean): Boolean {
        val f = factory ?: return false
        if (audioTrack == null) {
            audioSource = f.createAudioSource(MediaConstraints())
            audioTrack = f.createAudioTrack("ghostlink-audio", audioSource)
            peer?.addTrack(audioTrack, listOf("ghostlink-stream"))
        }
        if (video && videoTrack == null) {
            try {
                val enumerator = Camera1Enumerator(false)
                val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull() ?: return false
                cameraCapturer = enumerator.createCapturer(cameraName, null)
                val capturer = cameraCapturer ?: return false
                textureHelper = SurfaceTextureHelper.create("GhostLinkCamera", eglBase.eglBaseContext)
                videoSource = f.createVideoSource(capturer.isScreencast)
                capturer.initialize(textureHelper, appContext, videoSource?.capturerObserver)
                capturer.startCapture(640, 480, 24)
                videoTrack = f.createVideoTrack("ghostlink-video", videoSource)
                videoTrack?.setEnabled(true)
                videoTrack?.let { track ->
                    peer?.addTrack(track, listOf("ghostlink-stream"))
                    try { localRenderer?.let { track.addSink(it) } } catch (e: Exception) {
                        status("Could not attach local video renderer: ${e.message}")
                    }
                    mainHandler.post { onLocalVideo(track) }
                }
            } catch (e: Exception) { status("Camera start failed: ${e.message}"); return false }
        }
        return true
    }

    private fun createRenegotiationOffer() {
        val p = peer ?: return
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val hasVideo = sdp.description.contains("m=video")
                val hasAudio = sdp.description.contains("m=audio")
                status("Call SDP created: audio=$hasAudio video=$hasVideo")
                p.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal("offer", JSONObject().put("sdp", sdp.description))
                    }
                    override fun onSetFailure(error: String) { status("Call offer failed: $error") }
                    override fun onCreateSuccess(sdp: SessionDescription) = Unit
                    override fun onCreateFailure(error: String) = Unit
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { status("Call offer failed: $error") }
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private fun stopLocalMedia() {
        // Disable and detach tracks first; disposing a track alone can leave an RTP sender
        // attached to the live PeerConnection on some WebRTC builds.
        try { audioTrack?.setEnabled(false) } catch (_: Exception) {}
        try { videoTrack?.setEnabled(false) } catch (_: Exception) {}
        try {
            peer?.senders?.forEach { sender ->
                val kind = sender.track()?.kind()
                if (kind == "audio" || kind == "video") sender.setTrack(null, false)
            }
        } catch (_: Exception) {}
        try { cameraCapturer?.stopCapture() } catch (_: Exception) {}
        cameraCapturer?.dispose(); cameraCapturer = null
        videoTrack?.let { track ->
            try { localRenderer?.let { track.removeSink(it) } } catch (_: Exception) {}
            mainHandler.post { onLocalVideo(null) }
            try { track.dispose() } catch (_: Exception) {}
        }
        videoTrack = null
        videoSource?.dispose(); videoSource = null
        textureHelper?.dispose(); textureHelper = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        microphoneMuted = false
    }

    fun sendFile(name: String, mime: String, bytes: ByteArray): Boolean {
        val active = channel ?: run { status("File send failed: data channel missing"); return false }
        if (active.state() != DataChannel.State.OPEN || bytes.isEmpty()) {
            status("File send failed: data channel=${active.state()}, bytes=${bytes.size}")
            return false
        }
        if (bytes.size > 90 * 1024 * 1024) { status("File is too large for this MVP (maximum 90 MB)"); return false }
        Thread {
            try {
                val id = UUID.randomUUID().toString()
                val chunkSize = 18 * 1024
                val count = (bytes.size + chunkSize - 1) / chunkSize
                if (!sendControl(JSONObject().put("kind", "file-start").put("id", id).put("name", name.take(160)).put("mime", mime).put("size", bytes.size).put("chunks", count))) throw IllegalStateException("Could not start transfer")
                for (i in 0 until count) {
                    // Keep the SCTP send buffer bounded instead of flooding a large file into RAM.
                    while (!closed && active.state() == DataChannel.State.OPEN && active.bufferedAmount() > 256 * 1024) Thread.sleep(10)
                    if (closed || active.state() != DataChannel.State.OPEN) throw IllegalStateException("Connection lost")
                    val start = i * chunkSize; val end = minOf(bytes.size, start + chunkSize)
                    val part = android.util.Base64.encodeToString(bytes.copyOfRange(start, end), android.util.Base64.NO_WRAP)
                    if (!sendControl(JSONObject().put("kind", "file-chunk").put("id", id).put("index", i).put("data", part))) throw IllegalStateException("Transfer interrupted")
                    if (i % 20 == 0) status("Sending $name: ${((i + 1) * 100 / count).coerceAtMost(100)}%")
                }
                sendControl(JSONObject().put("kind", "file-end").put("id", id))
                featureEvent(JSONObject().put("kind", "file-sent").put("name", name).put("size", bytes.size))
            } catch (e: Exception) { featureEvent(JSONObject().put("kind", "file-error").put("message", e.message ?: "File transfer failed")) }
        }.start()
        return true
    }

    private fun handleControl(text: String) {
        try {
            val e = JSONObject(text.removePrefix("GLSYS:"))
            when (e.optString("kind")) {
                "call-invite" -> {
                    if (callState != "idle") {
                        status("Call invite rejected as busy; current state=$callState")
                        sendControl(JSONObject().put("kind", "call-busy"))
                    } else {
                        currentCallVideo = e.optBoolean("video")
                        callState = "incoming"
                        status("Call state: incoming; video=$currentCallVideo")
                        featureEvent(e)
                    }
                }
                "call-accepted" -> {
                    // The caller is the only side that initiates the media renegotiation.
                    if (callState != "outgoing") {
                        status("Ignored call-accepted because state=$callState, expected outgoing")
                        return
                    }
                    currentCallVideo = e.optBoolean("video")
                    if (createLocalMedia(currentCallVideo)) {
                        callState = "active"
                        status("Call state: active on initiating device; video=$currentCallVideo")
                        try { peer?.receivers?.forEach { receiver -> receiver.track()?.setEnabled(true) } } catch (_: Exception) {}
                        featureEvent(JSONObject().put("kind", "call-active").put("video", currentCallVideo))
                        createRenegotiationOffer()
                    } else {
                        sendControl(JSONObject().put("kind", "call-ended"))
                        cleanupCall(notifyPeer = false)
                        featureEvent(JSONObject().put("kind", "call-error").put("message", "Could not start microphone/camera"))
                    }
                }
                "call-ready" -> Unit // Legacy message; renegotiation is initiated only by the caller.
                "call-busy" -> {
                    cleanupCall(notifyPeer = false)
                    featureEvent(JSONObject().put("kind", "call-busy"))
                }
                "call-rejected", "call-ended" -> {
                    cleanupCall(notifyPeer = false)
                    featureEvent(JSONObject().put("kind", "call-ended"))
                }
                "file-start" -> {
                    val count = e.optInt("chunks"); val size = e.optInt("size")
                    if (count in 1..5000 && size in 1..(90 * 1024 * 1024)) incomingTransfers[e.getString("id")] = IncomingTransfer(e.optString("name", "received-file"), e.optString("mime", "application/octet-stream"), size, arrayOfNulls(count))
                }
                "file-chunk" -> {
                    val transfer = incomingTransfers[e.getString("id")] ?: return
                    val index = e.optInt("index", -1)
                    if (index in transfer.chunks.indices && transfer.chunks[index] == null) { transfer.chunks[index] = e.getString("data"); transfer.received++; featureEvent(JSONObject().put("kind", "file-progress").put("name", transfer.name).put("received", transfer.received).put("total", transfer.total)) }
                }
                "file-end" -> {
                    val id = e.getString("id"); val transfer = incomingTransfers.remove(id) ?: return
                    if (transfer.received != transfer.chunks.size) { featureEvent(JSONObject().put("kind", "file-error").put("message", "File incomplete: ${transfer.received}/${transfer.chunks.size} chunks")); return }
                    val output = java.io.ByteArrayOutputStream(transfer.total)
                    transfer.chunks.forEach { chunk -> output.write(android.util.Base64.decode(chunk, android.util.Base64.NO_WRAP)) }
                    if (output.size() == transfer.total) mainHandler.post { onFileReceived(transfer.name, transfer.mime, output.toByteArray()) }
                }
            }
        } catch (e: Exception) { status("Feature message error: ${e.message}") }
    }

    fun initializeRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
    }
    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteVideoTrack?.let {
            it.setEnabled(true)
            it.addSink(renderer)
            status("Attached existing remote video track to renderer")
        }
    }
    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        localRenderer = renderer
        renderer.setMirror(true)
        videoTrack?.addSink(renderer)
    }

    fun sendMessage(text: String): Boolean {
        val activeChannel = channel ?: return false

        if (activeChannel.state() != DataChannel.State.OPEN) {
            return false
        }

        val bytes = ByteBuffer.wrap(
            text.toByteArray(StandardCharsets.UTF_8)
        )

        val sent = activeChannel.send(
            DataChannel.Buffer(bytes, false)
        )

        if (sent) {
            messageReceived(text, true)
        }

        return sent
    }

    private fun sendSignal(type: String, data: JSONObject) {
        sendOrQueue(
            JSONObject()
                .put("type", type)
                .put("data", data)
        )
    }

    private fun closePeerConnection() {
        synchronized(iceLock) {
            pendingIceCandidates.clear()
            remoteDescriptionSet = false
        }

        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        channel = null

        peer?.close()
        peer?.dispose()
        peer = null
        cleanupCall(notifyPeer = false)
        remoteVideoTrack?.let { mainHandler.post { onRemoteVideo(null) } }
        remoteVideoTrack = null
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true

        closePeerConnection()

        factory?.dispose()
        factory = null
        try { eglBase.release() } catch (_: Exception) {}
        factoryInitialized = false

        synchronized(sendLock) {
            pendingMessages.clear()
        }

        socket?.close(1000, "Leaving GhostLink")
        socket = null

        http.dispatcher.cancelAll()
    }
}