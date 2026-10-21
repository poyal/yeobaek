package com.poyal.yeobaek

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookListTest {
    @get:Rule val ui = createComposeRule()
    @Test fun percentageOnlyCircleAndMenuFitAtLargeFontWithoutOpeningTheBook() {
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        var opened = 0
        var managed = 0
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                YeobaekTheme(if (dark) AppTheme.DARK else AppTheme.LIGHT) {
                    Box(Modifier.width(320.dp)) {
                        BookFileRow("아주 긴 이름의 책.pdf", "PDF · 128 KB", "서재 / 소설", ReadingProgress.at(1.0 / 3), "row", "more",
                            { opened++ }, {}, { managed++ }) { Text("책") }
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            ui.runOnIdle { scale = fontScale; dark = !dark }
            val status = ui.onNodeWithTag("status_아주 긴 이름의 책.pdf", useUnmergedTree = true).assertIsDisplayed()
            status.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "읽는 중 · 33.3%"))
            ui.onNodeWithText("읽는 중", substring = false).assertDoesNotExist()
            val ring = status.fetchSemanticsNode().boundsInRoot
            val menu = ui.onNodeWithTag("more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(ring.right <= menu.left + 1)
            ui.onNodeWithTag("more").performClick()
            ui.onNodeWithTag("more_records").performClick()
            ui.runOnIdle { assertEquals(0, opened) }
        }
        ui.runOnIdle { assertEquals(3, managed) }
        ui.onNodeWithTag("row").performClick()
        ui.runOnIdle { assertEquals(1, opened) }
    }
}
