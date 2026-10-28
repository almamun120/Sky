package com.skychat.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.skychat.app.data.Kv
import com.skychat.app.data.MsgDao
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps secrets with an AES key that lives in the Android Keystore (it never leaves secure
 * hardware where available). Signal session state and private keys are stored sealed by this.
 */
class Vault {
    private val alias = "sky_vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)               // 12-byte IV, then ciphertext+tag
    }

    fun open(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** [KvStore] on Room, every value sealed by the [Vault]. Call only from background threads. */
class RoomKv(private val dao: MsgDao, private val vault: Vault) : KvStore {
    override fun get(table: String, key: String): ByteArray? = dao.kvGet(table, key)?.let { vault.open(it) }
    override fun put(table: String, key: String, value: ByteArray) { dao.kvPut(Kv(table, key, vault.seal(value))) }
    override fun remove(table: String, key: String) { dao.kvRemove(table, key) }
    override fun keys(table: String): List<String> = dao.kvKeys(table)
}

/** Attachments: random AES-256-GCM key per file; the key travels inside the encrypted message. */
object MediaCrypto {
    private val rnd = SecureRandom()

    /** Writes IV + ciphertext + tag to [dst] and returns the key. */
    fun encrypt(src: File, dst: File): ByteArray {
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        dst.outputStream().use { o ->
            o.write(iv)
            CipherOutputStream(o, c).use { co -> src.inputStream().use { it.copyTo(co) } }
        }
        return key
    }

    /** Returns false if the file was tampered with or the key is wrong. */
    fun decrypt(src: File, dst: File, key: ByteArray): Boolean {
        return try {
            src.inputStream().use { i ->
                val iv = ByteArray(12)
                var n = 0
                while (n < 12) { val r = i.read(iv, n, 12 - n); if (r < 0) return false; n += r }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                CipherInputStream(i, c).use { ci -> dst.outputStream().use { ci.copyTo(it) } }
            }
            true
        } catch (e: Exception) {
            dst.delete(); false
        }
    }
}

/**
 * The profile (about text, photo id and photo key) is sealed with a random 32-byte profile key that only the
 * owner and the people the owner chats with ever get (inside end-to-end encrypted messages). The server stores
 * the sealed blob and cannot read it. Layout: 12-byte IV + AES-256-GCM ciphertext.
 */
object ProfileCrypto {
    private val rnd = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    /** Null if the key is wrong or the blob was altered. */
    fun open(key: ByteArray, blob: ByteArray): ByteArray? = try {
        if (blob.size < 12 + 16) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            c.doFinal(blob, 12, blob.size - 12)
        }
    } catch (e: Exception) { null }
}
