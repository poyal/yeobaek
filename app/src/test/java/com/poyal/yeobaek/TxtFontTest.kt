package com.poyal.yeobaek

import com.poyal.yeobaek.data.TxtFont
import org.junit.Assert.*
import org.junit.Test

class TxtFontTest {
    @Test fun legacySelectionsAndFreshInstallKeepTheirFonts() {
        assertEquals(TxtFont.NOTO_SERIF, TxtFont.fromPreferences(null, null))
        assertEquals(TxtFont.NOTO_SERIF, TxtFont.fromPreferences(null, true))
        assertEquals(TxtFont.SYSTEM_SANS, TxtFont.fromPreferences(null, false))
    }

    @Test fun explicitSelectionWinsOverLegacyAndUnknownValuesFallBack() {
        for (font in TxtFont.entries) for (legacy in listOf(true, false, null)) {
            assertEquals(font, TxtFont.fromPreferences(font.name, legacy))
        }
        assertEquals(TxtFont.NOTO_SERIF, TxtFont.fromPreferences("future-font", true))
        assertEquals(TxtFont.SYSTEM_SANS, TxtFont.fromPreferences("future-font", false))
    }
}
