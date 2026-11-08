package com.skychat.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp

/** Online status / typing switches (also used from the chat list menu). */
@Composable
fun PrivacyDialog(app: SkyApp, onDismiss: () -> Unit) {
    var presence by remember { mutableStateOf(app.prefs.sharePresence) }
    var typingOn by remember { mutableStateOf(app.prefs.shareTyping) }
    var previews by remember { mutableStateOf(app.prefs.linkPreviews) }
    var noShots by remember { mutableStateOf(app.prefs.blockScreenshots) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Privacy")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Online status and last seen"))
                        Text(t("If you turn this off, you won't see other people's either."), fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(presence, { presence = it; app.client.setSharePresence(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Typing indicator"))
                        Text("Show \"typing…\" to the other person while you write.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(typingOn, { typingOn = it; app.prefs.shareTyping = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Block screenshots"))
                        Text("No screenshots or screen recording of Sky, and Sky is hidden in the recent-apps list.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(noShots, { noShots = it; app.prefs.blockScreenshots = it; app.refreshSecure() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Link previews"))
                        Text("When you send a link, your phone loads the page to add a title and description. Receivers never load it.",
                            fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(previews, { previews = it; app.prefs.linkPreviews = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Done")) } }
    )
}

@Composable
private fun Item(icon: String, title: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Column {
            Text(title, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 13.sp, color = Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: SkyApp, onBack: () -> Unit, onProfile: () -> Unit, onStarred: () -> Unit, onQr: () -> Unit) {
    val ctx = LocalContext.current
    val dao = app.db.dao()
    var dlg by remember { mutableIntStateOf(0) }    // 1 privacy, 2 blocked, 3 theme, 4 server, 5 log out
    val mode by app.themeMode.collectAsState()
    val pinOn by app.client.pinOnFlow.collectAsState()
    val ver by app.client.avatarVersion.collectAsState()
    val photo = if (ver >= 0 && app.client.hasOwnPhoto) app.client.ownPhotoFile.absolutePath else ""
    val modeName = listOf(t("System default"), t("Light"), t("Dark"))[mode.coerceIn(0, 2)]
    val storage = remember {
        val bytes = app.client.mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (bytes < 1_000_000) "${bytes / 1000} KB" else "%.1f MB".format(bytes / 1_000_000.0)
    }
    val versionName = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(t("Settings")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back")) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.bar, titleContentColor = Color.White,
                navigationIconContentColor = Color.White)
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onProfile).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(app, photo, 64.dp, version = ver)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.prefs.name ?: t("Your name"), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(app.client.ownAbout, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
                    Text(app.prefs.phone ?: "", fontSize = 12.sp, color = Color.Gray)
                }
            }
            HorizontalDivider(color = Sky.divider)
            Item("▦", t("QR code"), t("Your code and scanner")) { onQr() }
            Item("🔒", t("Privacy"), t("Online status, typing, screenshots")) { dlg = 1 }
            Item("💾", t("Chat backup"), t("Save or restore your chats and files")) { dlg = 8 }
            Item("🔐", t("App lock"), if (app.prefs.appLock) t("On") else t("Off")) { dlg = 6 }
            Item("🔑", t("Two-step verification"), if (pinOn) t("On") else t("Off")) { dlg = 7 }
            Item("🚫", t("Blocked contacts")) { dlg = 2 }
            Item("★", t("Starred messages")) { onStarred() }
            Item("🎨", t("Appearance"), "Theme: $modeName") { dlg = 3 }
            Item("🌐", t("Language"), when (app.lang.collectAsState().value) { "bn" -> "বাংলা"; "en" -> t("English"); else -> t("System default") }) { dlg = 9 }
            Item("🔠", t("Font size"), "%d%%".format((app.fontScale.collectAsState().value * 100).toInt())) { dlg = 10 }
            Item("🖼", t("Chat wallpaper")) { dlg = 11 }
            Item("🔔", t("Notifications"), t("Message, group and call tones")) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            Item("💾", t("Storage and data"), "Media on this phone: $storage") { dlg = 12 }
            Item("🛠", t("Server address"), app.prefs.serverUrl) { dlg = 4 }
            Item("👥", t("Invite a friend")) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t("Let's chat on Sky."))
                }
                ctx.startActivity(Intent.createChooser(send, null))
            }
            Item("ℹ", t("About"), "Sky $versionName") {}
            Item("⏻", t("Log out")) { dlg = 5 }
        }
    }

    if (dlg == 1) PrivacyDialog(app) { dlg = 0 }
    if (dlg == 2) {
        val blocked by dao.blockedPhones().collectAsState(initial = emptyList())
        val contacts by dao.contacts().collectAsState(initial = emptyList())
        val names = contacts.associate { it.phone to it.name }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(t("Blocked contacts")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (blocked.isEmpty()) Text(t("No blocked contacts."), color = Color.Gray)
                    blocked.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(names[p]?.ifEmpty { null } ?: p, Modifier.weight(1f))
                            TextButton(onClick = { app.client.setBlocked(p, false) }) { Text(t("Unblock")) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Close")) } }
        )
    }
    if (dlg == 3) AlertDialog(
        onDismissRequest = { dlg = 0 },
        title = { Text(t("Theme")) },
        text = {
            Column {
                listOf(t("System default"), t("Light"), t("Dark")).forEachIndexed { i, label ->
                    Row(Modifier.fillMaxWidth().clickable {
                        app.prefs.themeMode = i; app.themeMode.value = i; dlg = 0
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == i, onClick = { app.prefs.themeMode = i; app.themeMode.value = i; dlg = 0 })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) } }
    )
    if (dlg == 4) {
        var url by remember { mutableStateOf(app.prefs.serverUrl) }
        var json by remember { mutableStateOf(app.prefs.preferJson) }
        val problem = serverProblem(url)
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(t("Server address")) },
            text = {
                Column {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("wss://chat.example.com/ws") })
                    ServerNote(url)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("JSON text format (fallback)", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(json, { json = it })
                    }
                    Text("Default is MessagePack (binary, smaller). Takes effect when the connection is made again.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(enabled = problem == null, onClick = { app.prefs.preferJson = json; app.client.changeServer(url); dlg = 0 }) { Text(t("Save")) } },
            dismissButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) } }
        )
    }
    if (dlg == 8) BackupDialogs(app) { dlg = 0 }
    if (dlg == 9) AlertDialog(
        onDismissRequest = { dlg = 0 },
        title = { Text(t("Language")) },
        text = {
            Column {
                listOf("system" to t("System default"), "en" to t("English"), "bn" to "বাংলা").forEach { (code, label) ->
                    val pick = { app.prefs.lang = code; app.lang.value = code; dlg = 0 }
                    Row(Modifier.fillMaxWidth().clickable { pick() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = app.lang.value == code, onClick = { pick() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) } }
    )
    if (dlg == 10) AlertDialog(
        onDismissRequest = { dlg = 0 },
        title = { Text(t("Font size")) },
        text = {
            Column {
                listOf(0.85f to t("Small"), 1f to t("Default"), 1.15f to t("Large"), 1.3f to t("Huge")).forEach { (f, label) ->
                    val pick = { app.prefs.fontScale = f; app.fontScale.value = f; dlg = 0 }
                    Row(Modifier.fillMaxWidth().clickable { pick() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = app.fontScale.value == f, onClick = { pick() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) } }
    )
    if (dlg == 11) {
        val pickImg = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) { app.client.setWallpaperImage(uri); dlg = 0 }
        }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(t("Chat wallpaper")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("" to Sky.chatBg, "c:FFF2E7D5" to Color(0xFFF2E7D5), "c:FFD9E8D4" to Color(0xFFD9E8D4), "c:FFCFE2F3" to Color(0xFFCFE2F3),
                            "c:FFF4CCCC" to Color(0xFFF4CCCC), "c:FF1F2C34" to Color(0xFF1F2C34), "c:FF000000" to Color(0xFF000000)).forEach { (v, c) ->
                            Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                                .clickable { app.client.setWallpaper(v); dlg = 0 })
                        }
                    }
                    TextButton(onClick = { pickImg.launch("image/*") }) { Text(t("Choose a picture")) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) } }
        )
    }
    if (dlg == 12) {
        val dao2 = app.db.dao()
        val contacts2 by dao2.contacts().collectAsState(initial = emptyList())
        val nm = contacts2.associate { it.phone to it.name }
        var tick by remember { mutableIntStateOf(0) }
        var kinds by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        var chats by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
        var confirm by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(tick) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val rows = dao2.mediaFiles()
                val k = HashMap<String, Long>(); val c = HashMap<String, Long>()
                for (r in rows) {
                    val n = java.io.File(r.localPath).length()
                    if (n == 0L) continue
                    k[r.kind] = (k[r.kind] ?: 0L) + n; c[r.chat] = (c[r.chat] ?: 0L) + n
                }
                kinds = k; chats = c.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
            }
        }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(t("Storage and data")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(t("Media on this phone"), fontSize = 13.sp, color = Color.Gray)
                    listOf("image" to t("Photos"), "video" to t("Videos"), "audio" to t("Voice notes"), "file" to t("Files"), "sticker" to t("Stickers")).forEach { (k, label) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label, Modifier.weight(1f)); Text(sizeText(kinds[k] ?: 0L), color = Color.Gray) }
                    }
                    Text(t("Biggest chats"), Modifier.padding(top = 12.dp), fontSize = 13.sp, color = Color.Gray)
                    chats.forEach { (chat, bytes) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(nm[chat]?.ifEmpty { null } ?: chat, maxLines = 1); Text(sizeText(bytes), fontSize = 12.sp, color = Color.Gray) }
                            TextButton(onClick = { confirm = chat }) { Text(t("Clear"), color = Color(0xFFD32F2F)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Close")) } }
        )
        confirm?.let { chat ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(t("Clear the files of this chat?")) },
                text = { Text(nm[chat]?.ifEmpty { null } ?: chat) },
                confirmButton = { TextButton(onClick = { app.client.clearChatMedia(chat); confirm = null; tick++ }) { Text(t("Clear"), color = Color(0xFFD32F2F)) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("Cancel")) } }
            )
        }
    }
    if (dlg == 6) {
        var on by remember { mutableStateOf(app.prefs.appLock) }
        var timeout by remember { mutableStateOf(app.prefs.lockTimeoutMs) }
        val secure = remember { app.deviceSecure() }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(t("App lock")) },
            text = {
                Column {
                    if (!secure) Text("Set a screen lock (PIN, pattern, password or fingerprint) in your phone's settings first.",
                        color = Color(0xFFD32F2F), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Unlock Sky with the phone's lock"), Modifier.weight(1f))
                        Switch(on, { on = it; app.prefs.appLock = it; app.refreshSecure() }, enabled = secure)
                    }
                    if (on) {
                        Text(t("Lock again"), Modifier.padding(top = 12.dp), fontSize = 13.sp, color = Color.Gray)
                        listOf(0L to t("Immediately"), 60_000L to t("After 1 minute"), 1_800_000L to t("After 30 minutes")).forEach { (ms, label) ->
                            Row(Modifier.fillMaxWidth().clickable { timeout = ms; app.prefs.lockTimeoutMs = ms }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = timeout == ms, onClick = { timeout = ms; app.prefs.lockTimeoutMs = ms })
                                Text(label)
                            }
                        }
                        Text("Calls can still be answered when Sky is locked. Notifications still show their text.", fontSize = 12.sp, color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Done")) } }
        )
    }
    if (dlg == 7) {
        val on = pinOn
        var pin by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        var old by remember { mutableStateOf("") }
        var removing by remember { mutableStateOf(false) }
        val ok = pin.length == 6 && pin == pin2 && (!on || old.length == 6)
        fun digits(s: String) = s.length <= 6 && s.all { it.isDigit() }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text(t("Two-step verification")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("With a PIN, somebody who gets your SMS code still cannot register your number on another phone. " +
                        "If you forget the PIN, you can register again after the number was not used for 7 days.", fontSize = 12.sp, color = Color.Gray)
                    val pw = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    if (on) OutlinedTextField(old, { if (digits(it)) old = it }, singleLine = true, label = { Text(t("Current PIN")) }, visualTransformation = pw, keyboardOptions = kb)
                    if (!removing) {
                        OutlinedTextField(pin, { if (digits(it)) pin = it }, singleLine = true, label = { Text(if (on) t("New PIN (6 digits)") else t("PIN (6 digits)")) }, visualTransformation = pw, keyboardOptions = kb)
                        OutlinedTextField(pin2, { if (digits(it)) pin2 = it }, singleLine = true, label = { Text(t("Repeat PIN")) }, visualTransformation = pw, keyboardOptions = kb)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = if (removing) old.length == 6 else ok, onClick = {
                    if (removing) app.client.removePin(old) else app.client.setPin(pin, old)
                    dlg = 0
                }) { Text(if (removing) t("Turn off") else if (on) t("Change PIN") else t("Turn on")) }
            },
            dismissButton = {
                Row {
                    if (on) TextButton(onClick = { removing = !removing }) { Text(if (removing) t("Back") else t("Turn off…"), color = Color(0xFFD32F2F)) }
                    TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) }
                }
            }
        )
    }
    if (dlg == 5) AlertDialog(
        onDismissRequest = { dlg = 0 },
        title = { Text(t("Log out?")) },
        text = { Text(t("Chats stored on this phone will be removed.")) },
        confirmButton = { TextButton(onClick = { dlg = 0; app.client.logout() }) { Text(t("Log out")) } },
        dismissButton = { TextButton(onClick = { dlg = 0 }) { Text(t("Cancel")) } }
    )
}

