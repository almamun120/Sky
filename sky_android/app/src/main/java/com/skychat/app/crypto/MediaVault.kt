package com.skychat.app.crypto

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted storage for downloaded and sent attachments (files/media).
 *
 * One random 32-byte file key is sealed by the Android Keystore [Vault] and kept in its own prefs
 * file ("sky_mediakey"). Every file is AES-256-GCM in 64 KiB chunks:
 *
 *     "SKYM1\0" | 8-byte random nonce | chunk, chunk, ...      chunk = ciphertext + 16-byte tag
 *
 * The IV of a chunk is nonce + 4-byte counter, and the "last chunk" flag is authenticated, so
 * reordering, cutting or extending a file is detected. Plain files left over from step 12 are
 * recognised by the missing header and are converted by [migrateLegacy].
 *
 * Plaintext only ever exists briefly in [tmpDir] (cache), which is emptied at every app start.
 */
class MediaVault(private val ctx: Context, private val vault: Vault = Vault()) {
    val dir = File(ctx.filesDir, "media").apply { mkdirs() }

    /** Short-lived plaintext: recordings before they are stored, files handed to other apps. */
    val tmpDir = File(ctx.cacheDir, "plain").apply { mkdirs() }

    /** Subfolder shared through FileProvider (see file_paths.xml). */
    val viewDir = File(tmpDir, "view").apply { mkdirs() }

    private val rnd = SecureRandom()
    @Volatile private var cachedKey: ByteArray? = null

    // ---- key ----

