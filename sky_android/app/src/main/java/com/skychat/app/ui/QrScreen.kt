package com.skychat.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.skychat.app.SkyApp
import com.skychat.app.net.AuthEvent

private fun qrBitmap(text: String): ImageBitmap {
    val size = 512
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** A scanned text -> a phone number in +E.164 form, or null if it is not a Sky code. */
private fun phoneFrom(text: String): String? {
    val t = text.trim().removePrefix("skychat://user/")
    return if (Regex("\\+[0-9]{8,15}").matches(t)) t else null
}

/** My QR code (anyone who scans it can start a chat with me) and a scanner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScreen(app: SkyApp, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val me = app.prefs.phone ?: ""
    var tab by remember { mutableIntStateOf(0) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@rememberLauncherForActivityResult
        val phone = phoneFrom(text)
        when {
            phone == null -> Toast.makeText(ctx, "This is not a Sky QR code", Toast.LENGTH_SHORT).show()
            phone == me -> Toast.makeText(ctx, "This is your own code", Toast.LENGTH_SHORT).show()
            else -> app.client.lookup(phone)
        }
    }
    fun openScanner() = scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("Scan a Sky QR code").setBeepEnabled(false).setOrientationLocked(false))
    LaunchedEffect(Unit) {
        app.client.events.collect { e ->
            when (e) {
                is AuthEvent.Profile ->
                    if (e.exists) onOpenChat(e.phone)
                    else Toast.makeText(ctx, "${e.phone} is not registered on Sky", Toast.LENGTH_SHORT).show()
                is AuthEvent.Error -> Toast.makeText(ctx, toastText(e.code), Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("QR code") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.bar, titleContentColor = Color.White,
                navigationIconContentColor = Color.White)
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My code") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Scan code") })
            }
            if (tab == 0) {
                val bmp = remember(me) { qrBitmap("skychat://user/$me") }
                val ver by app.client.avatarVersion.collectAsState()
                val photo = if (ver >= 0 && app.client.hasOwnPhoto) app.client.ownPhotoFile.absolutePath else ""
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(app, photo, 72.dp, version = ver)
                    Spacer(Modifier.height(8.dp))
                    Text(app.prefs.name ?: me, fontSize = 18.sp)
                    Text(me, color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {   // white even in dark mode, so scanners can read it
                        Image(bmp, contentDescription = "Your QR code", modifier = Modifier.size(240.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("People who scan this code can start a chat with you. Only share it with people you trust.",
                        color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            } else {
                LaunchedEffect(Unit) { openScanner() }
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("Point the camera at another person's Sky QR code.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { openScanner() }) { Text("Open scanner") }
                }
            }
        }
    }
}
