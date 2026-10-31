package com.skychat.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object St {
    const val PENDING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val READ = 3
    const val FAILED = 4
}

/** 1:1 chats are keyed by phone ("+880..."), group chats by group id. */
fun isGroup(id: String) = !id.startsWith("+")

@Entity(tableName = "messages")
data class Msg(
    @PrimaryKey val id: String,
    val chat: String,        // phone number or group id
    val fromMe: Boolean,
    val body: String,
    val ts: Long,
    val status: Int,         // only meaningful when fromMe
    val seen: Boolean,       // only meaningful for incoming
    val sender: String = "", // author's phone for incoming messages
    val kind: String = "text",   // text | image | audio | file
    val media: String = "",      // server file id (empty until uploaded)
    val fname: String = "",
    val mime: String = "",
    val size: Long = 0,
    val dur: Int = 0,            // audio length in ms
    val localPath: String = "",  // file on this phone (empty until downloaded)
    val mkey: String = "",       // AES key of the encrypted attachment (base64)
    val replyId: String = "",    // id of the message this one answers (kind "ctl": the message it acts on)
    val quote: String = "",      // short copy of the answered text, so the quote survives deletion
    val quoteBy: String = "",    // phone of the quoted message's author
    val edited: Boolean = false,
    val fwd: Boolean = false,    // forwarded
    val starred: Boolean = false,    // local only
    val mentions: String = "",   // JSON [["+88017..","Ana"],...]: who is @-mentioned and the label typed
    val preview: String = "",    // link preview built by the sender's phone: JSON {"u": url, "t": title, "d": description}
    val once: Int = 0,           // view-once photo/video: 0 no, 1 not opened yet, 2 opened (the picture is gone)
    val expiresAt: Long = 0      // disappearing message: when it is deleted (ms), 0 = never
)
// kind = text | image | video | audio | file | sticker | location | contact | call | info | deleted (tombstone) | ctl (hidden control message:
// mime = "del", "edit" or "react", replyId = target message id, body = new text for "edit" / the emoji for "react")

/** Kinds whose content is an encrypted attachment on the server (the others travel inside the message itself). */
fun hasFile(kind: String) = kind == "image" || kind == "video" || kind == "audio" || kind == "file" || kind == "sticker"

fun previewText(kind: String, body: String, fname: String) = when (kind) {
    "image" -> "\uD83D\uDCF7 Photo"
    "video" -> "\uD83C\uDFA5 Video"
    "sticker" -> "\uD83D\uDE42 Sticker"
    "location" -> "\uD83D\uDCCD Location"
    "contact" -> "\uD83D\uDC64 " + (try { org.json.JSONObject(body).optString("n") } catch (e: Exception) { "" }).ifEmpty { "Contact" }
    "audio" -> "\uD83C\uDFA4 Voice message"
    "file" -> "\uD83D\uDCCE " + fname.ifEmpty { "File" }
    "deleted" -> "\uD83D\uDEAB Message deleted"
    else -> body
}

@Entity(tableName = "contacts")
data class Contact(@PrimaryKey val phone: String, val name: String, val local: Boolean = false)   // local = from this phone's address book

@Entity(tableName = "chat_groups")
data class ChatGroup(
    @PrimaryKey val gid: String,
    val name: String,
    val members: String,     // comma-separated phones
    val owner: String,
    val created: Long,
    val descr: String = "",       // group description (set by the owner, sent as an encrypted control message)
    val photoMid: String = "",    // server id of the encrypted group photo
    val photoKey: String = "",    // its AES key (base64), kept so new members can be sent the photo too
    val photoPath: String = "",   // decrypted-at-rest copy on this phone
    val admins: String = "",      // comma-separated admin phones (the server always includes the owner)
    val announce: Boolean = false // true = only admins can send messages
) {
    fun isAdmin(phone: String?) = phone != null && (owner == phone || admins.split(",").contains(phone))
}

/** A status update (24 hours). Others' updates arrive as encrypted control messages; [mine] = I posted it. */
@Entity(tableName = "status")
data class StatusItem(
    @PrimaryKey val id: String,
    val owner: String,
    val kind: String,          // "text" or "image"
    val text: String,          // the text, or the caption of a photo
    val bg: Int,               // background colour of a text update (ARGB)
    val mid: String = "",
    val key: String = "",
    val path: String = "",     // vault-encrypted copy of the photo on this phone
    val ts: Long,
    val seen: Boolean = false,
    val mine: Boolean = false
)

