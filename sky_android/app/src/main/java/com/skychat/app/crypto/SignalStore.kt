package com.skychat.app.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Persists all Signal protocol state in a [KvStore] (the Android side encrypts the values with
 * a hardware-backed key). Identity policy is trust-on-first-use: a changed key is accepted but
 * reported through [onIdentityReplaced] so the UI can warn ("safety number changed").
 */
class SignalStore(
    private val kv: KvStore,
    private val onIdentityReplaced: (String) -> Unit
) : SignalProtocolStore {

    private companion object {
        const val META = "meta"
        const val PEER = "peer"
        const val SESSION = "session"
        const val PREKEY = "prekey"
        const val SPK = "spk"
        const val KYBER = "kyber"
        const val KYBER_USED = "kyberused"
        const val SENDER = "sender"
    }

    // ---------- own identity ----------

    fun hasIdentity(): Boolean = kv.get(META, "identity") != null

    fun createIdentity(): IdentityKeyPair {
        val pair = IdentityKeyPair.generate()
        kv.put(META, "identity", pair.serialize())
        // Registration id: 14 bits like Signal uses.
        val reg = (java.security.SecureRandom().nextInt(16380) + 1)
        kv.put(META, "reg", ByteBuffer.allocate(4).putInt(reg).array())
        return pair
    }

    /** Next free one-time prekey id (wraps well before 2^24). */
    fun nextPreKeyIds(count: Int): IntRange {
        val cur = kv.get(META, "nextPreKey")?.let { ByteBuffer.wrap(it).int } ?: 1
        kv.put(META, "nextPreKey", ByteBuffer.allocate(4).putInt(cur + count).array())
        return cur until (cur + count)
    }

    fun nextSignedId(): Int {
        val cur = kv.get(META, "nextSpk")?.let { ByteBuffer.wrap(it).int } ?: 1
        kv.put(META, "nextSpk", ByteBuffer.allocate(4).putInt(cur + 1).array())
        return cur
    }

    fun nextKyberId(): Int {
        val cur = kv.get(META, "nextKyber")?.let { ByteBuffer.wrap(it).int } ?: 1
        kv.put(META, "nextKyber", ByteBuffer.allocate(4).putInt(cur + 1).array())
        return cur
    }

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(kv.get(META, "identity") ?: error("identity not created"))

    override fun getLocalRegistrationId(): Int =
        ByteBuffer.wrap(kv.get(META, "reg") ?: error("identity not created")).int

    // ---------- peer identities ----------

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val old = kv.get(PEER, address.name)
        val new = identityKey.serialize()
        kv.put(PEER, address.name, new)
        return if (old != null && !old.contentEquals(new)) {
            onIdentityReplaced(address.name)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    // Trust on first use; changes are accepted and surfaced as a warning in the chat.
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        kv.get(PEER, address.name)?.let { IdentityKey(it) }

    // ---------- sessions ----------

    private fun sKey(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        kv.get(SESSION, sKey(address))?.let { SessionRecord(it) } ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a ->
            kv.get(SESSION, sKey(a))?.let { SessionRecord(it) }
                ?: throw NoSessionException("no session for ${a.name}")
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        kv.keys(SESSION).filter { it.startsWith("$name.") }
            .mapNotNull { it.substringAfterLast('.').toIntOrNull() }
            .filter { it != 1 }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        kv.put(SESSION, sKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        kv.get(SESSION, sKey(address))?.let { SessionRecord(it).hasSenderChain(0.0) } ?: false

    override fun deleteSession(address: SignalProtocolAddress) { kv.remove(SESSION, sKey(address)) }

    override fun deleteAllSessions(name: String) {
        kv.keys(SESSION).filter { it.startsWith("$name.") }.forEach { kv.remove(SESSION, it) }
    }

    // ---------- one-time prekeys ----------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        PreKeyRecord(kv.get(PREKEY, preKeyId.toString()) ?: throw InvalidKeyIdException("no prekey $preKeyId"))

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { kv.put(PREKEY, preKeyId.toString(), record.serialize()) }
    override fun containsPreKey(preKeyId: Int): Boolean = kv.get(PREKEY, preKeyId.toString()) != null
    override fun removePreKey(preKeyId: Int) { kv.remove(PREKEY, preKeyId.toString()) }

    fun oneTimePreKeyCount(): Int = kv.keys(PREKEY).size

    // ---------- signed prekeys ----------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        SignedPreKeyRecord(kv.get(SPK, signedPreKeyId.toString()) ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId"))

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        kv.keys(SPK).mapNotNull { k -> kv.get(SPK, k)?.let { SignedPreKeyRecord(it) } }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        kv.put(SPK, signedPreKeyId.toString(), record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = kv.get(SPK, signedPreKeyId.toString()) != null
    override fun removeSignedPreKey(signedPreKeyId: Int) { kv.remove(SPK, signedPreKeyId.toString()) }

    // ---------- Kyber (post-quantum) prekeys: we only publish one "last resort" key ----------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        KyberPreKeyRecord(kv.get(KYBER, kyberPreKeyId.toString()) ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId"))

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        kv.keys(KYBER).mapNotNull { k -> kv.get(KYBER, k)?.let { KyberPreKeyRecord(it) } }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kv.put(KYBER, kyberPreKeyId.toString(), record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kv.get(KYBER, kyberPreKeyId.toString()) != null

    /** Last-resort key: never deleted, but each (kyber id, signed id, base key) may be used once. */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val tag = "$kyberPreKeyId:$signedPreKeyId:" + baseKey.serialize().joinToString("") { "%02x".format(it) }
        if (kv.get(KYBER_USED, tag) != null) throw ReusedBaseKeyException("kyber prekey base key reused")
        kv.put(KYBER_USED, tag, byteArrayOf(1))
    }

    // ---------- sender keys (not used: groups are fan-out over pairwise sessions) ----------

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        kv.put(SENDER, "${sKey(sender)}:$distributionId", record.serialize())
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        kv.get(SENDER, "${sKey(sender)}:$distributionId")?.let { SenderKeyRecord(it) }
}
