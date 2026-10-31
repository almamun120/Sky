package com.skychat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.skychat.app.SkyApp
import com.skychat.app.data.StatusItem
import kotlinx.coroutines.delay

private val StatusColors = listOf(0xFF075E54, 0xFF6A1B9A, 0xFFC2185B, 0xFFE65100, 0xFF1565C0, 0xFF37474F)
    .map { it.toInt() }

/** The Updates tab: my own updates, then everybody else's, unseen ones first. */
@Composable
fun UpdatesTab(app: SkyApp, titleOf: (String) -> String, photoOf: Map<String, String>) {
    val dao = app.db.dao()
    val since = remember { System.currentTimeMillis() - 24 * 3600_000L }
    val all by dao.statuses(since).collectAsState(initial = emptyList())
    val mine = all.filter { it.mine }
    val groups = all.filter { !it.mine }.groupBy { it.owner }.entries
        .sortedWith(compareBy({ g -> g.value.all { it.seen } }, { g -> -g.value.maxOf { it.ts } }))
    var viewing by remember { mutableStateOf<List<StatusItem>?>(null) }
    var composeText by remember { mutableStateOf(false) }
    val ver by app.client.avatarVersion.collectAsState()
    val myPhoto = if (ver >= 0 && app.client.hasOwnPhoto) app.client.ownPhotoFile.absolutePath else ""
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) app.client.postImageStatus(uri, "")
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().clickable { if (mine.isEmpty()) composeText = true else viewing = mine }
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(app, myPhoto, 52.dp, version = ver)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("My status", fontWeight = FontWeight.Medium)
                    Text(if (mine.isEmpty()) "Tap to add a status update" else "${mine.size} update(s) · ${timeText(mine.last().ts)}",
                        color = Color.Gray, fontSize = 13.sp)
                }
                TextButton(onClick = { composeText = true }) { Text("✏", fontSize = 20.sp) }
                TextButton(onClick = { pick.launch("image/*") }) { Text("📷", fontSize = 20.sp) }
            }
            HorizontalDivider(color = Sky.divider)
            Text("Recent updates", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), color = Color.Gray, fontSize = 13.sp)
            if (groups.isEmpty()) Text("No updates yet. People you chat with will appear here.",
                Modifier.padding(16.dp), color = Color.Gray)
        }
        items(groups.toList(), key = { it.key }) { g ->
            val unseen = g.value.any { !it.seen }
            Row(Modifier.fillMaxWidth().clickable { viewing = g.value.sortedBy { it.ts } }
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.border(3.dp, if (unseen) Accent else Color.Gray, CircleShape).padding(4.dp)) {
                    Avatar(app, photoOf[g.key] ?: "", 48.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(titleOf(g.key), fontWeight = FontWeight.Medium)
                    Text(timeText(g.value.maxOf { it.ts }), color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }

    viewing?.let { StatusViewer(app, it, titleOf(it.first().owner)) { viewing = null } }
    if (composeText) TextStatusComposer(onClose = { composeText = false }) { text, color ->
        app.client.postTextStatus(text, color); composeText = false
    }
}

@Composable
private fun TextStatusComposer(onClose: () -> Unit, onPost: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var ci by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(StatusColors[ci]))) {
            TextField(text, { if (it.length <= 700) text = it },
                Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                placeholder = { Text("Type a status", color = Color(0xAAFFFFFF), fontSize = 28.sp, textAlign = TextAlign.Center) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 28.sp, textAlign = TextAlign.Center),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White))
            TextButton(onClick = onClose, Modifier.align(Alignment.TopStart).padding(top = 32.dp)) { Text("✕", color = Color.White, fontSize = 22.sp) }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusColors.forEachIndexed { i, c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (i == ci) 3.dp else 1.dp, Color.White, CircleShape).clickable { ci = i })
                    }
                }
                Button(onClick = { onPost(text, StatusColors[ci]) }, enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Post") }
            }
        }
    }
}

/** Full-screen viewer: tap right/left for next/previous, advances by itself after 5 seconds. */
@Composable
private fun StatusViewer(app: SkyApp, items: List<StatusItem>, name: String, onClose: () -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    val s = items[idx.coerceIn(0, items.lastIndex)]
    val views by app.db.dao().viewsOf(s.id).collectAsState(initial = emptyList())
    LaunchedEffect(s.id) {
        if (!s.mine) app.client.markStatusSeen(s.id)
        delay(5000)
        if (idx < items.lastIndex) idx++ else onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(if (s.kind == "text") Color(s.bg) else Color.Black)) {
            if (s.kind == "text") Text(s.text, Modifier.align(Alignment.Center).padding(32.dp), color = Color.White,
                fontSize = 28.sp, textAlign = TextAlign.Center)
            else {
                PhotoImage(app, s.path)
                if (s.text.isNotEmpty()) Text(s.text, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0x99000000)).padding(16.dp, 16.dp, 16.dp, 64.dp), color = Color.White, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (idx > 0) idx-- })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (idx < items.lastIndex) idx++ else onClose() })
            }
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    items.forEachIndexed { i, _ ->
                        Box(Modifier.weight(1f).height(3.dp).background(if (i <= idx) Color.White else Color(0x66FFFFFF)))
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("←", color = Color.White, fontSize = 22.sp) }
                    Text(if (s.mine) "My status" else name, color = Color.White, fontWeight = FontWeight.Medium)
                    Text("  ${timeText(s.ts)}", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                }
            }
            if (s.mine) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("👁 ${views.size}", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.client.deleteStatus(s.id); onClose() }) { Text("🗑 Delete", color = Color.White) }
            }
        }
    }
}
