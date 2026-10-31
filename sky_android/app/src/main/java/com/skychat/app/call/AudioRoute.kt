package com.skychat.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Where the sound of a call goes. */
enum class Route(val label: String, val icon: String) {
    EARPIECE("Phone", "📱"), SPEAKER("Speaker", "🔊"), BLUETOOTH("Bluetooth", "🎧"), WIRED("Headset", "🎧")
}

/** Chooses the speaker, the earpiece, a Bluetooth headset or a wired headset for a call. */
object AudioRoute {
    private val btTypes = buildList {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
    private val wiredTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun available(ctx: Context): List<Route> {
        val devs = am(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val out = mutableListOf(Route.EARPIECE, Route.SPEAKER)
        if (devs.any { it.type in btTypes }) out.add(Route.BLUETOOTH)
        if (devs.any { it.type in wiredTypes }) out.add(Route.WIRED)
        return out
    }

    /** At the start of a call: a connected headset first, otherwise the speaker for video and the earpiece for voice. */
    fun best(ctx: Context, video: Boolean): Route {
        val a = available(ctx)
        return when {
            Route.BLUETOOTH in a -> Route.BLUETOOTH
            Route.WIRED in a -> Route.WIRED
            video -> Route.SPEAKER
            else -> Route.EARPIECE
        }
    }

    @Suppress("DEPRECATION")
    fun select(ctx: Context, route: Route) {
        val a = am(ctx)
        if (Build.VERSION.SDK_INT >= 31) {
            val types = when (route) {
                Route.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                Route.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                Route.BLUETOOTH -> btTypes
                Route.WIRED -> wiredTypes
            }
            try {
                a.availableCommunicationDevices.firstOrNull { it.type in types }?.let { a.setCommunicationDevice(it) }
            } catch (_: Exception) { }      // e.g. Bluetooth permission not granted
        } else when (route) {
            Route.SPEAKER -> { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = true }
            Route.BLUETOOTH -> { a.isSpeakerphoneOn = false; a.startBluetoothSco(); a.isBluetoothScoOn = true }
            else -> { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = false }
        }
    }

    @Suppress("DEPRECATION")
    fun clear(ctx: Context) {
        val a = am(ctx)
        if (Build.VERSION.SDK_INT >= 31) try { a.clearCommunicationDevice() } catch (_: Exception) { }
        else { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = false }
    }
}
