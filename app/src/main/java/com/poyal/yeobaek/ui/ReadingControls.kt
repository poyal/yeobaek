@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.poyal.yeobaek.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.image.*
import kotlinx.coroutines.CancellationException
import kotlin.math.min

@Composable fun FolderFileThumbnail(vm: AppViewModel, entry: DocumentEntry) {
    if (entry.directory) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(28.dp), tint = LocalPaper.current.accent)
        return
    }
    val bitmap by produceState<android.graphics.Bitmap?>(null, entry.uri, entry.modified, entry.size) {
        try { value = vm.thumbnails.load(entry) } catch (e: CancellationException) { throw e } catch (_: Exception) { value = null }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), null, Modifier.size(36.dp, 48.dp), contentScale = ContentScale.Fit)
    else Icon(if (entry.image) Icons.Outlined.Image else if (entry.archive) Icons.Outlined.FolderZip else Icons.Outlined.Description, null, Modifier.size(28.dp), tint = LocalPaper.current.accent)
}

@Composable fun ReaderWindowSettings(vm: AppViewModel, reading: Boolean) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle, reading, settings.screen) {
        val previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fun apply(active: Boolean) {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = if (active && reading) settings.screen.brightness else previousBrightness }
            if (active && reading && settings.screen.awake) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = if (!active || !reading) previousOrientation else when (settings.screen.rotation) {
                "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        val observer = LifecycleEventObserver { _, _ -> apply(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer); apply(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); apply(false) }
    }
}

@Composable fun SettingsScopePicker(vm: AppViewModel, format: String, showTitle: Boolean = true) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope by vm.settingsScope.collectAsStateWithLifecycle()
    val book = state.reader?.book ?: state.pdfReader?.book ?: state.imageReader?.book ?: state.epubReader?.book
    if (showTitle) Text("설정 적용 범위")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsScope.entries.filter { it == SettingsScope.COMMON || book != null && (it == SettingsScope.BOOK || settings.folders.containsKey(book.id)) }.forEach {
            Choice(when (it) { SettingsScope.COMMON -> "공통"; SettingsScope.FOLDER -> "이 폴더"; SettingsScope.BOOK -> "이 책" }, scope == it, "scope_$it", Modifier.weight(1f)) { vm.settingsScope.value = it }
        }
    }
    Small(if (scope == SettingsScope.COMMON) "개별 설정이 없는 책에 적용됩니다." else "바꾼 항목만 따로 저장합니다. 폴더 설정은 하위 폴더에 적용되지 않습니다.")
    if (scope != SettingsScope.COMMON && book != null) {
        val values = settings.overrides[settings.scopeKey(scope, format, book.id)].orEmpty()
        val labels = mapOf("menuVisibility" to "독서 메뉴 표시", "doubleTapZoom" to "두 번 탭 확대", "pinchZoom" to "두 손가락 확대", "tapToTurn" to "가장자리 탭 넘김", "publisherStyles" to "원본 서식", "font" to "글꼴", "size" to "글자 크기", "line" to "줄 간격", "margin" to "좌우 여백", "paper" to "본문 색상",
            "fit" to "화면 맞춤", "inverted" to "색상 반전", "spacing" to "페이지 간격", "canvas" to "배경", "mode" to "읽기 방식",
            "direction" to "읽기 방향", "spread" to "두 쪽 보기", "coverAlone" to "표지 단독", "verticalFit" to "세로 맞춤", "pagedFit" to "페이지 맞춤",
            "splitWide" to "펼침면 분할", "cropMode" to "여백 자르기", "cropLeft" to "왼쪽 여백", "cropTop" to "위 여백", "cropRight" to "오른쪽 여백", "cropBottom" to "아래 여백")
        values.forEach { (field, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${labels[field] ?: field} · 개별 설정", Modifier.weight(1f))
                TextButton(onClick = { vm.inheritSetting(format, field) }, modifier = Modifier.testTag("inherit_$field")) { Text("상속") }
            }
        }
        if (values.isNotEmpty()) TextButton(onClick = { vm.resetSettings(format) }, modifier = Modifier.testTag("scope_reset")) { Text("이 범위의 개별 설정 모두 해제") }
    }
}