    @Synchronized
    private fun fileKey(): ByteArray {
        cachedKey?.let { return it }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY, null)?.let {
            try { vault.open(Base64.decode(it, Base64.NO_WRAP)) } catch (e: Exception) { null }
        }
        if (existing != null && existing.size == 32) { cachedKey = existing; return existing }
        // First run, or the Keystore key was lost (then the old files cannot be read anyway).
        val k = ByteArray(32).also { rnd.nextBytes(it) }
        val sealed = Base64.encodeToString(vault.seal(k), Base64.NO_WRAP)
        check(sp.edit().putString(KEY, sealed).commit()) { "could not store media key" }
        cachedKey = k
        return k
    }

    // ---- API ----

    fun isEncrypted(f: File): Boolean {
        if (!f.isFile) return false
        return try {
            f.inputStream().use { i ->
                val h = ByteArray(MAGIC.size)
                readFully(i, h) == h.size && h.contentEquals(MAGIC)
            }
        } catch (e: Exception) { false }
    }

    /** Encrypts [src] into [dst] (different files). */
    fun encryptFile(src: File, dst: File) {
        src.inputStream().use { i -> dst.outputStream().use { o -> encryptStream(i, o) } }
    }

    /**
     * Moves a plaintext file into the vault: writes <dst>, then wipes and deletes [plain].
     * Returns false (and leaves [plain] alone) if encryption failed.
     */
    fun store(plain: File, dst: File): Boolean {
        val part = File(dst.path + ".part")
        return try {
            encryptFile(plain, part)
            if (dst.exists()) dst.delete()
            check(part.renameTo(dst))
            shred(plain)
            true
        } catch (e: Exception) {
            part.delete(); false
        }
    }

    /** Decrypts [src] (vault file, or a legacy plain file) into [out]. False if damaged. */
    fun decryptTo(src: File, out: OutputStream): Boolean {
        return try {
            src.inputStream().use { i ->
                val h = ByteArray(MAGIC.size)
                val n = readFully(i, h)
                if (n == h.size && h.contentEquals(MAGIC)) decryptStream(i, out)
                else {                                    // legacy plain file: copy through
                    out.write(h, 0, n)
                    i.copyTo(out)
                    true
                }
            }
        } catch (e: Exception) { false }
    }

    /** Decrypts into a file under [tmpDir] (or [viewDir] if [forOtherApp]). Null if unreadable. */
    fun toTemp(src: File, name: String, forOtherApp: Boolean = false): File? {
        val base = if (forOtherApp) viewDir else tmpDir
        base.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "file" }
        val out = File(base, System.nanoTime().toString(16) + "_" + safe)
        val ok = out.outputStream().use { decryptTo(src, it) }
        if (!ok) { out.delete(); return null }
        return out
    }

    /** Whole file in memory (images for thumbnails). Null if unreadable. */
    fun readBytes(src: File): ByteArray? {
        val bo = java.io.ByteArrayOutputStream()
        return if (decryptTo(src, bo)) bo.toByteArray() else null
    }

    /** Encrypts every plain file left in [dir] by step 12 (same file name, so the database stays valid). */
    fun migrateLegacy() {
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.name.endsWith(".part")) { if (f.name.endsWith(".part")) f.delete(); return@forEach }
            if (isEncrypted(f)) return@forEach
            val tmp = File(dir, f.name + ".part")
            try {
                encryptFile(f, tmp)
                // Verify before replacing the original.
                val verify = java.io.ByteArrayOutputStream()
                val same = tmp.inputStream().use { i ->
                    val h = ByteArray(MAGIC.size); readFully(i, h)
                    decryptStream(i, verify)
                } && verify.size().toLong() == f.length()
                if (same) {
                    shred(f)
                    tmp.renameTo(f)
                } else tmp.delete()
            } catch (e: Exception) {
                tmp.delete()
            }
        }
    }

    /** Removes all leftover plaintext. Call at app start. */
    fun cleanTemp() {
        tmpDir.listFiles()?.forEach { it.deleteRecursively() }
        viewDir.mkdirs()
        dir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
    }

    fun clearAll() {
        dir.deleteRecursively(); dir.mkdirs()
        cleanTemp()
    }

    /** Best-effort overwrite before delete (flash wear levelling may still keep old blocks). */
    fun shred(f: File) {
        try {
            if (f.isFile) {
                val len = f.length()
                java.io.RandomAccessFile(f, "rw").use { r ->
                    val z = ByteArray(8192)
                    var left = len
                    while (left > 0) { val n = minOf(left, z.size.toLong()).toInt(); r.write(z, 0, n); left -= n }
                    r.fd.sync()
                }
            }
        } catch (_: Exception) { }
        f.delete()
    }

    // ---- chunked AES-GCM ----

    private fun cipher(mode: Int, nonce: ByteArray, counter: Int): Cipher {
        val iv = ByteArray(12)
        System.arraycopy(nonce, 0, iv, 0, 8)
        iv[8] = (counter ushr 24).toByte(); iv[9] = (counter ushr 16).toByte()
        iv[10] = (counter ushr 8).toByte(); iv[11] = counter.toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(fileKey(), "AES"), GCMParameterSpec(128, iv))
        return c
    }

    private fun encryptStream(input: InputStream, out: OutputStream) {
        val nonce = ByteArray(8).also { rnd.nextBytes(it) }
        out.write(MAGIC); out.write(nonce)
        var cur = ByteArray(CHUNK)
        var nxt = ByteArray(CHUNK)
        var curN = readFully(input, cur)
        var ctr = 0
        while (true) {
            val nxtN = if (curN == CHUNK) readFully(input, nxt) else 0
            val last = nxtN == 0
            val c = cipher(Cipher.ENCRYPT_MODE, nonce, ctr++)
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            out.write(c.doFinal(cur, 0, curN))
            if (last) break
            val t = cur; cur = nxt; nxt = t; curN = nxtN
        }
        cur.fill(0); nxt.fill(0)
    }

    /** [input] is positioned right after the magic. Returns false if anything fails to verify. */
    private fun decryptStream(input: InputStream, out: OutputStream): Boolean {
        val nonce = ByteArray(8)
        if (readFully(input, nonce) != 8) return false
        val size = CHUNK + TAG
        var cur = ByteArray(size)
        var nxt = ByteArray(size)
        var curN = readFully(input, cur)
        if (curN < TAG) return false
        var ctr = 0
        while (true) {
            val nxtN = if (curN == size) readFully(input, nxt) else 0
            val last = nxtN == 0
            val c = cipher(Cipher.DECRYPT_MODE, nonce, ctr++)
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            val plain = try { c.doFinal(cur, 0, curN) } catch (e: Exception) { return false }
            out.write(plain)
            plain.fill(0)
            if (last) break
            if (nxtN < TAG) return false
            val t = cur; cur = nxt; nxt = t; curN = nxtN
        }
        cur.fill(0); nxt.fill(0)
        return true
    }

    /** Reads until the buffer is full or the stream ends; returns the number of bytes read. */
    private fun readFully(i: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    companion object {
        private const val PREFS = "sky_mediakey"
        private const val KEY = "k"
        private const val CHUNK = 64 * 1024
        private const val TAG = 16
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'Y'.code.toByte(),
            'M'.code.toByte(), '1'.code.toByte(), 0)
    }
}
