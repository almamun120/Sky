package com.skychat.app.crypto

/** Tiny key-value abstraction so the crypto code has no Android dependency. */
interface KvStore {
    fun get(table: String, key: String): ByteArray?
    fun put(table: String, key: String, value: ByteArray)
    fun remove(table: String, key: String)
    fun keys(table: String): List<String>
}

/** For tests. */
class MemoryKv : KvStore {
    private val m = HashMap<String, ByteArray>()
    override fun get(table: String, key: String) = m["$table\u0000$key"]
    override fun put(table: String, key: String, value: ByteArray) { m["$table\u0000$key"] = value }
    override fun remove(table: String, key: String) { m.remove("$table\u0000$key") }
    override fun keys(table: String) =
        m.keys.filter { it.startsWith("$table\u0000") }.map { it.substringAfter('\u0000') }
}
