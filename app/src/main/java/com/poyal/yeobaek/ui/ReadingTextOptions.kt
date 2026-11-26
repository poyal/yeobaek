package com.poyal.yeobaek.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.data.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable internal fun TxtTextOptions(vm: AppViewModel, prefs: TxtPreferences) {
    SettingsSection("글꼴과 크기", Icons.Outlined.TextFields) {
        TxtFontPicker(prefs.font) { vm.changeSetting("TXT", "font", it) }
        TextSizeStepper("${prefs.size.roundToInt()} sp", "font_size",
            canShrink = prefs.size > 14f, canGrow = prefs.size < 32f) { vm.stepTextSize("TXT", it) }
    }
    SettingsSection("간격과 여백", Icons.Outlined.FormatLineSpacing) {
        ReadingSlider("줄 간격", "line_spacing", prefs.line, 1.3f..2.1f, ::multiple) { vm.changeSetting("TXT", "line", it) }
        ReadingSlider("좌우 여백", "text_margin", prefs.margin, 16f..40f, { "${it.roundToInt()} dp" }, steps = 23) {
            vm.changeSetting("TXT", "margin", it)
        }
    }
    PaperOptions(prefs.paper, "paper") { vm.changeSetting("TXT", "paper", it) }
}

@Composable internal fun EpubTextOptions(vm: AppViewModel, prefs: EpubReaderPreferences) {
    SettingsSection("글꼴과 크기", Icons.Outlined.TextFields) {
        ReadingFontPicker(EpubFont.entries, prefs.font, EpubFont::label, EpubFont::textFont, "epub_font",
            { "epub_font_$it" }) { vm.changeSetting("EPUB", "font", it) }
        TextSizeStepper("${(prefs.size * 100).roundToInt()}%", "epub_size",
            canShrink = prefs.size > .75f, canGrow = prefs.size < 2.5f) { vm.stepTextSize("EPUB", it) }
    }
    SettingsSection("간격과 여백", Icons.Outlined.FormatLineSpacing) {
        LabeledSwitch("원본 서식", prefs.publisherStyles, "epub_publisher_styles", "책에 지정된 줄 간격과 여백을 사용합니다.") {
            vm.changeSetting("EPUB", "publisherStyles", it)
        }
        if (!prefs.publisherStyles) {
            ReadingSlider("줄 간격", "epub_line", prefs.line, 1.2f..2.2f, ::multiple) { vm.changeSetting("EPUB", "line", it) }
            ReadingSlider("좌우 여백", "epub_margin", prefs.margin, .5f..2f, ::multiple) { vm.changeSetting("EPUB", "margin", it) }
        }
    }
    PaperOptions(prefs.paper, "epub_paper") { vm.changeSetting("EPUB", "paper", it) }
}

@Composable private fun TextSizeStepper(value: String, tag: String, canShrink: Boolean, canGrow: Boolean, step: (Int) -> Unit) {
    Text("글자 크기")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Tool(Icons.Outlined.Remove, "글자 작게", "font_smaller", outlined = true, enabled = canShrink) { step(-1) }
        Text(value, Modifier.testTag(tag))
        Tool(Icons.Outlined.Add, "글자 크게", "font_larger", outlined = true, enabled = canGrow) { step(1) }
    }
}

private fun multiple(value: Float) = String.format(Locale.US, "%.2f배", value)

@Composable internal fun ReadingSlider(label: String, tag: String, value: Float, range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String, steps: Int = 0, commit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label · ${display(draft)}", Modifier.testTag("${tag}_value"))
        Slider(draft, { draft = it }, Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            valueRange = range, steps = steps, onValueChangeFinished = { commit(draft) })
    }
}

@Composable private fun PaperOptions(paper: TxtPaper, tag: String, change: (TxtPaper) -> Unit) {
    SettingsSection("본문 색상", Icons.Outlined.Palette) {
        ChoiceGroup(TxtPaper.entries, paper,
            label = { when (it) { TxtPaper.FOLLOW_APP -> "앱 테마"; TxtPaper.LIGHT -> "라이트"; TxtPaper.SEPIA -> "세피아"; TxtPaper.DARK -> "다크" } },
            tag = { "${tag}_$it" }, onSelect = change)
    }
}
