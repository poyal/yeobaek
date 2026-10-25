package com.poyal.yeobaek

import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.image.ImagePreferences
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class ReaderGestureSettingsTest {
    @Test fun oldSettingsUseSafeDefaultsAndEachFormatRoundTrips() {
        val old = Json.decodeFromString<ReaderSettings>("{}")
        assertFalse(ReaderSettingValues.pdf(PdfPreferences(), old.fields("PDF", null)).doubleTapZoom)
        assertFalse(ReaderSettingValues.image(ImagePreferences(), old.fields("IMAGE", null)).doubleTapZoom)
        assertTrue(ReaderSettingValues.epub(EpubReaderPreferences(), old.fields("EPUB", null)).swipeToTurn)
        assertTrue(ReaderSettingValues.epub(EpubReaderPreferences(), old.fields("EPUB", null)).pinchZoom)
        assertTrue(ReaderSettingValues.image(ImagePreferences(), old.fields("IMAGE", null)).swipeToTurn)
        val image = ImagePreferences(doubleTapZoom = true, pinchZoom = false, tapToTurn = false, swipeToTurn = false, menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(image, ReaderSettingValues.image(ImagePreferences(), ReaderSettingValues.image(image)))
        val pdf = PdfPreferences(doubleTapZoom = true, pinchZoom = false, menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(pdf, ReaderSettingValues.pdf(PdfPreferences(), ReaderSettingValues.pdf(pdf)))
        val txt = TxtPreferences(menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(txt, ReaderSettingValues.txt(TxtPreferences(), ReaderSettingValues.txt(txt)))
        val epub = EpubReaderPreferences(menuVisibility = MenuVisibility.ALWAYS, swipeToTurn = false, pinchZoom = false)
        assertEquals(epub, ReaderSettingValues.epub(EpubReaderPreferences(), ReaderSettingValues.epub(epub)))
    }
    @Test fun gestureOverridesSurviveBackupAndResetOnlyTheSelectedScope() {
        val settings = ReaderSettings(folders = mapOf("a" to "folder"), overrides = mapOf(
            "common:EPUB" to mapOf("swipeToTurn" to "false", "pinchZoom" to "true"),
            "folder:folder:EPUB" to mapOf("pinchZoom" to "false"),
            "book:a:EPUB" to mapOf("swipeToTurn" to "true"),
            "common:IMAGE" to mapOf("swipeToTurn" to "false")))
        val backup = ReadingBackup(library = LibrarySnapshot(), settings = settings, theme = AppTheme.LIGHT)
        val restored = Json.decodeFromString<ReadingBackup>(Json.encodeToString(backup)).settings
        val book = ReaderSettingValues.epub(EpubReaderPreferences(), restored.fields("EPUB", "a"))
        assertTrue(book.swipeToTurn); assertFalse(book.pinchZoom)
        val inherited = restored.copy(overrides = restored.overrides - "book:a:EPUB")
        assertFalse(ReaderSettingValues.epub(EpubReaderPreferences(), inherited.fields("EPUB", "a")).swipeToTurn)
        assertFalse(ReaderSettingValues.image(ImagePreferences(), inherited.fields("IMAGE", "a")).swipeToTurn)
        assertEquals(PdfPreferences(), ReaderSettingValues.pdf(PdfPreferences(), inherited.fields("PDF", "a")))
        val invalid = ReaderSettingValues.epub(EpubReaderPreferences(), mapOf("swipeToTurn" to "invalid", "pinchZoom" to "invalid"))
        assertTrue(invalid.swipeToTurn); assertTrue(invalid.pinchZoom)
    }
    @Test fun bookOnlyOverridesChangedFieldsAndOtherFormatsStayIndependent() {
        val settings = ReaderSettings(folders = mapOf("a" to "folder"), overrides = mapOf(
            "common:IMAGE" to mapOf("doubleTapZoom" to "true", "pinchZoom" to "true"),
            "folder:folder:IMAGE" to mapOf("pinchZoom" to "false"),
            "book:a:IMAGE" to mapOf("menuVisibility" to "ALWAYS")))
        val effective = ReaderSettingValues.image(ImagePreferences(), settings.fields("IMAGE", "a"))
        assertTrue(effective.doubleTapZoom); assertFalse(effective.pinchZoom)
        assertEquals(MenuVisibility.ALWAYS, effective.menuVisibility)
        assertEquals(PdfPreferences(), ReaderSettingValues.pdf(PdfPreferences(), settings.fields("PDF", "a")))
    }
}
