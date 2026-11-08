package com.skychat.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.skychat.app.data.ChatState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import kotlinx.coroutines.withContext
import androidx.compose.ui.focus.focusRequester
import com.skychat.app.data.isGroup
import com.skychat.app.data.likePattern
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.skychat.app.data.previewText
import com.skychat.app.net.AuthEvent
import com.skychat.app.net.Conn

private data class ChatItem(
    val id: String, val title: String, val preview: String, val ts: Long, val unread: Int,
    val key: String = id, val typing: Boolean = false,
    val pinned: Boolean = false, val archived: Boolean = false, val muted: Boolean = false,
    val markedUnread: Boolean = false, val blocked: Boolean = false, val photo: String = ""
)

fun toastText(code: String) = when (code) {
    "unknown_member" -> t("One of the numbers is not registered on Sky")
    "not_member" -> t("You are not a member of this group")
    "unknown_group" -> t("This group no longer exists")
    "not_owner", "not_admin" -> t("Only group admins can do that")
    "admins_only" -> t("Only admins can send messages in this group")
    "bad_invite" -> t("This invite link is not valid any more")
    "join_pending" -> t("Request sent. An admin of the group has to approve it.")
    "join_rejected" -> t("An admin did not accept your request to join the group")
    "too_many_requests" -> t("This group has too many open join requests right now")
    "call_busy" -> t("You are already in a call")
    "bad_pin" -> t("Wrong PIN")
    "pin_locked" -> t("Too many wrong tries. Try again in 15 minutes.")
    "bad_pin_format" -> t("The PIN must be 6 digits")
    "sms_failed" -> t("Could not send the SMS. Try again later.")
    "registration_closed" -> t("New registrations are closed right now.")
    "group_full" -> t("A group can have at most 50 members")
    "unknown_recipient" -> t("That number is not registered on Sky")
    "too_large" -> t("File is larger than 25 MB")
    "rate_limited" -> t("You're going too fast. Wait a moment.")
    "no_connection" -> t("Not connected to the server")
    "call_unverified" -> "The call could not be verified, so it was refused"
    "e2e_required" -> t("Please update Sky")
    "no_keys" -> "This person hasn't set up secure messaging yet. Ask them to open the latest Sky."
    "encrypt_failed" -> t("Couldn't encrypt the message")
    "bad_file" -> t("Couldn't read that file")
    "pin_limit" -> t("You can pin up to 3 chats")
    "reported" -> t("Report sent")
    "profile_saved" -> t("Profile updated")
    "bad_name" -> "Please enter a name (at most 64 bytes)"
    "upload_failed" -> t("Upload failed. Try again.")
    "too_many_blocks" -> t("You can block up to 1000 people")
    else -> code
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(app: SkyApp, onStarred: () -> Unit, onProfile: () -> Unit, onSettings: () -> Unit, onQr: () -> Unit, onOpen: (String) -> Unit) {
    val dao = app.db.dao()
    val chats by dao.lastMessages().collectAsState(initial = emptyList())
    val unread by dao.unread().collectAsState(initial = emptyList())
    val contacts by dao.contacts().collectAsState(initial = emptyList())
    val groups by dao.groups().collectAsState(initial = emptyList())
    val conn by app.client.state.collectAsState()
    var dialog by remember { mutableIntStateOf(0) }   // 0 none, 1 new chat, 2 new group, 3 log out, 4 privacy
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val typingAll by app.client.typing.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val states by dao.chatStates().collectAsState(initial = emptyList())
    val peerProfiles by dao.peerProfiles().collectAsState(initial = emptyList())
    val broadcasts by dao.broadcasts().collectAsState(initial = emptyList())
    val blockedList by dao.blockedPhones().collectAsState(initial = emptyList())
    var archivedView by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }             // 0 Chats, 1 Updates, 2 Calls, 3 Communities
    var filter by remember { mutableIntStateOf(0) }          // 0 All, 1 Unread, 2 Groups
    val calls by dao.callLog().collectAsState(initial = emptyList())
    var actionChat by remember { mutableStateOf<ChatItem?>(null) }   // long-pressed row
    var chatDlg by remember { mutableIntStateOf(0) }                 // 1 mute, 2 block, 3 report
    BackHandler(enabled = archivedView && !searching) { archivedView = false }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        app.client.events.collect { e ->
            when (e) {
                is AuthEvent.Profile ->
                    if (e.exists) onOpen(e.phone)
                    else Toast.makeText(ctx, "${e.phone} is not registered on Sky", Toast.LENGTH_SHORT).show()
                is AuthEvent.Error ->
                    Toast.makeText(ctx, toastText(e.code), Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    val stateMap = states.associateBy { it.chat }
    val photoOf: Map<String, String> =
        peerProfiles.filter { it.photoPath.isNotEmpty() }.associate { it.phone to it.photoPath } +
        groups.filter { it.photoPath.isNotEmpty() }.associate { it.gid to it.photoPath }
    val blockedSet = blockedList.toSet()
    val names = contacts.associate { it.phone to it.name }
    val gmap = groups.associateBy { it.gid }
    val unreadMap = unread.associate { it.chat to it.n }
    val bmap = broadcasts.associateBy { it.id }
    fun titleOf(id: String) =
        if (id.startsWith("b:")) bmap[id]?.name ?: t("Broadcast list")
        else if (isGroup(id)) gmap[id]?.name ?: t("Group") else names[id] ?: id

    val items = (chats.map { m ->
        val who = when {
            m.fromMe -> t("You: ")
            isGroup(m.chat) -> (names[m.sender] ?: m.sender) + ": "
            else -> ""
        }
        val st = stateMap[m.chat]
        ChatItem(m.chat, titleOf(m.chat), who + previewText(m.kind, m.body, m.fname), m.ts, unreadMap[m.chat] ?: 0,
            typing = (typingAll[m.chat] ?: emptyMap()).any { it.value > System.currentTimeMillis() },
            pinned = (st?.pinned ?: 0) > 0, archived = st?.archived == true, muted = st?.muted() == true,
            markedUnread = st?.markedUnread == true, blocked = m.chat in blockedSet, photo = photoOf[m.chat] ?: "")
    } + broadcasts.filter { b -> chats.none { it.chat == b.id } }.map { b ->
        val st = stateMap[b.id]
        ChatItem(b.id, b.name, t("Broadcast list"), b.created, 0, pinned = (st?.pinned ?: 0) > 0, archived = st?.archived == true,
            muted = st?.muted() == true, markedUnread = st?.markedUnread == true)
    } + groups.filter { g -> chats.none { it.chat == g.gid } }.map {
        val st = stateMap[it.gid]
        ChatItem(it.gid, it.name, t("Group created"), it.created, 0,
            pinned = (st?.pinned ?: 0) > 0, archived = st?.archived == true, muted = st?.muted() == true,
            markedUnread = st?.markedUnread == true, photo = photoOf[it.gid] ?: "")
    })
    val archivedCount = items.count { it.archived }
    // Pinned chats first (most recently pinned first), then the rest by last message.
    val pinTime = { c: ChatItem -> stateMap[c.id]?.pinned ?: 0L }
    val mainList = items.filter { !it.archived }
        .sortedWith(compareByDescending<ChatItem> { it.pinned }.thenByDescending { if (it.pinned) pinTime(it) else it.ts })
    val archivedList = items.filter { it.archived }.sortedByDescending { it.ts }

    // Search: chats whose name or number matches, then messages that contain the text.
    val q = query.trim()
    val hits by produceState(emptyList<com.skychat.app.data.Msg>(), q) {
        value = if (q.length < 2) emptyList() else withContext(kotlinx.coroutines.Dispatchers.IO) { dao.search(likePattern(q)) }
    }
    val shown: List<ChatItem> = if (!searching || q.isEmpty()) (if (archivedView) archivedList else when (filter) {
        1 -> mainList.filter { it.unread > 0 || it.markedUnread }
        2 -> mainList.filter { isGroup(it.id) }
        else -> mainList
    }) else {
        items.filter { it.title.contains(q, ignoreCase = true) || it.id.contains(q) } +
        hits.map { m ->
            val who = when {
                m.fromMe -> t("You: ")
                isGroup(m.chat) -> (names[m.sender] ?: m.sender) + ": "
                else -> ""
            }
            ChatItem(m.chat, titleOf(m.chat), who + previewText(m.kind, m.body, m.fname), m.ts, 0, key = "m:" + m.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            query, { query = it }, singleLine = true,
                            placeholder = { Text(t("Search chats and messages"), color = Color(0xCCFFFFFF)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = Color.White)
                        )
                    } else if (archivedView) {
                        Text(t("Archived"), fontWeight = FontWeight.Bold)
                    } else Column {
                        Text("Sky", fontWeight = FontWeight.Bold)
                        if (conn != Conn.Online)
                            Text(if (conn == Conn.Connecting) t("Connecting…") else t("Offline"), fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = t("Close search"), tint = Color.White)
                    } else if (archivedView) IconButton(onClick = { archivedView = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White)
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = t("Search"), tint = Color.White)
                        }
                        if (!archivedView) TextButton(onClick = { dialog = 2 }) { Text(t("New group"), color = Color.White) }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Text("⋮", color = Color.White, fontSize = 20.sp)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(t("New broadcast")) },
                                onClick = { menuOpen = false; dialog = 7 })
                            DropdownMenuItem(text = { Text(t("Settings")) },
                                onClick = { menuOpen = false; onSettings() })
                            DropdownMenuItem(text = { Text(t("Server address")) },
                                onClick = { menuOpen = false; dialog = 6 })
                            DropdownMenuItem(text = { Text(t("Join group by link")) },
                                onClick = { menuOpen = false; dialog = 5 })
                            DropdownMenuItem(text = { Text(t("Profile")) },
                                onClick = { menuOpen = false; onProfile() })
                            DropdownMenuItem(text = { Text(t("Starred messages")) },
                                onClick = { menuOpen = false; onStarred() })
                            DropdownMenuItem(text = { Text(t("Privacy")) },
                                onClick = { menuOpen = false; dialog = 4 })
                            DropdownMenuItem(text = { Text(t("Log out")) },
                                onClick = { menuOpen = false; dialog = 3 })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Sky.bar, titleContentColor = Color.White)
            )
        },
        bottomBar = {
            if (!searching) NavigationBar(containerColor = Sky.surface) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { BadgedBox(badge = { if (items.any { it.unread > 0 }) Badge { Text("${items.count { it.unread > 0 }}") } }) { Text("💬", fontSize = 20.sp) } },
                    label = { Text(t("Chats")) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1; archivedView = false },
                    icon = { Text("◎", fontSize = 22.sp) }, label = { Text(t("Updates")) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2; archivedView = false },
                    icon = { Text("📞", fontSize = 20.sp) }, label = { Text(t("Calls")) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3; archivedView = false },
                    icon = { Text("🏘", fontSize = 20.sp) }, label = { Text(t("Communities")) })
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(onClick = { dialog = 1 },
                containerColor = Accent, contentColor = Color.White) {
                Icon(Icons.Filled.Add, contentDescription = t("New chat"))
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
        if (tab == 0 && !searching && !archivedView) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable { searching = true },
                shape = RoundedCornerShape(24.dp), color = Sky.pill) {
                Text(t("🔍  Search chats and messages"), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color.Gray)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(t("All"), t("Unread"), t("Groups")).forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
        }
        Box(Modifier.weight(1f)) {
        if (tab == 1) UpdatesTab(app, { titleOf(it) }, photoOf)
        else if (tab == 3) CommunitiesTab(app, onOpen)
        else if (tab == 2) {
            if (calls.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("No calls yet."), color = Color.Gray)
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(calls, key = { it.id }) { m ->
                    val missed = m.body.endsWith("Missed")
                    Row(Modifier.fillMaxWidth().clickable { onOpen(m.chat) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(app, photoOf[m.chat] ?: "", 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(titleOf(m.chat), fontWeight = FontWeight.Medium, maxLines = 1,
                                color = if (missed) Color(0xFFD32F2F) else Color.Unspecified)
                            Text(m.body, color = Color.Gray, fontSize = 14.sp, maxLines = 1)
                        }
                        Text(timeText(m.ts), fontSize = 12.sp, color = Color.Gray)
                    }
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = Sky.divider)
                }
            }
        } else
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searching && q.isNotEmpty()) (if (q.length < 2) t("Type at least 2 letters") else t("Nothing found"))
                     else if (archivedView) t("No archived chats.")
                     else t("No chats yet. Tap + to start one."), color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (!archivedView && !searching && archivedCount > 0) item(key = "archived-row") {
                    Row(Modifier.fillMaxWidth().clickable { archivedView = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🗄", fontSize = 20.sp, modifier = Modifier.width(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(t("Archived"), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("$archivedCount", color = Color.Gray)
                    }
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = Sky.divider)
                }
                items(shown, key = { it.key }) { c ->
                    ChatRow(app, c, onLongClick = { if (c.key == c.id) actionChat = c }) { onOpen(c.id) }
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = Sky.divider)
                }
            }
        }
        }
        }
    }

    // ---- long-press menu of a chat row ----
    var target by remember { mutableStateOf<ChatItem?>(null) }
    actionChat?.let { c ->
        val st = stateMap[c.id]
        AlertDialog(
            onDismissRequest = { actionChat = null },
            title = { Text(c.title, maxLines = 1) },
            text = {
                Column {
                    if (!c.archived) ChatMenuLine(if (c.pinned) t("Unpin chat") else t("Pin chat")) {
                        actionChat = null; app.client.setPinned(c.id, !c.pinned)
                    }
                    ChatMenuLine(if (c.archived) t("Unarchive") else t("Archive")) {
                        actionChat = null; app.client.setArchived(c.id, !c.archived)
                    }
                    ChatMenuLine(if (c.muted) t("Unmute notifications") else t("Mute notifications")) {
                        actionChat = null
                        if (c.muted) app.client.setMuted(c.id, 0) else { target = c; chatDlg = 1 }
                    }
                    if (c.unread > 0 || c.markedUnread) ChatMenuLine(t("Mark as read")) {
                        actionChat = null
                        if (c.unread > 0) app.client.markChatRead(c.id)
                        if (st?.markedUnread == true) app.client.setMarkedUnread(c.id, false)
                    } else ChatMenuLine(t("Mark as unread")) {
                        actionChat = null; app.client.setMarkedUnread(c.id, true)
                    }
                    if (!isGroup(c.id)) ChatMenuLine(if (c.blocked) t("Unblock") else t("Block")) {
                        actionChat = null; target = c; chatDlg = 2
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionChat = null }) { Text(t("Cancel")) } }
        )
    }
    target?.let { c ->
        val done = { chatDlg = 0; target = null }
        if (chatDlg == 1) MuteDialog(isGroup(c.id), onPick = { app.client.setMuted(c.id, it); done() }, onDismiss = { done() })
        if (chatDlg == 2) BlockDialog(c.title, block = !c.blocked,
            onConfirm = { app.client.setBlocked(c.id, !c.blocked); done() }, onDismiss = { done() })
    }

    if (dialog == 1) {
        var phone by remember { mutableStateOf("+880") }
        val people by dao.localContacts().collectAsState(initial = emptyList())
        val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) app.client.syncContacts()
            else Toast.makeText(ctx, t("Allow contacts to find your friends on Sky"), Toast.LENGTH_SHORT).show()
        }
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(t("New chat")) },
            text = {
                Column {
                    OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(t("Phone number")) })
                    Row {
                        TextButton(onClick = {
                            if (app.client.hasContactsPermission()) app.client.syncContacts()
                            else askContacts.launch(android.Manifest.permission.READ_CONTACTS)
                        }) { Text(t("Find friends")) }
                        TextButton(onClick = { dialog = 0; onQr() }) { Text(t("QR code")) }
                    }
                    if (people.isEmpty()) Text(t("Friends from your contacts who use Sky will appear here."),
                        fontSize = 12.sp, color = Color.Gray)
                    else LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(people, key = { it.phone }) { c ->
                            Row(Modifier.fillMaxWidth().clickable { dialog = 0; onOpen(c.phone) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Avatar(app, photoOf[c.phone] ?: "", 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(c.name, maxLines = 1)
                                    Text(c.phone, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = phone.startsWith("+") && phone.length >= 8,
                    onClick = { dialog = 0; app.client.lookup(phone.trim()) }
                ) { Text(t("Chat")) }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text(t("Cancel")) } }
        )
    }

    if (dialog == 4) PrivacyDialog(app) { dialog = 0 }

    if (dialog == 3) {
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(t("Log out?")) },
            text = { Text(t("Chats stored on this phone will be removed.")) },
            confirmButton = {
                TextButton(onClick = { dialog = 0; app.client.logout() }) { Text(t("Log out")) }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text(t("Cancel")) } }
        )
    }

    if (dialog == 7) {
        var bname by remember { mutableStateOf("") }
        val picked = remember { mutableStateListOf<String>() }
        val localPeople by dao.localContacts().collectAsState(initial = emptyList())
        val everyone = (localPeople.map { it.phone } + items.filter { !isGroup(it.id) }.map { it.id }).distinct()
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(t("New broadcast")) },
            text = {
                Column {
                    Text(t("Each person gets your message as a normal chat message."), fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(bname, { bname = it }, singleLine = true, label = { Text(t("Broadcast name")) })
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(everyone, key = { it }) { p ->
                            Row(Modifier.fillMaxWidth().clickable { if (p in picked) picked.remove(p) else picked.add(p) },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(p in picked, { if (it) picked.add(p) else picked.remove(p) })
                                Text(titleOf(p), maxLines = 1)
                            }
                        }
                    }
                    if (picked.size < 2) Text(t("Choose at least 2 people"), fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(enabled = picked.size >= 2, onClick = { app.client.createBroadcast(bname, picked.toList()); dialog = 0 }) { Text(t("Create")) } },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text(t("Cancel")) } }
        )
    }

    if (dialog == 6) {
        var url by remember { mutableStateOf(app.prefs.serverUrl) }
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(t("Server address")) },
            text = {
                Column {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("wss://chat.example.com/ws") })
                    ServerNote(url)
                }
            },
            confirmButton = { TextButton(enabled = serverProblem(url) == null, onClick = { app.client.changeServer(url); dialog = 0 }) { Text(t("Save")) } },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text(t("Cancel")) } }
        )
    }

    if (dialog == 5) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(t("Join group by link")) },
            text = {
                OutlinedTextField(link, { link = it }, singleLine = true,
                    label = { Text(t("Paste the link or code")) })
            },
            confirmButton = {
                TextButton(enabled = link.isNotBlank(), onClick = { app.client.joinInvite(link); dialog = 0 }) { Text(t("Join")) }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text(t("Cancel")) } }
        )
    }

    if (dialog == 2) {
        var gname by remember { mutableStateOf("") }
        var members by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text(t("New group")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(gname, { gname = it }, singleLine = true, label = { Text(t("Group name")) })
                    OutlinedTextField(members, { members = it }, label = { Text(t("Members (comma separated numbers)")) },
                        placeholder = { Text("+8801700000002, +8801700000003") })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = gname.isNotBlank() && members.isNotBlank(),
                    onClick = {
                        dialog = 0
                        app.client.createGroup(gname.trim(),
                            members.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                ) { Text(t("Create")) }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text(t("Cancel")) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(app: SkyApp, c: ChatItem, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(app, c.photo, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = if (c.unread > 0 || c.markedUnread) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            if (c.typing) Text("typing…", color = Accent, maxLines = 1)
            else Text(c.preview, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val hot = (c.unread > 0 || c.markedUnread) && !c.muted
            Text(timeText(c.ts), fontSize = 12.sp, color = if (hot) Accent else Color.Gray)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.blocked) Text("🚫", fontSize = 12.sp)
                if (c.muted) Text("🔇", fontSize = 12.sp)
                if (c.pinned) Text("📌", fontSize = 12.sp)
                if (c.unread > 0) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .clip(CircleShape).background(if (c.muted) Color(0xFF9E9E9E) else Accent)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("${c.unread}", color = Color.White, fontSize = 12.sp) }
                } else if (c.markedUnread) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (c.muted) Color(0xFF9E9E9E) else Accent))
                }
            }
        }
    }
}

@Composable
private fun ChatMenuLine(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp))
}
