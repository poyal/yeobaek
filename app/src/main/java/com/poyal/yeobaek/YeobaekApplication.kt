package com.poyal.yeobaek

import android.app.Application
import androidx.room.Room
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.txt.TextStore
import java.io.File

class YeobaekApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").build() }
    val preferences by lazy { Preferences(this) }
    val folders by lazy { FolderSource(contentResolver) }
    val texts by lazy { TextStore(File(cacheDir, "text")) }
}
