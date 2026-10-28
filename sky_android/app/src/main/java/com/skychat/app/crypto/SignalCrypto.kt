package com.skychat.app.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

class PublicPreKey(val id: Int, val pub: ByteArray)
class SignedPub(val id: Int, val pub: ByteArray, val sig: ByteArray)

/** What we publish to the server (public parts only). */
class KeyUpload(
    val identity: ByteArray,
    val regId: Int,
    val spk: SignedPub,
    val kyber: SignedPub,
    val preKeys: List<PublicPreKey>
)

/** What the server returns for someone we want to talk to. */
class RemoteBundle(
    val identity: ByteArray,
    val regId: Int,
    val spk: SignedPub,
    val kyber: SignedPub,
    val preKey: PublicPreKey?
)

class Sealed(val type: Int, val data: ByteArray)

/**
 * End-to-end encryption for one account: X3DH/PQXDH session setup + Double Ratchet, using
 * Signal's libsignal. One device per account (device id 1). All methods are synchronized.
 */
class SignalCrypto(
    private val kv: KvStore,
    private val myPhone: String,
    onIdentityReplaced: (String) -> Unit
) {
    private val store = SignalStore(kv, onIdentityReplaced)
    private val local = SignalProtocolAddress(myPhone, 1)
    private fun addr(phone: String) = SignalProtocolAddress(phone, 1)

    @Synchronized fun hasIdentity(): Boolean = store.hasIdentity()

    /** Creates identity + signed prekey + last-resort Kyber prekey + [oneTime] one-time prekeys. */
    @Synchronized
    fun createKeys(oneTime: Int): KeyUpload {
        val pair = if (store.hasIdentity()) store.identityKeyPair else store.createIdentity()
        val now = System.currentTimeMillis()

        val spkId = store.nextSignedId()
        val spkPair = ECKeyPair.generate()
        val spkSig = pair.privateKey.calculateSignature(spkPair.publicKey.serialize())
        store.storeSignedPreKey(spkId, SignedPreKeyRecord(spkId, now, spkPair, spkSig))

        val kyberId = store.nextKyberId()
        val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kemPub = kem.publicKey.serialize()
        val kemSig = pair.privateKey.calculateSignature(kemPub)
        store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, now, kem, kemSig))

        return KeyUpload(
            identity = pair.publicKey.serialize(),
            regId = store.localRegistrationId,
            spk = SignedPub(spkId, spkPair.publicKey.serialize(), spkSig),
            kyber = SignedPub(kyberId, kemPub, kemSig),
            preKeys = moreOneTimeKeysLocked(oneTime)
        )
    }

    @Synchronized fun moreOneTimeKeys(count: Int): List<PublicPreKey> = moreOneTimeKeysLocked(count)

    private fun moreOneTimeKeysLocked(count: Int): List<PublicPreKey> =
        store.nextPreKeyIds(count).map { id ->
            val kp = ECKeyPair.generate()
            store.storePreKey(id, PreKeyRecord(id, kp))
            PublicPreKey(id, kp.publicKey.serialize())
        }

    @Synchronized fun hasSession(phone: String): Boolean = store.containsSession(addr(phone))

    /** Builds a session from the keys the server handed out for [phone]. */
    @Synchronized
    fun processBundle(phone: String, b: RemoteBundle) {
        val bundle = PreKeyBundle(
            b.regId, 1,
            b.preKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            b.preKey?.let { ECPublicKey(it.pub) },
            b.spk.id, ECPublicKey(b.spk.pub), b.spk.sig,
            IdentityKey(b.identity),
            b.kyber.id, KEMPublicKey(b.kyber.pub), b.kyber.sig
        )
        SessionBuilder(store, addr(phone), local).process(bundle)
    }

    @Synchronized
    fun encrypt(phone: String, plaintext: ByteArray): Sealed {
        val msg = SessionCipher(store, local, addr(phone)).encrypt(pad(plaintext))
        return Sealed(msg.type, msg.serialize())
    }

    @Synchronized
    fun decrypt(phone: String, type: Int, data: ByteArray): ByteArray {
        val cipher = SessionCipher(store, local, addr(phone))
        val plain = when (type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(data))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(data))
            else -> throw IllegalArgumentException("unknown ciphertext type $type")
        }
        return unpad(plain)
    }

    /** 60-digit number both people can compare (in person or over a call). Null if never talked. */
    @Synchronized
    fun safetyNumber(phone: String): String? {
        val remote = store.getIdentity(addr(phone)) ?: return null
        if (!store.hasIdentity()) return null
        val fp = NumericFingerprintGenerator(5200).createFor(
            2, myPhone.toByteArray(), store.identityKeyPair.publicKey,
            phone.toByteArray(), remote
        )
        return fp.displayableFingerprint.displayText.chunked(5).joinToString(" ")
    }

    @Synchronized fun oneTimeKeysLeft(): Int = store.oneTimePreKeyCount()

    // Pad to a multiple of 160 bytes so message length leaks less (0x80, then zeros).
    private fun pad(b: ByteArray): ByteArray {
        val total = ((b.size + 1) / 160 + 1) * 160
        val out = ByteArray(total)
        System.arraycopy(b, 0, out, 0, b.size)
        out[b.size] = 0x80.toByte()
        return out
    }

    private fun unpad(b: ByteArray): ByteArray {
        var i = b.size - 1
        while (i >= 0 && b[i] == 0.toByte()) i--
        return if (i >= 0 && b[i] == 0x80.toByte()) b.copyOf(i) else b
    }
}
