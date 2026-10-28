package com.skychat.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.skychat.app.SkyApp
import com.skychat.app.call.CallUi
import com.skychat.app.call.Phase
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(app: SkyApp, call: CallUi) {
    val ctx = LocalContext.current
    val calls = app.calls
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val askAccept = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) calls.accept() else calls.reject()
    }
    fun accept() {
        val need = if (call.video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                   else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (need.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) calls.accept()
        else askAccept.launch(need)
    }

    LaunchedEffect(call.phase) {
        while (call.phase == Phase.ACTIVE) { now = System.currentTimeMillis(); delay(500) }
    }

    val secs = ((now - call.startedAt) / 1000).toInt().coerceAtLeast(0)
    val status = when (call.phase) {
        Phase.OUTGOING -> "Calling…"
        Phase.INCOMING -> if (call.video) "Incoming video call" else "Incoming voice call"
        Phase.CONNECTING -> "Connecting…"
        Phase.ACTIVE -> "%d:%02d".format(secs / 60, secs % 60)
        Phase.IDLE -> ""
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101820))) {
        if (call.video && call.phase != Phase.INCOMING) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    SurfaceViewRenderer(c).apply {
                        init(calls.eglBase.eglBaseContext, null)
                        setEnableHardwareScaler(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        calls.attachRemote(this)
                    }
                },
                onRelease = { calls.detachRemote(it); it.release() }
            )
            AndroidView(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp)
                    .size(width = 110.dp, height = 160.dp),
                factory = { c ->
                    SurfaceViewRenderer(c).apply {
                        init(calls.eglBase.eglBaseContext, null)
                        setZOrderMediaOverlay(true)
                        setMirror(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        calls.attachLocal(this)
                    }
                },
                onRelease = { calls.detachLocal(it); it.release() }
            )
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(call.name.ifEmpty { call.peer }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(status, color = Color(0xFFB0BEC5), fontSize = 16.sp)
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (call.phase == Phase.INCOMING) {
                RoundButton("📵", Color(0xFFD32F2F)) { calls.reject() }
                RoundButton("📞", Color(0xFF2E7D32)) { accept() }
            } else {
                RoundButton(if (call.muted) "🔇" else "🎤", Color(0x33FFFFFF)) { calls.toggleMute() }
                RoundButton(if (call.speaker) "🔊" else "🔈", Color(0x33FFFFFF)) { calls.toggleSpeaker() }
                if (call.video) {
                    RoundButton(if (call.camOff) "🚫" else "📷", Color(0x33FFFFFF)) { calls.toggleCamera() }
                    RoundButton("🔄", Color(0x33FFFFFF)) { calls.switchCamera() }
                }
                RoundButton("📵", Color(0xFFD32F2F)) { calls.hangup() }
            }
        }
    }
}

@Composable
private fun RoundButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(60.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 24.sp) }
}
