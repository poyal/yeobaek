package com.poyal.yeobaek.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.data.*

@Composable fun ReadingProgressCircle(progress: ReadingProgress, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    val color = when (progress.status) {
        ReadingStatus.UNREAD -> p.muted
        ReadingStatus.COMPLETED -> p.accent
        ReadingStatus.READING -> if (progress.fraction == null) p.muted else if (p.surface.luminance() > .5f) Color(0xFF8A5A1F) else Color(0xFFE3B879)
    }
    val style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
    val text = rememberTextMeasurer().measure(AnnotatedString(progress.label), style)
    val diameter = with(LocalDensity.current) { maxOf(48.dp, text.size.width.toDp() + 16.dp, text.size.height.toDp() + 16.dp) }
    Box(modifier.size(diameter).clearAndSetSemantics {
        stateDescription = progress.description
        progress.fraction?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..1f) }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val stroke = 2.5.dp.toPx()
            val area = Size(size.width - stroke, size.height - stroke)
            val origin = Offset(stroke / 2, stroke / 2)
            drawArc(p.control.copy(alpha = .4f), 0f, 360f, false, origin, area, style = Stroke(stroke))
            val amount = (progress.fraction?.toFloat() ?: 0f).coerceAtMost(if (progress.status == ReadingStatus.COMPLETED) 1f else .999f)
            if (amount > 0) drawArc(color, -90f, 360f * amount, false, origin, area, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(progress.label, style = style, color = color, maxLines = 1)
    }
}

@Composable internal fun BookFileRow(name: String, metadata: String, path: String? = null,
    progress: ReadingProgress?, tag: String, menuTag: String, onOpen: () -> Unit, onInfo: () -> Unit,
    onManage: (() -> Unit)? = null, thumbnail: @Composable () -> Unit) {
    var menu by rememberSaveable(tag) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onOpen).testTag(tag)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { thumbnail() }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, fontSize = 14.sp, color = LocalPaper.current.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Small(metadata)
            path?.takeIf { it.isNotBlank() }?.let { Text(it, color = LocalPaper.current.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (progress != null) ReadingProgressCircle(progress, Modifier.testTag("status_$name"))
        Box {
            Tool(Icons.Outlined.MoreVert, "$name 더보기", menuTag, onClick = { menu = true })
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("파일 정보") }, onClick = { menu = false; onInfo() }, modifier = Modifier.testTag("${menuTag}_info"))
                if (onManage != null) DropdownMenuItem(text = { Text("독서 기록 관리") }, onClick = { menu = false; onManage() }, modifier = Modifier.testTag("${menuTag}_records"))
            }
        }
    }
}

internal fun fileMetadata(entry: DocumentEntry): String {
    if (entry.directory) return "이미지 폴더"
    if (!entry.supported) return "지원 여부 확인 필요"
    val bytes = entry.size?.takeIf { it >= 0 }
    val size = when { bytes == null -> "크기 정보 없음"; bytes < 1024 -> "$bytes B"; else -> "${bytes / 1024} KB" }
    return "${entry.formatLabel} · $size"
}

@Composable internal fun RecentBookRow(vm: AppViewModel, book: Book, progress: ReadingProgress) {
    val entry by produceState<DocumentEntry?>(null, book.uri, book.openedAt) { value = vm.bookMetadata(book) }
    BookFileRow(book.name, entry?.let(::fileMetadata) ?: when (book.format) {
        "IMAGE_FOLDER" -> "이미지 폴더"; "IMAGE_ARCHIVE" -> "압축 이미지"; "IMAGE_SINGLE" -> "이미지"; else -> book.format
    }, book.folder, progress, "recent_${book.name}", "manage_${book.name}", { vm.openBook(book) }, { vm.bookInfo(book) },
        { vm.sheet("forget:${book.id}") }) {
        entry?.let { FolderFileThumbnail(vm, it) } ?: Icon(when (book.format) {
            "IMAGE_FOLDER" -> Icons.Outlined.Folder; "IMAGE_ARCHIVE" -> Icons.Outlined.FolderZip
            "IMAGE_SINGLE" -> Icons.Outlined.Image; else -> Icons.Outlined.Description
        }, null, Modifier.size(28.dp), tint = LocalPaper.current.accent)
    }
}
