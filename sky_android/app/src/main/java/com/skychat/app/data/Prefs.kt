package com.skychat.app.data

import android.content.Context
import android.util.Base64
import com.skychat.app.crypto.Vault

/**
 * Settings. The login token and phone number are stored sealed by the Android Keystore [Vault]
 * (keys "token.s" / "phone.s"); values from older versions are sealed on first start.
 * If the Keystore key is lost the sealed values simply read as null (= logged out).
 */
class Prefs(ctx: Context, private val vault: Vault = Vault()) {
    private val sp = ctx.getSharedPreferences("sky", Context.MODE_PRIVATE)

    private class Slot { @Volatile var loaded = false; @Volatile var value: String? = null }
    private val tokenSlot = Slot()
    private val phoneSlot = Slot()
    private val pkeySlot = Slot()
    private val profSlot = Slot()

    init {
        // Step 12 and older kept these in clear text.
        for (name in listOf("token", "phone")) {
            val old = sp.getString(name, null) ?: continue
            if (sealedPut(name, old)) sp.edit().remove(name).apply()
        }
    }

    private fun sealedPut(name: String, v: String): Boolean = try {
        val b = Base64.encodeToString(vault.seal(v.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        sp.edit().putString("$name.s", b).commit()
    } catch (e: Exception) { false }

    private fun sealedGet(name: String): String? {
        val b = sp.getString("$name.s", null) ?: return null
        return try { String(vault.open(Base64.decode(b, Base64.NO_WRAP)), Charsets.UTF_8) } catch (e: Exception) { null }
    }

    private fun get(slot: Slot, name: String): String? {
        if (!slot.loaded) { slot.value = sealedGet(name); slot.loaded = true }
        return slot.value
    }

    private fun set(slot: Slot, name: String, v: String?) {
        if (v == null) sp.edit().remove("$name.s").commit()
        else check(sealedPut(name, v)) { "could not store $name" }
        slot.value = v; slot.loaded = true
    }

    // 10.0.2.2 = host machine as seen from the Android emulator
    var serverUrl: String
        get() = sp.getString("server", "ws://10.0.2.2:8080/ws")!!
        set(v) = sp.edit().putString("server", v).apply()
    var token: String?
        get() = get(tokenSlot, "token")
        set(v) = set(tokenSlot, "token", v)
    var phone: String?
        get() = get(phoneSlot, "phone")
        set(v) = set(phoneSlot, "phone", v)

    /** My profile key (base64, sealed). Created the first time it is needed. */
    var profileKey: String?
        get() = get(pkeySlot, "pkey")
        set(v) = set(pkeySlot, "pkey", v)
    /** My profile as JSON {about, mid, pk} (sealed): about text, server id and key of my photo. */
    var ownProfile: String?
        get() = get(profSlot, "oprof")
        set(v) = set(profSlot, "oprof", v)
    /** The profile changed while offline: publish it at the next login. */
    var profileDirty: Boolean
        get() = sp.getBoolean("profileDirty", false)
        set(v) = sp.edit().putBoolean("profileDirty", v).apply()
    /** My display name as last set on this phone (the server keeps the truth). */
    var name: String?
        get() = sp.getString("myname", null)
        set(v) = sp.edit().putString("myname", v).apply()

    /** Own push channel: same host as the chat socket, path /push. */
    val pushUrl: String
        get() = serverUrl.trim().let { if (it.endsWith("/ws")) it.dropLast(3) + "/push" else it.trimEnd('/') + "/push" }

    /** Share my online status / last seen (reciprocal: if off, I cannot see others' either). Server keeps the truth. */
    var sharePresence: Boolean
        get() = sp.getBoolean("sharePresence", true)
        set(v) = sp.edit().putBoolean("sharePresence", v).apply()
    /** Send "typing…" to the other person. */
    var shareTyping: Boolean
        get() = sp.getBoolean("shareTyping", true)
        set(v) = sp.edit().putBoolean("shareTyping", v).apply()

    /** The battery-optimisation prompt is shown only once. */
    var askedBattery: Boolean
        get() = sp.getBoolean("askedBattery", false)
        set(v) = sp.edit().putBoolean("askedBattery", v).apply()

    fun clearAuth() {
        sp.edit().remove("token").remove("phone").remove("token.s").remove("phone.s")
            .remove("pkey.s").remove("oprof.s").remove("profileDirty").remove("myname").commit()
        pkeySlot.value = null; pkeySlot.loaded = true
        profSlot.value = null; profSlot.loaded = true
        tokenSlot.value = null; tokenSlot.loaded = true
        phoneSlot.value = null; phoneSlot.loaded = true
    }
}
