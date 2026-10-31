package com.skychat.app.media

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.skychat.app.crypto.MediaVault
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

/** Records AAC voice notes (mono, 16 kHz, 32 kbps: small and clear for speech). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    @Suppress("DEPRECATION")
    private fun newRecorder() =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    fun start(dir: File): Boolean {
        val f = File(dir, UUID.randomUUID().toString() + ".m4a")
        val r = newRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            r.release(); f.delete(); false
        }
    }

    /** Returns the file and its length in ms, or null if it was too short or failed. */
    fun stop(): Pair<File, Int>? {
        val r = rec ?: return null
        val f = file ?: return null
        rec = null
        val dur = (System.currentTimeMillis() - startedAt).toInt()
        return try {
            r.stop(); r.release()
            if (dur < 800) { f.delete(); null } else f to dur
        } catch (e: Exception) {
            r.release(); f.delete(); null
        }
    }

    fun cancel() {
        rec?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        rec = null
        file?.delete()
    }
}

/** One voice note plays at a time. Files are decrypted to a temporary copy that is deleted afterwards. */
object AudioPlayer {
    val playing = MutableStateFlow<String?>(null)
    /** 1, 1.5 or 2 times normal speed (kept for the next voice notes too). */
    val speed = MutableStateFlow(1f)

    fun cycleSpeed() {
        speed.value = when (speed.value) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        mp?.let { try { it.playbackParams = it.playbackParams.setSpeed(speed.value) } catch (_: Exception) { } }
    }
    private var mp: MediaPlayer? = null
    private var tmp: File? = null
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    /** [media] decrypts [path]; playback state is only touched on the main thread. */
    fun toggle(id: String, media: MediaVault, path: String) {
        if (playing.value == id) { stop(); return }
        stop()
        val my = ++gen
        playing.value = id                        // shows ⏸ immediately while decrypting
        Thread {
            val f = media.toTemp(File(path), "voice.m4a")
            main.post {
                if (gen != my || playing.value != id) { f?.let { media.shred(it) }; return@post }
                if (f == null) { playing.value = null; return@post }
                val p = MediaPlayer()
                try {
                    p.setDataSource(f.absolutePath)
                    p.prepare()
                    p.setOnCompletionListener { stop() }
                    p.start()
                    if (speed.value != 1f) p.playbackParams = p.playbackParams.setSpeed(speed.value)   // after start(): setting it earlier would start playback
                    mp = p; tmp = f
                } catch (e: Exception) {
                    p.release(); media.shred(f); playing.value = null
                }
            }
        }.start()
    }

    fun stop() {
        gen++
        mp?.release()
        mp = null
        tmp?.let { try { it.delete() } catch (_: Exception) { } }
        tmp = null
        playing.value = null
    }
}

/**
 * Hands a file to another app. The decrypted copy lives in cache/plain/view and is removed at the
 * next app start (the other app may still be reading it, so it cannot be deleted right away).
 */
fun openFile(ctx: Context, media: MediaVault, path: String, mime: String, name: String) {
    Thread {
        val f = media.toTemp(File(path), name, forOtherApp = true) ?: return@Thread
        Handler(Looper.getMainLooper()).post {
            try {
                val uri = FileProvider.getUriForFile(ctx, "com.skychat.app.files", f)
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime.ifEmpty { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, null))
            } catch (_: Exception) { }
        }
    }.start()
}
