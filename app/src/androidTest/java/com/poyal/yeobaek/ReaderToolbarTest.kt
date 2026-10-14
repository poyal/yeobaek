package com.poyal.yeobaek

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyal.yeobaek.data.AppTheme
import com.poyal.yeobaek.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderToolbarTest {
    @get:Rule val ui = createComposeRule()

    @Test fun narrowToolbarAtDoubleFontScaleKeepsLabelsAndFullCellTargets() {
        val actions = listOf(ReaderAction.MOVE, ReaderAction.BOOKMARKS, ReaderAction.SEARCH, ReaderAction.INVERT, ReaderAction.SETTINGS)
        val tapped = mutableListOf<ReaderAction>()
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                YeobaekTheme(AppTheme.LIGHT) {
                    Box(Modifier.width(320.dp)) { ReaderToolbar(actions.map { action ->
                        ReaderToolbarItem(action, action.name) { tapped += action }
                    }) }
                }
            }
        }
        var previousRight = 0f
        for (action in actions) {
            val node = ui.onNodeWithTag(action.name).assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= previousRight - 1f)
            previousRight = bounds.right
            ui.onNodeWithText(action.label, useUnmergedTree = true).assertIsDisplayed().performClick()
            // The padded bottom of the cell is clickable, not just its icon or label.
            node.performTouchInput { click(bottomCenter - androidx.compose.ui.geometry.Offset(0f, 2f)) }
        }
        ui.runOnIdle { assertEquals(actions.flatMap { listOf(it, it) }, tapped) }
    }

    @Test fun inversionExposesStateAndRespondsToLabelTap() {
        var inverted by mutableStateOf(false)
        ui.setContent { YeobaekTheme(AppTheme.DARK) {
            ReaderToolbar(listOf(ReaderToolbarItem(ReaderAction.INVERT, "invert", inverted) { inverted = !inverted }))
        } }
        ui.onNodeWithTag("invert").assertIsNotSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "꺼짐"))
        ui.onNodeWithText("색상 반전", useUnmergedTree = true).performClick()
        ui.onNodeWithTag("invert").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "켜짐"))
    }
}
