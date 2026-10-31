package com.skychat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import com.skychat.app.data.isGroup
import com.skychat.app.data.previewText

/** Messages starred on this phone, newest first. Tap opens the chat; ★ removes the star. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(app: SkyApp, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val dao = app.db.dao()
    val list by dao.starred().collectAsState(initial = emptyList())
    val contacts by dao.contacts().collectAsState(initial = emptyList())
    val groups by dao.groups().collectAsState(initial = emptyList())
    val names = contacts.associate { it.phone to it.name }
    val gnames = groups.associate { it.gid to it.name }
    fun titleOf(chat: String) = if (isGroup(chat)) gnames[chat] ?: "Group" else names[chat] ?: chat

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Starred messages") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.bar, titleContentColor = Color.White)
        )
    }) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No starred messages. Long-press a message and choose Star.", color = Color.Gray,
                    modifier = Modifier.padding(24.dp))
            }
        } else LazyColumn(Modifier.padding(pad)) {
            items(list, key = { it.id }) { m ->
                val who = if (m.fromMe) "You" else names[m.sender] ?: m.sender.ifEmpty { titleOf(m.chat) }
                Row(Modifier.fillMaxWidth().clickable { onOpen(m.chat) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(who + "  ›  " + titleOf(m.chat), fontSize = 12.sp, color = Sky.accentText, maxLines = 1)
                        Text(previewText(m.kind, m.body, m.fname), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(timeText(m.ts), fontSize = 11.sp, color = Color.Gray)
                    }
                    TextButton(onClick = { app.client.star(m, false) }) { Text("★", fontSize = 22.sp, color = Color(0xFFF5B301)) }
                }
                HorizontalDivider(color = Sky.divider)
            }
        }
    }
}
