package com.skychat.app.net

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * MessagePack for the /ws connection (spec: github.com/msgpack/msgpack/blob/master/spec.md).
 * The app works with JSONObject everywhere; this converts at the edge, like the server does:
 *  - encode: the values of [BIN_KEYS] that are canonical base64 go out as MessagePack "bin" (raw bytes, no base64);
 *    everything else is unchanged.
 *  - decode: every "bin" becomes a base64 string, so the rest of the app sees the same JSON it always saw.
 */
object MsgPack {
    /** Fields that carry raw bytes: ciphertext, call signalling, profile blob, key material. Same list as the server. */
    val BIN_KEYS = setOf("ct", "c", "sct", "blob", "identity", "pub", "sig")
    private const val MAX_DEPTH = 64

    fun encode(o: JSONObject): ByteArray {
        val out = ByteArrayOutputStream(256)
        writeObject(out, o, 0)
        return out.toByteArray()
    }

    /** Null if the bytes are not a valid MessagePack map. */
    fun decode(b: ByteArray): JSONObject? = try {
        val buf = ByteBuffer.wrap(b)
        val v = read(buf, 0)
        if (buf.hasRemaining() || v !is JSONObject) null else v
    } catch (e: Exception) { null }

    // ---------------- encode ----------------

    private fun writeValue(out: ByteArrayOutputStream, v: Any?, key: String?, depth: Int) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("too deep")
        when (v) {
            null, JSONObject.NULL -> out.write(0xc0)
            is Boolean -> out.write(if (v) 0xc3 else 0xc2)
            is Int -> writeInt(out, v.toLong())
            is Long -> writeInt(out, v)
            is Short -> writeInt(out, v.toLong())
            is Double -> { out.write(0xcb); out.write(ByteBuffer.allocate(8).putDouble(v).array()) }
            is Float -> { out.write(0xcb); out.write(ByteBuffer.allocate(8).putDouble(v.toDouble()).array()) }
            is String -> {
                val raw = if (key != null && key in BIN_KEYS) canonicalBase64(v) else null
                if (raw != null) writeBin(out, raw) else writeStr(out, v)
            }
            is JSONObject -> writeObject(out, v, depth)
            is JSONArray -> {
                writeHeader(out, v.length(), 0x90, 0xdc, 0xdd)
                for (i in 0 until v.length()) writeValue(out, v.opt(i), key, depth + 1)
            }
            is ByteArray -> writeBin(out, v)
            else -> writeStr(out, v.toString())
        }
    }

    private fun writeObject(out: ByteArrayOutputStream, o: JSONObject, depth: Int) {
        writeHeader(out, o.length(), 0x80, 0xde, 0xdf)
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            writeStr(out, k)
            writeValue(out, o.opt(k), k, depth + 1)
        }
    }

    /** fixed header (n < 16), 16-bit or 32-bit length form. */
    private fun writeHeader(out: ByteArrayOutputStream, n: Int, fix: Int, c16: Int, c32: Int) {
        when {
            n < 16 -> out.write(fix or n)
            n < 65536 -> { out.write(c16); out.write(n ushr 8); out.write(n) }
            else -> { out.write(c32); out.write(ByteBuffer.allocate(4).putInt(n).array()) }
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..0x7f -> out.write(v.toInt())
            v in -32..-1 -> out.write(v.toInt() and 0xff)
            v in 0..0xff -> { out.write(0xcc); out.write(v.toInt()) }
            v in 0..0xffff -> { out.write(0xcd); out.write(ByteBuffer.allocate(2).putShort(v.toShort()).array()) }
            v in 0..0xffffffffL -> { out.write(0xce); out.write(ByteBuffer.allocate(4).putInt(v.toInt()).array()) }
            v >= 0 -> { out.write(0xcf); out.write(ByteBuffer.allocate(8).putLong(v).array()) }
            v >= -0x80 -> { out.write(0xd0); out.write(v.toInt() and 0xff) }
            v >= -0x8000 -> { out.write(0xd1); out.write(ByteBuffer.allocate(2).putShort(v.toShort()).array()) }
            v >= -0x80000000L -> { out.write(0xd2); out.write(ByteBuffer.allocate(4).putInt(v.toInt()).array()) }
            else -> { out.write(0xd3); out.write(ByteBuffer.allocate(8).putLong(v).array()) }
        }
    }

    private fun writeStr(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val n = b.size
        when {
            n < 32 -> out.write(0xa0 or n)
            n < 256 -> { out.write(0xd9); out.write(n) }
            n < 65536 -> { out.write(0xda); out.write(n ushr 8); out.write(n) }
            else -> { out.write(0xdb); out.write(ByteBuffer.allocate(4).putInt(n).array()) }
        }
        out.write(b)
    }

    private fun writeBin(out: ByteArrayOutputStream, b: ByteArray) {
        val n = b.size
        when {
            n < 256 -> { out.write(0xc4); out.write(n) }
            n < 65536 -> { out.write(0xc5); out.write(n ushr 8); out.write(n) }
            else -> { out.write(0xc6); out.write(ByteBuffer.allocate(4).putInt(n).array()) }
        }
        out.write(b)
    }

    /** The bytes if [s] is base64 that encodes back to exactly the same text; otherwise null (stays a string). */
    private fun canonicalBase64(s: String): ByteArray? = try {
        val raw = Base64.decode(s, Base64.NO_WRAP)
        if (Base64.encodeToString(raw, Base64.NO_WRAP) == s) raw else null
    } catch (e: IllegalArgumentException) { null }

    // ---------------- decode ----------------

    private fun read(b: ByteBuffer, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("too deep")
        val t = b.get().toInt() and 0xff
        return when {
            t <= 0x7f -> t.toLong()
            t >= 0xe0 -> (t - 256).toLong()
            t in 0x80..0x8f -> readMap(b, t and 15, depth)
            t in 0x90..0x9f -> readArr(b, t and 15, depth)
            t in 0xa0..0xbf -> readStr(b, t and 31)
            else -> when (t) {
                0xc0 -> JSONObject.NULL
                0xc2 -> false
                0xc3 -> true
                0xc4 -> readBin(b, b.get().toInt() and 0xff)
                0xc5 -> readBin(b, b.short.toInt() and 0xffff)
                0xc6 -> readBin(b, len32(b))
                0xca -> b.float.toDouble()
                0xcb -> b.double
                0xcc -> (b.get().toInt() and 0xff).toLong()
                0xcd -> (b.short.toInt() and 0xffff).toLong()
                0xce -> b.int.toLong() and 0xffffffffL
                0xcf -> b.long
                0xd0 -> b.get().toLong()
                0xd1 -> b.short.toLong()
                0xd2 -> b.int.toLong()
                0xd3 -> b.long
                0xd9 -> readStr(b, b.get().toInt() and 0xff)
                0xda -> readStr(b, b.short.toInt() and 0xffff)
                0xdb -> readStr(b, len32(b))
                0xdc -> readArr(b, b.short.toInt() and 0xffff, depth)
                0xdd -> readArr(b, len32(b), depth)
                0xde -> readMap(b, b.short.toInt() and 0xffff, depth)
                0xdf -> readMap(b, len32(b), depth)
                else -> throw IllegalArgumentException("unsupported type")
            }
        }
    }

    private fun len32(b: ByteBuffer): Int {
        val n = b.int
        if (n < 0 || n > b.remaining()) throw IllegalArgumentException("length")
        return n
    }

    private fun take(b: ByteBuffer, n: Int): ByteArray {
        if (n < 0 || n > b.remaining()) throw IllegalArgumentException("length")
        return ByteArray(n).also { b.get(it) }
    }

    private fun readStr(b: ByteBuffer, n: Int) = String(take(b, n), Charsets.UTF_8)

    private fun readBin(b: ByteBuffer, n: Int): String = Base64.encodeToString(take(b, n), Base64.NO_WRAP)

    private fun readArr(b: ByteBuffer, n: Int, depth: Int): JSONArray {
        val a = JSONArray()
        repeat(n) { a.put(read(b, depth + 1)) }
        return a
    }

    private fun readMap(b: ByteBuffer, n: Int, depth: Int): JSONObject {
        val o = JSONObject()
        repeat(n) {
            val k = read(b, depth + 1) as? String ?: throw IllegalArgumentException("key")
            o.put(k, read(b, depth + 1))
        }
        return o
    }
}
