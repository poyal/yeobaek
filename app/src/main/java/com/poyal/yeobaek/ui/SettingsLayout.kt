package com.poyal.yeobaek.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun SettingsPage(title: String, tag: String, back: () -> Unit,
    modifier: Modifier = Modifier, contentTag: String = "${tag}_options", content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(LocalPaper.current.surface).testTag(tag)) {
        PageHeader(title, back)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag(contentTag).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable fun SettingsCard(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaper.current
    Surface(modifier.fillMaxWidth(), color = p.raised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, p.line)) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable fun SettingsSection(title: String, icon: ImageVector, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    SettingsCard(modifier) {
        Row(Modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = LocalPaper.current.accent)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        content()
    }
}
