package com.poyal.yeobaek

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyal.yeobaek.data.AppTheme
import com.poyal.yeobaek.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsChoicesTest {
    @get:Rule val ui = createComposeRule()

    @Test fun choicesKeepBalancedRowsAndStableGeometryAcrossSelectionsAndLargeText() {
        var labels by mutableStateOf(listOf("항상 표시", "메뉴와 함께 표시", "숨김"))
        var selected by mutableIntStateOf(0)
        var width by mutableIntStateOf(240)
        var scale by mutableFloatStateOf(1f)
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                YeobaekTheme(AppTheme.LIGHT) {
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        ChoiceGroup(labels.indices.toList(), selected, label = { labels[it] }, tag = { "choice_$it" }) { selected = it }
                    }
                }
            }
        }
        for (options in listOf(listOf("항상 표시", "메뉴와 함께 표시", "숨김"), listOf("앱 테마", "라이트", "세피아", "다크"), listOf("왼쪽 → 오른쪽", "오른쪽 → 왼쪽"), listOf("공통", "이 폴더", "이 책"))) {
            for (available in listOf(240, 280)) for (fontScale in listOf(1f, 2f)) {
                ui.runOnIdle { labels = options; selected = 0; width = available; scale = fontScale }
                fun geometry() = options.indices.map { ui.onNodeWithTag("choice_$it").fetchSemanticsNode().let { n -> n.positionInRoot to n.size } }
                val before = geometry()
                val rows = before.groupBy { it.first.y }
                assertEquals("No orphaned final choice", 1, rows.values.map { it.size }.distinct().size)
                assertEquals("Every choice has equal width", 1, before.map { it.second.width }.distinct().size)
                for (i in options.indices) {
                    ui.runOnIdle { selected = i }
                    ui.onNodeWithTag("choice_$i").assertIsSelected()
                    assertEquals("Selection cannot resize or move options", before, geometry())
                }
                ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    val results = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                    assertTrue("Choice text must not be clipped", results.none { it.hasVisualOverflow })
                }
            }
        }
    }
}
