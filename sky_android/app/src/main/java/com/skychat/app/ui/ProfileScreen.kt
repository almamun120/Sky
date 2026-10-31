package com.skychat.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.skychat.app.net.AuthEvent

/** My own name, about text and photo. Name is public; about and photo are readable only by people I chat with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(app: SkyApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val client = app.client
    var name by remember { mutableStateOf(app.prefs.name ?: "") }
    var about by remember { mutableStateOf(client.ownAbout) }
    val ver by client.avatarVersion.collectAsState()
    val photo = if (ver >= 0 && client.hasOwnPhoto) client.ownPhotoFile.absolutePath else ""
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) client.setOwnPhoto(uri)
    }
    LaunchedEffect(Unit) {
        client.events.collect { e ->
            if (e is AuthEvent.Error) Toast.makeText(ctx, toastText(e.code), Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Profile") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Sky.bar, titleContentColor = Color.White, navigationIconContentColor = Color.White)
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(app, photo, 120.dp, version = ver)
            Row {
                TextButton(onClick = { pick.launch("image/*") }) { Text(if (photo.isEmpty()) "Add photo" else "Change photo") }
                if (photo.isNotEmpty()) TextButton(onClick = { client.removeOwnPhoto() }) { Text("Remove", color = Color.Red) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") },
                supportingText = { Text("Shown to everyone who gets a message from you.") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(about, { if (it.length <= 139) about = it }, Modifier.fillMaxWidth(), label = { Text("About") },
                supportingText = { Text("${about.length}/139. Only people you chat with can read this.") })
            Spacer(Modifier.height(8.dp))
            Text(app.prefs.phone ?: "", color = Color.Gray, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { client.saveProfile(name, about) }, enabled = name.isNotBlank()) { Text("Save") }
        }
    }
}
