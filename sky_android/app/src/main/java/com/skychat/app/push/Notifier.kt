package com.skychat.app.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.skychat.app.MainActivity
import com.skychat.app.data.MsgDao
import com.skychat.app.data.isGroup

/** Local notifications. Message text never leaves the app/server for this. */
class Notifier(private val ctx: Context, private val dao: MsgDao) {
    private val channel = "messages"

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channel, "Messages", NotificationManager.IMPORTANCE_HIGH))
            val calls = NotificationChannel("calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
            calls.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            calls.enableVibration(true)
            nm.createNotificationChannel(calls)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun show(chat: String, sender: String, body: String, mention: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // Muted chats stay silent; in a muted group, being @-mentioned still notifies.
        val st = dao.chatState(chat)
        if (st != null && st.muted() && !(mention && isGroup(chat))) return

        val title = if (isGroup(chat)) dao.groupName(chat) ?: "Group" else dao.contactName(chat) ?: chat
        val text = if (isGroup(chat)) (dao.contactName(sender) ?: sender) + ": " + body else body

        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("chat", chat)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, chat.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(chat.hashCode(), n)
    }

    fun cancel(chat: String) = NotificationManagerCompat.from(ctx).cancel(chat.hashCode())

    /** Heads-up / full-screen notification for a call that arrives while the app is closed. */
    @SuppressLint("MissingPermission")
    fun showIncomingCall(name: String, video: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, 7001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, "calls")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setTimeoutAfter(45_000)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(7001, notification)
    }

    fun cancelIncomingCall() = NotificationManagerCompat.from(ctx).cancel(7001)
}
