package com.poyal.yeobaek.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ReaderAction(val label: String, val icon: ImageVector) {
    CONTENTS("목차", Icons.Outlined.List),
    MOVE("이동", Icons.Outlined.SwapVert),
    BOOKMARKS("책갈피", Icons.Outlined.Bookmarks),
    SEARCH("검색", Icons.Outlined.Search),
    INFO("정보", Icons.Outlined.Info),
    INVERT("색상 반전", Icons.Outlined.InvertColors),
    SETTINGS("설정", Icons.Outlined.Tune),
}

data class ReaderToolbarItem(val action: ReaderAction, val tag: String, val active: Boolean? = null, val onClick: () -> Unit)

/** All viewers share names, icons and a full-cell touch target, including the label. */
@Composable fun ReaderToolbar(items: List<ReaderToolbarItem>, modifier: Modifier = Modifier) {
    val colors = LocalPaper.current
    Column(modifier) {
        Rule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            items.forEach { item ->
                val active = item.active == true
                Column(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp)
                        .clickable(role = Role.Button, onClick = item.onClick)
                        .testTag(item.tag).semantics(mergeDescendants = true) {
                            item.active?.let { selected = it; stateDescription = if (it) "켜짐" else "꺼짐" }
                        }.padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (item.action == ReaderAction.INVERT && !active) Icons.Outlined.InvertColorsOff else item.action.icon,
                        null, Modifier.size(24.dp), tint = if (active) colors.accent else colors.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(item.action.label, color = if (active) colors.accent else colors.muted, fontSize = 11.sp,
                        lineHeight = 16.sp, maxLines = 2, textAlign = TextAlign.Center,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
