package com.poyal.yeobaek.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.poyal.yeobaek.R

@Composable fun Rule() { HorizontalDivider(color = LocalPaper.current.line, thickness = 1.dp) }
@Composable fun Symbol(size: Dp = 27.dp) { Icon(painterResource(R.drawable.ic_mark), "여백 리듬", Modifier.size(size), tint = LocalPaper.current.accent) }
@Composable fun Small(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = LocalPaper.current.muted, fontSize = 12.sp, lineHeight = 20.sp) }
@Composable fun Heading(text: String, modifier: Modifier = Modifier) { Text(text, modifier, fontFamily = Serif, fontSize = 27.sp, lineHeight = 40.sp, color = LocalPaper.current.ink) }
@Composable fun PaperButton(text: String, tag: String, modifier: Modifier = Modifier, outlined: Boolean = false, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val p = LocalPaper.current
    val accent = if (destructive) p.danger else p.accent
    val label: @Composable RowScope.() -> Unit = { Text(text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center) }
    val buttonModifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    if (outlined) OutlinedButton(onClick, buttonModifier, enabled = enabled, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, (if (destructive) p.danger else p.control).copy(alpha = if (enabled) 1f else .38f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (destructive) p.danger.copy(alpha = .06f) else p.raised, contentColor = accent),
        contentPadding = padding, content = label)
    else Button(onClick, buttonModifier, enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (destructive) MaterialTheme.colorScheme.onError else p.onAccent),
        contentPadding = padding, content = label)
}
@Composable fun Tool(icon: ImageVector, label: String, tag: String, modifier: Modifier = Modifier, outlined: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    val p = LocalPaper.current
    val tint = if (destructive) p.danger else p.ink
    val buttonModifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag)
    if (outlined || destructive) OutlinedIconButton(onClick, buttonModifier, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (destructive) p.danger else p.control),
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = if (destructive) p.danger.copy(alpha = .06f) else p.raised, contentColor = tint)) {
        Icon(icon, label, Modifier.size(22.dp))
    } else IconButton(onClick, buttonModifier) { Icon(icon, label, Modifier.size(22.dp), tint = tint) }
}
@Composable fun PageHeader(title: String, onBack: () -> Unit) {
    Column { Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Tool(Icons.Outlined.ArrowBack, "뒤로", "page_back", onClick = onBack)
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }; Rule() }
}
@Composable fun SettingsRow(icon: ImageVector, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).heightIn(min = 72.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = LocalPaper.current.accent)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(title, fontSize = 14.sp); Small(detail) }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = LocalPaper.current.muted)
    }
}
@Composable fun Choice(text: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPaper.current
    Surface(onClick, modifier.heightIn(min = 48.dp).testTag(tag), shape = RoundedCornerShape(3.dp), color = if (selected) p.soft else p.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.control)) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(if (selected) "$text ✓" else text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
    }
}
@Composable fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(32.dp), tint = LocalPaper.current.accent)
        Heading(title); Small(detail); action()
    }
}
@Composable fun Filename(name: String, modifier: Modifier = Modifier) { Text(name, modifier, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
