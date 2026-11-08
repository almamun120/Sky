package com.skychat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import com.skychat.app.data.previewText

/** A broadcast list: one text, picture, video or file goes to every member as an ordinary one-to-one message. The answers arrive in the normal chats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScreen(app: SkyApp, id: String, onBack: () -> Unit) {
    val dao = app.db.dao()
    val list by dao.broadcastFlow(id).collectAsState(initial = null)
    val msgs by dao.messages(id).collectAsState(initial = emptyList())
    val contacts by dao.contacts().collectAsState(initial = emptyList())
    val names = contacts.associate { it.phone to it.name }
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) app.client.sendBroadcastMedia(id, u) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) app.client.sendBroadcastMedia(id, u) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) app.client.sendBroadcastMedia(id, u) }
    var attachMenu by remember { mutableStateOf(false) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.lastIndex) }
    val members = (list?.members ?: "").split(",").filter { it.isNotEmpty() }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(list?.name ?: t("Broadcast list"), maxLines = 1)
                    Text("${members.size} " + t("recipients"), fontSize = 12.sp)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back")) } },
            actions = { TextButton(onClick = { confirm = true }) { Text(t("Delete list"), color = Color.White) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.bar, titleContentColor = Color.White,
                navigationIconContentColor = Color.White)
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Sky.chatBg).imePadding()) {
            Text(members.joinToString(", ") { names[it]?.ifEmpty { null } ?: it }, Modifier.padding(12.dp), fontSize = 12.sp, color = Color.Gray, maxLines = 2)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(msgs, key = { it.id }) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(shape = RoundedCornerShape(10.dp), color = Sky.bubbleOut, modifier = Modifier.widthIn(max = 280.dp)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                if (m.kind == "text") Text(m.body)
                                else {
                                    Text(previewText(m.kind, "", m.fname))
                                    if (m.body.isNotEmpty()) Text(m.body)
                                }
                                Text(timeText(m.ts), fontSize = 11.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Sky.surface2).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { attachMenu = true }) { Text("＋", fontSize = 22.sp) }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem(text = { Text(t("Photo")) }, onClick = { attachMenu = false; pickPhoto.launch("image/*") })
                        DropdownMenuItem(text = { Text(t("Video")) }, onClick = { attachMenu = false; pickVideo.launch("video/*") })
                        DropdownMenuItem(text = { Text(t("File")) }, onClick = { attachMenu = false; pickFile.launch("*/*") })
                    }
                }
                TextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(t("Type a message")) }, maxLines = 4,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Sky.surface, unfocusedContainerColor = Sky.surface,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Spacer(Modifier.width(8.dp))
                Button(enabled = text.isNotBlank(), onClick = { app.client.sendBroadcast(id, text); text = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(t("Send")) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(t("Delete list")) },
        text = { Text(list?.name ?: "") },
        confirmButton = { TextButton(onClick = { app.client.deleteBroadcast(id); confirm = false; onBack() }) { Text(t("Delete"), color = Color(0xFFD32F2F)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("Cancel")) } }
    )
}
