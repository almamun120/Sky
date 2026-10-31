package com.skychat.app.call

import android.content.Context
import android.media.AudioManager
import com.skychat.app.data.Msg
import com.skychat.app.data.MsgDao
import com.skychat.app.data.St
import com.skychat.app.net.AuthEvent
import com.skychat.app.net.ChatClient
import com.skychat.app.net.Conn
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
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
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

/** True while a group call rings or runs; a one-to-one call then answers "busy" (and the other way round). */
object GroupCallState { @Volatile var active = false }

enum class GPhase { IDLE, INCOMING, JOINED }

data class GPeer(val phone: String, val name: String, val connected: Boolean)

data class GroupCallUi(
    val phase: GPhase = GPhase.IDLE,
    val room: String = "",
    val gid: String = "",
    val groupName: String = "",
    val video: Boolean = false,
    val inviter: String = "",
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val camOff: Boolean = false,
    val route: Route = Route.EARPIECE,
    val peers: List<GPeer> = emptyList(),
    val note: String = ""
)

/**
 * Group voice / video call as a mesh: everybody is connected to everybody (at most 4 people in a call).
 * All signalling travels as end-to-end encrypted control messages between two people ("gc"); the server sees nothing new.
 *
 *  invite  the starter asks every group member            join   "I am in the room now" (to every member)
 *  here    "I am in the room, too" (answer to join)       offer / answer   the SDP, sent once all ICE candidates are gathered
 *  leave   "I left"                                       busy   "I cannot join"
 * Of two people in the room the one with the smaller phone number sends the offer, so there is never an offer from both sides.
 */
