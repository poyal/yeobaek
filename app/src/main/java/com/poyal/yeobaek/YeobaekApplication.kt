package com.poyal.yeobaek

import android.app.Application
import androidx.room.Room
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.TextStore
import java.io.File
import com.poyal.yeobaek.update.*
import kotlinx.coroutines.*

class YeobaekApplication : Application() {
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val updates by lazy { UpdateController(BuildConfig.VERSION_NAME, GitHubUpdates(),
        AndroidUpdatePersistence(this), AndroidUpdateDownloads(this), updateScope) }
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").build() }
    val preferences by lazy { Preferences(this) }
    val folders by lazy { FolderSource(contentResolver) }
    val texts by lazy { TextStore(File(cacheDir, "text")) }
}
