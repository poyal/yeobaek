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
        val image = ImagePreferences(doubleTapZoom = true, pinchZoom = false, tapToTurn = false, menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(image, ReaderSettingValues.image(ImagePreferences(), ReaderSettingValues.image(image)))
        val pdf = PdfPreferences(doubleTapZoom = true, pinchZoom = false, menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(pdf, ReaderSettingValues.pdf(PdfPreferences(), ReaderSettingValues.pdf(pdf)))
        val txt = TxtPreferences(menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(txt, ReaderSettingValues.txt(TxtPreferences(), ReaderSettingValues.txt(txt)))
        val epub = EpubReaderPreferences(menuVisibility = MenuVisibility.ALWAYS)
        assertEquals(epub, ReaderSettingValues.epub(EpubReaderPreferences(), ReaderSettingValues.epub(epub)))
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
