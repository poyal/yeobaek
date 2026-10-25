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
    doubleTap: Boolean? = null, pinch: Boolean? = null, tapToTurn: Boolean? = null, quick: Boolean = false,
    swipeToTurn: Boolean? = null, swipeLabel: String = "좌우 스와이프로 페이지 넘김", pinchDetail: String? = null) {
    @Composable fun Options() {
        if (doubleTap != null) LabeledSwitch("두 번 탭하여 확대", doubleTap, "${format.lowercase()}_double_tap_zoom") { vm.changeSetting(format, "doubleTapZoom", it) }
        if (pinch != null) LabeledSwitch("두 손가락으로 확대·축소", pinch, "${format.lowercase()}_pinch_zoom", pinchDetail) { vm.changeSetting(format, "pinchZoom", it) }
        if (swipeToTurn != null) LabeledSwitch(swipeLabel, swipeToTurn, "${format.lowercase()}_swipe_to_turn") { vm.changeSetting(format, "swipeToTurn", it) }
        if (!quick && tapToTurn != null) LabeledSwitch("가장자리 탭으로 페이지 넘김", tapToTurn, "image_tap_to_turn") { vm.changeSetting(format, "tapToTurn", it) }
        Text("독서 메뉴 표시 방식")
        ChoiceGroup(MenuVisibility.entries, menu,
            label = { if (it == MenuVisibility.TAP) "탭으로 전환" else "항상 표시" }, tag = { "${format.lowercase()}_menu_$it" }) {
            vm.changeSetting(format, "menuVisibility", it)
        }
    }
    if (quick) Options() else SettingsSection("조작", Icons.Outlined.TouchApp) { Options() }
}