class GroupCallManager(
    private val ctx: Context,
    private val client: ChatClient,
    private val dao: MsgDao,
    private val calls: CallManager
) {
    val ui = MutableStateFlow(GroupCallUi())

    private val exec = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val MAX_PEERS = 3

    private class Link(val phone: String, val pc: PeerConnection) {
        @Volatile var video: VideoTrack? = null
        @Volatile var sink: SurfaceViewRenderer? = null
        var connected = false
        var sdpSent = false
        var wantAnswer = false
    }

    private val links = HashMap<String, Link>()
    private val roster = HashSet<String>()                 // people known to be in the room (not me)
    private var members: List<String> = emptyList()        // the group's members except me
    private val names = HashMap<String, String>()
    private var timeout: Job? = null
    private var aloneJob: Job? = null

    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: VideoCapturer? = null
    @Volatile private var localVideo: VideoTrack? = null
    @Volatile private var localSink: SurfaceViewRenderer? = null

    private val me: String get() = client.myPhone ?: ""

    // ---------------- user actions ----------------

    fun start(gid: String, groupName: String, groupMembers: List<String>, video: Boolean) = exec.execute {
        if (ui.value.phase != GPhase.IDLE || calls.ui.value.phase != Phase.IDLE || GroupCallState.active) {
            client.events.tryEmit(AuthEvent.Error("call_busy")); return@execute
        }
        if (client.state.value != Conn.Online) { client.events.tryEmit(AuthEvent.Error("no_connection")); return@execute }
        members = groupMembers.filter { it != me }
        if (members.isEmpty()) return@execute
        val room = UUID.randomUUID().toString()
        GroupCallState.active = true
        roster.clear()
        ui.value = GroupCallUi(GPhase.JOINED, room, gid, groupName, video, me, System.currentTimeMillis())
        enterRoom(video)
        members.forEach { sendTo(it, JSONObject().put("t", "invite").put("room", room).put("gid", gid).put("video", video)) }
        logLine(gid, if (video) "🎥 Group video call started" else "📞 Group voice call started")
        timeout = scope.launch { delay(60_000); exec.execute { if (ui.value.room == room && links.isEmpty()) finish("nobody joined", true) } }
    }

    /** The user taps Join on an invitation. */
    fun join() = exec.execute {
        val s = ui.value
        if (s.phase != GPhase.INCOMING) return@execute
        timeout?.cancel(); calls.stopRinging()
        ui.value = s.copy(phase = GPhase.JOINED, startedAt = System.currentTimeMillis(), inviter = me)
        enterRoom(s.video)
        members.forEach { sendTo(it, JSONObject().put("t", "join").put("room", s.room)) }
        timeout = scope.launch {
            delay(10_000)
            exec.execute { if (ui.value.room == s.room && links.isEmpty() && roster.isEmpty()) finish("nobody is here", true) }
        }
    }

    fun decline() = exec.execute {
        if (ui.value.phase != GPhase.INCOMING) return@execute
        val s = ui.value
        calls.stopRinging()
        members.forEach { if (it == s.inviter) sendTo(it, JSONObject().put("t", "busy").put("room", s.room)) }
        reset()
    }

    fun hangup() = exec.execute {
        when (ui.value.phase) {
            GPhase.JOINED -> finish("hangup", true)
            GPhase.INCOMING -> { calls.stopRinging(); reset() }
            else -> {}
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

    fun setRoute(r: Route) = exec.execute {
        AudioRoute.select(ctx, r)
        ui.update { it.copy(route = r) }
    }

    fun attachPeer(phone: String, r: SurfaceViewRenderer) { links[phone]?.let { it.sink = r; it.video?.addSink(r) } }
    fun detachPeer(phone: String, r: SurfaceViewRenderer) { links[phone]?.let { it.video?.removeSink(r); if (it.sink === r) it.sink = null } }
    fun attachLocal(r: SurfaceViewRenderer) { localSink = r; localVideo?.addSink(r) }
    fun detachLocal(r: SurfaceViewRenderer) { localVideo?.removeSink(r); if (localSink === r) localSink = null }

    // ---------------- signalling in ----------------

    /** [groupMembers] and [groupName] come from the group named in the message (empty if we do not know that group). */
    fun onSignal(from: String, j: JSONObject, groupMembers: List<String>, groupName: String, fromName: String) = exec.execute {
        names[from] = fromName
        if (Math.abs(System.currentTimeMillis() - j.optLong("ts")) > 120_000) return@execute        // old message that was queued
        val room = j.optString("room")
        val inRoom = ui.value.phase == GPhase.JOINED && ui.value.room == room
        when (j.optString("t")) {
            "invite" -> {
                val gid = j.optString("gid")
                if (!(from in groupMembers && me in groupMembers)) return@execute            // not a group we are both in
                if (ui.value.room == room) return@execute
                if (ui.value.phase != GPhase.IDLE || calls.ui.value.phase != Phase.IDLE || GroupCallState.active) {
                    sendTo(from, JSONObject().put("t", "busy").put("room", room)); return@execute
                }
                GroupCallState.active = true
                members = groupMembers.filter { it != me }
                roster.clear(); roster.add(from)
                val video = j.optBoolean("video")
                ui.value = GroupCallUi(GPhase.INCOMING, room, gid, groupName, video, from)
                calls.startRinging(groupName.ifEmpty { "Group call" }, video)
                timeout?.cancel()
                timeout = scope.launch { delay(45_000); exec.execute { if (ui.value.room == room && ui.value.phase == GPhase.INCOMING) { calls.stopRinging(); reset() } } }
            }
            "join" -> {
                if (inRoom) { roster.add(from); sendTo(from, JSONObject().put("t", "here").put("room", room)); maybeOffer(from); publish() }
                else if (ui.value.phase == GPhase.INCOMING && ui.value.room == room) roster.add(from)
            }
            "here" -> if (inRoom) { roster.add(from); maybeOffer(from); publish() }
            "offer" -> if (inRoom) onOffer(from, j.optString("sdp"))
            "answer" -> if (inRoom) links[from]?.pc?.setRemoteDescription(SdpAdapter(onFail = { exec.execute { closeLink(from) } }),
                SessionDescription(SessionDescription.Type.ANSWER, j.optString("sdp")))
            "leave" -> {
                roster.remove(from)
                if (inRoom) { closeLink(from); publish(); checkAlone() }
                else if (ui.value.phase == GPhase.INCOMING && ui.value.room == room && roster.isEmpty()) { calls.stopRinging(); reset() }
            }
            "busy" -> {}
        }
    }

    // ---------------- peer connections ----------------

    private fun enterRoom(video: Boolean) {
        client.keepAlive = true
        calls.startService()
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val r = AudioRoute.best(ctx, video)
        AudioRoute.select(ctx, r)
        ui.update { it.copy(route = r) }
        val f = calls.factory()
        audioSource = f.createAudioSource(MediaConstraints())
        localAudio = f.createAudioTrack("ga0", audioSource)
        if (video) try { startVideo(f) } catch (_: Exception) { /* no camera or permission: audio only */ }
    }

    private fun startVideo(f: org.webrtc.PeerConnectionFactory) {
        val en = Camera2Enumerator(ctx)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
        val cap = en.createCapturer(name, null) ?: return
        helper = SurfaceTextureHelper.create("gcapture", calls.eglBase.eglBaseContext)
        val src = f.createVideoSource(false)
        videoSource = src
        cap.initialize(helper, ctx, src.capturerObserver)
        cap.startCapture(480, 360, 20)               // small: the picture is sent to up to three people at once
        capturer = cap
        val track = f.createVideoTrack("gv0", src)
        localVideo = track
        localSink?.let { track.addSink(it) }
    }

    private fun createLink(peer: String): Link? {
        val cfg = PeerConnection.RTCConfiguration(calls.iceServers()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        lateinit var link: Link
        val obs = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) exec.execute { sendSdp(link) }
            }
            override fun onIceCandidate(c: IceCandidate?) {}                    // no trickle: the SDP is sent when gathering is complete
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                exec.execute {
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> { link.connected = true; publish() }
                        PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> {
                            if (links[peer] === link) { closeLink(peer); roster.remove(peer); publish(); checkAlone() }
                        }
                        else -> {}
                    }
                }
            }
            override fun onTrack(t: RtpTransceiver?) {
                val track = t?.receiver?.track() as? VideoTrack ?: return
                link.video = track
                link.sink?.let { track.addSink(it) }
                publish()
            }
        }
        val p = calls.factory().createPeerConnection(cfg, obs) ?: return null
        link = Link(peer, p)
        localAudio?.let { p.addTrack(it, listOf("gs0")) }
        localVideo?.let { p.addTrack(it, listOf("gs0")) }
        if (localVideo == null && ui.value.video) p.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))     // we can see them even without a camera
        links[peer] = link
        return link
    }

    private fun maybeOffer(peer: String) {
        if (me >= peer || links.containsKey(peer) || links.size >= MAX_PEERS) return
        val l = createLink(peer) ?: return
        l.pc.createOffer(SdpAdapter(
            onCreate = { sdp -> l.pc.setLocalDescription(SdpAdapter(), sdp); armSdpTimeout(l) },
            onFail = { exec.execute { closeLink(peer) } }
        ), MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (ui.value.video) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")) })
    }

    private fun onOffer(from: String, sdp: String) {
        if (sdp.isEmpty()) return
        val l = links[from] ?: (if (links.size < MAX_PEERS) createLink(from) else null) ?: return
        roster.add(from)
        l.wantAnswer = true
        l.pc.setRemoteDescription(SdpAdapter(
            onSet = { exec.execute {
                l.pc.createAnswer(SdpAdapter(
                    onCreate = { a -> l.pc.setLocalDescription(SdpAdapter(), a); armSdpTimeout(l) },
                    onFail = { exec.execute { closeLink(from) } }
                ), MediaConstraints())
            } },
            onFail = { exec.execute { closeLink(from) } }
        ), SessionDescription(SessionDescription.Type.OFFER, sdp))
        publish()
    }

    /** ICE gathering normally finishes within a second; do not wait for ever. */
    private fun armSdpTimeout(l: Link) { scope.launch { delay(3500); exec.execute { sendSdp(l) } } }

    private fun sendSdp(l: Link) {
        if (l.sdpSent || links[l.phone] !== l) return
        val d = l.pc.localDescription ?: return
        l.sdpSent = true
        sendTo(l.phone, JSONObject().put("t", if (l.wantAnswer) "answer" else "offer").put("room", ui.value.room).put("sdp", d.description))
    }

    private fun closeLink(peer: String) {
        val l = links.remove(peer) ?: return
        l.video?.let { v -> l.sink?.let { v.removeSink(it) } }
        try { l.pc.close(); l.pc.dispose() } catch (_: Exception) { }
    }

    private fun checkAlone() {
        aloneJob?.cancel()
        if (ui.value.phase == GPhase.JOINED && links.isEmpty() && roster.isEmpty()) {
            val room = ui.value.room
            aloneJob = scope.launch { delay(20_000); exec.execute { if (ui.value.room == room && links.isEmpty() && roster.isEmpty()) finish("everyone left", true) } }
        }
    }

    // ---------------- state, ending ----------------

    private fun publish() {
        val list = (roster + links.keys).filter { it != me }.sorted().map { GPeer(it, names[it] ?: it, links[it]?.connected == true) }
        ui.update { s -> s.copy(peers = list, startedAt = if (s.startedAt == 0L && list.any { it.connected }) System.currentTimeMillis() else s.startedAt) }
        val missing = list.filter { it.name == it.phone }
        if (missing.isNotEmpty()) scope.launch {
            missing.forEach { p -> dao.contactName(p.phone)?.let { n -> names[p.phone] = n } }
            exec.execute { if (ui.value.phase == GPhase.JOINED) ui.update { s -> s.copy(peers = s.peers.map { it.copy(name = names[it.phone] ?: it.name) }) } }
        }
    }

    private fun finish(reason: String, sendLeave: Boolean) {
        val s = ui.value
        if (s.phase == GPhase.IDLE) return
        if (sendLeave && s.phase == GPhase.JOINED) members.forEach { sendTo(it, JSONObject().put("t", "leave").put("room", s.room)) }
        val connected = s.startedAt > 0 && links.values.any { it.connected } || s.startedAt > 0
        if (s.phase == GPhase.JOINED && s.startedAt > 0) {
            val secs = ((System.currentTimeMillis() - s.startedAt) / 1000).toInt()
            logLine(s.gid, (if (s.video) "🎥 Group video call" else "📞 Group voice call") + " · %d:%02d".format(secs / 60, secs % 60))
        }
        links.keys.toList().forEach { closeLink(it) }
        try { capturer?.stopCapture() } catch (_: Exception) { }
        capturer?.dispose(); capturer = null
        helper?.dispose(); helper = null
        localVideo?.let { v -> localSink?.let { v.removeSink(it) } }
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        localAudio = null; localVideo = null
        client.keepAlive = false
        calls.stopService()
        AudioRoute.clear(ctx); audio.mode = AudioManager.MODE_NORMAL
        reset()
    }

    private fun reset() {
        timeout?.cancel(); aloneJob?.cancel()
        roster.clear(); links.clear(); members = emptyList()
        ui.value = GroupCallUi()
        GroupCallState.active = false
    }

    private fun sendTo(peer: String, j: JSONObject) {
        client.sendGc(peer, j.put("ts", System.currentTimeMillis()).put("gid", j.optString("gid").ifEmpty { ui.value.gid }).toString())
    }

    private fun logLine(gid: String, text: String) {
        scope.launch {
            dao.insert(Msg(id = UUID.randomUUID().toString(), chat = gid, fromMe = false, body = text, ts = System.currentTimeMillis(),
                status = St.DELIVERED, seen = true, sender = me, kind = "call"))
        }
    }
}
