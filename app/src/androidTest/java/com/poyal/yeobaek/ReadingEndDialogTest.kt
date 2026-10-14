package com.poyal.yeobaek

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.poyal.yeobaek.data.AppTheme
import com.poyal.yeobaek.data.DocumentEntry
import com.poyal.yeobaek.ui.ReadingEndDialog
import com.poyal.yeobaek.ui.YeobaekTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingEndDialogTest {
    @get:Rule val ui = createComposeRule()
    @Test fun loadingNextAndExitActionsAndBackDismiss() {
        var state by mutableStateOf<ReadingEnd?>(ReadingEnd("test"))
        var next = 0
        var exits = 0
        val entry = DocumentEntry("next", "content://test/next", "아주 긴 다음 파일의 이름을 표시하고 이동할 수 있는지 확인하는 문서.pdf", false, null, null, "application/pdf")
        ui.setContent { YeobaekTheme(AppTheme.LIGHT) { state?.let {
            ReadingEndDialog(it, { next++ }, { exits++ }, { state = null })
        } } }
        ui.onNodeWithTag("reading_end_next").assertIsNotEnabled()
        ui.onNodeWithTag("reading_end_exit").assertIsEnabled()
        ui.runOnIdle { state = ReadingEnd("test", loading = false, next = entry) }
        ui.onNodeWithText(entry.name).assertIsDisplayed()
        ui.onNodeWithTag("reading_end_next").performClick()
        ui.runOnIdle { assertEquals(1, next); assertEquals(0, exits) }
        pressBack()
        ui.onNodeWithTag("reading_end_dialog").assertDoesNotExist()
    }
    @Test fun noNextAndLookupFailureStillAllowExit() {
        var state by mutableStateOf(ReadingEnd("test", loading = false))
        var exits = 0
        ui.setContent { YeobaekTheme(AppTheme.DARK) { ReadingEndDialog(state, {}, { exits++ }, {}) } }
        ui.onNodeWithTag("reading_end_next").assertDoesNotExist()
        ui.onNodeWithText("이 폴더에 다음으로 읽을 항목이 없어요.").assertIsDisplayed()
        ui.onNodeWithTag("reading_end_exit").performClick()
        ui.runOnIdle { state = state.copy(error = "폴더 접근 권한을 확인해 주세요.") }
        ui.onNodeWithText("폴더 접근 권한을 확인해 주세요.").assertIsDisplayed()
        ui.onNodeWithTag("reading_end_exit").performClick()
        ui.runOnIdle { assertEquals(2, exits) }
    }
}
