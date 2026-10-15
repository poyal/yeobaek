package com.poyal.yeobaek

import android.app.Application
import androidx.room.Room
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.TextStore
import java.io.File
import com.poyal.yeobaek.update.*
import kotlinx.coroutines.*

class YeobaekApplication : Application() {
    val scrollDiagnostics by lazy { com.poyal.yeobaek.diagnostics.ScrollDiagnostics(this) }
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val updates by lazy { UpdateController(BuildConfig.VERSION_NAME, GitHubUpdates(),
        AndroidUpdatePersistence(this), AndroidUpdateDownloads(this), updateScope) }
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4).build() }
    val readerSettings by lazy { ReaderSettingsStore(this) }
    val preferences by lazy { Preferences(this) }
    val folders by lazy { FolderSource(contentResolver) }
    val texts by lazy { TextStore(File(cacheDir, "text")) }
}
