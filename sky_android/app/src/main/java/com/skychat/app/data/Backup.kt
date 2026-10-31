package com.skychat.app.data

import android.content.ContentValues
import android.database.Cursor
import android.util.Base64
import com.skychat.app.SkyApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the backup / restore screen shows. */
data class BackupState(val busy: Boolean = false, val text: String = "", val ok: Boolean? = null)

/**
 * Chat backup: one file, encrypted with a password the person chooses (AES-256-GCM in 1 MB pieces, key from PBKDF2-HMAC-SHA256,
 * 250 000 rounds). Inside (a zip): db.json with the chat tables, and every photo, video, voice note and file decrypted from the
 * phone's own vault (so it can be read on another phone) next to it. The Signal keys are NOT in the backup: after a restore on a
 * new phone the contacts see a new safety number, but the history is there.
 */
object Backup {
    val state = MutableStateFlow(BackupState())

    private val MAGIC = "SKYBK1".toByteArray()
    private const val ITER = 250_000
    private const val CHUNK = 1 shl 20
    private val TABLES = listOf("messages", "contacts", "chat_groups", "receipts", "reactions", "chat_state", "peer_profile")

    // ---------------- key and pieces ----------------

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt); mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }

    /** A different nonce for every piece; the last piece is marked (so a cut-off file is noticed). */
    private fun pieceCipher(mode: Int, key: ByteArray, baseIv: ByteArray, counter: Long, last: Boolean): Cipher {
        val iv = baseIv.copyOf()
        for (i in 0 until 8) iv[4 + i] = (iv[4 + i].toInt() xor ((counter shr (56 - 8 * i)) and 0xff).toInt()).toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(byteArrayOf(if (last) 1 else 0))
        return c
    }

    private class EncOut(private val out: OutputStream, private val key: ByteArray, private val baseIv: ByteArray) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var counter = 0L
        private var done = false
        override fun write(b: Int) { if (n == buf.size) flushPiece(false); buf[n++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                if (n == buf.size) flushPiece(false)
                val c = minOf(l, buf.size - n)
                System.arraycopy(b, o, buf, n, c); n += c; o += c; l -= c
            }
        }
        private fun flushPiece(last: Boolean) {
            val ct = pieceCipher(Cipher.ENCRYPT_MODE, key, baseIv, counter, last).doFinal(buf, 0, n)
            out.write(ByteBuffer.allocate(4).putInt(ct.size or (if (last) Int.MIN_VALUE else 0)).array())
            out.write(ct)
            counter++; n = 0
        }
        override fun close() { if (!done) { done = true; flushPiece(true); out.flush(); out.close() } }
    }

    private class DecIn(private val inp: InputStream, private val key: ByteArray, private val baseIv: ByteArray) : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        private var counter = 0L
        private var finished = false
        private fun readFully(b: ByteArray): Int {
            var got = 0
            while (got < b.size) { val r = inp.read(b, got, b.size - got); if (r < 0) break; got += r }
            return got
        }
        private fun fill(): Boolean {
            if (finished) return false
            val h = ByteArray(4)
            if (readFully(h) < 4) throw IOException("The file is cut off")
            val w = ByteBuffer.wrap(h).int
            val last = w < 0
            val len = w and Int.MAX_VALUE
            if (len > CHUNK + 16) throw IOException("The file is damaged")
            val ct = ByteArray(len)
            if (readFully(ct) < len) throw IOException("The file is cut off")
            buf = pieceCipher(Cipher.DECRYPT_MODE, key, baseIv, counter, last).doFinal(ct)    // fails if password or file is wrong
            pos = 0; counter++
            if (last) finished = true
            return true
        }
        override fun read(): Int { while (pos >= buf.size) { if (!fill()) return -1 }; return buf[pos++].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) { if (!fill()) return -1 }
            val c = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, c); pos += c
            return c
        }
    }

    // ---------------- reading and writing table rows ----------------

    private fun rows(app: SkyApp, table: String, where: String = ""): JSONArray {
        val arr = JSONArray()
        app.db.openHelper.readableDatabase.query("SELECT * FROM $table $where").use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> o.put(c.getColumnName(i), JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> o.put(c.getColumnName(i), Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                        else -> o.put(c.getColumnName(i), c.getString(i))
                    }
                }
                arr.put(o)
            }
        }
        return arr
    }

    private fun columnsOf(app: SkyApp, table: String): Set<String> {
        val out = HashSet<String>()
        app.db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c -> while (c.moveToNext()) out.add(c.getString(1)) }
        return out
    }

    private fun dbVersion(app: SkyApp) = app.db.openHelper.readableDatabase.version

    private fun say(text: String) { state.value = BackupState(busy = true, text = text) }

    // ---------------- backup ----------------

    /** Writes the backup to [out] (blocking: call it off the main thread). */
    fun backup(app: SkyApp, out: OutputStream, password: String) {
        try {
            say("Preparing…")
            val rnd = SecureRandom()
            val salt = ByteArray(16).also { rnd.nextBytes(it) }
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            out.write(MAGIC); out.write(salt); out.write(iv); out.write(ByteBuffer.allocate(4).putInt(ITER).array())
            val key = pbkdf2(password, salt, ITER)
            ZipOutputStream(EncOut(out, key, iv)).use { zip ->
                zip.setLevel(1)                 // photos and videos are compressed already
                val tables = JSONObject()
                for (t in TABLES) {
                    val r = rows(app, t, if (t == "messages") "WHERE kind != 'ctl'" else "")
                    // pictures of profiles and groups are fetched again later
                    if (t == "chat_groups") for (i in 0 until r.length()) r.getJSONObject(i).put("photoPath", "").put("photoMid", "").put("photoKey", "")
                    if (t == "peer_profile") for (i in 0 until r.length()) r.getJSONObject(i).put("photoPath", "").put("photoMid", "").put("fetched", 0)
                    tables.put(t, r)
                }
                val meta = JSONObject().put("app", "sky").put("ver", dbVersion(app)).put("time", System.currentTimeMillis())
                    .put("phone", app.prefs.phone ?: "").put("tables", tables)
                zip.putNextEntry(ZipEntry("db.json")); zip.write(meta.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()

                val msgs = tables.getJSONArray("messages")
                var files = 0
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    val path = m.optString("localPath")
                    if (path.isEmpty() || !File(path).exists()) continue
                    files++
                    say("Saving files… $files")
                    val id = m.getString("id")
                    zip.putNextEntry(ZipEntry("media/$id"))
                    app.media.decryptTo(File(path), zip)
                    zip.closeEntry()
                    val thumb = File("$path.thumb")
                    if (thumb.exists()) {
                        zip.putNextEntry(ZipEntry("thumb/$id")); app.media.decryptTo(thumb, zip); zip.closeEntry()
                    }
                }
                state.value = BackupState(false, "Backup saved: ${msgs.length()} messages and $files files.", true)
            }
        } catch (e: Exception) {
            state.value = BackupState(false, "Backup failed: " + (e.message ?: e.javaClass.simpleName), false)
        }
    }

    // ---------------- restore ----------------

    /** Reads a backup from [inp] into the app (blocking: call it off the main thread). Newer rows with the same id are replaced. */
    fun restore(app: SkyApp, inp: InputStream, password: String) {
        try {
            say("Opening the backup…")
            val head = ByteArray(MAGIC.size + 16 + 12 + 4)
            var got = 0
            while (got < head.size) { val r = inp.read(head, got, head.size - got); if (r < 0) break; got += r }
            if (got < head.size || !head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("This is not a Sky backup")
            val salt = head.copyOfRange(MAGIC.size, MAGIC.size + 16)
            val iv = head.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
            val iter = ByteBuffer.wrap(head, MAGIC.size + 28, 4).int
            if (iter < 1000 || iter > 5_000_000) throw IOException("This is not a Sky backup")
            val key = try { pbkdf2(password, salt, iter) } catch (e: Exception) { throw IOException("Wrong password") }

            val files = HashMap<String, String>()                 // message id -> where the file is now
            var meta: JSONObject? = null
            var fnames = HashMap<String, String>()
            try {
                ZipInputStream(DecIn(inp, key, iv)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        when {
                            e.name == "db.json" -> {
                                val j = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                                if (j.optString("app") != "sky") throw IOException("This is not a Sky backup")
                                if (j.optInt("ver") != dbVersion(app))
                                    throw IOException("The backup is from another version of the app (database ${j.optInt("ver")}, this app ${dbVersion(app)}). Use the same version.")
                                meta = j
                                val msgs = j.getJSONObject("tables").getJSONArray("messages")
                                for (i in 0 until msgs.length()) msgs.getJSONObject(i).let { fnames[it.getString("id")] = it.optString("fname") }
                            }
                            e.name.startsWith("media/") && meta != null -> {
                                val id = e.name.removePrefix("media/")
                                val ext = fnames[id]?.substringAfterLast('.', "")?.take(8)?.let { if (it.isEmpty()) "" else ".$it" } ?: ""
                                val tmp = File(app.media.tmpDir, "rst_$id")
                                tmp.outputStream().use { o -> zip.copyTo(o) }
                                val dst = File(app.client.mediaDir, "rst_$id$ext")
                                if (app.media.store(tmp, dst)) files[id] = dst.absolutePath else app.media.shred(tmp)
                                say("Restoring files… ${files.size}")
                            }
                            e.name.startsWith("thumb/") && meta != null -> {
                                val id = e.name.removePrefix("thumb/")
                                val tmp = File(app.media.tmpDir, "rst_t_$id")
                                tmp.outputStream().use { o -> zip.copyTo(o) }
                                val base = files[id]
                                if (base == null || !app.media.store(tmp, File("$base.thumb"))) app.media.shred(tmp)
                            }
                        }
                    }
                }
            } catch (e: javax.crypto.AEADBadTagException) {
                throw IOException("Wrong password, or the file is damaged")
            }
            val j = meta ?: throw IOException("The backup is empty")

            say("Saving the chats…")
            val sdb = app.db.openHelper.writableDatabase
            var count = 0
            app.db.runInTransaction(Runnable {
                val tables = j.getJSONObject("tables")
                for (t in TABLES) {
                    val cols = columnsOf(app, t)
                    val arr = tables.optJSONArray(t) ?: continue
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (t == "messages") {
                            val id = o.getString("id")
                            o.put("localPath", files[id] ?: "")
                            if (o.optInt("fromMe") == 1 && o.optInt("status") == St.PENDING) o.put("status", St.FAILED)   // not sent before: do not send now
                            count++
                        }
                        val cv = ContentValues()
                        for (k in o.keys()) {
                            if (k !in cols) continue
                            when (val v = o.get(k)) {
                                JSONObject.NULL -> cv.putNull(k)
                                is Boolean -> cv.put(k, if (v) 1L else 0L)
                                is Int -> cv.put(k, v.toLong())
                                is Long -> cv.put(k, v)
                                is Double -> cv.put(k, v)
                                else -> cv.put(k, v.toString())
                            }
                        }
                        sdb.insert(t, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
                    }
                }
            })
            state.value = BackupState(false, "Restored $count messages and ${files.size} files.", true)
        } catch (e: Exception) {
            state.value = BackupState(false, "Restore failed: " + (e.message ?: e.javaClass.simpleName), false)
        }
    }
}
