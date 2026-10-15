package com.poyal.yeobaek

import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.image.*
import org.junit.Assert.*
import org.junit.Test

class ReaderUpgradeTest {
    @Test fun toolbarChangesTheEffectiveOverrideRatherThanAnInvisibleCommonValue() {
        val settings = ReaderSettings(folders = mapOf("book" to "parent"), overrides = mapOf(
            "book:book:PDF" to mapOf("inverted" to "true"), "folder:parent:IMAGE" to mapOf("inverted" to "false")))
        assertEquals("book:book:PDF", settings.effectiveFieldScope("PDF", "book", "inverted", SettingsScope.COMMON))
        assertEquals("folder:parent:IMAGE", settings.effectiveFieldScope("IMAGE", "book", "inverted", SettingsScope.COMMON))
        assertEquals("common:TXT", settings.effectiveFieldScope("TXT", "book", "paper", SettingsScope.COMMON))
    }
    @Test fun partialOverridesInheritCommonChangesAndOnlyExactParent() {
        val extra = ReaderSettings(folders = mapOf("a" to "folder", "b" to "child"), overrides = mapOf(
            "common:TXT" to mapOf("size" to "19", "paper" to "SEPIA"),
            "folder:folder:TXT" to mapOf("line" to "2.0"), "book:a:TXT" to mapOf("size" to "24")))
        val a = ReaderSettingValues.txt(TxtPreferences(), extra.fields("TXT", "a"))
        assertEquals(24f, a.size, 0f); assertEquals(2f, a.line, 0f); assertEquals(TxtPaper.SEPIA, a.paper)
        assertEquals(1.85f, ReaderSettingValues.txt(TxtPreferences(), extra.fields("TXT", "b")).line, 0f)
        val changed = extra.copy(overrides = extra.overrides + ("common:TXT" to mapOf("font" to "SYSTEM_SANS", "paper" to "DARK")))
        assertEquals(TxtPaper.DARK, ReaderSettingValues.txt(TxtPreferences(), changed.fields("TXT", "a")).paper)
        val reset = changed.copy(overrides = changed.overrides - "book:a:TXT")
        assertEquals(18f, ReaderSettingValues.txt(TxtPreferences(), reset.fields("TXT", "a")).size, 0f)
        assertFalse(extra.editingFields("TXT", "a", SettingsScope.COMMON).containsKey("line"))
    }
    @Test fun badNumericPreferencesCannotPoisonLayout() {
        val p = ReaderSettingValues.txt(TxtPreferences(), mapOf("size" to "NaN", "line" to "Infinity", "margin" to "-200"))
        assertEquals(18f, p.size, 0f); assertEquals(1.85f, p.line, 0f); assertEquals(16f, p.margin, 0f)
    }
    @Test fun naturalSortingAndUnknownMetadataHaveStableOrder() {
        fun entry(name: String, size: Long? = null, folder: Boolean = false) = DocumentEntry(name, "content://test/$name", name, folder, size, null, "")
        val values = listOf(entry("10.txt", 100), entry("2.txt", 300), entry("1.txt"), entry("folder", folder = true))
        assertEquals(listOf("folder", "1.txt", "2.txt", "10.txt"), sortEntries(values, FolderSort.NAME).map { it.name })
        assertEquals(listOf("folder", "2.txt", "10.txt", "1.txt"), sortEntries(values, FolderSort.SIZE).map { it.name })
    }
    @Test fun cropDetectorKeepsFullBleedAndBlankPagesAndFindsBlackWhiteBorders() {
        for (color in listOf(0xffffffff.toInt(), 0xff000000.toInt())) {
            val pixels = IntArray(10000) { color }
            assertEquals(CropRect(), detectImageCrop(pixels, 100, 100))
            for (y in 10 until 90) for (x in 10 until 90) pixels[y * 100 + x] = 0xff804020.toInt()
            val crop = detectImageCrop(pixels, 100, 100)
            assertEquals(.08f, crop.left, .001f); assertEquals(.92f, crop.right, .001f); assertTrue(crop.valid())
        }
        assertEquals(CropRect(), detectImageCrop(IntArray(10000) { 0xff804020.toInt() }, 100, 100))
        assertEquals(CropRect(.5f, 0f, 1f, 1f), CropRect().half(true))
    }
    @Test fun backupRejectsUnknownVersionAndOrphanRecords() {
        val empty = ReadingBackup(library = LibrarySnapshot(), settings = ReaderSettings(), theme = AppTheme.SYSTEM)
        validateBackup(empty)
        assertThrows(IllegalArgumentException::class.java) { validateBackup(empty.copy(version = 999)) }
        assertThrows(IllegalArgumentException::class.java) { validateBackup(empty.copy(library = LibrarySnapshot(positions = listOf(ReadingPosition("missing", "v1", 3))))) }
    }
}
