package com.skychat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.skychat.app.SkyApp
import com.skychat.app.call.AudioRoute
import com.skychat.app.call.GPeer
import com.skychat.app.call.GPhase
import com.skychat.app.call.GroupCallUi
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Full-screen group call: a grid with everybody (video if the call is a video call), and the controls. */
@Composable
fun GroupCallScreen(app: SkyApp, g: GroupCallUi) {
    val ctx = LocalContext.current
    val gc = app.client.groupCalls ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showRoutes by remember { mutableStateOf(false) }

    val askJoin = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) gc.join() else gc.decline()
    }
    fun join() {
        val need = if (g.video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (need.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) gc.join() else askJoin.launch(need)
    }
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { showRoutes = true }
    fun chooseRoute() {
        if (android.os.Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            askBt.launch(Manifest.permission.BLUETOOTH_CONNECT) else showRoutes = true
    }
    LaunchedEffect(g.phase) { while (g.phase == GPhase.JOINED) { now = System.currentTimeMillis(); delay(500) } }

    if (showRoutes) AlertDialog(
        onDismissRequest = { showRoutes = false },
        title = { Text("Sound") },
        text = {
            Column {
                AudioRoute.available(ctx).forEach { r ->
                    Text((if (r == g.route) "✓  " else "     ") + r.icon + "  " + r.label,
                        Modifier.fillMaxWidth().clickable { showRoutes = false; gc.setRoute(r) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { showRoutes = false }) { Text("Cancel") } }
    )

    val secs = if (g.startedAt > 0) ((now - g.startedAt) / 1000).toInt().coerceAtLeast(0) else 0
    val connectedCount = g.peers.count { it.connected }
    val status = when {
        g.phase == GPhase.INCOMING -> (if (g.video) "Group video call" else "Group voice call") + " · invitation"
        connectedCount == 0 -> if (g.peers.isEmpty()) "Waiting for others to join…" else "Connecting…"
        else -> "%d:%02d · %d people".format(secs / 60, secs % 60, connectedCount + 1)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101820))) {
        Column(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 110.dp)) {
            Text(g.groupName.ifEmpty { "Group call" }, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(status, color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (g.phase == GPhase.JOINED) {
                val tiles = listOf<GPeer?>(null) + g.peers          // null = me
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tiles, key = { it?.phone ?: "me" }) { p -> Tile(app, g, p) }
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("🎥".takeIf { g.video } ?: "📞", fontSize = 64.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            if (g.phase == GPhase.INCOMING) {
                Round("📵", Color(0xFFD32F2F)) { gc.decline() }
                Round("📞", Color(0xFF2E7D32)) { join() }
            } else {
                Round(if (g.muted) "🔇" else "🎤", Color(0x33FFFFFF)) { gc.toggleMute() }
                Round(g.route.icon, Color(0x33FFFFFF)) { chooseRoute() }
                if (g.video) {
                    Round(if (g.camOff) "🚫" else "📷", Color(0x33FFFFFF)) { gc.toggleCamera() }
                    Round("🔄", Color(0x33FFFFFF)) { gc.switchCamera() }
                }
                Round("📵", Color(0xFFD32F2F)) { gc.hangup() }
            }
        }
    }
}

@Composable
private fun Tile(app: SkyApp, g: GroupCallUi, p: GPeer?) {
    val gc = app.client.groupCalls ?: return
    val egl = app.calls.eglBase.eglBaseContext
    val mine = p == null
    val showVideo = g.video && if (mine) !g.camOff else p!!.connected
    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1F2C34)),
        contentAlignment = Alignment.Center) {
        if (showVideo) AndroidView(
            factory = { c -> SurfaceViewRenderer(c).apply {
                init(egl, null); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); setEnableHardwareScaler(true)
                setMirror(mine)
            } },
            update = { if (mine) gc.attachLocal(it) else gc.attachPeer(p!!.phone, it) },
            onRelease = { if (mine) gc.detachLocal(it) else gc.detachPeer(p!!.phone, it); it.release() },
            modifier = Modifier.fillMaxSize())
        else Avatar(app, "", 72.dp)
        Text(if (mine) "You" else p!!.name + if (p.connected) "" else " · connecting…", color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Round(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(60.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 24.sp)
    }
}
