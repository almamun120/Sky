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
import com.skychat.app.data.MIGRATION_10_11
import com.skychat.app.data.MIGRATION_11_12
import com.skychat.app.data.MIGRATION_12_13
import com.skychat.app.data.MIGRATION_13_14
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
    val themeMode = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** true = the app lock screen covers the app. */
    val locked = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** true = FLAG_SECURE on the window (screenshots blocked, recent-apps picture hidden). */
    val secure = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var lastStop = 0L

    fun deviceSecure(): Boolean = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure

    fun refreshSecure() { secure.value = prefs.blockScreenshots || prefs.appLock }

    /** Called when the app comes to the front: lock again if it was away long enough. */
    fun maybeLock() {
        if (!prefs.appLock) return
        if (!deviceSecure()) { prefs.appLock = false; refreshSecure(); return }     // the phone's lock screen was removed
        if (System.currentTimeMillis() - lastStop >= prefs.lockTimeoutMs) locked.value = true
    }

    override fun onCreate() {
        super.onCreate()
        // Must run before Prefs is read: after a lost Keystore key it clears the saved login.
        val opened = DbCipher.prepare(this)
        prefs = Prefs(this)
        media = MediaVault(this)
        themeMode.value = prefs.themeMode
        refreshSecure()
        locked.value = prefs.appLock
        java.io.File(cacheDir, "camera").deleteRecursively()      // photos taken for sending are not kept
        media.cleanTemp()                       // plaintext never survives an app restart
        Thread { media.migrateLegacy() }.start() // step-12 files are encrypted in the background
        db = Room.databaseBuilder(this, AppDb::class.java, DbCipher.DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(opened.passphrase.toByteArray(Charsets.US_ASCII)))
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14).fallbackToDestructiveMigration().build()
        // Open now so a wrong key shows up here rather than deep inside a background thread.
        db.openHelper.writableDatabase
        client = ChatClient(this, prefs, db.dao(), media)
        notifier = Notifier(this, db.dao())
        client.notifier = notifier
        client.startHousekeeping()
        calls = CallManager(this, client, db.dao(), notifier)
        client.calls = calls
        client.groupCalls = com.skychat.app.call.GroupCallManager(this, client, db.dao(), calls)
        // The connection is opened by MainActivity.onStart (foreground) or by a push (background).
    }
}