@Composable fun QuickReaderSettings(vm: AppViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val txt by vm.editTxt.collectAsStateWithLifecycle()
    val pdf by vm.editPdf.collectAsStateWithLifecycle()
    val image by vm.editImage.collectAsStateWithLifecycle()
    val format = when { state.imageReader != null -> "IMAGE"; state.pdfReader != null -> "PDF"; else -> "TXT" }
    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).testTag("quick_options"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsScopePicker(vm, format)
        when (format) {
            "TXT" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("글자 크기 ${txt.size.toInt()}", Modifier.weight(1f))
                    Tool(Icons.Outlined.Remove, "글자 작게", "font_smaller") { vm.changeSetting("TXT", "size", (txt.size - 1).coerceAtLeast(14f)) }
                    Tool(Icons.Outlined.Add, "글자 크게", "font_larger") { vm.changeSetting("TXT", "size", (txt.size + 1).coerceAtMost(32f)) }
                }
                var fontMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { fontMenu = true }, modifier = Modifier.fillMaxWidth().testTag("quick_font")) { Text("글꼴 · ${txt.font.label}") }
                    DropdownMenu(fontMenu, onDismissRequest = { fontMenu = false }) {
                        TxtFont.entries.forEach { font -> DropdownMenuItem(text = { Text(font.label) }, onClick = { vm.changeSetting("TXT", "font", font.name); fontMenu = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { TxtPaper.entries.forEach { paper ->
                    Choice(when (paper) { TxtPaper.FOLLOW_APP -> "앱 테마"; TxtPaper.LIGHT -> "라이트"; TxtPaper.SEPIA -> "세피아"; TxtPaper.DARK -> "다크" }, txt.paper == paper, "paper_$paper", Modifier.weight(1f)) { vm.changeSetting("TXT", "paper", paper.name) }
                } }
            }
            "PDF" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfFit.entries.forEach { fit -> Choice(if (fit == PdfFit.WIDTH) "너비에 맞춤" else "페이지에 맞춤", pdf.fit == fit, "pdf_fit_$fit", Modifier.weight(1f)) { vm.changeSetting("PDF", "fit", fit.name) } } }
                LabeledSwitch("색상 반전", pdf.inverted, "pdf_inversion_switch") { vm.changeSetting("PDF", "inverted", it) }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ImageMode.entries.forEach { mode -> Choice(if (mode == ImageMode.VERTICAL) "세로 연속" else "좌우 넘김", image.mode == mode, "image_mode_$mode", Modifier.weight(1f)) { vm.setImage(image.copy(mode = mode)) } } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ImageFit.entries.forEach { fit -> Choice(if (fit == ImageFit.WIDTH) "너비 맞춤" else "전체 이미지", image.fit == fit, "image_fit_$fit", Modifier.weight(1f)) { vm.setImage(if (image.mode == ImageMode.VERTICAL) image.copy(verticalFit = fit) else image.copy(pagedFit = fit)) } } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ImageDirection.entries.forEach { direction -> Choice(if (direction == ImageDirection.LTR) "왼쪽부터" else "오른쪽부터", image.direction == direction, "image_direction_$direction", Modifier.weight(1f)) { vm.setImage(image.copy(direction = direction)) } } }
                ImageCropOptions(vm, image)
            }
        }
        when (format) {
            "TXT" -> ReaderGestureSettings(vm, format, txt.menuVisibility, quick = true)
            "PDF" -> {
                ReaderGestureSettings(vm, format, pdf.menuVisibility, pdf.doubleTapZoom, quick = true)
                PaperButton("확대 초기화", "pdf_zoom_reset", outlined = true, onClick = { vm.pdfReader.resetZoom(); vm.sheet(null) })
            }
            else -> {
                ReaderGestureSettings(vm, format, image.menuVisibility, image.doubleTapZoom, quick = true)
                PaperButton("확대 초기화", "image_zoom_reset", outlined = true, onClick = { vm.imageReader.resetZoom(); vm.sheet(null) })
            }
        }
        PaperButton("자세한 읽기 설정", "quick_details", Modifier.fillMaxWidth(), outlined = true, onClick = { when (format) { "TXT" -> vm.openTxtSettings(); "PDF" -> vm.openPdfSettings(); else -> vm.openImageSettings() } })
        Rule(); Text("독서 화면 · 모든 책 공통")
        val screen = settings.screen
        LabeledSwitch("시스템 밝기 사용", screen.brightness < 0, "brightness_system") { vm.setScreen(screen.copy(brightness = if (it) -1f else .5f)) }
        if (screen.brightness >= 0) SettingSlider("밝기", screen.brightness, .02f..1f, "reader_brightness") { vm.setScreen(screen.copy(brightness = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("SYSTEM" to "자동 회전", "PORTRAIT" to "세로", "LANDSCAPE" to "가로").forEach { (value, label) ->
            Choice(label, screen.rotation == value, "rotation_$value", Modifier.weight(1f)) { vm.setScreen(screen.copy(rotation = value)) }
        } }
        LabeledSwitch("읽는 동안 화면 켜두기", screen.awake, "reader_awake") { vm.setScreen(screen.copy(awake = it)) }
    }
}

