package com.poyal.yeobaek.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poyal.yeobaek.data.TxtFont
import com.poyal.yeobaek.data.TxtPreferences

@Composable internal fun <T> ReadingFontPicker(options: List<T>, selected: T, label: (T) -> String,
    previewFont: (T) -> TxtFont?, tag: String, optionTag: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    PaperButton("글꼴 · ${label(selected)}", tag, Modifier.fillMaxWidth(), outlined = true, onClick = { expanded = !expanded })
    if (!expanded) return
    val colors = LocalPaper.current
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { font ->
            Surface(color = if (selected == font) colors.soft else colors.raised,
                border = BorderStroke(1.dp, if (selected == font) colors.accent else colors.line),
                shape = RoundedCornerShape(4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected == font, role = Role.RadioButton) { onSelect(font); expanded = false }
                    .testTag(optionTag(font)).padding(12.dp)) {
                    RadioButton(selected == font, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(label(font), color = colors.ink, fontSize = 13.sp)
                        previewFont(font)?.let { face ->
                            Text("여백에서 읽는 이야기 · Aa 123", color = colors.ink,
                                style = TxtPreferences(font = face, size = 16f, line = 1.5f).textStyle())
                        } ?: Small("책에 지정된 글꼴을 사용합니다.")
                    }
                }
            }
        }
    }
    Small("시스템 글꼴은 기기마다 다르며, 일부 선택지는 한글 모양이 같을 수 있어요.")
}

@Composable internal fun TxtFontPicker(selected: TxtFont, onSelect: (TxtFont) -> Unit) =
    ReadingFontPicker(TxtFont.entries, selected, TxtFont::label, { it }, "quick_font", { "font_$it" }, onSelect)
