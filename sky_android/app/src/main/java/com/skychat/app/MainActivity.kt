package com.skychat.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.skychat.app.call.Phase
import com.skychat.app.data.isGroup
import com.skychat.app.push.PushService
import com.skychat.app.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openRequest = MutableStateFlow<String?>(null)
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SkyApp
        openRequest.value = intent.getStringExtra("chat")
        joinFrom(intent)
        setContent { SkyTheme { AppNav(app, openRequest) } }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = intent.getStringExtra("chat")
        joinFrom(intent)
    }

    /** skychat://join/CODE: ask to join once the connection is up. */
    private fun joinFrom(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "skychat" || uri.host != "join") return
        val code = uri.lastPathSegment ?: return
        (application as SkyApp).client.joinWhenOnline(code)
    }

    override fun onStart() {
        super.onStart()
        val app = application as SkyApp
        app.client.foreground = true
        if (app.prefs.token != null) {
            app.client.start()
            PushService.ensure(this)          // the app is visible, so Android allows starting it
            askBatteryExemption(app)
        }
    }

    /** Phones kill idle background apps; ask once to be left alone so pushes keep arriving. */
    private fun askBatteryExemption(app: SkyApp) {
        if (app.prefs.askedBattery) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        app.prefs.askedBattery = true
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        val app = application as SkyApp
        app.client.foreground = false
        app.client.scheduleIdleStop()
    }
}

private fun chatRoute(id: String) =
    "chat/" + (if (isGroup(id)) "g" else "p") + id.removePrefix("+")

@Composable
fun AppNav(app: SkyApp, openRequest: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val loggedIn by app.client.loggedIn.collectAsState()
    val request by openRequest.collectAsState()
    val call by app.calls.ui.collectAsState()
    val activity = LocalContext.current as? Activity

    LaunchedEffect(loggedIn) {
        val route = nav.currentBackStackEntry?.destination?.route
        if (!loggedIn && route != null && route != "login") {
            nav.navigate("login") { popUpTo(0) }
        }
    }

    // Tapping a notification opens that chat.
    LaunchedEffect(request, loggedIn) {
        val id = request
        if (id != null && loggedIn) {
            nav.navigate(chatRoute(id))
            openRequest.value = null
        }
    }

    // An incoming call may show over the lock screen; chats never do.
    LaunchedEffect(call.phase) {
        if (Build.VERSION.SDK_INT >= 27) {
            activity?.setShowWhenLocked(call.phase != Phase.IDLE)
            activity?.setTurnScreenOn(call.phase == Phase.INCOMING)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = if (app.prefs.token != null) "chats" else "login") {
            composable("login") {
                LoginScreen(app) { nav.navigate("chats") { popUpTo("login") { inclusive = true } } }
            }
            composable("chats") {
                ChatListScreen(app, onStarred = { nav.navigate("starred") }, onProfile = { nav.navigate("profile") }) { id -> nav.navigate(chatRoute(id)) }
            }
            composable("profile") {
                ProfileScreen(app, onBack = { nav.popBackStack() })
            }
            composable("starred") {
                StarredScreen(app, onBack = { nav.popBackStack() }) { id -> nav.navigate(chatRoute(id)) }
            }
            composable("chat/{phone}") { e ->
                val arg = e.arguments!!.getString("phone")!!
                val chat = if (arg.startsWith("p")) "+" + arg.drop(1) else arg.drop(1)
                ChatScreen(app, chat) { nav.popBackStack() }
            }
        }
        if (call.phase != Phase.IDLE) CallScreen(app, call)
    }
}
