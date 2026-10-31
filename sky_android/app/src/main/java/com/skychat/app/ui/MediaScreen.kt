package com.skychat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import com.skychat.app.media.openFile

/** All photos, videos and documents of one chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(app: SkyApp, chat: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = app.db.dao()
    val all by dao.mediaOf(chat).collectAsState(initial = emptyList())
    val docs by dao.docsOf(chat).collectAsState(initial = emptyList())
    val links by dao.linksOf(chat).collectAsState(initial = emptyList())
    val shown = all.filter { hasLocalFile(it) }
    var tab by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Media and documents") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.bar, titleContentColor = Color.White,
                navigationIconContentColor = Color.White)
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Media (${shown.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Docs (${docs.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Links (${links.size})") })
            }
            if (tab == 0) {
                if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No photos or videos yet.", color = Color.Gray)
                } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown.reversed(), key = { it.id }) { m ->
                        MsgThumb(app, m, Modifier.aspectRatio(1f).clickable { viewer = m.id })
                    }
                }
            } else if (tab == 2) {
                if (links.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No links yet.", color = Color.Gray)
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(links, key = { it.id }) { m ->
                        val url = Regex("https?://[^\\s<>\"]+").find(m.body)?.value ?: ""
                        Column(Modifier.fillMaxWidth().clickable {
                            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { }
                        }.padding(16.dp)) {
                            Text(url, color = Sky.accentText, maxLines = 2)
                            Text(timeText(m.ts), fontSize = 12.sp, color = Color.Gray)
                        }
                        HorizontalDivider(color = Sky.divider)
                    }
                }
            } else {
                if (docs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No documents yet.", color = Color.Gray)
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(docs, key = { it.id }) { m ->
                        val local = hasLocalFile(m)
                        Row(Modifier.fillMaxWidth().clickable {
                            if (local) openFile(ctx, app.media, m.localPath, m.mime, m.fname) else app.client.fetch(m)
                        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📄", fontSize = 24.sp, modifier = Modifier.width(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.fname.ifEmpty { "File" }, maxLines = 1)
                                Text(timeText(m.ts) + if (local) "" else " · tap to download", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        HorizontalDivider(color = Sky.divider)
                    }
                }
            }
        }
    }
    viewer?.let { MediaViewer(app, shown, it) { viewer = null } }
}