/** Chat backup and restore: password, file chooser (any place the phone offers, e.g. Google Drive or an SD card), progress. */
@Composable
private fun BackupDialogs(app: SkyApp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by com.skychat.app.data.Backup.state.collectAsState()
    var mode by remember { mutableIntStateOf(0) }       // 0 menu, 1 password for a backup, 2 password for a restore
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var started by remember { mutableStateOf(false) }

    val createDoc = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        started = true
        val password = pw; pw = ""; pw2 = ""
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val out = ctx.contentResolver.openOutputStream(uri)
            if (out == null) com.skychat.app.data.Backup.state.value = com.skychat.app.data.BackupState(false, t("Could not write the file"), false)
            else com.skychat.app.data.Backup.backup(app, out, password)
        }
    }
    val openDoc = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; mode = 2 }
    }
    fun digitsOk() = pw.length >= 8 && pw == pw2

    if (started) {
        AlertDialog(
            onDismissRequest = { if (!st.busy) onClose() },
            title = { Text(if (st.busy) t("Please wait…") else if (st.ok == true) t("Done") else t("Problem")) },
            text = { Text(st.text) },
            confirmButton = { if (!st.busy) TextButton(onClick = onClose) { Text("OK") } }
        )
        return
    }
    when (mode) {
        0 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(t("Chat backup")) },
            text = {
                Text("The backup file holds your chats and all photos, videos, voice notes and files, locked with a password you choose. " +
                    "Keep the file anywhere you like (the file chooser can save to Google Drive or an SD card). " +
                    "It does not hold your encryption keys: on a new phone your contacts will see a new safety number. " +
                    t("If you forget the password the backup cannot be opened by anybody."), fontSize = 13.sp)
            },
            confirmButton = { TextButton(onClick = { mode = 1 }) { Text(t("Back up")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { openDoc.launch(arrayOf("*/*")) }) { Text(t("Restore")) }
                    TextButton(onClick = onClose) { Text(t("Close")) }
                }
            }
        )
        1 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(t("Choose a password")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(t("Password (at least 8 characters)")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    OutlinedTextField(pw2, { pw2 = it }, singleLine = true, label = { Text(t("Repeat password")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = digitsOk(), onClick = {
                    createDoc.launch("sky-backup-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".skybak")
                }) { Text(t("Choose file")) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(t("Cancel")) } }
        )
        else -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(t("Backup password")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Messages in the backup replace messages with the same id on this phone; everything else stays.", fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(t("Password")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = pw.isNotEmpty(), onClick = {
                    val uri = restoreUri ?: return@TextButton
                    started = true
                    val password = pw; pw = ""
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val inp = ctx.contentResolver.openInputStream(uri)
                        if (inp == null) com.skychat.app.data.Backup.state.value = com.skychat.app.data.BackupState(false, t("Could not read the file"), false)
                        else inp.use { com.skychat.app.data.Backup.restore(app, it, password) }
                    }
                }) { Text(t("Restore")) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(t("Cancel")) } }
        )
    }
}
