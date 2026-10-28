package com.skychat.app.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.skychat.app.data.Msg
import com.skychat.app.data.MsgDao
import com.skychat.app.data.St
import com.skychat.app.net.AuthEvent
import com.skychat.app.net.ChatClient
import com.skychat.app.net.Conn
import com.skychat.app.push.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.Executors

enum class Phase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

data class CallUi(
    val phase: Phase = Phase.IDLE,
    val peer: String = "",
    val name: String = "",
    val video: Boolean = false,
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val camOff: Boolean = false,
    val speaker: Boolean = false
)

private class SdpAdapter(
    private val onCreate: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
    private val onFail: (String) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) = onCreate(d)
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(e: String?) = onFail(e ?: "create failed")
    override fun onSetFailure(e: String?) = onFail(e ?: "set failed")
}

/**
 * One call at a time. Signalling goes through the chat WebSocket; media is peer-to-peer
 * (or relayed by TURN). All state changes run on a single thread (`exec`).
 */
class CallManager(
    private val ctx: Context,
    private val client: ChatClient,
    private val dao: MsgDao,
    private val notifier: Notifier
) {
    val ui = MutableStateFlow(CallUi())
    val eglBase: EglBase by lazy { EglBase.create() }

    private val exec = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var localAudio: AudioTrack? = null
    @Volatile private var localVideo: VideoTrack? = null
    @Volatile private var remoteVideo: VideoTrack? = null
    @Volatile private var localSink: SurfaceViewRenderer? = null
    @Volatile private var remoteSink: SurfaceViewRenderer? = null

    private var cid = ""
    private var incomingOffer: String? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var outgoing = false
    private var wasConnected = false
    private var timeout: Job? = null
    private var ringtone: Ringtone? = null

    // ---------------- public API ----------------

    fun start(peer: String, video: Boolean) = exec.execute {
        if (ui.value.phase != Phase.IDLE) return@execute
        if (client.state.value != Conn.Online) {
            client.events.tryEmit(AuthEvent.Error("no_connection")); return@execute
        }
        cid = UUID.randomUUID().toString()
        outgoing = true; wasConnected = false; resetNegotiation()
        ui.value = CallUi(Phase.OUTGOING, peer, "", video, speaker = video)
        scope.launch {
            dao.contactName(peer)?.let { n -> ui.update { if (it.peer == peer) it.copy(name = n) else it } }
        }
        client.keepAlive = true
        startService()
        setupAudio(video)
        createPc(video)
        pc?.createOffer(SdpAdapter(
            onCreate = { sdp ->
                pc?.setLocalDescription(SdpAdapter(), sdp)
                val myCid = cid
                scope.launch {
                    val sealed = client.sealSdp(peer, sdp.description)
                    if (sealed == null) {
                        client.events.tryEmit(AuthEvent.Error("no_keys"))
                        exec.execute { if (cid == myCid) finish("error", false) }
                    } else {
                        client.sendJson(JSONObject().put("type", "call").put("cid", myCid).put("to", peer)
                            .put("video", video).put("sct", sealed.first).put("sctype", sealed.second))
                    }
                }
            },
            onFail = { exec.execute { finish("error", true) } }
        ), MediaConstraints())
        armTimeout(45_000, "no_answer")
    }

    fun accept() = exec.execute {
        val s = ui.value
        if (s.phase != Phase.INCOMING) return@execute
        stopRinging(); timeout?.cancel()
        ui.value = s.copy(phase = Phase.CONNECTING)
        startService()
        setupAudio(s.video)
        createPc(s.video)
        val offer = incomingOffer
        if (offer == null || pc == null) { finish("error", true); return@execute }
        pc?.setRemoteDescription(SdpAdapter(
            onSet = {
                exec.execute {
                    remoteSet = true; flushIce()
                    pc?.createAnswer(SdpAdapter(
                        onCreate = { sdp ->
                            pc?.setLocalDescription(SdpAdapter(), sdp)
                            val myCid = cid
                            scope.launch {
                                val sealed = client.sealSdp(s.peer, sdp.description)
                                if (sealed == null) exec.execute { if (cid == myCid) finish("error", true) }
                                else client.sendJson(JSONObject().put("type", "call_answer").put("cid", myCid)
                                    .put("to", s.peer).put("sct", sealed.first).put("sctype", sealed.second))
                            }
                        },
                        onFail = { exec.execute { finish("error", true) } }
                    ), MediaConstraints())
                }
            },
            onFail = { exec.execute { finish("error", true) } }
        ), SessionDescription(SessionDescription.Type.OFFER, offer))
        armTimeout(30_000, "failed")
    }

    fun reject() = exec.execute { if (ui.value.phase == Phase.INCOMING) finish("declined", true) }

    fun hangup() = exec.execute {
        when (ui.value.phase) {
            Phase.IDLE -> {}
            Phase.INCOMING -> finish("declined", true)
            else -> finish("hangup", true)
        }
    }

    fun toggleMute() = exec.execute {
        val m = !ui.value.muted
        localAudio?.setEnabled(!m)
        ui.update { it.copy(muted = m) }
    }

    fun toggleCamera() = exec.execute {
        val off = !ui.value.camOff
        localVideo?.setEnabled(!off)
        ui.update { it.copy(camOff = off) }
    }

    fun switchCamera() = exec.execute { (capturer as? CameraVideoCapturer)?.switchCamera(null) }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() = exec.execute {
        val on = !ui.value.speaker
        audio.isSpeakerphoneOn = on
        ui.update { it.copy(speaker = on) }
    }

    fun attachRemote(r: SurfaceViewRenderer) { remoteSink = r; remoteVideo?.addSink(r) }
    fun detachRemote(r: SurfaceViewRenderer) { remoteVideo?.removeSink(r); if (remoteSink === r) remoteSink = null }
    fun attachLocal(r: SurfaceViewRenderer) { localSink = r; localVideo?.addSink(r) }
    fun detachLocal(r: SurfaceViewRenderer) { localVideo?.removeSink(r); if (localSink === r) localSink = null }

    // ---------------- signalling in ----------------

    fun onSignal(o: JSONObject) = exec.execute {
        val c = o.optString("cid")
        when (o.getString("type")) {
            "call_in" -> {
                if (c == cid) return@execute                       // duplicate delivery
                val from = o.getString("from")
                if (ui.value.phase != Phase.IDLE) {
                    client.sendJson(endJson(c, from, "busy")); return@execute
                }
                // The offer must open with our Signal session, otherwise it is not from who it claims.
                val offer = client.openSdp(from, o.optString("sct"), o.optInt("sctype"))
                if (offer == null) {
                    client.events.tryEmit(AuthEvent.Error("call_unverified"))
                    client.sendJson(endJson(c, from, "error")); return@execute
                }
                cid = c; outgoing = false; wasConnected = false; resetNegotiation()
                incomingOffer = offer
                val video = o.optBoolean("video")
                val name = o.optString("name").ifEmpty { from }
                ui.value = CallUi(Phase.INCOMING, from, name, video, speaker = video)
                client.keepAlive = true
                startRinging(name, video)
                armTimeout(45_000, "missed")
            }
            "call_answered" -> {
                if (c != cid || !outgoing || ui.value.phase != Phase.OUTGOING) return@execute
                val answer = client.openSdp(ui.value.peer, o.optString("sct"), o.optInt("sctype"))
                if (answer == null) {
                    client.events.tryEmit(AuthEvent.Error("call_unverified"))
                    finish("error", true); return@execute
                }
                timeout?.cancel()
                ui.update { it.copy(phase = Phase.CONNECTING) }
                pc?.setRemoteDescription(SdpAdapter(
                    onSet = { exec.execute { remoteSet = true; flushIce() } },
                    onFail = { exec.execute { finish("error", true) } }
                ), SessionDescription(SessionDescription.Type.ANSWER, answer))
                armTimeout(30_000, "failed")
            }
            "call_ice" -> {
                if (c != cid) return@execute
                val j = o.optJSONObject("cand") ?: return@execute
                val cand = IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate"))
                if (remoteSet) pc?.addIceCandidate(cand) else pendingIce.add(cand)
            }
            "call_end" -> if (c == cid) finish(o.optString("reason"), false)
        }
    }

    fun onCallError(c: String, code: String) = exec.execute {
        if (c == cid) {
            client.events.tryEmit(AuthEvent.Error(code))
            finish(code, false)
        }
    }

    // ---------------- WebRTC ----------------

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val out = ArrayList<PeerConnection.IceServer>()
        val arr = client.iceJson
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val urls = s.getJSONArray("urls")
                val list = ArrayList<String>()
                for (j in 0 until urls.length()) list.add(urls.getString(j))
                val b = PeerConnection.IceServer.builder(list)
                if (s.has("username")) b.setUsername(s.getString("username")).setPassword(s.getString("credential"))
                out.add(b.createIceServer())
            }
        }
        if (out.isEmpty()) out.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        return out
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onIceCandidate(c: IceCandidate) {
            client.sendJson(JSONObject().put("type", "call_ice").put("cid", cid).put("to", ui.value.peer)
                .put("cand", JSONObject().put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)))
        }

        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> exec.execute {
                    if (ui.value.phase == Phase.CONNECTING) {
                        wasConnected = true
                        timeout?.cancel()
                        ui.update { it.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis()) }
                    }
                }
                PeerConnection.PeerConnectionState.FAILED -> exec.execute { finish("failed", true) }
                else -> {}
            }
        }

        override fun onTrack(t: RtpTransceiver?) {
            val track = t?.receiver?.track()
            if (track is VideoTrack) {
                remoteVideo = track
                remoteSink?.let { track.addSink(it) }
            }
        }
    }

    private fun createPc(video: Boolean) {
        val f = factory()
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val p = f.createPeerConnection(cfg, observer)
        if (p == null) { finish("error", true); return }
        pc = p
        audioSource = f.createAudioSource(MediaConstraints())
        localAudio = f.createAudioTrack("a0", audioSource)
        p.addTrack(localAudio, listOf("s0"))
        if (video) {
            try { startVideo(f, p) } catch (e: Exception) { /* no camera/permission: audio only */ }
        }
    }

    private fun startVideo(f: PeerConnectionFactory, p: PeerConnection) {
        val en = Camera2Enumerator(ctx)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
        val cap = en.createCapturer(name, null) ?: return
        helper = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
        val src = f.createVideoSource(false)
        videoSource = src
        cap.initialize(helper, ctx, src.capturerObserver)
        cap.startCapture(640, 480, 24)
        capturer = cap
        val track = f.createVideoTrack("v0", src)
        localVideo = track
        localSink?.let { track.addSink(it) }
        p.addTrack(track, listOf("s0"))
    }

    private fun flushIce() {
        pendingIce.forEach { pc?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun resetNegotiation() { pendingIce.clear(); remoteSet = false }

    private fun releaseMedia() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        helper?.dispose(); helper = null
        localVideo?.let { v -> localSink?.let { v.removeSink(it) } }
        remoteVideo?.let { v -> remoteSink?.let { v.removeSink(it) } }
        pc?.close(); pc?.dispose(); pc = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        localAudio = null; localVideo = null; remoteVideo = null
    }

    // ---------------- ending ----------------

    private fun finish(reason: String, sendEnd: Boolean) {
        val s = ui.value
        if (s.phase == Phase.IDLE) return
        if (sendEnd) client.sendJson(endJson(cid, s.peer, reason))
        timeout?.cancel()
        stopRinging()
        logCall(s, reason)
        releaseMedia()
        client.keepAlive = false
        stopService()
        resetAudio()
        ui.value = CallUi()
        cid = ""; incomingOffer = null
        resetNegotiation()
    }

    private fun endJson(c: String, to: String, reason: String) =
        JSONObject().put("type", "call_end").put("cid", c).put("to", to).put("reason", reason)

    private fun armTimeout(ms: Long, reason: String) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(ms)
            exec.execute {
                val p = ui.value.phase
                if (p != Phase.IDLE && p != Phase.ACTIVE) finish(reason, true)
            }
        }
    }

    /** Adds a line like "📞 Voice call · 1:23" or "· Missed" to the chat. */
    private fun logCall(s: CallUi, reason: String) {
        val kind = if (s.video) "🎥 Video call" else "📞 Voice call"
        val secs = if (wasConnected) ((System.currentTimeMillis() - s.startedAt) / 1000).toInt() else -1
        val tail = when {
            secs >= 0 -> " · %d:%02d".format(secs / 60, secs % 60)
            reason == "declined" -> " · Declined"
            reason == "busy" -> " · Busy"
            outgoing -> " · No answer"
            else -> " · Missed"
        }
        val now = System.currentTimeMillis()
        scope.launch {
            dao.insert(Msg(id = UUID.randomUUID().toString(), chat = s.peer, fromMe = false,
                body = kind + tail, ts = now, status = St.DELIVERED, seen = true,
                sender = s.peer, kind = "call"))
        }
    }

    // ---------------- audio / ringing / service ----------------

    @Suppress("DEPRECATION")
    private fun setupAudio(video: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = video
    }

    @Suppress("DEPRECATION")
    private fun resetAudio() {
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun startRinging(name: String, video: Boolean) {
        if (client.foreground) {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply { if (Build.VERSION.SDK_INT >= 28) isLooping = true; play() }
        } else {
            notifier.showIncomingCall(name, video)
        }
    }

    private fun stopRinging() {
        ringtone?.stop(); ringtone = null
        notifier.cancelIncomingCall()
    }

    private fun startService() {
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) } catch (_: Exception) {}
    }

    private fun stopService() {
        try { ctx.stopService(Intent(ctx, CallService::class.java)) } catch (_: Exception) {}
    }
}
