package com.poyal.yeobaek.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.poyal.yeobaek.ReadingEnd

@Composable internal fun ReadingEndDialog(end: ReadingEnd, next: () -> Unit, exit: () -> Unit, dismiss: () -> Unit) {
    val colors = LocalPaper.current
    AlertDialog(
        modifier = Modifier.testTag("reading_end_dialog").semantics { testTagsAsResourceId = true },
        shape = RoundedCornerShape(8.dp), containerColor = colors.raised,
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Outlined.DoneAll, null, tint = colors.accent) },
        title = { Text("끝까지 읽었어요") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                end.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Small("다음 항목을 확인하고 있어요.") }
                end.error != null -> Text(end.error)
                end.next != null -> {
                    Small("다음에 읽을 항목")
                    Surface(color = colors.alt, shape = RoundedCornerShape(4.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(end.next.name, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Small(if (end.next.directory) "이미지 폴더" else end.next.formatLabel)
                        }
                    }
                }
                else -> Text("이 폴더에 다음으로 읽을 항목이 없어요.")
            }
        } },
        confirmButton = {
            // Vertical, full-width actions keep long labels usable at larger system font sizes.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (end.loading || end.next != null) PaperButton("다음 것 보기", "reading_end_next", Modifier.fillMaxWidth(),
                    enabled = !end.loading && end.next != null, onClick = next)
                PaperButton("나가기", "reading_end_exit", Modifier.fillMaxWidth(), outlined = end.loading || end.next != null, onClick = exit)
            }
        },
    )
}
