package com.skychat.app.crypto

import android.content.Context
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

/**
 * Whole-database encryption (SQLCipher, AES-256).
 *
 * The passphrase is 32 random bytes written as 64 hex characters. It is sealed by the Android
 * Keystore [Vault] and only the sealed form is stored (own prefs file "sky_dbkey").
 * Without the Keystore key the database file is unreadable.
 */
object DbCipher {
    const val DB_NAME = "sky.db"
    private const val PREFS = "sky_dbkey"
    private const val KEY = "k"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    enum class Start { Fresh, Existing, Migrated, Reset }

    class Opened(val passphrase: String, val start: Start)

    /** Call once, before Room opens the database. Safe to call again. */
    fun prepare(ctx: Context, vault: Vault = Vault()): Opened {
        System.loadLibrary("sqlcipher")
        val dbFile = ctx.getDatabasePath(DB_NAME)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        var pass = loadKey(sp, vault)
        val state = fileState(dbFile)

        if (pass == null) {
            return when (state) {
                FileState.Missing -> Opened(newKey(sp, vault), Start.Fresh)
                FileState.Plain -> {
                    val p = newKey(sp, vault)
                    migratePlain(dbFile, p)
                    Opened(p, Start.Migrated)
                }
                // An encrypted database exists but its key is gone (Keystore wiped, app data
                // restored to another phone ...). It can never be opened again: start clean.
                FileState.Encrypted -> {
                    wipe(ctx, dbFile)
                    Opened(newKey(sp, vault), Start.Reset)
                }
            }
        }
        if (state == FileState.Plain) {
            // Key exists but the file is still plaintext (an earlier migration was cut short).
            migratePlain(dbFile, pass)
            return Opened(pass, Start.Migrated)
        }
        return Opened(pass, if (state == FileState.Missing) Start.Fresh else Start.Existing)
    }

    // ---- key ----

    private fun loadKey(sp: android.content.SharedPreferences, vault: Vault): String? {
        val sealed = sp.getString(KEY, null) ?: return null
        return try {
            String(vault.open(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.US_ASCII)
        } catch (e: Exception) {
            null   // Keystore key lost or data damaged
        }
    }

    private fun newKey(sp: android.content.SharedPreferences, vault: Vault): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = raw.joinToString("") { "%02x".format(it) }
        raw.fill(0)
        val sealed = Base64.encodeToString(vault.seal(hex.toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP)
        // commit(), not apply(): the key must be on disk before any data is encrypted with it.
        check(sp.edit().putString(KEY, sealed).commit()) { "could not store database key" }
        return hex
    }

    // ---- file state ----

    private enum class FileState { Missing, Plain, Encrypted }

    private fun fileState(f: File): FileState {
        if (!f.exists() || f.length() == 0L) return FileState.Missing
        val head = ByteArray(16)
        val n = f.inputStream().use { it.read(head) }
        return if (n == 16 && head.contentEquals(PLAIN_HEADER)) FileState.Plain else FileState.Encrypted
    }

    private fun sidecars(f: File) = listOf(File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal"))

    private fun wipe(ctx: Context, dbFile: File) {
        dbFile.delete(); sidecars(dbFile).forEach { it.delete() }
        // Attachments belong to the lost database; remove them too. Also forget the login:
        // the Signal keys went with the database, so the account must sign in again.
        File(ctx.filesDir, "media").deleteRecursively()
        ctx.getSharedPreferences("sky", Context.MODE_PRIVATE).edit()
            .remove("token").remove("phone").remove("token.s").remove("phone.s").apply()
    }

    // ---- plaintext -> encrypted ----

    /**
     * Copies the whole old database into a new encrypted file (sqlcipher_export), checks the copy,
     * then swaps it in. The original stays untouched until the copy has been verified, so a crash
     * at any point leaves either the old plaintext file (migration runs again) or the new one.
     */
    private fun migratePlain(dbFile: File, pass: String) {
        val tmp = File(dbFile.path + ".enc")
        tmp.delete(); sidecars(tmp).forEach { it.delete() }

        val plain = SQLiteDatabase.openDatabase(dbFile.path, "", null, SQLiteDatabase.OPEN_READWRITE, null)
        val rows = try {
            val version = plain.version                      // Room's schema version lives here
            plain.rawExecSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY '$pass';")
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            plain.rawExecSQL("PRAGMA encrypted.user_version = $version;")
            plain.rawExecSQL("DETACH DATABASE encrypted;")
            count(plain)
        } finally {
            plain.close()                                    // also folds the WAL into the file
        }

        // Verify: opens with the key and holds as many messages as the original.
        val enc = SQLiteDatabase.openDatabase(tmp.path, pass, null, SQLiteDatabase.OPEN_READONLY, null)
        try {
            check(count(enc) == rows) { "encrypted copy does not match the original" }
        } finally {
            enc.close()
        }

        sidecars(dbFile).forEach { it.delete() }
        check(tmp.renameTo(dbFile)) { "could not move the encrypted database into place" }
    }

    private fun count(db: SQLiteDatabase): Long {
        val c = db.rawQuery("SELECT COUNT(*) FROM messages", arrayOf<String>())
        return try { if (c.moveToFirst()) c.getLong(0) else 0L } finally { c.close() }
    }
}
