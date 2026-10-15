package com.poyal.yeobaek

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TxtFontTest {
    @get:Rule val ui = createComposeRule()

    @Test fun eachFontCanBeSelectedAndPreviewUsesTheReaderTypography() {
        var selected by mutableStateOf(TxtFont.NOTO_SERIF)
        ui.setContent {
            YeobaekTheme(AppTheme.LIGHT) {
                Column(Modifier.verticalScroll(rememberScrollState())) { TxtFontPicker(selected) { selected = it } }
            }
        }
        for (font in TxtFont.entries) {
            ui.onNodeWithTag("font_${font.name}").performScrollTo().performClick().assertIsSelected()
            ui.runOnIdle { assertEquals(font, selected) }
            val prefs = TxtPreferences(font = font)
            assertEquals(font.family, prefs.textStyle().fontFamily)
            assertEquals(font.weight, prefs.textStyle().fontWeight!!.weight)
        }
        assertEquals(TxtFont.entries.size, TxtFont.entries.map { TxtPreferences(font = it).layoutKey(600, 2f, 1f) }.toSet().size)
    }

    @Test fun selectedFontPersistsAcrossPreferencesInstancesAndResetRestoresDefault() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Preferences(context)
        val original = prefs.txt.first()
        try {
            for (font in TxtFont.entries) {
                prefs.setFont(font)
                assertEquals(font, Preferences(context).txt.first().font)
                assertEquals(original.size, prefs.txt.first().size, .001f)
            }
            prefs.resetTxt()
            assertEquals(TxtPreferences(), Preferences(context).txt.first())
        } finally {
            prefs.setFont(original.font); prefs.setSize(original.size); prefs.setLine(original.line)
            prefs.setMargin(original.margin); prefs.setPaper(original.paper)
        }
    }
}
