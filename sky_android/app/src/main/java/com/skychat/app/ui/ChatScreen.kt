package com.skychat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import com.skychat.app.data.ChatGroup
import com.skychat.app.data.Msg
import com.skychat.app.data.Reaction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.skychat.app.data.St
import com.skychat.app.data.isGroup
import com.skychat.app.media.AudioPlayer
import com.skychat.app.media.VoiceRecorder
import com.skychat.app.media.openFile
import com.skychat.app.net.AuthEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: SkyApp, chat: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = app.db.dao()
    val msgs by dao.messages(chat).collectAsState(initial = emptyList())
    val contacts by dao.contacts().collectAsState(initial = emptyList())
    val groups by dao.groups().collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    val peer by dao.peerProfileFlow(chat).collectAsState(initial = null)
    LaunchedEffect(chat) { if (!isGroup(chat)) app.client.refreshProfile(chat) }
    var topMenu by remember { mutableStateOf(false) }
    var dlg by remember { mutableIntStateOf(0) }     // 1 mute, 2 block / unblock, 3 report
    val cstate by dao.chatStateFlow(chat).collectAsState(initial = null)
    val blockedList by dao.blockedPhones().collectAsState(initial = emptyList())
    val iBlocked = !isGroup(chat) && chat in blockedList
    val isMuted = cstate?.muted() == true
    var recording by remember { mutableStateOf(false) }
    val reactions by dao.reactions(chat).collectAsState(initial = emptyList())
    val reactMap = remember(reactions) { reactions.groupBy { it.msgId } }
    val mentionPicks = remember { mutableStateListOf<Pair<String, String>>() }   // phone to typed label
    var replyTo by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var actionMsg by remember { mutableStateOf<Msg?>(null) }
    var infoMsg by remember { mutableStateOf<Msg?>(null) }
    var deleteMsg by remember { mutableStateOf<Msg?>(null) }
    var forwardMsg by remember { mutableStateOf<Msg?>(null) }
    val scope = rememberCoroutineScope()
    var elapsed by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder(ctx) }

    val names = contacts.associate { it.phone to it.name }
    val group = groups.firstOrNull { it.gid == chat }
    val title = if (isGroup(chat)) group?.name ?: "Group" else names[chat] ?: chat
    val me = app.prefs.phone
    val inGroup = !isGroup(chat) || group == null || group.members.split(",").contains(me)
    val adminsOnly = isGroup(chat) && group != null && group.announce && !group.isAdmin(me)
    val canWrite = inGroup && !adminsOnly
    val typingAll by app.client.typing.collectAsState()
    val typers = (typingAll[chat] ?: emptyMap()).filterValues { it > System.currentTimeMillis() }.keys
    val presence by app.client.presence.collectAsState()
    val subtitle = when {
        typers.isNotEmpty() -> when {
            !isGroup(chat) -> "typing…"
            typers.size == 1 -> "${typers.first().let { names[it] ?: it }} is typing…"
            else -> "${typers.size} people are typing…"
        }
        group != null && !canWrite -> "You are no longer in this group"
        group != null -> "${group.members.split(",").size} members"
        !isGroup(chat) && presence?.phone == chat && presence?.hidden == false ->
            presence!!.let { if (it.online) "online" else it.lastSeen?.let { t -> "last seen " + lastSeenText(t) } ?: chat }
        !isGroup(chat) && names[chat] != null -> chat
        else -> null
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) app.client.sendMedia(chat, uri, "image")
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) app.client.sendMedia(chat, uri, "file")
    }
    fun startRec() {
        if (recorder.start(app.client.tmpDir)) { recording = true; elapsed = 0 }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec()
    }

    var wantVideo by remember { mutableStateOf(false) }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true)
            app.calls.start(chat, wantVideo && res[Manifest.permission.CAMERA] == true)
    }
    fun callPeer(video: Boolean) {
        wantVideo = video
        val need = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                   else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (need.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            app.calls.start(chat, video)
        else askCall.launch(need)
    }

    DisposableEffect(chat) {
        if (!isGroup(chat)) app.client.watch(chat)
        app.client.openChat.value = chat
        app.notifier.cancel(chat)
        onDispose {
            app.client.openChat.value = null
            app.client.typingNow(chat, false)
            if (!isGroup(chat)) app.client.watch(null)
            recorder.cancel()
            AudioPlayer.stop()
        }
    }
    LaunchedEffect(msgs.size) {
        app.client.markChatRead(chat)
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }
    // "typing…": on after the first keystroke (at most every 4 s), off after 3 s without typing.
    LaunchedEffect(text) {
        if (text.isNotEmpty()) {
            app.client.typingNow(chat, true)
            delay(3000)
            app.client.typingNow(chat, false)
        }
    }
    LaunchedEffect(recording) {
        val t0 = System.currentTimeMillis()
        while (recording) {
            elapsed = ((System.currentTimeMillis() - t0) / 1000).toInt()
            delay(300)
        }
    }
    LaunchedEffect(Unit) {
        app.client.events.collect { e ->
            if (e is AuthEvent.Error) Toast.makeText(ctx, toastText(e.code), Toast.LENGTH_SHORT).show()
        }
    }

    if (showGroup && group != null) {
        GroupInfoDialog(app, group, names) { showGroup = false }
    }
    infoMsg?.let { m ->
        val receipts by dao.receiptsOf(m.id).collectAsState(initial = emptyList())
        val st = receipts.associate { it.phone to it.status }
        val others = (group?.members ?: "").split(",").filter { it.isNotEmpty() && it != me }
        AlertDialog(
            onDismissRequest = { infoMsg = null },
            title = { Text("Message info") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    others.forEach { p ->
                        val s = st[p] ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(names[p] ?: p, Modifier.weight(1f))
                            Text(when { s >= 3 -> "Read ✓✓"; s == 2 -> "Delivered ✓✓"; else -> "Waiting…" },
                                color = if (s >= 3) Accent else Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { infoMsg = null }) { Text("Close") } }
        )
    }
    if (showContact) {
        AlertDialog(
            onDismissRequest = { showContact = false },
            title = { Text(title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(app, peer?.photoPath ?: "", 120.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(chat, color = Color.Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("About", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                    Text(peer?.about?.ifEmpty { null } ?: "No about text yet.", modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showContact = false }) { Text("Close") } }
        )
    }

    if (showSafety) {
        val number by produceState<String?>(null, chat) { value = app.client.safetyNumber(chat) }
        AlertDialog(
            onDismissRequest = { showSafety = false },
            title = { Text("🔒 End-to-end encrypted") },
            text = {
                Column {
                    Text("Messages and files in this chat are encrypted on your phone. Only you and the other person can read them.")
                    Spacer(Modifier.height(12.dp))
                    Text("Safety number", fontWeight = FontWeight.Bold)
                    Text(number ?: "Send a message first to create a secure session.", fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Compare this number with the other person, in person or over a call. If it matches, nobody is intercepting your chat. If it ever changes you will see a warning here.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text("Close") } }
        )
    }

    actionMsg?.let { m ->
        val gone = m.kind == "deleted"
        val fresh = System.currentTimeMillis() - m.ts < 15 * 60 * 1000
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            confirmButton = {},
            text = {
                Column {
                    if (!gone) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                            TextButton(onClick = { actionMsg = null; app.client.react(m, e) },
                                contentPadding = PaddingValues(4.dp), modifier = Modifier.defaultMinSize(minWidth = 1.dp)) {
                                Text(e, fontSize = 24.sp)
                            }
                        }
                    }
                    if (!gone) ActionItem(if (m.starred) "Unstar" else "Star") { actionMsg = null; app.client.star(m, !m.starred) }
                    if (!gone && canWrite) ActionItem("Reply") { actionMsg = null; replyTo = m; editing = null }
                    if (!gone && m.kind == "text") ActionItem("Copy") {
                        actionMsg = null
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Sky message", m.body))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    if (!gone) ActionItem("Forward") { actionMsg = null; forwardMsg = m }
                    if (!gone && m.fromMe && m.kind == "text" && fresh && canWrite) ActionItem("Edit") {
                        actionMsg = null; editing = m; replyTo = null; text = m.body
                    }
                    if (!gone && m.fromMe && isGroup(chat)) ActionItem("Info") { actionMsg = null; infoMsg = m }
                    ActionItem("Delete", Color.Red) { actionMsg = null; deleteMsg = m }
                }
            }
        )
    }

    deleteMsg?.let { m ->
        val everyone = m.fromMe && m.kind != "deleted" && m.status != St.FAILED &&
            System.currentTimeMillis() - m.ts < 48L * 3600 * 1000
        AlertDialog(
            onDismissRequest = { deleteMsg = null },
            title = { Text("Delete message?") },
            text = { if (everyone) Text("\"Delete for everyone\" replaces it with \"Message deleted\" for the other person(s) " +
                "if they use a Sky version that supports it.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (everyone) TextButton(onClick = { deleteMsg = null; app.client.deleteForEveryone(m) }) {
                        Text("Delete for everyone", color = Color.Red)
                    }
                    TextButton(onClick = { deleteMsg = null; app.client.deleteForMe(m) }) {
                        Text("Delete for me", color = Color.Red)
                    }
                    TextButton(onClick = { deleteMsg = null }) { Text("Cancel") }
                }
            }
        )
    }

    forwardMsg?.let { m ->
        val targets = groups.filter { g -> g.members.split(",").contains(me) }.map { it.gid to it.name } +
            contacts.filter { it.phone != me }.map { it.phone to it.name.ifEmpty { it.phone } }
        AlertDialog(
            onDismissRequest = { forwardMsg = null },
            title = { Text("Forward to…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (targets.isEmpty()) Text("No chats yet.", color = Color.Gray)
                    targets.forEach { (id, label) ->
                        TextButton(onClick = {
                            forwardMsg = null
                            app.client.forward(m, id)
                            Toast.makeText(ctx, "Forwarded to $label", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { forwardMsg = null }) { Text("Cancel") } }
        )
    }

    if (dlg == 1) MuteDialog(isGroup(chat), onPick = { dlg = 0; app.client.setMuted(chat, it) }, onDismiss = { dlg = 0 })
    if (dlg == 2) BlockDialog(title, block = !iBlocked,
        onConfirm = { dlg = 0; app.client.setBlocked(chat, !iBlocked) }, onDismiss = { dlg = 0 })
    if (dlg == 3) ReportDialog(title, blockedAlready = iBlocked,
        onSend = { reason, withMsgs, alsoBlock -> dlg = 0; app.client.report(chat, reason, withMsgs, alsoBlock) },
        onDismiss = { dlg = 0 })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(Modifier.clickable { if (isGroup(chat)) showGroup = true else showContact = true },
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(app, (if (isGroup(chat)) group?.photoPath else peer?.photoPath) ?: "", 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, maxLines = 1)
                            if (subtitle != null) Text(subtitle, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isGroup(chat) && group != null) {
                        IconButton(onClick = { showGroup = true }) { Text("ⓘ", fontSize = 22.sp, color = Color.White) }
                    }
                    if (!isGroup(chat)) {
                        IconButton(onClick = { showSafety = true }) { Text("🔒", fontSize = 20.sp) }
                        if (!iBlocked) {
                            IconButton(onClick = { callPeer(false) }) { Text("📞", fontSize = 20.sp) }
                            IconButton(onClick = { callPeer(true) }) { Text("🎥", fontSize = 20.sp) }
                        }
                    }
                    Box {
                        IconButton(onClick = { topMenu = true }) { Text("⋮", color = Color.White, fontSize = 20.sp) }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(text = { Text(if (isMuted) "Unmute notifications" else "Mute notifications") },
                                onClick = { topMenu = false; if (isMuted) app.client.setMuted(chat, 0) else dlg = 1 })
                            if (!isGroup(chat)) {
                                DropdownMenuItem(text = { Text(if (iBlocked) "Unblock" else "Block") },
                                    onClick = { topMenu = false; dlg = 2 })
                                DropdownMenuItem(text = { Text("Report") },
                                    onClick = { topMenu = false; dlg = 3 })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BarGreen, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(ChatBg).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(msgs, key = { it.id }) { m ->
                    val who = if (isGroup(chat) && !m.fromMe) names[m.sender] ?: m.sender else null
                    Bubble(m, who, app, names, me, reactMap[m.id] ?: emptyList(),
                        onReact = { e -> app.client.react(m, e) },
                        onLongPress = { if (m.kind != "info" && m.kind != "call") actionMsg = m },
                        onQuote = { id ->
                            val i = msgs.indexOfFirst { it.id == id }
                            if (i >= 0) scope.launch { listState.animateScrollToItem(i) }
                        })
                }
            }

            val members = if (isGroup(chat)) group?.members?.split(",")?.filter { it.isNotEmpty() && it != me }
                ?: emptyList() else emptyList()
            val mq = Regex("(?:^|\\s)@([^\\s@]*)$").find(text)?.groupValues?.get(1)
            val suggestions = if (mq != null && canWrite && editing == null)
                members.map { it to (names[it] ?: it) }.filter { it.second.contains(mq, ignoreCase = true) }.take(6)
            else emptyList()
            if (suggestions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)) {
                    suggestions.forEach { (phone, label) ->
                        TextButton(onClick = {
                            text = text.dropLast(mq!!.length + 1) + "@" + label + " "
                            mentionPicks.add(phone to label)
                        }) { Text("@$label") }
                    }
                }
            }
            if (canWrite && (replyTo != null || editing != null)) {
                val r = replyTo
                val ed = editing
                Row(Modifier.fillMaxWidth().background(Color(0xFFEFEFEF)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (ed != null) {
                            Text("Editing message", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BarGreen)
                            Text(ed.body, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
                        } else if (r != null) {
                            Text("Replying to " + (if (r.fromMe) "yourself" else names[r.sender] ?: r.sender),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BarGreen)
                            Text(com.skychat.app.data.previewText(r.kind, r.body, r.fname), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { if (ed != null) text = ""; replyTo = null; editing = null }) { Text("✕", fontSize = 18.sp) }
                }
            }
            if (!canWrite) {
                Text(if (adminsOnly && inGroup) "Only admins can send messages in this group."
                     else "You can't send messages because you are no longer in this group.",
                    Modifier.fillMaxWidth().padding(16.dp), color = Color.Gray, fontSize = 13.sp)
            } else if (iBlocked) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("You blocked $title.", Modifier.weight(1f), color = Color.Gray, fontSize = 13.sp)
                    TextButton(onClick = { app.client.setBlocked(chat, false) }) { Text("Unblock") }
                }
            } else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    TextButton(onClick = { recorder.cancel(); recording = false }) {
                        Text("✕", fontSize = 20.sp, color = Color.Red)
                    }
                    Text("🔴 Recording  ${elapsed / 60}:${"%02d".format(elapsed % 60)}",
                        Modifier.weight(1f).padding(start = 8.dp))
                } else {
                    Box {
                        IconButton(onClick = { menu = true }) { Text("📎", fontSize = 22.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Photo") },
                                onClick = { menu = false; pickImage.launch("image/*") })
                            DropdownMenuItem(text = { Text("File") },
                                onClick = { menu = false; pickFile.launch("*/*") })
                        }
                    }
                    OutlinedTextField(
                        text, { text = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        shape = RoundedCornerShape(24.dp), maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
                    )
                }
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        when {
                            recording -> {
                                recording = false
                                recorder.stop()?.let { (f, d) -> app.client.sendVoice(chat, f, d) }
                            }
                            text.isNotBlank() -> {
                                val ed = editing
                                if (ed != null) app.client.editMessage(ed, text)
                                else {
                                    val mj = org.json.JSONArray()
                                    mentionPicks.filter { text.contains("@" + it.second) }.distinctBy { it.first }
                                        .forEach { mj.put(org.json.JSONArray().put(it.first).put(it.second)) }
                                    app.client.send(chat, text.trim(), replyTo, if (mj.length() > 0) mj.toString() else "")
                                }
                                mentionPicks.clear()
                                text = ""; replyTo = null; editing = null
                                app.client.typingNow(chat, false)
                            }
                            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED -> startRec()
                            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    containerColor = Accent, contentColor = Color.White, shape = CircleShape
                ) {
                    if (recording || text.isNotBlank())
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    else Text("🎤", fontSize = 22.sp)
                }
            }
        }
    }
}

/** Members, add / remove (owner only) and leave. */
@Composable
private fun GroupInfoDialog(app: SkyApp, group: ChatGroup, names: Map<String, String>, onClose: () -> Unit) {
    val me = app.prefs.phone
    val members = group.members.split(",").filter { it.isNotEmpty() }
    val iAmOwner = group.owner == me
    val iAmAdmin = group.isAdmin(me)
    val isMember = members.contains(me)
    var showInvite by remember { mutableStateOf(false) }
    var newPhone by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var descr by remember(group.gid, group.descr) { mutableStateOf(group.descr) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) app.client.setGroupInfo(group.gid, null, uri, false)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(group.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(app, group.photoPath, 96.dp)
                    if (iAmAdmin) Row {
                        TextButton(onClick = { pick.launch("image/*") }) { Text(if (group.photoPath.isEmpty()) "Add photo" else "Change photo") }
                        if (group.photoPath.isNotEmpty())
                            TextButton(onClick = { app.client.setGroupInfo(group.gid, null, null, true) }) { Text("Remove", color = Color.Red) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (iAmAdmin) {
                    OutlinedTextField(descr, { if (it.length <= 500) descr = it }, Modifier.fillMaxWidth(),
                        label = { Text("Description") }, supportingText = { Text("${descr.length}/500") })
                    TextButton(enabled = descr.trim() != group.descr,
                        onClick = { app.client.setGroupInfo(group.gid, descr, null, false) }) { Text("Save description") }
                } else if (group.descr.isNotEmpty()) {
                    Text(group.descr, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(8.dp))
                if (iAmAdmin) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Only admins can send messages", Modifier.weight(1f))
                        Switch(group.announce, { app.client.setAnnounce(group.gid, it) })
                    }
                    TextButton(onClick = { showInvite = true; app.client.requestInvite(group.gid) }) { Text("Invite link") }
                } else if (group.announce) {
                    Text("Only admins can send messages.", fontSize = 13.sp, color = Color.Gray)
                }
                Text("${members.size} members", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                members.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (p == me) "You" else names[p] ?: p) + if (p == group.owner) "  · owner" else if (group.isAdmin(p)) "  · admin" else "")
                            if (p != me && names[p] != null) Text(p, fontSize = 12.sp, color = Color.Gray)
                        }
                        if (iAmAdmin && p != me && p != group.owner)
                            TextButton(onClick = { app.client.setAdmin(group.gid, p, !group.isAdmin(p)) }) {
                                Text(if (group.isAdmin(p)) "Dismiss admin" else "Make admin")
                            }
                        if (p != me && (iAmOwner || (iAmAdmin && !group.isAdmin(p))))
                            TextButton(onClick = { app.client.removeMember(group.gid, p) }) {
                                Text("Remove", color = Color.Red)
                            }
                    }
                }
                if (iAmAdmin) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(newPhone, { newPhone = it }, singleLine = true,
                        label = { Text("Add by phone number, e.g. +8801700000009") })
                    TextButton(
                        enabled = newPhone.trim().startsWith("+") && newPhone.trim().length >= 8,
                        onClick = { app.client.addMembers(group.gid, listOf(newPhone.trim())); newPhone = "" }
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {
            if (isMember) TextButton(onClick = { confirmLeave = true }) { Text("Leave group", color = Color.Red) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
    if (showInvite) {
        val code = app.client.invites.collectAsState().value[group.gid]
        val link = if (code == null) null else "skychat://join/$code"
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { showInvite = false },
            title = { Text("Invite link") },
            text = {
                Column {
                    Text(link ?: "Getting the link…", fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Anyone who has this link can join the group. Reset it to make the old link stop working.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = link != null, onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "Join my group \"${group.name}\" on Sky: $link\nCode: $code")
                        }
                        ctx.startActivity(android.content.Intent.createChooser(send, null))
                    }) { Text("Share") }
                    TextButton(enabled = link != null, onClick = {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("invite", link))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { app.client.requestInvite(group.gid, reset = true) }) { Text("Reset") }
                    TextButton(onClick = { showInvite = false }) { Text("Close") }
                }
            }
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave \"${group.name}\"?") },
            text = { Text("You will no longer receive messages from this group." +
                if (iAmOwner) " Someone else becomes the owner." else "") },
            confirmButton = {
                TextButton(onClick = { app.client.leaveGroup(group.gid); confirmLeave = false; onClose() }) {
                    Text("Leave", color = Color.Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ActionItem(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = color, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Msg, senderName: String?, app: SkyApp, names: Map<String, String>, me: String?,
    reacts: List<Reaction>, onReact: (String) -> Unit,
    onLongPress: () -> Unit, onQuote: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (m.fromMe) BubbleOut else Color.White,
            shape = RoundedCornerShape(12.dp), shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (senderName != null)
                    Text(senderName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BarGreen)
                if (m.fwd && m.kind != "deleted")
                    Text("↪ Forwarded", fontSize = 11.sp, fontStyle = FontStyle.Italic, color = Color.Gray)
                if (m.replyId.isNotEmpty() && m.kind != "deleted") {
                    Row(Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color(0x14000000)).clickable { onQuote(m.replyId) }) {
                        Box(Modifier.width(3.dp).height(36.dp).background(BarGreen))
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(if (m.quoteBy == me) "You" else names[m.quoteBy] ?: m.quoteBy,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BarGreen, maxLines = 1)
                            Text(m.quote, fontSize = 12.sp, color = Color.DarkGray, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (m.kind == "deleted") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (m.fromMe) "🚫 You deleted this message" else "🚫 This message was deleted",
                            fontStyle = FontStyle.Italic, color = Color.Gray, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(timeText(m.ts), fontSize = 11.sp, color = Color.Gray)
                    }
                } else if (m.kind == "text") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(mentionText(m.body, m.mentions, me), modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        TimeAndTicks(m)
                    }
                } else {
                    MediaContent(m, app)
                    if (m.body.isNotBlank()) Text(m.body)
                    Row(Modifier.align(Alignment.End)) { TimeAndTicks(m) }
                }
                if (reacts.isNotEmpty() && m.kind != "deleted") {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        reacts.groupBy { it.emoji }.forEach { (e, who) ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (who.any { it.phone == me }) Color(0x3325D366) else Color(0x14000000),
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onReact(e) }
                            ) {
                                Text(if (who.size > 1) "$e ${who.size}" else e, fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Body text with "@Name" of each mention highlighted (yellow background if it is me). */
private fun mentionText(body: String, json: String, me: String?): AnnotatedString {
    if (json.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        append(body)
        try {
            val a = org.json.JSONArray(json)
            for (i in 0 until a.length()) {
                val e = a.getJSONArray(i)
                val tag = "@" + e.getString(1)
                val style = SpanStyle(color = Color(0xFF1565C0), fontWeight = FontWeight.Bold,
                    background = if (e.getString(0) == me) Color(0x55FFD54F) else Color.Unspecified)
                var from = 0
                while (true) {
                    val k = body.indexOf(tag, from)
                    if (k < 0) break
                    addStyle(style, k, k + tag.length)
                    from = k + tag.length
                }
            }
        } catch (_: Exception) { }
    }
}

@Composable
private fun TimeAndTicks(m: Msg) {
    if (m.starred) Text("★ ", fontSize = 11.sp, color = Color(0xFFF5B301))
    if (m.edited) Text("edited  ", fontSize = 11.sp, color = Color.Gray)
    Text(timeText(m.ts), fontSize = 11.sp, color = Color.Gray)
    if (m.fromMe) {
        Spacer(Modifier.width(3.dp))
        Ticks(m.status)
    }
}

@Composable
private fun MediaContent(m: Msg, app: SkyApp) {
    val ctx = LocalContext.current
    val hasLocal = m.localPath.isNotEmpty() && File(m.localPath).exists()
    when (m.kind) {
        "image" ->
            if (hasLocal) ImageThumb(app, m.localPath) { openFile(ctx, app.media, m.localPath, "image/jpeg", "photo.jpg") }
            else Text("📷 Photo · tap to download", color = Color.Gray,
                modifier = Modifier.clickable { app.client.fetch(m) })

        "audio" -> {
            val playing by AudioPlayer.playing.collectAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    if (hasLocal) AudioPlayer.toggle(m.id, app.media, m.localPath) else app.client.fetch(m)
                }
            ) {
                Text(if (playing == m.id) "⏸" else "▶", fontSize = 26.sp)
                Spacer(Modifier.width(8.dp))
                Text("🎤 " + durText(m.dur) + if (hasLocal) "" else " · tap to download")
            }
        }

        "call", "info" -> Text(m.body, color = Color.DarkGray)

        else -> Column(Modifier.clickable {
            if (hasLocal) openFile(ctx, app.media, m.localPath, m.mime, m.fname) else app.client.fetch(m)
        }) {
            Text("📎 " + m.fname.ifEmpty { "File" }, fontWeight = FontWeight.Medium)
            Text(sizeText(m.size) + if (hasLocal) " · tap to open" else " · tap to download",
                fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ImageThumb(app: SkyApp, path: String, onClick: () -> Unit) {
    var failed by remember(path) { mutableStateOf(false) }
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            // Decrypted in memory only; nothing plaintext touches the disk for thumbnails.
            val bytes = app.media.readBytes(File(path))
            if (bytes == null) { null } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / s > 800) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }
        }
        if (value == null) failed = true
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b, contentDescription = "Photo", contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp)
                .clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        )
    } else {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) { Text(if (failed) "⚠" else "…") }
    }
}

/** "today at 14:05", "yesterday at 09:30", otherwise "12 Oct 2026". */
private fun lastSeenText(t: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
    fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    val yest = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(now, then) -> "today at $hm"
        sameDay(yest, then) -> "yesterday at $hm"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}

private fun durText(ms: Int): String {
    val s = ms / 1000
    return "${s / 60}:${"%02d".format(s % 60)}"
}

private fun sizeText(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1024.0 / 1024.0)
}

@Composable
private fun Ticks(status: Int) {
    when (status) {
        St.PENDING -> Text("🕓", fontSize = 10.sp)
        St.SENT -> Text("✓", fontSize = 12.sp, color = Color.Gray)
        St.DELIVERED -> Text("✓✓", fontSize = 12.sp, color = Color.Gray)
        St.READ -> Text("✓✓", fontSize = 12.sp, color = ReadBlue)
        else -> Text("!", fontSize = 12.sp, color = Color.Red)
    }
}
