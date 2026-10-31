package com.skychat.app.net

import com.skychat.app.data.*
import com.skychat.app.push.Notifier
import com.skychat.app.push.PushService
import com.skychat.app.call.CallManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import android.util.Base64
import okio.ByteString
import okio.ByteString.Companion.toByteString
import com.skychat.app.crypto.*
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface AuthEvent {
    data class OtpSent(val devCode: String?) : AuthEvent
    data object LoggedIn : AuthEvent
    data class Error(val code: String) : AuthEvent
    data object PinNeeded : AuthEvent
    data class Profile(val phone: String, val name: String?, val exists: Boolean) : AuthEvent
}

enum class Conn { Offline, Connecting, Online }

/** Online status of the contact whose chat is open. [hidden] = one of the two sides turned it off. */
data class Presence(val phone: String, val online: Boolean, val lastSeen: Long?, val hidden: Boolean)

private fun js(vararg p: Pair<String, Any?>): JSONObject =
    JSONObject().apply { p.forEach { put(it.first, it.second) } }

/** Owns the WebSocket, reconnects, and writes everything into Room. */
class ChatClient(
    private val ctx: Context, private val prefs: Prefs, private val dao: MsgDao,
    private val media: MediaVault
) {

    /** Encrypted attachments (see [MediaVault]). Never write plaintext here. */
    val mediaDir: File get() = media.dir
    /** Plaintext scratch space, emptied at every app start. */
    val tmpDir: File get() = media.tmpDir
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxUpload = 25L * 1024 * 1024
    private val MAX_PINNED = 3
    private val DEFAULT_ABOUT = "Hey there! I am using Sky."

    // One thread for all frame handling keeps message order intact.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()

    private var ws: WebSocket? = null
    private var connectedUrl: String? = null
    @Volatile private var open = false
    @Volatile private var authBusy = false
    @Volatile private var firstFrame: JSONObject? = null
    /** true = this connection speaks MessagePack (binary frames), false = JSON text. Set when it opens. */
    @Volatile private var binary = false
    private val debuggable: Boolean get() = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    @Volatile private var pendingPhone: String? = null
    @Volatile private var wantOnline = false
    private var retry = 0
    private var reconnectJob: Job? = null

    val state = MutableStateFlow(Conn.Offline)
    val events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val openChat = MutableStateFlow<String?>(null)
    val loggedIn = MutableStateFlow(prefs.token != null)

    // ---- typing and online status ----
    /** chat id -> (sender phone -> time until which "typing" is shown). Group chats key on the group id. */
    val typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val presence = MutableStateFlow<Presence?>(null)
    @Volatile private var watching: String? = null
    private var lastTypingSent = 0L

    @Volatile var foreground = false      // set by MainActivity
    var notifier: Notifier? = null
    private var idleJob: Job? = null

    var calls: CallManager? = null
    val myPhone: String? get() = prefs.phone
    @Volatile var groupCalls: com.skychat.app.call.GroupCallManager? = null
    @Volatile var keepAlive = false            // true during a call: no idle disconnect
    @Volatile var iceJson: org.json.JSONArray? = null

    fun sendJson(o: JSONObject): Boolean = ws?.let { sendFrame(it, o) } ?: false

    private fun sendFrame(w: WebSocket, o: JSONObject): Boolean =
        if (binary) w.send(MsgPack.encode(o).toByteString()) else w.send(o.toString())

    // ---- end-to-end encryption state ----
    private val vault = Vault()
    private var crypto: SignalCrypto? = null
    private val awaiting = LinkedHashMap<String, Msg>()     // messages waiting for a peer's keys
    private val requested = HashSet<String>()               // peers whose keys we already asked for
    private val keyWaiters = HashMap<String, CompletableDeferred<Boolean>>()   // calls waiting for a peer's keys
    @Volatile private var lastKeyUpload = 0L
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)

    // ---- connection ----

    @Synchronized
    fun start() {
        wantOnline = true
        connect()
    }

    /** Dev option: use another server address right away (also while logged in). */
    @Synchronized
    fun changeServer(url: String) {
        ServerUrl.problem(url, debuggable)?.let { events.tryEmit(AuthEvent.Error("bad_url")); return }
        prefs.serverUrl = url.trim()
        ws?.cancel(); ws = null; open = false
        state.value = Conn.Offline
        if (wantOnline) connect()
    }

    @Synchronized
    private fun connect() {
        if (ws != null) return
        val url = prefs.serverUrl
        ServerUrl.problem(url, debuggable)?.let {
            authBusy = false
            events.tryEmit(AuthEvent.Error("bad_url"))
            return
        }
        val req = try {
            Request.Builder().url(url)
                // the wire format we would like; the server picks one it allows (no answer = an old server = JSON)
                .header("Sec-WebSocket-Protocol", if (prefs.preferJson) "sky.json" else "sky.msgpack, sky.json")
                .build()
        } catch (e: IllegalArgumentException) {
            authBusy = false
            events.tryEmit(AuthEvent.Error("bad_url"))
            return
        }
        state.value = Conn.Connecting
        connectedUrl = url
        ws = http.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            binary = response.header("Sec-WebSocket-Protocol") == "sky.msgpack"
            open = true
            retry = 0
            val f = firstFrame
            firstFrame = null
            val t = prefs.token
            if (f != null) sendFrame(webSocket, f)
            else if (t != null) sendFrame(webSocket, js("type" to "auth", "token" to t))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            serial.launch { runCatching { MsgPack.decode(bytes.toByteArray())?.let { handle(it) } } }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            serial.launch { runCatching { handle(JSONObject(text)) } }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (reason == "logged_in_elsewhere") wantOnline = false
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    @Synchronized
    private fun dropped(w: WebSocket) {
        if (ws != null && w !== ws) return
        ws = null
        open = false
        state.value = Conn.Offline
        if (authBusy) {
            authBusy = false
            firstFrame = null
            events.tryEmit(AuthEvent.Error("connection_failed"))
        }
        if (wantOnline && prefs.token != null) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val wait = minOf(30L, 1L shl minOf(retry, 5))
        retry++
        reconnectJob = serial.launch {
            delay(wait * 1000)
            connect()
        }
    }

    // ---- login ----

    fun requestOtp(phone: String) {
        pendingPhone = phone
        sendAuth(js("type" to "request_otp", "phone" to phone))
    }

    val pinOnFlow = MutableStateFlow(false)

    /** The second step of a login: the two-step verification PIN. */
    fun verifyPin(pin: String) {
        authBusy = true
        wsSend(js("type" to "verify_pin", "pin" to pin))
    }

    /** Sets or changes the two-step PIN ([old] is needed when one is already set). */
    fun setPin(pin: String, old: String) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        wsSend(js("type" to "set_pin", "pin" to pin, "old" to old))
    }

    fun removePin(pin: String) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        wsSend(js("type" to "remove_pin", "pin" to pin))
    }

    fun verifyOtp(phone: String, code: String, name: String) {
        pendingPhone = phone
        prefs.name = name.trim()
        sendAuth(js("type" to "verify_otp", "phone" to phone, "code" to code, "name" to name))
    }

    @Synchronized
    private fun sendAuth(frame: JSONObject) {
        authBusy = true
        if (ws != null && connectedUrl != prefs.serverUrl) {   // server address changed
            ws?.cancel(); ws = null; open = false
        }
        val w = ws
        if (w != null && open) {
            sendFrame(w, frame)
        } else {
            firstFrame = frame
            wantOnline = true
            connect()
        }
    }

    /** Called when the push channel says something is waiting: connect, collect messages, disconnect when idle. */
    fun wake() {
        if (prefs.token == null) return
        start()
        scheduleIdleStop()
    }

    /** In the background the socket is closed after 60s of quiet; pushes wake it again. */
    fun scheduleIdleStop() {
        idleJob?.cancel()
        idleJob = serial.launch {
            delay(60_000)
            if (!foreground && !keepAlive) stop()
        }
    }

    @Synchronized
    fun stop() {
        wantOnline = false
        reconnectJob?.cancel()
        ws?.close(1000, null)
    }

    fun logout() {
        // Tell the server to revoke this session (best effort; it expires anyway).
        prefs.token?.let { t -> if (open) wsSend(js("type" to "logout", "token" to t)) }
        wantOnline = false
        prefs.clearAuth()
        PushService.stop(ctx)
        loggedIn.value = false
        ws?.close(1000, null)
        serial.launch {
            dao.deleteAll(); dao.clearReactions(); dao.deleteContacts(); dao.deleteGroups(); dao.deleteReceipts(); dao.kvClear()
            dao.clearStates(); dao.clearBlocked(); dao.clearPeerProfiles(); dao.clearStatus(); dao.clearStatusViews(); lastProfileFetch.clear()
            crypto = null; awaiting.clear(); requested.clear()
            media.clearAll()
        }
    }

    // ---- pin / archive / mute / mark unread (this phone only) ----

    private suspend fun stateOf(chat: String) = dao.chatState(chat) ?: ChatState(chat)

    /** A chat comes back to the main list when we write in it. */
    private suspend fun unarchive(chat: String) {
        dao.chatState(chat)?.let { if (it.archived) dao.putState(it.copy(archived = false)) }
    }

    fun setPinned(chat: String, on: Boolean) {
        serial.launch {
            val st = stateOf(chat)
            if (on && st.pinned == 0L && dao.pinnedCount() >= MAX_PINNED) {
                events.emit(AuthEvent.Error("pin_limit")); return@launch
            }
            // a pinned chat is never archived
            dao.putState(st.copy(pinned = if (on) System.currentTimeMillis() else 0, archived = if (on) false else st.archived))
        }
    }

    /** Archiving also unpins. A muted archived chat stays archived when new messages arrive. */
    fun setArchived(chat: String, on: Boolean) {
        serial.launch {
            val st = stateOf(chat)
            dao.putState(st.copy(archived = on, pinned = if (on) 0 else st.pinned))
        }
    }

    /** [until] = 0 unmutes, [ChatState.FOREVER] mutes for good, otherwise an end time in ms. */
    fun setMuted(chat: String, until: Long) {
        serial.launch { dao.putState(stateOf(chat).copy(muteUntil = until)) }
    }

    fun setMarkedUnread(chat: String, on: Boolean) {
        serial.launch { dao.putState(stateOf(chat).copy(markedUnread = on)) }
    }

    // ---- group admins, only-admins mode, invite links ----

    /** Invite code per group, as the server last told us (admins only). */
    val invites = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun groupFrame(vararg kv: Pair<String, Any?>) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        wsSend(js(*kv))
    }

    fun setAdmin(gid: String, phone: String, on: Boolean) =
        groupFrame("type" to "set_admin", "group" to gid, "phone" to phone, "on" to on)

    fun setAnnounce(gid: String, on: Boolean) =
        groupFrame("type" to "group_settings", "group" to gid, "announce" to on)

    fun requestInvite(gid: String, reset: Boolean = false) =
        groupFrame("type" to "invite_create", "group" to gid, "reset" to reset)

    /** [raw] is a code or a link like skychat://join/CODE. */
    fun joinInvite(raw: String) {
        val code = raw.trim().substringAfterLast('/').trim().uppercase()
        if (!Regex("[A-Z0-9]{8,64}").matches(code)) { events.tryEmit(AuthEvent.Error("bad_invite")); return }
        groupFrame("type" to "join_invite", "code" to code)
    }

    /** For links opened from outside the app: wait for the connection, then join. */
    fun joinWhenOnline(raw: String) {
        bg.launch {
            state.first { it == Conn.Online }
            joinInvite(raw)
        }
    }

    // ---- status (24-hour updates) ----
    // An update is sent to every person I chat with (not blocked) as an encrypted control message; photos are
    // encrypted with their own key, which travels inside that message. The server never sees an update.

    private val STATUS_LIFE = 24 * 3600_000L

    private suspend fun statusRecipients(): List<String> =
        dao.allChatIds().filter { !isGroup(it) && it != prefs.phone && dao.isBlocked(it) == 0 }

    private suspend fun sendCtlToAll(op: String, body: String) {
        statusRecipients().forEach {
            enqueue(Msg(UUID.randomUUID().toString(), it, true, body, System.currentTimeMillis(),
                St.PENDING, true, kind = "ctl", mime = op))
        }
    }

    private suspend fun publishStatus(s: StatusItem) {
        dao.putStatus(s)
        sendCtlToAll("status", JSONObject().put("i", s.id).put("k", s.kind).put("t", s.text).put("c", s.bg)
            .put("m", s.mid).put("x", s.key).put("ts", s.ts).toString())
    }

    fun postTextStatus(text: String, color: Int) {
        val t = text.trim().take(700)
        if (t.isEmpty()) return
        bg.launch {
            publishStatus(StatusItem(UUID.randomUUID().toString(), prefs.phone ?: return@launch, "text", t, color,
                ts = System.currentTimeMillis(), seen = true, mine = true))
        }
    }

    fun postImageStatus(uri: Uri, caption: String) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        bg.launch {
            val id = UUID.randomUUID().toString()
            val plain = File(media.tmpDir, "$id.st")
            val enc = File(ctx.cacheDir, "$id.st.enc")
            try {
                if (!prepareImage(uri, plain)) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                val key = MediaCrypto.encrypt(plain, enc)
                val mid = upload(enc, "application/octet-stream")
                if (mid == null) { events.emit(AuthEvent.Error("upload_failed")); return@launch }
                val out = File(mediaDir, "st_$id.jpg")
                if (!media.store(plain, out)) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                publishStatus(StatusItem(id, prefs.phone ?: return@launch, "image", caption.trim().take(300), 0,
                    mid = mid, key = b64(key), path = out.absolutePath, ts = System.currentTimeMillis(),
                    seen = true, mine = true))
            } finally { media.shred(plain); enc.delete() }
        }
    }

    fun deleteStatus(id: String) {
        bg.launch {
            val s = dao.statusById(id) ?: return@launch
            if (s.path.isNotEmpty()) media.shred(File(s.path))
            dao.deleteStatus(id)
            if (s.mine) sendCtlToAll("sdel", id)
        }
    }

    /** I looked at someone's update: tell the owner (once). */
    fun markStatusSeen(id: String) {
        serial.launch {
            val s = dao.statusById(id) ?: return@launch
            if (s.mine || s.seen) return@launch
            dao.markStatusSeen(id)
            enqueue(Msg(UUID.randomUUID().toString(), s.owner, true, id, System.currentTimeMillis(),
                St.PENDING, true, kind = "ctl", mime = "sview"))
        }
    }

    private suspend fun onStatus(from: String, body: String) {
        val j = try { JSONObject(body) } catch (e: Exception) { return }
        val id = j.optString("i").takeIf { it.isNotEmpty() && it.length <= 64 } ?: return
        val now = System.currentTimeMillis()
        val ts = minOf(j.optLong("ts", now), now)
        if (ts <= now - STATUS_LIFE || dao.statusById(id) != null) return
        val kind = j.optString("k")
        val text = j.optString("t").take(700)
        if (kind == "text") {
            if (text.isEmpty()) return
            dao.putStatus(StatusItem(id, from, "text", text, j.optInt("c"), ts = ts))
        } else if (kind == "image") {
            val mid = j.optString("m").takeIf { it.length == 32 } ?: return
            val path = downloadPhoto(mid, j.optString("x"), "st_$id.jpg") ?: return
            dao.putStatus(StatusItem(id, from, "image", text.take(300), 0, mid, j.optString("x"), path, ts))
        }
    }

    private suspend fun onStatusDeleted(from: String, id: String) {
        val s = dao.statusById(id) ?: return
        if (s.owner != from || s.mine) return
        if (s.path.isNotEmpty()) media.shred(File(s.path))
        dao.deleteStatus(id)
    }

    private suspend fun onStatusView(from: String, id: String) {
        if (dao.statusById(id)?.mine == true) dao.putStatusView(StatusView(id, from, System.currentTimeMillis()))
    }

    /** Updates older than 24 hours disappear (called at every login). */
    private suspend fun expireStatuses() {
        val cutoff = System.currentTimeMillis() - STATUS_LIFE
        dao.oldStatuses(cutoff).forEach { if (it.path.isNotEmpty()) media.shred(File(it.path)) }
        dao.deleteOldStatuses(cutoff)
        dao.pruneStatusViews()
    }

    // ---- friends from the phone's address book ----
    // Only short hashes of the numbers are sent (see the server README); the names stay on this phone.

    private val bookByHash = HashMap<String, Pair<String, String>>()     // hash -> (+E.164, name in the address book)

    private fun contactHash(e164: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(("sky-contact-v1:" + e164).toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    fun hasContactsPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Reads the address book, asks the server which numbers are on Sky, and saves those people as contacts. */
    fun syncContacts() {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        if (!hasContactsPermission()) return
        bg.launch {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val iso = (tm?.simCountryIso?.ifEmpty { null } ?: tm?.networkCountryIso?.ifEmpty { null }
                ?: java.util.Locale.getDefault().country.ifEmpty { null } ?: "BD").uppercase()
            val book = HashMap<String, Pair<String, String>>()
            ctx.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: continue
                    val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(number, iso) ?: continue
                    if (e164 == prefs.phone) continue
                    book.putIfAbsent(contactHash(e164), e164 to (c.getString(1) ?: ""))
                }
            }
            synchronized(bookByHash) { bookByHash.clear(); bookByHash.putAll(book) }
            prefs.lastContactSync = System.currentTimeMillis()
            book.keys.chunked(400).forEach { wsSend(js("type" to "find_contacts", "hashes" to org.json.JSONArray(it))) }
        }
    }

    private suspend fun onContactsFound(o: JSONObject) {
        val arr = o.getJSONArray("matches")
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val phone = m.getString("phone")
            val mine = synchronized(bookByHash) { bookByHash[contactHash(phone)]?.second }?.takeIf { it.isNotBlank() }
            // the name from the address book wins over the name the person chose for themselves
            dao.upsertContact(Contact(phone, mine ?: m.optString("name").ifEmpty { phone }, local = true))
        }
    }

    // ---- group calls: signalling is an end-to-end encrypted control message between two people ----

    fun sendGc(peer: String, body: String) {
        bg.launch {
            enqueue(Msg(UUID.randomUUID().toString(), peer, true, body, System.currentTimeMillis(),
                St.PENDING, true, kind = "ctl", mime = "gc"))
        }
    }

    private suspend fun onGroupCallSignal(from: String, body: String) {
        val j = try { JSONObject(body) } catch (e: Exception) { return }
        val g = j.optString("gid").takeIf { it.isNotEmpty() }?.let { dao.group(it) }
        val name = dao.contactName(from)?.ifEmpty { null } ?: from
        groupCalls?.onSignal(from, j, g?.members?.split(",")?.filter { it.isNotEmpty() } ?: emptyList(), g?.name ?: "", name)
    }

    // ---- disappearing messages, view once ----

    private val disappearChoices = setOf(0L, 86_400L, 604_800L, 7_776_000L)

    private fun disLabel(s: Long) = when { s <= 0 -> "off"; s <= 86_400 -> "24 hours"; s <= 604_800 -> "7 days"; else -> "90 days" }

    /** New messages of this chat disappear after [seconds] (0 = off). In a group only admins may change it. */
    fun setDisappear(chat: String, seconds: Long) {
        if (seconds !in disappearChoices) return
        serial.launch {
            if (isGroup(chat) && dao.group(chat)?.isAdmin(prefs.phone) != true) { events.emit(AuthEvent.Error("not_admin")); return@launch }
            val st = stateOf(chat)
            if (st.disappear == seconds) return@launch
            dao.putState(st.copy(disappear = seconds))
            info(chat, if (seconds == 0L) "You turned off disappearing messages" else "You turned on disappearing messages: ${disLabel(seconds)}")
            enqueue(Msg(UUID.randomUUID().toString(), chat, true, seconds.toString(), System.currentTimeMillis(),
                St.PENDING, true, kind = "ctl", mime = "dis"))
        }
    }

    private suspend fun onDisappearSet(from: String, chat: String, body: String) {
        val sec = body.toLongOrNull() ?: return
        if (sec !in disappearChoices) return
        if (isGroup(chat) && dao.group(chat)?.isAdmin(from) != true) return
        val st = stateOf(chat)
        if (st.disappear == sec) return
        dao.putState(st.copy(disappear = sec))
        val who = dao.contactName(from)?.ifEmpty { null } ?: from
        info(chat, if (sec == 0L) "$who turned off disappearing messages" else "$who turned on disappearing messages: ${disLabel(sec)}")
    }

    @Volatile private var housekeeping = false

    /** Deletes disappearing messages when their time has come (checked every 30 seconds while the app runs). */
    fun startHousekeeping() {
        if (housekeeping) return
        housekeeping = true
        bg.launch { while (true) { try { purgeExpired() } catch (_: Exception) { }; delay(30_000) } }
    }

    private suspend fun purgeExpired() {
        dao.expired(System.currentTimeMillis()).forEach {
            shredMedia(it.localPath); dao.deleteReactions(it.id); dao.deleteLocal(it.id)
        }
    }

    /** The receiver closed a view-once picture: delete it here and tell the sender that it was opened. */
    fun viewOnceDone(m: Msg) {
        bg.launch {
            shredMedia(m.localPath)
            dao.onceDone(m.id)
            enqueue(Msg(UUID.randomUUID().toString(), m.chat, true, m.id, System.currentTimeMillis(),
                St.PENDING, true, kind = "ctl", mime = "ovw"))
        }
    }

    // ---- block and report ----

    /** The server enforces the block; its answer (the full list) updates the local copy. */
    fun setBlocked(phone: String, on: Boolean) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        wsSend(js("type" to "block", "phone" to phone, "on" to on))
    }

    /**
     * Tells the server about [phone]. If [withMessages], the last 5 things this person sent are included
     * (this phone decrypted them; the server could not read them itself). The user is asked before this.
     */
    fun report(phone: String, reason: String, withMessages: Boolean, alsoBlock: Boolean) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        bg.launch {
            val arr = org.json.JSONArray()
            if (withMessages) dao.lastIncoming(phone, 5).reversed().forEach {
                arr.put(js("ts" to it.ts, "b" to previewText(it.kind, it.body, it.fname).take(250)))
            }
            wsSend(js("type" to "report", "phone" to phone, "reason" to reason.take(32), "msgs" to arr))
            if (alsoBlock) wsSend(js("type" to "block", "phone" to phone, "on" to true))
        }
    }

    // ---- profile: name, about, photo ----

    private val lastProfileFetch = HashMap<String, Long>()

    private fun ownJson(): JSONObject = try { JSONObject(prefs.ownProfile ?: "{}") } catch (e: Exception) { JSONObject() }

    val ownAbout: String get() = ownJson().optString("about", DEFAULT_ABOUT)
    /** Where my own photo is kept on this phone (may not exist). */
    val ownPhotoFile: File get() = File(mediaDir, "me.jpg")
    val hasOwnPhoto: Boolean get() = ownJson().optString("mid").isNotEmpty() && ownPhotoFile.exists()

    /** Creates the profile key (and a first profile) when it is needed for the first time. */
    @Synchronized
    private fun ownKey(): ByteArray {
        prefs.profileKey?.let { return unb64(it) }
        val k = ProfileCrypto.newKey()
        prefs.profileKey = b64(k)
        if (prefs.ownProfile == null) prefs.ownProfile = JSONObject().put("about", DEFAULT_ABOUT).toString()
        prefs.profileDirty = true
        return k
    }

    /** Seals my profile with my profile key and stores it on the server (the server cannot read it). */
    private fun publishProfile() {
        val key = ownKey()
        val blob = ProfileCrypto.seal(key, ownJson().toString().toByteArray(StandardCharsets.UTF_8))
        prefs.profileDirty = true
        if (open) wsSend(js("type" to "set_profile", "blob" to b64(blob)))
    }

    /** Saves name and about. The name is public (it travels with every message); the about text is sealed. */
    fun saveProfile(name: String, about: String) {
        val n = name.trim()
        val a = about.trim().take(139)
        if (n.isEmpty() || n.toByteArray(StandardCharsets.UTF_8).size > 64) { events.tryEmit(AuthEvent.Error("bad_name")); return }
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        bg.launch {
            if (n != prefs.name) { wsSend(js("type" to "set_name", "name" to n)); prefs.name = n }
            val cur = ownJson()
            if (cur.optString("about", DEFAULT_ABOUT) != a) {
                prefs.ownProfile = cur.put("about", a).toString()
                publishProfile()
            }
            events.emit(AuthEvent.Error("profile_saved"))      // shown as a "Profile updated" toast
        }
    }

    /** Photo from the gallery: cropped to a square, 512 px, encrypted with its own key, uploaded. */
    fun setOwnPhoto(uri: Uri) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        bg.launch {
            val plain = File(media.tmpDir, "avatar.in")
            val enc = File(ctx.cacheDir, "avatar.enc")
            try {
                if (!prepareAvatar(uri, plain)) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                val key = MediaCrypto.encrypt(plain, enc)
                val mid = upload(enc, "application/octet-stream")
                if (mid == null) { events.emit(AuthEvent.Error("upload_failed")); return@launch }
                if (!media.store(plain, ownPhotoFile)) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                prefs.ownProfile = ownJson().put("mid", mid).put("pk", b64(key)).toString()
                publishProfile()
                avatarVersion.update { it + 1 }
                events.emit(AuthEvent.Error("profile_saved"))
            } finally { media.shred(plain); enc.delete() }
        }
    }

    fun removeOwnPhoto() {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        bg.launch {
            prefs.ownProfile = ownJson().put("mid", "").put("pk", "").toString()
            media.shred(ownPhotoFile)
            publishProfile()
            avatarVersion.update { it + 1 }
        }
    }

    /** Bumped when my own photo file changes, so screens reload it. */
    val avatarVersion = MutableStateFlow(0)

    /** Square crop, 512 px, upright, JPEG. */
    private fun prepareAvatar(uri: Uri, out: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val orientation = ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val up = if (deg != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true) else bmp
        val side = minOf(up.width, up.height)
        val square = Bitmap.createBitmap(up, (up.width - side) / 2, (up.height - side) / 2, side, side)
        val scaled = if (side > 512) Bitmap.createScaledBitmap(square, 512, 512, true) else square
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        true
    } catch (e: Exception) { false }

    /** Gives a chat partner my profile key (once), so they can read my about text and photo. */
    private suspend fun shareProfileKey(chat: String) {
        if (isGroup(chat) || dao.isBlocked(chat) > 0) return
        val pp = dao.peerProfile(chat) ?: PeerProfile(chat)
        if (pp.keySent) return
        val key = ownKey()
        dao.putPeerProfile(pp.copy(keySent = true))
        if (prefs.profileDirty) publishProfile()
        enqueue(Msg(UUID.randomUUID().toString(), chat, true, b64(key), System.currentTimeMillis(),
            St.PENDING, true, kind = "ctl", mime = "pkey"))
    }

    private suspend fun onProfileKey(from: String, keyB64: String) {
        val ok = try { unb64(keyB64).size == 32 } catch (e: Exception) { false }
        if (!ok) return
        val pp = dao.peerProfile(from) ?: PeerProfile(from)
        dao.putPeerProfile(pp.copy(key = keyB64, fetched = 0))
        askProfile(from, force = true)
    }

    private fun askProfile(phone: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastProfileFetch[phone] ?: 0L) < 10 * 60_000L) return
        lastProfileFetch[phone] = now
        if (open) wsSend(js("type" to "get_profile", "phone" to phone))
    }

    /** Called when a chat is opened: look for a newer about text / photo (at most every 10 minutes). */
    fun refreshProfile(phone: String) {
        serial.launch { if (dao.peerProfile(phone)?.key?.isNotEmpty() == true) askProfile(phone, force = false) }
    }

    private suspend fun onProfileBlob(o: JSONObject) {
        val phone = o.getString("phone")
        if (o.isNull("blob")) return
        val pp = dao.peerProfile(phone) ?: return
        if (pp.key.isEmpty()) return
        val ts = o.optLong("ts")
        if (ts != 0L && ts == pp.fetched) return
        // Wrong key or altered blob: keep what we already show.
        val plain = ProfileCrypto.open(unb64(pp.key), unb64(o.getString("blob"))) ?: return
        val j = try { JSONObject(String(plain, StandardCharsets.UTF_8)) } catch (e: Exception) { return }
        val about = j.optString("about").take(139)
        val mid = j.optString("mid").takeIf { it.length == 32 } ?: ""
        val pk = j.optString("pk")
        var path = pp.photoPath
        if (mid != pp.photoMid) {
            if (path.isNotEmpty()) media.shred(File(path))
            path = ""
            if (mid.isNotEmpty()) {
                path = downloadPhoto(mid, pk, "pp_${phone.removePrefix("+")}_$ts.jpg") ?: ""
                if (path.isEmpty()) {          // try again at the next refresh
                    dao.putPeerProfile(pp.copy(about = about, photoMid = "", photoPath = "", fetched = 0)); return
                }
            }
        }
        dao.putPeerProfile(pp.copy(about = about, photoMid = mid, photoPath = path, fetched = ts))
    }

    /** Downloads an encrypted photo, decrypts it and stores it vault-encrypted as [fileName]. Null on any failure. */
    private suspend fun downloadPhoto(mid: String, keyB64: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val enc = File(ctx.cacheDir, "$fileName.dl")
        val plain = File(media.tmpDir, "$fileName.dec")
        try {
            val req = Request.Builder().url(httpBase() + "/media/" + mid)
                .header("Authorization", "Bearer " + prefs.token).build()
            val got = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use false
                r.body!!.byteStream().use { i -> enc.outputStream().use { o -> i.copyTo(o) } }
                true
            }
            val out = File(mediaDir, fileName)
            if (got && enc.length() in 1..(8L * 1024 * 1024) && MediaCrypto.decrypt(enc, plain, unb64(keyB64)) &&
                media.store(plain, out)) out.absolutePath else null
        } catch (e: Exception) { null } finally { enc.delete(); media.shred(plain) }
    }

    // ---- group photo and description (the owner changes them; members get an encrypted control message) ----

    /** [descr] null = leave as is; [photo] = new picture; [removePhoto] = no picture. Owner only. */
    fun setGroupInfo(gid: String, descr: String?, photo: Uri?, removePhoto: Boolean) {
        if (!open) { events.tryEmit(AuthEvent.Error("no_connection")); return }
        bg.launch {
            val g = dao.group(gid) ?: return@launch
            if (!g.isAdmin(prefs.phone)) return@launch
            val newDescr = (descr ?: g.descr).trim().take(500)
            var mid = g.photoMid; var key = g.photoKey; var path = g.photoPath
            if (photo != null) {
                val plain = File(media.tmpDir, "gphoto.in")
                val enc = File(ctx.cacheDir, "gphoto.enc")
                try {
                    if (!prepareAvatar(photo, plain)) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                    val k = MediaCrypto.encrypt(plain, enc)
                    val up = upload(enc, "application/octet-stream")
                    if (up == null) { events.emit(AuthEvent.Error("upload_failed")); return@launch }
                    val out = File(mediaDir, "gp_${gid}_${System.currentTimeMillis()}.jpg")
                    if (!media.store(plain, out)) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                    if (path.isNotEmpty()) media.shred(File(path))
                    mid = up; key = b64(k); path = out.absolutePath
                } finally { media.shred(plain); enc.delete() }
            } else if (removePhoto && mid.isNotEmpty()) {
                if (path.isNotEmpty()) media.shred(File(path))
                mid = ""; key = ""; path = ""
            }
            if (newDescr == g.descr && mid == g.photoMid) return@launch
            dao.setGroupInfo(gid, newDescr, mid, key, path)
            if (newDescr != g.descr) info(gid, if (newDescr.isEmpty()) "You removed the group description" else "You changed the group description")
            if (mid != g.photoMid) info(gid, if (mid.isEmpty()) "You removed the group photo" else "You changed the group photo")
            sendGroupInfo(gid, newDescr, mid, key)
        }
    }

    private suspend fun sendGroupInfo(gid: String, descr: String, mid: String, key: String) {
        val body = JSONObject().put("d", descr).put("m", mid).put("k", key).toString()
        enqueue(Msg(UUID.randomUUID().toString(), gid, true, body, System.currentTimeMillis(),
            St.PENDING, true, kind = "ctl", mime = "ginfo"))
    }

    /** From the group's owner only: new description and/or photo. */
    private suspend fun onGroupInfo(from: String, gid: String, body: String) {
        val g = dao.group(gid) ?: return
        if (!g.isAdmin(from)) return
        val j = try { JSONObject(body) } catch (e: Exception) { return }
        val d = j.optString("d").take(500)
        val mid = j.optString("m").takeIf { it.length == 32 } ?: ""
        val k = j.optString("k")
        var path = g.photoPath
        if (mid != g.photoMid) {
            if (path.isNotEmpty()) media.shred(File(path))
            path = if (mid.isEmpty() || k.isEmpty()) "" else
                downloadPhoto(mid, k, "gp_${gid}_${System.currentTimeMillis()}.jpg") ?: ""
        }
        val who = dao.contactName(from) ?: from
        dao.setGroupInfo(gid, d, if (path.isEmpty()) "" else mid, if (path.isEmpty()) "" else k, path)
        if (d != g.descr) info(gid, if (d.isEmpty()) "$who removed the group description" else "$who changed the group description")
        if (mid != g.photoMid) info(gid, if (mid.isEmpty()) "$who removed the group photo" else "$who changed the group photo")
    }

    // ---- typing and online status ----

    private fun pruneTyping() {
        val now = System.currentTimeMillis()
        typing.update { all ->
            all.mapValues { (_, m) -> m.filterValues { it > now } }.filterValues { it.isNotEmpty() }
        }
    }

    /** Tell the other side(s) that I started / stopped typing. Sends at most one "on" per 4 s. */
    fun typingNow(chat: String, on: Boolean) {
        if (!prefs.shareTyping || !open) return
        val now = System.currentTimeMillis()
        if (on) { if (now - lastTypingSent < 4000) return; lastTypingSent = now } else lastTypingSent = 0
        val f = js("type" to "typing", "on" to on)
        if (isGroup(chat)) f.put("group", chat) else f.put("to", chat)
        wsSend(f)
    }

    /** Follow the online status of a contact while their chat is open. */
    fun watch(phone: String?) {
        watching = phone
        presence.value = null
        if (phone == null) wsSend(js("type" to "unwatch"))
        else if (open) wsSend(js("type" to "watch", "phone" to phone))
    }

    fun setSharePresence(on: Boolean) {
        prefs.sharePresence = on
        wsSend(js("type" to "set_privacy", "presence" to on))
    }

    // ---- chat ----

    fun send(to: String, body: String, reply: Msg? = null, mentions: String = "") {
        val me = prefs.phone ?: ""
        val m0 = Msg(UUID.randomUUID().toString(), to, true, body,
            System.currentTimeMillis(), St.PENDING, true,
            replyId = reply?.id ?: "",
            quote = reply?.let { quoteText(it) } ?: "",
            quoteBy = reply?.let { if (it.fromMe) me else it.sender } ?: "",
            mentions = mentions)
        serial.launch {
            val m = stamp(m0)
            dao.insert(m)
            unarchive(to)
            shareProfileKey(to)
            var out = m
            if (prefs.linkPreviews) buildLinkPreview(body)?.let { dao.setPreview(m.id, it); out = m.copy(preview = it) }
            if (state.value == Conn.Online) transmit(out)
        }
        if (state.value == Conn.Offline && prefs.token != null) start()
    }

    // ---- link preview: made by the sender's phone (title and description of the first link) ----

    /** What we accept from a message: a small JSON with an http(s) address. */
    private fun cleanPreview(o: JSONObject?): String {
        if (o == null) return ""
        val u = o.optString("u")
        if (!(u.startsWith("http://") || u.startsWith("https://")) || u.length > 500) return ""
        return JSONObject().put("u", u).put("t", o.optString("t").take(150)).put("d", o.optString("d").take(250)).toString()
    }

    private fun htmlText(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()

    private fun metaContent(html: String, key: String): String? {
        val k = Regex.escape(key)
        val a = Regex("<meta[^>]+(?:property|name)=[\"']$k[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)
        val b = Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$k[\"']", RegexOption.IGNORE_CASE).find(html)
        return (a ?: b)?.groupValues?.get(1)?.let { htmlText(it) }?.takeIf { it.isNotEmpty() }
    }

    private suspend fun buildLinkPreview(text: String): String? = withContext(Dispatchers.IO) {
        val url = Regex("https?://[^\\s<>\"]+").find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':') ?: return@withContext null
        try {
            val client = http.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; SkyLinkPreview)")
                .header("Accept", "text/html").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful || r.body == null || r.header("Content-Type")?.contains("text/html", true) != true) return@use null
                val buf = java.io.ByteArrayOutputStream()
                r.body!!.byteStream().use { i ->
                    val chunk = ByteArray(8192)
                    while (buf.size() < 200_000) { val n = i.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
                }
                val html = buf.toString("UTF-8")
                val title = metaContent(html, "og:title")
                    ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { htmlText(it) }
                if (title.isNullOrEmpty()) return@use null
                val desc = metaContent(html, "og:description") ?: metaContent(html, "description") ?: ""
                JSONObject().put("u", url).put("t", title.take(150)).put("d", desc.take(250)).toString()
            }
        } catch (e: Exception) { null }
    }

    // ---- location, contact card, sticker, GIF ----

    fun sendLocation(chat: String, lat: Double, lon: Double) {
        bg.launch {
            enqueue(Msg(UUID.randomUUID().toString(), chat, true, String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon),
                System.currentTimeMillis(), St.PENDING, true, kind = "location"))
        }
    }

    fun sendContactCard(chat: String, name: String, number: String) {
        bg.launch {
            enqueue(Msg(UUID.randomUUID().toString(), chat, true, JSONObject().put("n", name.take(100)).put("p", number.take(40)).toString(),
                System.currentTimeMillis(), St.PENDING, true, kind = "contact"))
        }
    }

    /** A phone number as written in an address book -> +E.164 (null if it cannot be made one). */
    fun toE164(number: String): String? {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val iso = (tm?.simCountryIso?.ifEmpty { null } ?: tm?.networkCountryIso?.ifEmpty { null }
            ?: java.util.Locale.getDefault().country.ifEmpty { null } ?: "BD").uppercase()
        return android.telephony.PhoneNumberUtils.formatNumberToE164(number, iso)
    }

    /** An animated GIF is sent untouched (it would lose its animation if it were re-encoded). */
    fun sendGif(chat: String, uri: Uri) {
        bg.launch {
            val (_, size) = queryMeta(uri)
            if (size > maxUpload - 1024) { events.emit(AuthEvent.Error("too_large")); return@launch }
            val id = UUID.randomUUID().toString()
            val out = File(mediaDir, "$id.gif")
            val plain = File(media.tmpDir, "$id.in")
            val ok = ctx.contentResolver.openInputStream(uri)?.use { i -> plain.outputStream().use { o -> i.copyTo(o) }; true } ?: false
            val len = plain.length()
            if (!ok || len > maxUpload - 1024 || !media.store(plain, out)) {
                media.shred(plain); events.emit(AuthEvent.Error("bad_file")); return@launch
            }
            enqueue(Msg(id, chat, true, "", System.currentTimeMillis(), St.PENDING, true, kind = "image",
                fname = "animation.gif", mime = "image/gif", size = len, localPath = out.absolutePath))
        }
    }

    /** A picture sent as a sticker: at most 512 px, PNG so that transparent parts stay transparent. */
    fun sendSticker(chat: String, uri: Uri) {
        bg.launch {
            val id = UUID.randomUUID().toString()
            val out = File(mediaDir, "$id.png")
            val plain = File(media.tmpDir, "$id.in")
            val made = try {
                val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (b == null) false else {
                    val sc = 512f / maxOf(b.width, b.height)
                    val r = if (sc < 1f) Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true) else b
                    plain.outputStream().use { r.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    true
                }
            } catch (e: Exception) { false }
            val len = plain.length()
            if (!made || !media.store(plain, out)) { media.shred(plain); events.emit(AuthEvent.Error("bad_file")); return@launch }
            enqueue(Msg(id, chat, true, "", System.currentTimeMillis(), St.PENDING, true, kind = "sticker",
                fname = "sticker.png", mime = "image/png", size = len, localPath = out.absolutePath))
        }
    }

    private fun quoteText(m: Msg) = previewText(m.kind, m.body, m.fname).take(200)

    // ---- forward, delete, edit ----

    /** Sends a copy of [m] to another chat, marked "Forwarded". Attachments are re-uploaded with a fresh key. */
    fun forward(m: Msg, to: String) {
        bg.launch {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            when (m.kind) {
                "text" -> enqueue(Msg(id, to, true, m.body, now, St.PENDING, true, fwd = true))
                "location", "contact" -> enqueue(Msg(id, to, true, m.body, now, St.PENDING, true, kind = m.kind, fwd = true))
                "image", "video", "audio", "file", "sticker" -> {
                    if (m.localPath.isEmpty()) { events.emit(AuthEvent.Error("bad_file")); return@launch }
                    val ext = File(m.localPath).extension.take(8)
                    val out = File(mediaDir, id + if (ext.isEmpty()) "" else ".$ext")
                    val tmp = media.toTemp(File(m.localPath), m.fname.ifEmpty { "file" })
                    if (tmp != null && m.kind == "video") makeVideoThumb(tmp, File(out.absolutePath + ".thumb"))
                    if (tmp == null || !media.store(tmp, out)) {
                        tmp?.let { media.shred(it) }; events.emit(AuthEvent.Error("bad_file")); return@launch
                    }
                    enqueue(Msg(id, to, true, m.body, now, St.PENDING, true, kind = m.kind, fname = m.fname,
                        mime = m.mime, size = m.size, dur = m.dur, localPath = out.absolutePath, fwd = true))
                }
            }
        }
    }

    /** React to a message; reacting again with the same emoji takes it back. Goes to everyone in the chat. */
    fun react(m: Msg, emoji: String) {
        val me = prefs.phone ?: return
        bg.launch {
            val cur = dao.getMsg(m.id) ?: return@launch
            if (cur.kind == "deleted") return@launch
            val now = if (dao.reactionOf(cur.id, me) == emoji) "" else emoji
            if (now.isEmpty()) dao.removeReaction(cur.id, me) else dao.putReaction(Reaction(cur.id, me, now))
            enqueue(Msg(UUID.randomUUID().toString(), cur.chat, true, now, System.currentTimeMillis(),
                St.PENDING, true, kind = "ctl", mime = "react", replyId = cur.id))
        }
    }

    fun star(m: Msg, on: Boolean) { bg.launch { dao.setStar(m.id, on) } }

    /** Deletes a stored attachment and, for videos, its small preview picture. */
    private fun shredMedia(path: String) {
        if (path.isEmpty()) return
        media.shred(File(path))
        File("$path.thumb").let { if (it.exists()) media.shred(it) }
    }

    /** Removes a message from this phone only. */
    fun deleteForMe(m: Msg) {
        bg.launch {
            val path = m.localPath
            dao.deleteReactions(m.id)
            dao.deleteLocal(m.id)
            shredMedia(path)
        }
    }

    /** Replaces my own message by "deleted" here and asks the other side(s) to do the same. */
    fun deleteForEveryone(m: Msg) {
        if (!m.fromMe) return
        bg.launch {
            val cur = dao.getMsg(m.id) ?: return@launch
            val path = cur.localPath
            if (cur.status == St.PENDING || cur.status == St.FAILED) {
                dao.deleteLocal(cur.id)                       // nobody has it yet
            } else {
                dao.tombstone(cur.id, cur.chat, 1, "")
                dao.deleteReactions(cur.id)
                enqueue(Msg(UUID.randomUUID().toString(), cur.chat, true, "", System.currentTimeMillis(),
                    St.PENDING, true, kind = "ctl", mime = "del", replyId = cur.id))
            }
            shredMedia(path)
        }
    }

    fun editMessage(m: Msg, newBody: String) {
        val text = newBody.trim()
        if (!m.fromMe || m.kind != "text" || text.isEmpty() || text.length > 4000) return
        bg.launch {
            val cur = dao.getMsg(m.id) ?: return@launch
            if (cur.kind != "text" || cur.body == text) return@launch
            dao.editBody(cur.id, cur.chat, 1, "", text)
            if (cur.status != St.PENDING && cur.status != St.FAILED)
                enqueue(Msg(UUID.randomUUID().toString(), cur.chat, true, text, System.currentTimeMillis(),
                    St.PENDING, true, kind = "ctl", mime = "edit", replyId = cur.id))
        }
    }

    /** A hidden control message from [from]: delete or edit one of that person's own messages. */
    private suspend fun applyControl(from: String, chat: String, p: JSONObject) {
        when (p.optString("op")) {                      // controls that do not point at a message
            "pkey" -> { if (!isGroup(chat)) onProfileKey(from, p.optString("b")); return }
            "ginfo" -> { if (isGroup(chat)) onGroupInfo(from, chat, p.optString("b")); return }
            "dis" -> { onDisappearSet(from, chat, p.optString("b")); return }
            "gc" -> { if (!isGroup(chat)) onGroupCallSignal(from, p.optString("b")); return }
            "ovw" -> { if (!isGroup(chat)) dao.onceOpenedBy(p.optString("b"), chat); return }
            "status" -> { if (!isGroup(chat)) onStatus(from, p.optString("b")); return }
            "sview" -> { if (!isGroup(chat)) onStatusView(from, p.optString("b")); return }
            "sdel" -> { if (!isGroup(chat)) onStatusDeleted(from, p.optString("b")); return }
        }
        val tid = p.optString("tid")
        if (tid.isEmpty()) return
        when (p.optString("op")) {
            "del" -> {
                val path = dao.pathOf(tid, chat, 0, from)         // null = not found or not theirs
                if (path != null) {
                    dao.tombstone(tid, chat, 0, from)
                    dao.deleteReactions(tid)
                    shredMedia(path)
                }
            }
            "react" -> {
                val e = p.optString("b")
                if (dao.chatOf(tid) == chat) {            // only for a message in this very chat
                    if (e.isEmpty() || e.length > 16) dao.removeReaction(tid, from)
                    else dao.putReaction(Reaction(tid, from, e))
                }
            }
            "edit" -> {
                val b = p.optString("b")
                if (b.isNotEmpty() && b.length <= 4000) dao.editBody(tid, chat, 0, from, b)
            }
        }
    }

    /**
     * Encrypts and sends. If we have no session with a recipient yet, their public keys are
     * requested first and the message waits in [awaiting].
     */
    private suspend fun transmit(m0: Msg) {
        var m = m0
        if (hasFile(m.kind) && m.media.isEmpty()) {
            // Encrypt the file with a fresh key, upload only the ciphertext.
            val enc = File(ctx.cacheDir, m.id + ".enc")
            val key = withContext(Dispatchers.IO) {
                // The stored copy is vault-encrypted; decrypt briefly, re-encrypt with the per-file key.
                val plain = File(media.tmpDir, m.id + ".up")
                try {
                    val ok = plain.outputStream().use { media.decryptTo(File(m.localPath), it) }
                    if (ok) MediaCrypto.encrypt(plain, enc) else null
                } finally { media.shred(plain) }
            }
            if (key == null) { enc.delete(); return }     // local copy unreadable: stays pending
            val up = upload(enc, "application/octet-stream")
            enc.delete()
            if (up == null) return                       // stays pending, retried at next login
            dao.setMedia(m.id, up, b64(key))
            m = m.copy(media = up, mkey = b64(key))
            if (m.once == 1 && m.fromMe) { shredMedia(m.localPath); dao.setLocal(m.id, "") }   // view once: I keep no copy
        }
        val payload = JSONObject().put("v", 1).put("k", m.kind).put("b", m.body)
        if (m.kind == "ctl") {
            payload.put("op", m.mime).put("tid", m.replyId)
        } else {
            if (m.replyId.isNotEmpty())
                payload.put("rt", m.replyId).put("rq", m.quote).put("rb", m.quoteBy)
            if (m.fwd) payload.put("fw", 1)
            if (m.mentions.isNotEmpty()) payload.put("mn", org.json.JSONArray(m.mentions))
            if (m.preview.isNotEmpty()) payload.put("lp", JSONObject(m.preview))
            if (m.once == 1) payload.put("vo", 1)
            if (m.expiresAt > 0) payload.put("ex", ((m.expiresAt - m.ts) / 1000).coerceAtLeast(1))
        }
        if (hasFile(m.kind)) {
            payload.put("mid", m.media).put("mk", m.mkey).put("fn", m.fname)
                .put("mt", m.mime).put("sz", m.size).put("du", m.dur)
        }
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)

        val recipients: List<String> =
            if (isGroup(m.chat)) {
                val me = prefs.phone
                (dao.group(m.chat)?.members?.split(",") ?: emptyList()).filter { it != me }
            } else listOf(m.chat)
        if (recipients.isEmpty()) { dao.fail(m.id); return }

        val cr = crypto()
        val missing = recipients.filter { !cr.hasSession(it) }
        if (missing.isNotEmpty()) {
            awaiting[m.id] = m
            for (p in missing) if (requested.add(p)) wsSend(js("type" to "fetch_keys", "phone" to p))
            return
        }
        try {
            if (isGroup(m.chat)) {
                val cts = JSONObject()
                for (p in recipients) {
                    val sealed = cr.encrypt(p, bytes)
                    cts.put(p, JSONObject().put("t", sealed.type).put("c", b64(sealed.data)))
                }
                wsSend(js("type" to "send_group", "id" to m.id, "group" to m.chat, "cts" to cts))
            } else {
                val sealed = cr.encrypt(m.chat, bytes)
                wsSend(js("type" to "send", "id" to m.id, "to" to m.chat,
                    "ct" to b64(sealed.data), "ctype" to sealed.type))
            }
        } catch (e: Exception) {
            dao.fail(m.id)
            events.emit(AuthEvent.Error("encrypt_failed"))
        }
    }

    // ---- attachments ----

    private fun httpBase() = prefs.serverUrl
        .replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").removeSuffix("/ws")

    private suspend fun upload(f: File, mime: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(httpBase() + "/upload")
                .header("Authorization", "Bearer " + prefs.token)
                .post(f.asRequestBody(mime.toMediaTypeOrNull())).build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body!!.string()).getString("id") else null
            }
        } catch (e: Exception) { null }
    }

    /** Downloads an encrypted attachment, decrypts it into mediaDir and records its local path. */
    fun fetch(m: Msg) {
        if (m.mkey.isEmpty()) return
        bg.launch {
            val enc = File(ctx.cacheDir, m.id + ".dl")
            try {
                val req = Request.Builder().url(httpBase() + "/media/" + m.media)
                    .header("Authorization", "Bearer " + prefs.token).build()
                val ok = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    r.body!!.byteStream().use { i -> enc.outputStream().use { o -> i.copyTo(o) } }
                    true
                }
                if (!ok) return@launch
                val ext = m.fname.substringAfterLast('.', "").take(8)
                val out = File(mediaDir, m.id + if (ext.isEmpty()) "" else ".$ext")
                val plain = File(media.tmpDir, m.id + ".dl")
                try {
                    if (MediaCrypto.decrypt(enc, plain, unb64(m.mkey))) {
                        if (m.kind == "video") makeVideoThumb(plain, File(out.absolutePath + ".thumb"))
                        if (media.store(plain, out)) dao.setLocal(m.id, out.absolutePath)
                    }
                } finally { media.shred(plain) }
            } catch (_: Exception) {
            } finally {
                enc.delete()
            }
        }
    }

    /** One photo or file to send: [rotation] in degrees (0/90/180/270), [aspect] width/height for a centre crop (0 = keep). */
    class OutMedia(val uri: Uri, val caption: String = "", val rotation: Int = 0, val aspect: Float = 0f, val once: Boolean = false)

    fun sendMedia(chat: String, uri: Uri, kind: String, caption: String = "", rotation: Int = 0, aspect: Float = 0f) {
        bg.launch { sendMediaNow(chat, uri, kind, caption, rotation, aspect) }
    }

    /** Several photos / videos from the preview screen, one message each, in order. */
    fun sendMediaBatch(chat: String, items: List<OutMedia>) {
        bg.launch {
            for (it in items) {
                val video = (ctx.contentResolver.getType(it.uri) ?: "").startsWith("video/")
                sendMediaNow(chat, it.uri, if (video) "video" else "image", it.caption, it.rotation, it.aspect, it.once && !isGroup(chat))
            }
        }
    }

    private suspend fun sendMediaNow(chat: String, uri: Uri, kind: String, caption: String, rotation: Int, aspect: Float, once: Boolean = false) {
        val (name, size) = queryMeta(uri)
        if (size > maxUpload - 1024) { events.emit(AuthEvent.Error("too_large")); return }
        val id = UUID.randomUUID().toString()
        val ext = name.substringAfterLast('.', "").take(8)
        val out = File(mediaDir, id + if (kind == "image") ".jpg" else if (ext.isEmpty()) "" else ".$ext")
        val plain = File(media.tmpDir, "$id.in")     // plaintext only until stored below
        val mime: String
        val fname: String
        if (kind == "image") {
            if (!prepareImage(uri, plain, rotation, aspect)) { media.shred(plain); events.emit(AuthEvent.Error("bad_file")); return }
            mime = "image/jpeg"; fname = "photo.jpg"
        } else {
            val ok = ctx.contentResolver.openInputStream(uri)?.use { i ->
                plain.outputStream().use { o -> i.copyTo(o) }; true
            } ?: false
            if (!ok) { media.shred(plain); events.emit(AuthEvent.Error("bad_file")); return }
            mime = ctx.contentResolver.getType(uri) ?: if (kind == "video") "video/mp4" else "application/octet-stream"
            fname = name
        }
        val plainSize = plain.length()
        if (plainSize > maxUpload - 1024) {
            media.shred(plain); events.emit(AuthEvent.Error("too_large")); return
        }
        if (kind == "video") makeVideoThumb(plain, File(out.absolutePath + ".thumb"))
        if (!media.store(plain, out)) {
            media.shred(plain); events.emit(AuthEvent.Error("bad_file")); return
        }
        enqueue(Msg(id = id, chat = chat, fromMe = true, body = caption.trim().take(1000), ts = System.currentTimeMillis(),
            status = St.PENDING, seen = true, kind = kind, fname = fname, mime = mime,
            size = plainSize, localPath = out.absolutePath, once = if (once) 1 else 0))
    }

    /** First frame of a video as a small picture, stored (vault-encrypted) next to the video as "<file>.thumb". */
    private fun makeVideoThumb(plainVideo: File, out: File) {
        val tmp = File(media.tmpDir, out.name + ".jpg")
        try {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(plainVideo.absolutePath)
                val f = r.getFrameAtTime(0) ?: return
                val sc = 480f / maxOf(f.width, f.height)
                val b = if (sc < 1f) Bitmap.createScaledBitmap(f, (f.width * sc).toInt(), (f.height * sc).toInt(), true) else f
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                media.store(tmp, out)
            } finally { r.release() }
        } catch (_: Exception) {
        } finally { media.shred(tmp) }
    }

    fun sendVoice(chat: String, file: File, durMs: Int) {
        bg.launch {
            val id = UUID.randomUUID().toString()
            val out = File(mediaDir, "$id.m4a")
            val size = file.length()
            if (!media.store(file, out)) { media.shred(file); events.emit(AuthEvent.Error("bad_file")); return@launch }
            enqueue(Msg(id = id, chat = chat, fromMe = true, body = "",
                ts = System.currentTimeMillis(), status = St.PENDING, seen = true, kind = "audio",
                fname = "voice.m4a", mime = "audio/mp4", size = size, dur = durMs,
                localPath = out.absolutePath))
        }
    }

    /** A message in a chat with a disappearing-messages timer gets its end time. */
    private suspend fun stamp(m: Msg): Msg {
        if (m.kind == "ctl" || m.kind == "info" || m.kind == "call" || m.expiresAt != 0L) return m
        val sec = dao.chatState(m.chat)?.disappear ?: 0L
        return if (sec > 0) m.copy(expiresAt = m.ts + sec * 1000) else m
    }

    private suspend fun enqueue(m0: Msg) {
        val m = stamp(m0)
        dao.insert(m)
        serial.launch { unarchive(m.chat); if (m.kind != "ctl") shareProfileKey(m.chat) }
        serial.launch { if (state.value == Conn.Online) transmit(m) }
        if (state.value == Conn.Offline && prefs.token != null) start()
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    /** Shrinks to max ~2400px, fixes rotation, saves as JPEG q80 (saves a lot of data). */
    private fun prepareImage(uri: Uri, out: File, rotation: Int = 0, aspect: Float = 0f): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return false
            val orientation = ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val fixed = if (deg != 0f)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            else bmp
            val turned = if (rotation % 360 != 0)
                Bitmap.createBitmap(fixed, 0, 0, fixed.width, fixed.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            else fixed
            val cropped = if (aspect > 0f) {                      // centre crop to width/height = aspect
                val cur = turned.width.toFloat() / turned.height
                if (cur > aspect) {
                    val w = (turned.height * aspect).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(turned, (turned.width - w) / 2, 0, w, turned.height)
                } else {
                    val h = (turned.width / aspect).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(turned, 0, (turned.height - h) / 2, turned.width, h)
                }
            } else turned
            out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            true
        } catch (e: Exception) { false }
    }

    fun lookup(phone: String) = wsSend(js("type" to "lookup", "phone" to phone))

    fun createGroup(name: String, members: List<String>) {
        wsSend(JSONObject().put("type", "create_group").put("name", name)
            .put("members", org.json.JSONArray(members)))
    }

    fun addMembers(gid: String, members: List<String>) {
        wsSend(JSONObject().put("type", "add_members").put("group", gid)
            .put("members", org.json.JSONArray(members)))
    }

    fun removeMember(gid: String, phone: String) =
        wsSend(js("type" to "remove_member", "group" to gid, "phone" to phone))

    fun leaveGroup(gid: String) = wsSend(js("type" to "leave_group", "group" to gid))

    fun markChatRead(chat: String) {
        serial.launch {
            dao.chatState(chat)?.let { if (it.markedUnread) dao.putState(it.copy(markedUnread = false)) }
            dao.unseen(chat).forEach {
                val f = js("type" to "read", "id" to it.id, "from" to it.sender)
                if (isGroup(chat)) f.put("group", chat)
                wsSend(f)
            }
            dao.markSeen(chat)
        }
    }

    // ---- end-to-end encryption helpers ----

    @Synchronized
    private fun crypto(): SignalCrypto {
        crypto?.let { return it }
        val phone = prefs.phone ?: error("not logged in")
        val c = SignalCrypto(RoomKv(dao, vault), phone) { peer ->
            serial.launch {
                val who = dao.contactName(peer) ?: peer
                info(peer, "⚠️ Safety number with $who changed. They may have reinstalled the app or switched phones.")
            }
        }
        crypto = c
        return c
    }

    private fun prekeysJson(keys: List<PublicPreKey>) = org.json.JSONArray().apply {
        keys.forEach { put(JSONObject().put("id", it.id).put("pub", b64(it.pub))) }
    }

    private fun signedJson(k: SignedPub) =
        JSONObject().put("id", k.id).put("pub", b64(k.pub)).put("sig", b64(k.sig))

    /** Publishes our public keys (identity, signed prekey, post-quantum prekey, one-time prekeys). */
    private fun uploadKeys() {
        val now = System.currentTimeMillis()
        if (now - lastKeyUpload < 5000) return
        lastKeyUpload = now
        val ku = crypto().createKeys(100)
        wsSend(JSONObject().put("type", "upload_keys")
            .put("identity", b64(ku.identity)).put("reg", ku.regId)
            .put("spk", signedJson(ku.spk)).put("kyber", signedJson(ku.kyber))
            .put("prekeys", prekeysJson(ku.preKeys)))
    }

    // ---- call signalling: offers and answers are sealed in the Signal session ----

    /** Makes sure a Signal session with [peer] exists, fetching their keys if needed (up to 8 s). */
    suspend fun ensureSession(peer: String): Boolean {
        if (crypto().hasSession(peer)) return true
        var fresh = false
        val d = synchronized(keyWaiters) { keyWaiters.getOrPut(peer) { fresh = true; CompletableDeferred() } }
        if (fresh) wsSend(js("type" to "fetch_keys", "phone" to peer))
        val ok = withTimeoutOrNull(8000) { d.await() } ?: false
        synchronized(keyWaiters) { if (keyWaiters[peer] === d) keyWaiters.remove(peer) }
        return ok && crypto().hasSession(peer)
    }

    private fun wakeKeyWaiter(peer: String, ok: Boolean) {
        synchronized(keyWaiters) { keyWaiters.remove(peer) }?.complete(ok)
    }

    /** Encrypts an SDP for [peer]; returns the base64 ciphertext and its Signal message type. */
    suspend fun sealSdp(peer: String, sdp: String): Pair<String, Int>? = withContext(Dispatchers.IO) {
        try {
            if (!ensureSession(peer)) return@withContext null
            val s = crypto().encrypt(peer, sdp.toByteArray(StandardCharsets.UTF_8))
            b64(s.data) to s.type
        } catch (e: Exception) { null }
    }

    /** Decrypts an SDP from [peer]; null if it does not authenticate. */
    fun openSdp(peer: String, ct: String, type: Int): String? = try {
        if (ct.isEmpty()) null
        else String(crypto().decrypt(peer, type, unb64(ct)), StandardCharsets.UTF_8)
    } catch (e: Exception) { null }

    private suspend fun onKeys(o: JSONObject) {
        val phone = o.getString("phone")
        requested.remove(phone)
        try {
            fun signed(j: JSONObject) = SignedPub(j.getInt("id"), unb64(j.getString("pub")), unb64(j.getString("sig")))
            val pk = o.optJSONObject("prekey")?.let { PublicPreKey(it.getInt("id"), unb64(it.getString("pub"))) }
            crypto().processBundle(phone, RemoteBundle(
                unb64(o.getString("identity")), o.getInt("reg"),
                signed(o.getJSONObject("spk")), signed(o.getJSONObject("kyber")), pk))
        } catch (e: Exception) {
            wakeKeyWaiter(phone, false)
            failAwaiting(phone)             // bad or forged key bundle
            return
        }
        wakeKeyWaiter(phone, true)
        val list = awaiting.values.toList()
        awaiting.clear()
        list.forEach { transmit(it) }
    }

    private suspend fun failAwaiting(phone: String) {
        requested.remove(phone)
        wakeKeyWaiter(phone, false)
        val dead = awaiting.values.filter { m ->
            m.chat == phone || (isGroup(m.chat) && (dao.group(m.chat)?.members?.split(",")?.contains(phone) == true))
        }
        dead.forEach { dao.fail(it.id); awaiting.remove(it.id) }
        if (dead.isNotEmpty()) events.emit(AuthEvent.Error("no_keys"))
    }

    private suspend fun info(chat: String, text: String) {
        dao.insert(Msg(id = UUID.randomUUID().toString(), chat = chat, fromMe = false, body = text,
            ts = System.currentTimeMillis(), status = St.DELIVERED, seen = true, kind = "info"))
    }

    private fun ackMsg(id: String, from: String, group: String?, read: Boolean) {
        for (type in if (read) listOf("delivered", "read") else listOf("delivered")) {
            val f = js("type" to type, "id" to id, "from" to from)
            if (group != null) f.put("group", group)
            wsSend(f)
        }
    }

    private suspend fun onEncryptedMsg(o: JSONObject) {
        val id = o.getString("id")
        val from = o.getString("from")
        val group = o.optString("group").ifEmpty { null }
        val chat = group ?: from
        val isOpen = openChat.value == chat
        val name = o.optString("name")
        if (name.isNotEmpty() && dao.isLocal(from) == 0) dao.upsertContact(Contact(from, name))

        if (dao.countById(id) > 0) {                 // re-delivery of something we already hold
            ackMsg(id, from, group, false); return
        }
        val plain = try {
            crypto().decrypt(from, o.getInt("ctype"), unb64(o.getString("ct")))
        } catch (e: Exception) { null }
        if (group == null && dao.isBlocked(from) > 0) {
            // Thrown away without telling the sender: "got" removes it from the server queue, no receipt is sent.
            wsSend(js("type" to "got", "pid" to id))
            return
        }
        if (plain == null) {
            info(chat, "🔒 A message from ${name.ifEmpty { from }} could not be decrypted.")
            ackMsg(id, from, group, false)
            return
        }
        val p = try { JSONObject(String(plain, StandardCharsets.UTF_8)) } catch (e: Exception) {
            info(chat, "🔒 A message from ${name.ifEmpty { from }} was unreadable.")
            ackMsg(id, from, group, false); return
        }
        if (p.optString("k") == "ctl") {                 // delete / edit: nothing to show
            applyControl(from, chat, p)
            ackMsg(id, from, group, false)
            return
        }
        val kind = p.optString("k", "text").let { if (it in setOf("text", "image", "video", "audio", "file", "sticker", "location", "contact")) it else "file" }
        val body = p.optString("b")
        val fname = p.optString("fn")
        val msg = Msg(id = id, chat = chat, fromMe = false, body = body, ts = o.getLong("ts"),
            status = St.DELIVERED, seen = isOpen, sender = from, kind = kind,
            media = p.optString("mid"), fname = fname, mime = p.optString("mt"),
            size = p.optLong("sz"), dur = p.optInt("du"), mkey = p.optString("mk"),
            mentions = p.optJSONArray("mn")?.toString()?.takeIf { it.length <= 2000 } ?: "",
            replyId = p.optString("rt").take(64), quote = p.optString("rq").take(200),
            quoteBy = p.optString("rb").take(32), fwd = p.optInt("fw") == 1,
            preview = cleanPreview(p.optJSONObject("lp")),
            once = if (p.optInt("vo") == 1 && group == null && (kind == "image" || kind == "video")) 1 else 0,
            expiresAt = p.optLong("ex").takeIf { it in 1..7_776_000L }?.let { o.getLong("ts") + it * 1000 } ?: 0L)
        val rowId = dao.insert(msg)
        if (rowId != -1L) {
            if (kind == "image" || kind == "audio" || kind == "sticker") fetch(msg)
            // An archived chat returns to the list on a new message, unless it is muted.
            dao.chatState(chat)?.let { if (it.archived && !it.muted()) dao.putState(it.copy(archived = false)) }
            if (!isOpen) {
                val me = prefs.phone
                val mentionedMe = me != null && msg.mentions.contains("\"$me\"")
                notifier?.show(chat, from, (if (mentionedMe) "@ " else "") + previewText(kind, body, fname), mentionedMe)
                if (!foreground) scheduleIdleStop()
            }
        }
        ackMsg(id, from, group, isOpen)
    }

    /** Safety number for a chat partner (call from any thread). */
    suspend fun safetyNumber(peer: String): String? = withContext(Dispatchers.IO) {
        try { crypto().safetyNumber(peer) } catch (e: Exception) { null }
    }

    private fun wsSend(o: JSONObject) {
        ws?.let { sendFrame(it, o) }
    }

    // ---- incoming frames ----

    private suspend fun handle(o: JSONObject) {
        when (o.getString("type")) {
            "otp_sent" -> {
                authBusy = false
                events.emit(AuthEvent.OtpSent(o.optString("dev_code").ifEmpty { null }))
            }
            "token" -> {
                prefs.token = o.getString("token")
                prefs.phone = pendingPhone
            }
            "login_ok" -> {
                authBusy = false
                o.optJSONArray("ice")?.let { iceJson = it }
                state.value = Conn.Online
                loggedIn.value = true
                events.emit(AuthEvent.LoggedIn)
                if (!crypto().hasIdentity()) uploadKeys()        // first login, or local data was reset
                if (prefs.profileKey != null && prefs.profileDirty) publishProfile()
                expireStatuses()
                if (hasContactsPermission() && System.currentTimeMillis() - prefs.lastContactSync > 12 * 3600_000L) syncContacts()
                dao.unsent().forEach { transmit(it) }
                PushService.ensure(ctx)            // keep the own push channel up
                watching?.let { wsSend(js("type" to "watch", "phone" to it)) }     // after a reconnect
            }
            "typing" -> {
                val from = o.getString("from")
                val chat = o.optString("group").ifEmpty { from }
                val until = if (o.getBoolean("on")) System.currentTimeMillis() + 7000 else 0L
                typing.update { all ->
                    val cur = (all[chat] ?: emptyMap()).toMutableMap()
                    if (until > 0) cur[from] = until else cur.remove(from)
                    if (cur.isEmpty()) all - chat else all + (chat to cur)
                }
                if (until > 0) bg.launch { delay(7100); pruneTyping() }
            }
            "presence" -> {
                val p = o.getString("phone")
                if (p == watching) presence.value = Presence(p, o.optBoolean("online"),
                    if (o.has("last_seen")) o.getLong("last_seen") else null, o.optBoolean("hidden"))
            }
            "privacy" -> prefs.sharePresence = o.optBoolean("presence", true)
            "call_in", "call_answered", "call_ice", "call_end", "call_state" -> calls?.onSignal(o)
            "ack" -> { dao.raiseStatus(o.getString("id"), St.SENT); dao.deleteCtl(o.getString("id")) }
            "blocks" -> {
                val arr = o.getJSONArray("phones")
                val list = (0 until arr.length()).map { arr.getString(it) }
                dao.putBlocked(list.map { Blocked(it) })
                dao.deleteBlockedExcept(list)
            }
            "reported" -> events.emit(AuthEvent.Error("reported"))     // shown as a "Report sent" toast
            "pin_needed" -> { authBusy = false; events.emit(AuthEvent.PinNeeded) }
            "pin_state" -> { prefs.pinOn = o.optBoolean("on"); pinOnFlow.value = prefs.pinOn }
            "contacts_found" -> onContactsFound(o)
            "invite" -> invites.update { it + (o.getString("group") to o.getString("code")) }
            "me" -> prefs.name = o.getString("name")
            "profile_saved" -> prefs.profileDirty = false
            "profile_blob" -> onProfileBlob(o)
            "keys_needed" -> uploadKeys()
            "prekeys_low" -> {
                val more = crypto().moreOneTimeKeys(100)
                wsSend(JSONObject().put("type", "upload_keys").put("prekeys", prekeysJson(more)))
            }
            "keys" -> onKeys(o)
            "msg" -> {
                // Only end-to-end encrypted messages are accepted; plaintext from the server is ignored.
                if (o.optString("ct").isNotEmpty()) onEncryptedMsg(o)
                else wsSend(js("type" to "delivered", "id" to o.getString("id"), "from" to o.getString("from")))
            }
            "group" -> {
                val members = o.getJSONArray("members")
                val phones = ArrayList<String>()
                val nameOf = HashMap<String, String>()
                for (i in 0 until members.length()) {
                    val m = members.getJSONObject(i)
                    phones.add(m.getString("phone"))
                    nameOf[m.getString("phone")] = m.optString("name")
                    m.getString("phone").let { p -> if (dao.isLocal(p) == 0) dao.upsertContact(Contact(p, m.optString("name"))) }
                }
                // Tell the chat who joined or left.
                val me = prefs.phone
                val prev = dao.group(o.getString("gid"))
                val old = prev?.members?.split(",")?.filter { it.isNotEmpty() }
                if (old != null) {
                    fun label(p: String) = if (p == me) "You" else nameOf[p]?.ifEmpty { null } ?: p
                    (phones - old.toSet()).forEach { info(o.getString("gid"), "${label(it)} ${if (it == me) "were" else "was"} added") }
                    (old - phones.toSet()).forEach { p ->
                        val n = dao.contactName(p) ?: p
                        info(o.getString("gid"), "$n is no longer in this group")
                    }
                }
                val admArr = o.optJSONArray("admins")
                val admins = if (admArr == null) listOf(o.getString("owner")) else (0 until admArr.length()).map { admArr.getString(it) }
                val announce = o.optBoolean("announce", false)
                if (prev != null) {
                    val before = prev.admins.split(",").filter { it.isNotEmpty() }.toSet()
                    (admins.toSet() - before - o.getString("owner")).forEach {
                        info(o.getString("gid"), (if (it == me) "You are" else (nameOf[it]?.ifEmpty { null } ?: it) + " is") + " now an admin")
                    }
                    if (announce != prev.announce)
                        info(o.getString("gid"), if (announce) "Only admins can send messages now" else "All members can send messages now")
                }
                dao.upsertGroup(ChatGroup(o.getString("gid"), o.getString("name"),
                    phones.joinToString(","), o.getString("owner"), o.getLong("ts"),
                    prev?.descr ?: "", prev?.photoMid ?: "", prev?.photoKey ?: "", prev?.photoPath ?: "",
                    admins.joinToString(","), announce))
                // The owner hands the description and photo to people who just joined.
                if (prev != null && me != null && o.getString("owner") == me && (phones - old.orEmpty().toSet()).isNotEmpty() &&
                    (prev.descr.isNotEmpty() || prev.photoMid.isNotEmpty()))
                    sendGroupInfo(prev.gid, prev.descr, prev.photoMid, prev.photoKey)
                wsSend(js("type" to "got", "pid" to o.getString("pid")))
            }
            "group_removed" -> {
                // We were removed from, or left, a group: keep the chat history, but we can no longer write.
                val gid = o.getString("gid")
                val g = dao.group(gid)
                val me = prefs.phone
                if (g != null && g.members.split(",").contains(me)) {
                    dao.upsertGroup(g.copy(members = g.members.split(",").filter { it != me && it.isNotEmpty() }
                        .joinToString(",")))
                    info(gid, "You are no longer in this group")
                }
                wsSend(js("type" to "got", "pid" to o.getString("pid")))
            }
            "profile" -> {
                val phone = o.getString("phone")
                val exists = o.getBoolean("exists")
                val name = if (exists) o.getString("name") else null
                if (name != null && dao.isLocal(phone) == 0) dao.upsertContact(Contact(phone, name))
                events.emit(AuthEvent.Profile(phone, name, exists))
            }
            "status" -> {
                val s = if (o.getString("status") == "read") St.READ else St.DELIVERED
                val id = o.getString("id")
                val gid = o.optString("group").ifEmpty { null }
                if (gid == null) {
                    dao.raiseStatus(id, s)
                } else {
                    // Group message: the tick only advances once every member has reached that state.
                    val by = o.getString("by")
                    if ((dao.receiptStatus(id, by) ?: 0) < s) dao.putReceipt(Receipt(id, by, s))
                    val me = prefs.phone
                    val others = dao.group(gid)?.members?.split(",")?.filter { it.isNotEmpty() && it != me }
                        ?: emptyList()
                    if (others.isNotEmpty() && others.all { (dao.receiptStatus(id, it) ?: 0) >= s })
                        dao.raiseStatus(id, s)
                }
                wsSend(js("type" to "got", "pid" to o.getString("pid")))
            }
            "error" -> {
                val code = o.getString("code")
                if (code == "no_keys") failAwaiting(o.optString("phone"))
                if (o.has("id")) dao.fail(o.getString("id"))
                if (o.has("cid")) calls?.onCallError(o.getString("cid"), code)
                authBusy = false
                if (code == "bad_token") logout()
                events.emit(AuthEvent.Error(code))
            }
        }
    }
}