/** Who looked at one of my updates. */
@Entity(tableName = "status_view", primaryKeys = ["statusId", "phone"])
data class StatusView(val statusId: String, val phone: String, val ts: Long)

/** What we know about another person's profile. [key] is the profile key they gave us (empty = none yet). */
@Entity(tableName = "peer_profile")
data class PeerProfile(
    @PrimaryKey val phone: String,
    val key: String = "",
    val about: String = "",
    val photoMid: String = "",
    val photoPath: String = "",
    val fetched: Long = 0,        // server timestamp of the profile blob we last read
    val keySent: Boolean = false  // we already gave them OUR profile key
)

/** What each group member has done with a group message we sent (status = St.DELIVERED or St.READ). */
@Entity(tableName = "receipts", primaryKeys = ["id", "phone"])
data class Receipt(val id: String, val phone: String, val status: Int)

/** An emoji reaction of [phone] to message [msgId]. */
@Entity(tableName = "reactions", primaryKeys = ["msgId", "phone"])
data class Reaction(val msgId: String, val phone: String, val emoji: String)

/**
 * Per-chat settings that live on this phone only. No row = defaults.
 * [pinned] = time of pinning (0 = not pinned), [muteUntil] = 0 not muted, [FOREVER] = always.
 */
@Entity(tableName = "chat_state")
data class ChatState(
    @PrimaryKey val chat: String,
    val pinned: Long = 0,
    val archived: Boolean = false,
    val muteUntil: Long = 0,
    val markedUnread: Boolean = false,
    val disappear: Long = 0      // new messages in this chat disappear after this many seconds (0 = off)
) {
    fun muted(now: Long = System.currentTimeMillis()) = muteUntil > now
    companion object { const val FOREVER = Long.MAX_VALUE }
}

/** People this account has blocked. The server keeps the truth; this is a copy for the UI and for dropping late messages. */
@Entity(tableName = "blocked")
data class Blocked(@PrimaryKey val phone: String)

/** Sealed (encrypted) key-value rows holding the Signal protocol state. */
@Entity(tableName = "kv", primaryKeys = ["tbl", "k"])
class Kv(val tbl: String, val k: String, val v: ByteArray)

data class Unread(val chat: String, val n: Int)

