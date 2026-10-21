package com.poyal.yeobaek

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsMigrationTest {
    private val key = stringPreferencesKey("settings")

    @Test fun removalPreservesEveryOtherSettingAndDoesNotRunAgain() = runBlocking {
        val migration = RemoveFolderFavorites()
        val original = Json.parseToJsonElement("""{
            "favorites":[{"uri":"content://test/folder","directory":true}],
            "sort":"SIZE","screen":{"brightness":0.4,"rotation":"LANDSCAPE","awake":true},
            "folders":{"book":"parent"},"overrides":{"book:book:TXT":{"size":"24"}},
            "futureField":{"keep":true}
        }""").jsonObject
        val unrelated = stringPreferencesKey("unrelated")
        val before = preferencesOf(key to original.toString(), unrelated to "keep")
        assertTrue(migration.shouldMigrate(before))
        val after = migration.migrate(before)
        assertEquals(JsonObject(original - "favorites"), Json.parseToJsonElement(after[key]!!))
        assertEquals("keep", after[unrelated])
        assertFalse(migration.shouldMigrate(after))
        assertEquals(after, migration.migrate(after))
        assertEquals(original.toString(), before[key])
    }

    @Test fun freshAndAlreadyMigratedSettingsNeedNoMigration() = runBlocking {
        val migration = RemoveFolderFavorites()
        assertFalse(migration.shouldMigrate(emptyPreferences()))
        assertEquals(emptyPreferences(), migration.migrate(emptyPreferences()))
        assertFalse(migration.shouldMigrate(preferencesOf(key to """{"sort":"MODIFIED"}""")))
        assertTrue(migration.shouldMigrate(preferencesOf(key to """{"favorites":[]}""")))
    }

    @Test fun oldBackupsKeepReadingRecordsWithoutRestoringOrExportingFavorites() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val book = Book("book", "source", "content://test/book", "sample.txt", "folder", version = "v1", length = 100)
        val library = LibrarySnapshot(books = listOf(book), positions = listOf(ReadingPosition("book", "v1", 35)),
            bookmarks = listOf(Bookmark("mark", "book", "v1", 20, "sample", "", "")))
        for (version in 1..2) {
            val backup = ReadingBackup(version = version, library = library,
                settings = ReaderSettings(sort = FolderSort.SIZE, folders = mapOf("book" to "parent")), theme = AppTheme.DARK)
            val encoded = json.encodeToJsonElement(backup).jsonObject
            val legacySettings = JsonObject(encoded.getValue("settings").jsonObject +
                ("favorites" to Json.parseToJsonElement("""[{"uri":"content://test/folder","directory":true}]""")))
            val restored = json.decodeFromJsonElement<ReadingBackup>(JsonObject(encoded + ("settings" to legacySettings)))
            validateBackup(restored)
            assertEquals(backup, restored)
            assertFalse(json.encodeToJsonElement(restored).jsonObject.getValue("settings").jsonObject.containsKey("favorites"))
        }
    }
}