@Composable fun LabeledSwitch(label: String, value: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, change, Modifier.testTag(tag)) }
}
@Composable fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, commit: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Text("$label · ${(current * 100).toInt()}%")
    Slider(current, { current = it }, Modifier.testTag(tag), valueRange = range, onValueChangeFinished = { commit(current) })
}
@Composable fun ImageCropOptions(vm: AppViewModel, prefs: ImagePreferences) {
    LabeledSwitch("펼침면 반쪽씩 읽기", prefs.splitWide, "image_split") { vm.setImage(prefs.copy(splitWide = it)) }
    Small("세로 화면 · 좌우 넘김에서 가로로 긴 페이지에 적용합니다.")
    LabeledSwitch("자동 여백 자르기", prefs.cropMode == CropMode.AUTO, "image_auto_crop") { vm.setImage(prefs.copy(cropMode = if (it) CropMode.AUTO else CropMode.OFF)) }
    if (vm.currentBook?.format?.startsWith("IMAGE_") == true) PaperButton("여백 직접 조절", "image_crop_edit", Modifier.fillMaxWidth(), outlined = true, onClick = { vm.sheet("image_crop") })
}

@Composable fun ImageCropSettings(vm: AppViewModel) {
    val opened = vm.ui.value.imageReader ?: return
    val locator by vm.imageReader.locator.collectAsStateWithLifecycle()
    val preferences by vm.image.collectAsStateWithLifecycle()
    var crop by remember(opened.token, locator.key) { mutableStateOf(preferences.pageCrops[locator.key] ?: preferences.crop) }
    var single by rememberSaveable { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, opened.token, locator.page) {
        try { value = opened.source.thumbnail(locator.page) } catch (e: CancellationException) { throw e } catch (_: Exception) { value = null }
    }
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).testTag("crop_options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Small("${locator.page + 1} 페이지 미리보기 · 테두리 안쪽을 표시합니다.")
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            bitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "자르기 미리보기", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(Modifier.fillMaxSize()) {
                    val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    drawRect(Color(0xffda5a28), Offset((size.width - w) / 2 + w * crop.left, (size.height - h) / 2 + h * crop.top), Size(w * crop.width, h * crop.height), style = Stroke(3.dp.toPx()))
                }
            }
        }
        SettingSlider("왼쪽", crop.left, 0f.. .3f, "crop_left") { crop = crop.copy(left = it) }
        SettingSlider("오른쪽", 1 - crop.right, 0f.. .3f, "crop_right") { crop = crop.copy(right = 1 - it) }
        SettingSlider("위", crop.top, 0f.. .3f, "crop_top") { crop = crop.copy(top = it) }
        SettingSlider("아래", 1 - crop.bottom, 0f.. .3f, "crop_bottom") { crop = crop.copy(bottom = 1 - it) }
        LabeledSwitch("현재 페이지만 적용", single, "crop_single") { single = it }
        PaperButton("자르기 적용", "crop_apply", Modifier.fillMaxWidth(), onClick = { if (single) vm.setPageCrop(crop) else vm.setBookCrop(crop); vm.sheet(null) })
        PaperButton("현재 페이지 예외 해제", "crop_inherit", Modifier.fillMaxWidth(), outlined = true, onClick = { vm.setPageCrop(null); vm.sheet(null) })
        PaperButton("자르기 없이 보기", "crop_off", Modifier.fillMaxWidth(), outlined = true, onClick = { if (single) vm.setPageCrop(CropRect()) else vm.setBookCrop(CropRect()); vm.sheet(null) })
    }
}

@Composable fun BackupSettings(vm: AppViewModel) {
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val status by vm.backupStatus.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::inspectBackup) }
    SettingsSection("독서 기록 백업", Icons.Outlined.SaveAlt, Modifier.testTag("backup_settings")) {
        Small("읽던 위치 · 책갈피 · 설정\n책 원본과 진단 로그는 포함하지 않습니다.")
        PaperButton("백업 파일 저장", "backup_export", Modifier.fillMaxWidth(), enabled = !busy, onClick = { export.launch("여백-독서기록-${java.time.LocalDate.now()}.json") })
        PaperButton("백업 가져오기", "backup_import", Modifier.fillMaxWidth(), outlined = true, enabled = !busy, onClick = { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Small(status, Modifier.testTag("backup_status"))
    }
}
@Composable fun BackupDialogs(vm: AppViewModel) {
    val preview by vm.backupPreview.collectAsStateWithLifecycle()
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    preview?.let { value ->
        AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { if (!busy) vm.backupPreview.value = null }, title = { Text("독서 기록 가져오기") }, text = {
            val data = value.backup.library
            Text("책 ${data.books.size}권 · 읽기 위치 ${data.positionCount}개 · 책갈피 ${data.bookmarkCount}개\n\n기존 책과 겹치는 기록 ${value.conflicts}권\n기존 값은 유지하고 없는 기록만 추가합니다.\n\n다른 기기의 파일 접근 권한은 다시 연결해야 합니다.")
        }, confirmButton = { PaperButton("없는 기록 추가", "backup_restore_confirm", enabled = !busy, onClick = vm::restoreBackup) },
            dismissButton = { TextButton(onClick = { vm.backupPreview.value = null }, enabled = !busy) { Text("취소") } })
    }
}
