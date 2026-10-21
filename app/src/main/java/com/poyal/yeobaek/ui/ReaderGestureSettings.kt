package com.poyal.yeobaek.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.data.MenuVisibility

@Composable fun ReaderGestureSettings(vm: AppViewModel, format: String, menu: MenuVisibility,
    doubleTap: Boolean? = null, pinch: Boolean? = null, tapToTurn: Boolean? = null, quick: Boolean = false) {
    @Composable fun Options() {
        if (doubleTap != null) LabeledSwitch("두 번 탭하여 확대", doubleTap, "${format.lowercase()}_double_tap_zoom") { vm.changeSetting(format, "doubleTapZoom", it) }
        if (!quick && pinch != null) LabeledSwitch("두 손가락으로 확대", pinch, "${format.lowercase()}_pinch_zoom") { vm.changeSetting(format, "pinchZoom", it) }
        if (!quick && tapToTurn != null) LabeledSwitch("가장자리 탭으로 페이지 넘김", tapToTurn, "image_tap_to_turn") { vm.changeSetting(format, "tapToTurn", it) }
        Text("독서 메뉴 표시 방식")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuVisibility.entries.forEach { value ->
                Choice(if (value == MenuVisibility.TAP) "탭으로 전환" else "항상 표시", menu == value, "${format.lowercase()}_menu_$value") { vm.changeSetting(format, "menuVisibility", value) }
            }
        }
    }
    if (quick) Options() else SettingsSection("조작", Icons.Outlined.TouchApp) { Options() }
}
