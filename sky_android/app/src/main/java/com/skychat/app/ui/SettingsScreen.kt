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
        title = { Text("Privacy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Online status and last seen")
                        Text("If you turn this off, you won't see other people's either.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(presence, { presence = it; app.client.setSharePresence(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Typing indicator")
                        Text("Show \"typing…\" to the other person while you write.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(typingOn, { typingOn = it; app.prefs.shareTyping = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block screenshots")
                        Text("No screenshots or screen recording of Sky, and Sky is hidden in the recent-apps list.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(noShots, { noShots = it; app.prefs.blockScreenshots = it; app.refreshSecure() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Link previews")
                        Text("When you send a link, your phone loads the page to add a title and description. Receivers never load it.",
                            fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(previews, { previews = it; app.prefs.linkPreviews = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
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
    val modeName = listOf("System default", "Light", "Dark")[mode.coerceIn(0, 2)]
    val storage = remember {
        val bytes = app.client.mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (bytes < 1_000_000) "${bytes / 1000} KB" else "%.1f MB".format(bytes / 1_000_000.0)
    }
    val versionName = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.bar, titleContentColor = Color.White,
                navigationIconContentColor = Color.White)
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onProfile).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(app, photo, 64.dp, version = ver)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.prefs.name ?: "Your name", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(app.client.ownAbout, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
                    Text(app.prefs.phone ?: "", fontSize = 12.sp, color = Color.Gray)
                }
            }
            HorizontalDivider(color = Sky.divider)
            Item("▦", "QR code", "Your code and scanner") { onQr() }
            Item("🔒", "Privacy", "Online status, typing, screenshots") { dlg = 1 }
            Item("💾", "Chat backup", "Save or restore your chats and files") { dlg = 8 }
            Item("🔐", "App lock", if (app.prefs.appLock) "On" else "Off") { dlg = 6 }
            Item("🔑", "Two-step verification", if (pinOn) "On" else "Off") { dlg = 7 }
            Item("🚫", "Blocked contacts") { dlg = 2 }
            Item("★", "Starred messages") { onStarred() }
            Item("🎨", "Appearance", "Theme: $modeName") { dlg = 3 }
            Item("🔔", "Notifications", "Message, group and call tones") {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            Item("💾", "Storage and data", "Media on this phone: $storage") {}
            Item("🛠", "Server address (dev)", app.prefs.serverUrl) { dlg = 4 }
            Item("👥", "Invite a friend") {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Let's chat on Sky.")
                }
                ctx.startActivity(Intent.createChooser(send, null))
            }
            Item("ℹ", "About", "Sky $versionName") {}
            Item("⏻", "Log out") { dlg = 5 }
        }
    }

    if (dlg == 1) PrivacyDialog(app) { dlg = 0 }
    if (dlg == 2) {
        val blocked by dao.blockedPhones().collectAsState(initial = emptyList())
        val contacts by dao.contacts().collectAsState(initial = emptyList())
        val names = contacts.associate { it.phone to it.name }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text("Blocked contacts") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (blocked.isEmpty()) Text("No blocked contacts.", color = Color.Gray)
                    blocked.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(names[p]?.ifEmpty { null } ?: p, Modifier.weight(1f))
                            TextButton(onClick = { app.client.setBlocked(p, false) }) { Text("Unblock") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = 0 }) { Text("Close") } }
        )
    }
    if (dlg == 3) AlertDialog(
        onDismissRequest = { dlg = 0 },
        title = { Text("Theme") },
        text = {
            Column {
                listOf("System default", "Light", "Dark").forEachIndexed { i, label ->
                    Row(Modifier.fillMaxWidth().clickable {
                        app.prefs.themeMode = i; app.themeMode.value = i; dlg = 0
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == i, onClick = { app.prefs.themeMode = i; app.themeMode.value = i; dlg = 0 })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { dlg = 0 }) { Text("Cancel") } }
    )
    if (dlg == 4) {
        var url by remember { mutableStateOf(app.prefs.serverUrl) }
        var json by remember { mutableStateOf(app.prefs.preferJson) }
        val problem = serverProblem(url)
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text("Server address (dev)") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("wss://chat.example.com/ws") })
                    ServerNote(url)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("JSON text format (debug)", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(json, { json = it })
                    }
                    Text("Default is MessagePack (binary, smaller). Takes effect when the connection is made again.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(enabled = problem == null, onClick = { app.prefs.preferJson = json; app.client.changeServer(url); dlg = 0 }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dlg = 0 }) { Text("Cancel") } }
        )
    }
    if (dlg == 8) BackupDialogs(app) { dlg = 0 }
    if (dlg == 6) {
        var on by remember { mutableStateOf(app.prefs.appLock) }
        var timeout by remember { mutableStateOf(app.prefs.lockTimeoutMs) }
        val secure = remember { app.deviceSecure() }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            title = { Text("App lock") },
            text = {
                Column {
                    if (!secure) Text("Set a screen lock (PIN, pattern, password or fingerprint) in your phone's settings first.",
                        color = Color(0xFFD32F2F), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Unlock Sky with the phone's lock", Modifier.weight(1f))
                        Switch(on, { on = it; app.prefs.appLock = it; app.refreshSecure() }, enabled = secure)
                    }
                    if (on) {
                        Text("Lock again", Modifier.padding(top = 12.dp), fontSize = 13.sp, color = Color.Gray)
                        listOf(0L to "Immediately", 60_000L to "After 1 minute", 1_800_000L to "After 30 minutes").forEach { (ms, label) ->
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
            confirmButton = { TextButton(onClick = { dlg = 0 }) { Text("Done") } }
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
            title = { Text("Two-step verification") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("With a PIN, somebody who gets your SMS code still cannot register your number on another phone. " +
                        "If you forget the PIN, you can register again after the number was not used for 7 days.", fontSize = 12.sp, color = Color.Gray)
                    val pw = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    if (on) OutlinedTextField(old, { if (digits(it)) old = it }, singleLine = true, label = { Text("Current PIN") }, visualTransformation = pw, keyboardOptions = kb)
                    if (!removing) {
                        OutlinedTextField(pin, { if (digits(it)) pin = it }, singleLine = true, label = { Text(if (on) "New PIN (6 digits)" else "PIN (6 digits)") }, visualTransformation = pw, keyboardOptions = kb)
                        OutlinedTextField(pin2, { if (digits(it)) pin2 = it }, singleLine = true, label = { Text("Repeat PIN") }, visualTransformation = pw, keyboardOptions = kb)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = if (removing) old.length == 6 else ok, onClick = {
                    if (removing) app.client.removePin(old) else app.client.setPin(pin, old)
                    dlg = 0
                }) { Text(if (removing) "Turn off" else if (on) "Change PIN" else "Turn on") }
            },
            dismissButton = {
                Row {
                    if (on) TextButton(onClick = { removing = !removing }) { Text(if (removing) "Back" else "Turn off…", color = Color(0xFFD32F2F)) }
                    TextButton(onClick = { dlg = 0 }) { Text("Cancel") }
                }
            }
        )
    }
    if (dlg == 5) AlertDialog(
        onDismissRequest = { dlg = 0 },
        title = { Text("Log out?") },
        text = { Text("Chats stored on this phone will be removed.") },
        confirmButton = { TextButton(onClick = { dlg = 0; app.client.logout() }) { Text("Log out") } },
        dismissButton = { TextButton(onClick = { dlg = 0 }) { Text("Cancel") } }
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
            if (out == null) com.skychat.app.data.Backup.state.value = com.skychat.app.data.BackupState(false, "Could not write the file", false)
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
            title = { Text(if (st.busy) "Please wait…" else if (st.ok == true) "Done" else "Problem") },
            text = { Text(st.text) },
            confirmButton = { if (!st.busy) TextButton(onClick = onClose) { Text("OK") } }
        )
        return
    }
    when (mode) {
        0 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Chat backup") },
            text = {
                Text("The backup file holds your chats and all photos, videos, voice notes and files, locked with a password you choose. " +
                    "Keep the file anywhere you like (the file chooser can save to Google Drive or an SD card). " +
                    "It does not hold your encryption keys: on a new phone your contacts will see a new safety number. " +
                    "If you forget the password the backup cannot be opened by anybody.", fontSize = 13.sp)
            },
            confirmButton = { TextButton(onClick = { mode = 1 }) { Text("Back up") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Restore") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        )
        1 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Choose a password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password (at least 8 characters)") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    OutlinedTextField(pw2, { pw2 = it }, singleLine = true, label = { Text("Repeat password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = digitsOk(), onClick = {
                    createDoc.launch("sky-backup-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".skybak")
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
        )
        else -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Messages in the backup replace messages with the same id on this phone; everything else stays.", fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") },
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
                        if (inp == null) com.skychat.app.data.Backup.state.value = com.skychat.app.data.BackupState(false, "Could not read the file", false)
                        else inp.use { com.skychat.app.data.Backup.restore(app, it, password) }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
        )
    }
}
