package com.skychat.app

import android.app.Application
import androidx.room.Room
import com.skychat.app.call.CallManager
import com.skychat.app.data.AppDb
import com.skychat.app.data.MIGRATION_4_5
import com.skychat.app.data.MIGRATION_5_6
import com.skychat.app.data.MIGRATION_6_7
import com.skychat.app.data.MIGRATION_7_8
import com.skychat.app.data.MIGRATION_8_9
import com.skychat.app.data.MIGRATION_9_10
import com.skychat.app.crypto.DbCipher
import com.skychat.app.crypto.MediaVault
import com.skychat.app.data.Prefs
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.skychat.app.net.ChatClient
import com.skychat.app.push.Notifier

class SkyApp : Application() {
    lateinit var prefs: Prefs
    lateinit var media: MediaVault
    lateinit var db: AppDb
    lateinit var client: ChatClient
    lateinit var notifier: Notifier
    lateinit var calls: CallManager

    override fun onCreate() {
        super.onCreate()
        // Must run before Prefs is read: after a lost Keystore key it clears the saved login.
        val opened = DbCipher.prepare(this)
        prefs = Prefs(this)
        media = MediaVault(this)
        media.cleanTemp()                       // plaintext never survives an app restart
        Thread { media.migrateLegacy() }.start() // step-12 files are encrypted in the background
        db = Room.databaseBuilder(this, AppDb::class.java, DbCipher.DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(opened.passphrase.toByteArray(Charsets.US_ASCII)))
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).fallbackToDestructiveMigration().build()
        // Open now so a wrong key shows up here rather than deep inside a background thread.
        db.openHelper.writableDatabase
        client = ChatClient(this, prefs, db.dao(), media)
        notifier = Notifier(this, db.dao())
        client.notifier = notifier
        calls = CallManager(this, client, db.dao(), notifier)
        client.calls = calls
        // The connection is opened by MainActivity.onStart (foreground) or by a push (background).
    }
}