@Dao
interface MsgDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: Msg): Long

    @Query("SELECT * FROM messages WHERE chat = :chat AND kind != 'ctl' ORDER BY ts")
    fun messages(chat: String): Flow<List<Msg>>

    /** Message search; [pat] is a LIKE pattern built by [likePattern]. Newest first. */
    @Query("SELECT * FROM messages WHERE kind NOT IN ('call', 'info', 'deleted', 'ctl') AND " +
        "(body LIKE :pat ESCAPE '\\' OR fname LIKE :pat ESCAPE '\\') ORDER BY ts DESC LIMIT 100")
    suspend fun search(pat: String): List<Msg>

    @Query("SELECT * FROM messages m WHERE kind != 'ctl' AND ts = (SELECT MAX(ts) FROM messages WHERE chat = m.chat AND kind != 'ctl') GROUP BY chat ORDER BY ts DESC")
    fun lastMessages(): Flow<List<Msg>>

    @Query("SELECT chat, COUNT(*) AS n FROM messages WHERE fromMe = 0 AND seen = 0 GROUP BY chat")
    fun unread(): Flow<List<Unread>>

    @Query("UPDATE messages SET status = :s WHERE id = :id AND fromMe = 1 AND status < :s")
    suspend fun raiseStatus(id: String, s: Int)

    @Query("UPDATE messages SET status = 4 WHERE id = :id AND status = 0")
    suspend fun fail(id: String)

    @Query("SELECT * FROM messages WHERE fromMe = 1 AND status = 0 ORDER BY ts")
    suspend fun unsent(): List<Msg>

    @Query("SELECT * FROM messages WHERE chat = :chat AND fromMe = 0 AND seen = 0")
    suspend fun unseen(chat: String): List<Msg>

    @Query("UPDATE messages SET seen = 1 WHERE chat = :chat AND fromMe = 0")
    suspend fun markSeen(chat: String)

    // ---- reply / forward / delete / edit ----

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMsg(id: String): Msg?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteLocal(id: String)

    @Query("DELETE FROM messages WHERE id = :id AND kind = 'ctl'")
    suspend fun deleteCtl(id: String)

    /** Replaces a message by a "deleted" tombstone (own message: [mine] = 1, otherwise only if [from] wrote it). */
    @Query("UPDATE messages SET kind = 'deleted', body = '', fname = '', mime = '', media = '', mkey = '', " +
        "localPath = '', quote = '', replyId = '', edited = 0, starred = 0, mentions = '' " +
        "WHERE id = :id AND chat = :chat AND ((fromMe = 1 AND :mine = 1) OR (fromMe = 0 AND :mine = 0 AND sender = :from))")
    suspend fun tombstone(id: String, chat: String, mine: Int, from: String)

    @Query("SELECT localPath FROM messages WHERE id = :id AND chat = :chat AND " +
        "((fromMe = 1 AND :mine = 1) OR (fromMe = 0 AND :mine = 0 AND sender = :from))")
    suspend fun pathOf(id: String, chat: String, mine: Int, from: String): String?

    @Query("UPDATE messages SET body = :body, edited = 1 WHERE id = :id AND chat = :chat AND kind = 'text' AND " +
        "((fromMe = 1 AND :mine = 1) OR (fromMe = 0 AND :mine = 0 AND sender = :from))")
    suspend fun editBody(id: String, chat: String, mine: Int, from: String, body: String)

    // ---- reactions (one per person per message) ----

    @Query("SELECT r.* FROM reactions r JOIN messages m ON m.id = r.msgId WHERE m.chat = :chat")
    fun reactions(chat: String): Flow<List<Reaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReaction(r: Reaction)

    @Query("DELETE FROM reactions WHERE msgId = :id AND phone = :phone")
    suspend fun removeReaction(id: String, phone: String)

    @Query("SELECT emoji FROM reactions WHERE msgId = :id AND phone = :phone")
    suspend fun reactionOf(id: String, phone: String): String?

    @Query("DELETE FROM reactions WHERE msgId = :id")
    suspend fun deleteReactions(id: String)

    @Query("DELETE FROM reactions")
    suspend fun clearReactions()

    @Query("SELECT chat FROM messages WHERE id = :id")
    suspend fun chatOf(id: String): String?

    // ---- starred (this phone only) ----

    @Query("SELECT * FROM messages WHERE starred = 1 AND kind != 'deleted' ORDER BY ts DESC")
    fun starred(): Flow<List<Msg>>

    @Query("UPDATE messages SET starred = :on WHERE id = :id")
    suspend fun setStar(id: String, on: Boolean)

    // ---- pin / archive / mute / marked unread (this phone only) ----

    @Query("SELECT * FROM chat_state")
    fun chatStates(): Flow<List<ChatState>>

    @Query("SELECT * FROM chat_state WHERE chat = :chat")
    fun chatStateFlow(chat: String): Flow<ChatState?>

    @Query("SELECT * FROM chat_state WHERE chat = :chat")
    suspend fun chatState(chat: String): ChatState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(s: ChatState)

    @Query("SELECT COUNT(*) FROM chat_state WHERE pinned > 0")
    suspend fun pinnedCount(): Int

    @Query("DELETE FROM chat_state")
    suspend fun clearStates()

    /** Call history: the lines CallManager writes into each chat when a call ends. */
    @Query("SELECT * FROM messages WHERE kind = 'call' ORDER BY ts DESC LIMIT 200")
    fun callLog(): Flow<List<Msg>>

    // ---- status (updates) ----

    @Query("SELECT * FROM status WHERE ts > :since ORDER BY ts")
    fun statuses(since: Long): Flow<List<StatusItem>>

    @Query("SELECT * FROM status WHERE id = :id")
    suspend fun statusById(id: String): StatusItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putStatus(s: StatusItem): Long

    @Query("UPDATE status SET seen = 1 WHERE id = :id")
    suspend fun markStatusSeen(id: String)

    @Query("DELETE FROM status WHERE id = :id")
    suspend fun deleteStatus(id: String)

    @Query("SELECT * FROM status WHERE ts <= :cutoff")
    suspend fun oldStatuses(cutoff: Long): List<StatusItem>

    @Query("DELETE FROM status WHERE ts <= :cutoff")
    suspend fun deleteOldStatuses(cutoff: Long)

    @Query("DELETE FROM status_view WHERE statusId NOT IN (SELECT id FROM status)")
    suspend fun pruneStatusViews()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putStatusView(v: StatusView)

    @Query("SELECT * FROM status_view WHERE statusId = :id ORDER BY ts")
    fun viewsOf(id: String): Flow<List<StatusView>>

    @Query("SELECT DISTINCT chat FROM messages")
    suspend fun allChatIds(): List<String>

    @Query("DELETE FROM status")
    suspend fun clearStatus()

    @Query("DELETE FROM status_view")
    suspend fun clearStatusViews()

    // ---- profiles ----

    @Query("SELECT * FROM peer_profile")
    fun peerProfiles(): Flow<List<PeerProfile>>

    @Query("SELECT * FROM peer_profile WHERE phone = :phone")
    fun peerProfileFlow(phone: String): Flow<PeerProfile?>

    @Query("SELECT * FROM peer_profile WHERE phone = :phone")
    suspend fun peerProfile(phone: String): PeerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPeerProfile(p: PeerProfile)

    @Query("DELETE FROM peer_profile")
    suspend fun clearPeerProfiles()

    @Query("UPDATE chat_groups SET descr = :descr, photoMid = :mid, photoKey = :key, photoPath = :path WHERE gid = :gid")
    suspend fun setGroupInfo(gid: String, descr: String, mid: String, key: String, path: String)

    // ---- blocked people ----

    @Query("SELECT phone FROM blocked")
    fun blockedPhones(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM blocked WHERE phone = :phone")
    suspend fun isBlocked(phone: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBlocked(b: List<Blocked>)

    @Query("DELETE FROM blocked WHERE phone NOT IN (:keep)")
    suspend fun deleteBlockedExcept(keep: List<String>)

    @Query("DELETE FROM blocked")
    suspend fun clearBlocked()

    /** The last few things [chat] sent us; a report may include them (the user is asked first). */
    @Query("SELECT * FROM messages WHERE chat = :chat AND fromMe = 0 AND kind IN ('text', 'image', 'audio', 'file') ORDER BY ts DESC LIMIT :n")
    suspend fun lastIncoming(chat: String, n: Int): List<Msg>

    /** Photos and videos of one chat, oldest first (for the viewer and the gallery). */
    @Query("SELECT * FROM messages WHERE chat = :chat AND kind IN ('image', 'video') AND once = 0 ORDER BY ts")
    fun mediaOf(chat: String): Flow<List<Msg>>

    @Query("SELECT * FROM messages WHERE chat = :chat AND kind = 'file' ORDER BY ts DESC")
    fun docsOf(chat: String): Flow<List<Msg>>

    @Query("SELECT * FROM contacts WHERE local = 1 ORDER BY name COLLATE NOCASE")
    fun localContacts(): Flow<List<Contact>>

    @Query("SELECT COUNT(*) FROM contacts WHERE phone = :phone AND local = 1")
    suspend fun isLocal(phone: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(c: Contact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(g: ChatGroup)

    @Query("SELECT * FROM contacts")
    fun contacts(): Flow<List<Contact>>

    @Query("SELECT * FROM chat_groups")
    fun groups(): Flow<List<ChatGroup>>

    @Query("SELECT name FROM contacts WHERE phone = :phone")
    suspend fun contactName(phone: String): String?

    @Query("SELECT name FROM chat_groups WHERE gid = :gid")
    suspend fun groupName(gid: String): String?

    @Query("SELECT * FROM messages WHERE expiresAt > 0 AND expiresAt <= :now AND starred = 0")
    suspend fun expired(now: Long): List<Msg>

    /** A view-once picture was opened: it is gone from this phone. */
    @Query("UPDATE messages SET once = 2, localPath = '' WHERE id = :id")
    suspend fun onceDone(id: String)

    @Query("UPDATE messages SET once = 2, localPath = '' WHERE id = :id AND chat = :chat AND fromMe = 1")
    suspend fun onceOpenedBy(id: String, chat: String)

    @Query("UPDATE messages SET preview = :preview WHERE id = :id")
    suspend fun setPreview(id: String, preview: String)

    @Query("SELECT * FROM messages WHERE chat = :chat AND kind = 'text' AND (body LIKE '%http://%' OR body LIKE '%https://%') ORDER BY ts DESC")
    fun linksOf(chat: String): Flow<List<Msg>>

    @Query("UPDATE messages SET media = :media, mkey = :mkey WHERE id = :id")
    suspend fun setMedia(id: String, media: String, mkey: String)

    @Query("SELECT COUNT(*) FROM messages WHERE id = :id")
    suspend fun countById(id: String): Int

    @Query("SELECT * FROM chat_groups WHERE gid = :gid")
    suspend fun group(gid: String): ChatGroup?

    // Signal state: plain (non-suspend) calls, used only from background threads.
    @Query("SELECT v FROM kv WHERE tbl = :t AND k = :k")
    fun kvGet(t: String, k: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun kvPut(e: Kv)

    @Query("DELETE FROM kv WHERE tbl = :t AND k = :k")
    fun kvRemove(t: String, k: String)

    @Query("SELECT k FROM kv WHERE tbl = :t")
    fun kvKeys(t: String): List<String>

    @Query("DELETE FROM kv")
    fun kvClear()

    @Query("UPDATE messages SET localPath = :path WHERE id = :id")
    suspend fun setLocal(id: String, path: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM contacts")
    suspend fun deleteContacts()

    @Query("DELETE FROM chat_groups")
    suspend fun deleteGroups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReceipt(r: Receipt)

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun receiptsOf(id: String): Flow<List<Receipt>>

    @Query("SELECT status FROM receipts WHERE id = :id AND phone = :phone")
    suspend fun receiptStatus(id: String, phone: String): Int?

    @Query("DELETE FROM receipts")
    suspend fun deleteReceipts()
}

@Database(entities = [Msg::class, Contact::class, ChatGroup::class, Kv::class, Receipt::class, Reaction::class,
    ChatState::class, Blocked::class, PeerProfile::class, StatusItem::class, StatusView::class], version = 14, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MsgDao
}

/** 4 -> 5 adds the group receipts table. Everything else (messages, Signal keys) is kept. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `receipts` (`id` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
            "`status` INTEGER NOT NULL, PRIMARY KEY(`id`, `phone`))")
    }
}

/** "50%" must match the text "50%", not everything: escape LIKE wildcards (see MsgDao.search). */
fun likePattern(q: String): String =
    "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** 5 -> 6: replies, forwarding, edit marks. Existing rows keep everything. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN quote TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN quoteBy TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN fwd INTEGER NOT NULL DEFAULT 0")
    }
}

/** 6 -> 7: reactions, starred messages, mentions. Existing rows keep everything. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN mentions TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reactions` (`msgId` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
            "`emoji` TEXT NOT NULL, PRIMARY KEY(`msgId`, `phone`))")
    }
}

/** 7 -> 8: pin/archive/mute settings and the block list. Existing rows keep everything. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `chat_state` (`chat` TEXT NOT NULL, `pinned` INTEGER NOT NULL, " +
            "`archived` INTEGER NOT NULL, `muteUntil` INTEGER NOT NULL, `markedUnread` INTEGER NOT NULL, " +
            "PRIMARY KEY(`chat`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `blocked` (`phone` TEXT NOT NULL, PRIMARY KEY(`phone`))")
    }
}

/** 8 -> 9: profiles of other people, group photo and description. Existing rows keep everything. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `peer_profile` (`phone` TEXT NOT NULL, `key` TEXT NOT NULL, " +
            "`about` TEXT NOT NULL, `photoMid` TEXT NOT NULL, `photoPath` TEXT NOT NULL, `fetched` INTEGER NOT NULL, " +
            "`keySent` INTEGER NOT NULL, PRIMARY KEY(`phone`))")
        for (c in listOf("descr", "photoMid", "photoKey", "photoPath"))
            db.execSQL("ALTER TABLE chat_groups ADD COLUMN $c TEXT NOT NULL DEFAULT ''")
    }
}

/** 9 -> 10: group admins and "only admins can send". */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_groups ADD COLUMN admins TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_groups ADD COLUMN announce INTEGER NOT NULL DEFAULT 0")
    }
}

/** 10 -> 11: status updates. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `status` (`id` TEXT NOT NULL, `owner` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`text` TEXT NOT NULL, `bg` INTEGER NOT NULL, `mid` TEXT NOT NULL, `key` TEXT NOT NULL, `path` TEXT NOT NULL, " +
            "`ts` INTEGER NOT NULL, `seen` INTEGER NOT NULL, `mine` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `status_view` (`statusId` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
            "`ts` INTEGER NOT NULL, PRIMARY KEY(`statusId`, `phone`))")
    }
}

/** 11 -> 12: contacts that come from the phone's address book. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN local INTEGER NOT NULL DEFAULT 0")
    }
}

/** 12 -> 13: link previews. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN preview TEXT NOT NULL DEFAULT ''")
    }
}

/** 13 -> 14: view-once pictures and disappearing messages. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN once INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_state ADD COLUMN disappear INTEGER NOT NULL DEFAULT 0")
    }
}
