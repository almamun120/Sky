package com.skychat.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp

/** Covers the whole app until the person proves it is them with the phone's own lock (PIN, pattern, password or fingerprint). */
@Composable
fun LockScreen(app: SkyApp) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) app.locked.value = false
    }
    fun unlock() {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("Sky", "Unlock to continue")
        if (intent == null) app.locked.value = false          // the phone has no lock screen any more
        else launcher.launch(intent)
    }
    LaunchedEffect(Unit) { unlock() }
    Box(Modifier.fillMaxSize().background(Sky.surface).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🔒", fontSize = 56.sp)
            Text("Sky is locked", fontSize = 20.sp)
            Button(onClick = { unlock() }) { Text("Unlock") }
        }
    }
}
