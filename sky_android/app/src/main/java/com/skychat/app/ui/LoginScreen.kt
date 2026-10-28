package com.skychat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import com.skychat.app.net.AuthEvent

private fun errText(code: String) = when (code) {
    "bad_phone" -> "Invalid phone number. Use the international format, e.g. +8801700000001"
    "rate_limited" -> "Too many code requests. Try again in a few minutes."
    "invalid" -> "Wrong code."
    "expired" -> "The code has expired. Request a new one."
    "too_many_attempts" -> "Too many wrong attempts. Request a new code."
    "connection_failed" -> "Can't reach the server."
    "bad_url" -> "Invalid server address."
    else -> code
}

@Composable
fun LoginScreen(app: SkyApp, onDone: () -> Unit) {
    var server by remember { mutableStateOf(app.prefs.serverUrl) }
    var phone by remember { mutableStateOf("+880") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.client.events.collect { e ->
            busy = false
            when (e) {
                is AuthEvent.OtpSent -> { step = 1; devCode = e.devCode; error = null }
                is AuthEvent.LoggedIn -> onDone()
                is AuthEvent.Error -> error = errText(e.code)
                is AuthEvent.Profile -> {}
            }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Sky", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = BarGreen)
        Text("Verify your phone number to continue.")

        OutlinedTextField(server, { server = it }, label = { Text("Server address") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = step == 0)
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = step == 0,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))

        if (step == 0) {
            Button(
                onClick = {
                    error = null; busy = true
                    app.prefs.serverUrl = server.trim()
                    app.client.requestOtp(phone.trim())
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait…" else "Send code") }
        } else {
            devCode?.let { Text("Dev mode code: $it", color = Accent) }
            OutlinedTextField(code, { code = it }, label = { Text("6-digit code") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(name, { name = it }, label = { Text("Your name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    error = null; busy = true
                    app.client.verifyOtp(phone.trim(), code.trim(), name.trim())
                },
                enabled = !busy && code.length == 6 && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait…" else "Verify") }
            TextButton(onClick = { step = 0; code = ""; error = null }) { Text("Change number") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
