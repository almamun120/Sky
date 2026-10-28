package com.skychat.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the push service after a reboot or an app update, if someone is logged in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) PushService.ensure(context)
    }
}
