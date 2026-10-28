package com.skychat.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.skychat.app.MainActivity
import com.skychat.app.SkyApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sky's own push client (replaces Firebase). A foreground service keeps one tiny WebSocket open to
 * the server's /push endpoint. When something is waiting, the server sends {"type":"wake"} on it and
 * we open the normal chat connection to fetch it. The wake frame carries no message content.
 *
 * Phones are allowed to kill background apps; see the README for the battery settings that matter.
 */
class PushService : Service() {

    companion object {
        private const val NOTE_ID = 7003
        private const val CHANNEL = "sky_service"
        @Volatile var running = false
            private set

        /** Starts the service if someone is logged in. Safe to call often. */
        fun ensure(ctx: Context) {
            val app = ctx.applicationContext as SkyApp
            if (app.prefs.token == null || running) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PushService::class.java))
            } catch (e: Exception) {
                // Android refuses to start a foreground service from the background at times;
                // the next time the app is opened (or the phone boots) it is started again.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PushService::class.java))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(45, TimeUnit.SECONDS)      // keeps NAT/proxies open; server idle limit is 150 s
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // All of these are touched on the main thread only.
    private var socket: WebSocket? = null
    private var socketUrl: String? = null
    private var retry = 0
    private var gaveUp = false                  // another device took over, or we were logged out
    private var destroyed = false
    private var wake: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val reconnect = Runnable { connect() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps Sky able to receive messages while it is closed." })
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sky:push").apply { setReferenceCounted(false) }
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must happen right away, even if we stop again below.
        val open = PendingIntent.getActivity(
            this, 7004, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Sky")
            .setContentText("Waiting for messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTE_ID, n, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as SkyApp
        if (app.prefs.token == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        gaveUp = false
        if (socket != null && socketUrl != app.prefs.pushUrl) {      // server address was changed
            socket?.cancel(); socket = null
        }
        ui.removeCallbacks(reconnect)
        connect()
        return START_STICKY
    }

    private fun connect() {
        if (destroyed || gaveUp || socket != null) return
        val app = application as SkyApp
        val token = app.prefs.token ?: run { stopSelf(); return }
        val url = app.prefs.pushUrl
        val req = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            return                                   // bad server address; nothing to connect to
        }
        socketUrl = url
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "hello").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                ui.post {
                    if (webSocket !== socket) return@post
                    when (o.optString("type")) {
                        "hello_ok" -> retry = 0
                        "wake" -> onWake()
                        "error" -> if (o.optString("code") == "bad_token") gaveUp = true
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ui.post {
                    if (webSocket === socket && (reason == "replaced" || reason == "logout" || reason == "bad_token"))
                        gaveUp = true
                }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(w: WebSocket) {
        ui.post {
            if (w !== socket) return@post
            socket = null
            if (destroyed || gaveUp) return@post
            val seconds = minOf(60, 2 shl minOf(retry, 5)) + Random.nextInt(0, 3)   // 2,4,8,..60 s
            retry++
            ui.removeCallbacks(reconnect)
            ui.postDelayed(reconnect, seconds * 1000L)
        }
    }

    private fun onWake() {
        // Keep the CPU awake long enough for the chat connection to open and download everything.
        wake?.acquire(30_000)
        (application as SkyApp).client.wake()
    }

    /** As soon as the phone gets a network again, do not wait for the retry timer. */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ui.post {
                    if (destroyed || gaveUp) return@post
                    if (socket == null) {
                        ui.removeCallbacks(reconnect)
                        retry = 0
                        connect()
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { /* the retry timer still works */ }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        ui.removeCallbacks(reconnect)
        socket?.cancel(); socket = null
        netCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
            catch (e: Exception) { }
        }
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
