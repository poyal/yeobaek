package com.poyal.yeobaek.ui

import android.app.DownloadManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyal.yeobaek.BuildConfig
import com.poyal.yeobaek.YeobaekApplication
import com.poyal.yeobaek.update.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun AboutScreen(updates: UpdateController, back: () -> Unit, modifier: Modifier = Modifier) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val exportLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val diagnostics = (context.applicationContext as YeobaekApplication).scrollDiagnostics
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("로그 파일을 열지 못했어요.")
                    output.use { diagnostics.export(it) }
                }
                message = "스크롤 진단 로그를 저장했어요. 재현한 날짜·시간과 함께 전달해 주세요."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "로그를 저장하지 못했어요. 저장 위치를 확인하고 다시 시도해 주세요." }
            finally { exporting = false }
        }
    }
    fun link(url: String) { if (!openExternal(context, url)) message = "연결할 앱을 찾지 못했어요." }
    SettingsPage("앱 정보·문의", "about_page", back, modifier, "about_screen") {
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Symbol(48.dp)
                Column { Heading("여백"); Small("버전 ${BuildConfig.VERSION_NAME}") }
            }
            Text("문장과 문장 사이,\n나만의 읽는 자리.", fontFamily = Serif)
        }
        SettingsSection("제작자 · 문의", Icons.Outlined.PersonOutline) {
            Small("제작자 Poyal")
            SettingsRow(Icons.Outlined.Email, "이메일 문의", "poyal.work@gmail.com", "about_email") { link("mailto:poyal.work@gmail.com") }
            Rule()
            SettingsRow(Icons.Outlined.Code, "GitHub", "소스와 프로젝트 안내", "about_github") { link("https://github.com/poyal/yeobaek") }
            SettingsRow(Icons.Outlined.BugReport, "버그 신고 · 기능 제안", "GitHub Issues", "about_issues") { link("https://github.com/poyal/yeobaek/issues") }
            SettingsRow(Icons.Outlined.NewReleases, "릴리즈 · 변경 내역", "공개 버전과 변경 사항", "about_releases") { link(state.release?.pageUrl ?: "$RELEASES_URL/latest") }
        }
        SettingsSection("TXT 스크롤 진단", Icons.Outlined.BugReport) {
            Small("터치·스크롤·화면 변화의 최근 기록을 기기 시간과 함께 보관해요. 최대 128MiB(약 134MB)이며 오래된 기록부터 지워져요. 책 이름과 본문은 기록하지 않아요.")
            PaperButton(if (exporting) "로그 저장 중…" else "스크롤 진단 로그 저장", "export_scroll_logs",
                Modifier.fillMaxWidth(), outlined = true, enabled = !exporting) {
                val date = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                exportLogs.launch("yeobaek-scroll-$date.txt")
            }
        }
        if (message.isNotBlank()) SettingsCard { Text(message) }
        SettingsSection("개인정보와 라이선스", Icons.Outlined.Info) {
            Small("책과 독서 기록은 기기에 보관해요. 업데이트 확인·다운로드에만 GitHub를 사용하며, 본문·책갈피·읽기 기록은 전송하지 않아요.")
            Rule()
            Small("본문 글꼴 · Noto Serif KR\nSIL Open Font License 1.1\n폰트 라이선스 원문은 앱에 함께 포함되어 있어요.")
            Rule()
            Small("PDF 엔진 · PdfiumAndroidKt 2.0.0 · PDFium\nApache License 2.0 · BSD 및 구성요소별 라이선스\n라이선스 원문은 앱에 함께 포함되어 있어요.")
            Rule()
            Small("EPUB 엔진 · Readium Kotlin 3.1.2\nBSD 3-Clause · jsoup MIT 및 구성요소별 라이선스\n라이선스 원문은 앱에 함께 포함되어 있어요.")
            Rule()
            Small("이미지 압축 엔진 · libarchive-android 1.1.6\n이미지 회전 정보 · AndroidX ExifInterface 1.4.1\nApache License 2.0 · BSD 및 구성요소별 라이선스\n라이선스 원문은 앱에 함께 포함되어 있어요.")
        }
    }
}

@Composable fun UpdatesScreen(updates: UpdateController, back: () -> Unit, modifier: Modifier = Modifier) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var folderMessage by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.installationMessage(installationId, if (context.packageManager.canRequestPackageInstalls())
            "설치가 허용됐어요. 설치 버튼을 눌러 계속해 주세요." else "설치 허용이 꺼져 있어요. Download 폴더의 APK는 보관돼요.")
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.installationMessage(installationId, "설치를 취소했거나 완료되지 않았다면 다시 시도할 수 있어요. Download 폴더의 APK는 그대로 보관돼요.")
    }
    fun install() { scope.launch {
        val prepared = updates.installationCopy() ?: return@launch
        if (!updates.isInstallationCurrent(prepared)) return@launch
        installationId = prepared.download.id
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                updates.installationMessage(installationId, "여백의 ‘이 출처의 앱 설치 허용’을 켜 주세요. 다운로드한 파일은 유지돼요.")
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            } else {
                installer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(prepared.uri.toUri(), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } catch (_: Exception) { updates.installationMessage(installationId, "설치 화면을 열지 못했어요. 다운로드 폴더에서 APK를 직접 열어 주세요.") }
    } }
    SettingsPage("업데이트", "updates_page", back, modifier, "updates_screen") {
        SettingsSection("버전 확인", Icons.Outlined.SystemUpdate) {
            Text(state.message, Modifier.testTag("update_status"), color = if (state.check == CheckStatus.ERROR) MaterialTheme.colorScheme.error else LocalPaper.current.ink)
            Small("현재 버전 ${updates.installedVersion}")
            state.release?.let { Small("최신 확인 버전 ${it.version}") }
            if (state.checkedAt > 0) Small("마지막 성공 확인: " + Instant.ofEpochMilli(state.checkedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy. M. d HH:mm")))
            if (state.check == CheckStatus.ERROR && state.release != null) Small("이전 확인 결과예요. 새 정보를 받지 못했어요.")
            PaperButton(if (state.check == CheckStatus.CHECKING) "확인 중…" else "업데이트 확인", "update_check", Modifier.fillMaxWidth(),
                outlined = true, enabled = state.check != CheckStatus.CHECKING, onClick = updates::checkNow)
            val newer = state.release?.let { compareVersions(it.version, updates.installedVersion) > 0 } == true
            val matching = newer && state.transferRelease == state.release
            val transfer = if (matching) state.transfer else TransferStatus.NONE
            if (newer) {
                val running = transfer in setOf(TransferStatus.DOWNLOADING, TransferStatus.VERIFYING)
                val version = state.release!!.version
                if (transfer != TransferStatus.READY) PaperButton("$version " + if (transfer in setOf(TransferStatus.FAILED, TransferStatus.CANCELLED)) "다시 다운로드" else "APK 다운로드",
                    "update_download", Modifier.fillMaxWidth(), enabled = !running, onClick = updates::download)
                if (transfer == TransferStatus.READY) PaperButton("$version 업데이트 설치", "update_install", Modifier.fillMaxWidth(), onClick = ::install)
            }
            if (transfer == TransferStatus.DOWNLOADING) {
                if (state.total > 0) LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Small("${state.downloaded / 1024} / ${state.total / 1024} KB")
                PaperButton("다운로드 취소", "update_cancel", outlined = true, onClick = updates::cancelDownload)
            }
            if (transfer == TransferStatus.VERIFYING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (matching && state.transferMessage.isNotBlank()) Text(state.transferMessage,
                color = if (transfer == TransferStatus.FAILED) MaterialTheme.colorScheme.error else LocalPaper.current.muted)
            if (matching) state.fileName?.let { Small("${if (transfer == TransferStatus.READY) "저장 파일" else "다운로드 파일"}: $it") }
            PaperButton("다운로드 폴더 열기", "update_folder", Modifier.fillMaxWidth(), outlined = true, onClick = {
                try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                catch (_: Exception) { folderMessage = "파일 앱의 Download 또는 다운로드 폴더에서 APK를 찾아 주세요." }
            })
            if (folderMessage.isNotBlank()) Small(folderMessage)
            Small("앱을 새로 실행하면 새 버전을 알려드려요. 자동 조회는 하루 한 번, 직접 확인은 언제든 가능해요.")
        }
        SettingsSection("업데이트와 독서 기록", Icons.Outlined.BookmarkBorder) {
            Small("기존 앱을 삭제하지 않고 같은 서명의 APK로 업데이트하면 시작 폴더·읽기 위치·책갈피·설정이 유지돼요. 앱을 삭제하거나 데이터를 초기화하면 독서 기록이 지워져요.")
            Small("설치가 취소되거나 끝나도 Download 폴더의 APK는 보관돼요. 필요 없어진 파일은 직접 정리해 주세요.")
            Rule()
            Small("업데이트 확인·다운로드에만 GitHub를 사용해요. 본문·책갈피·읽기 기록은 전송하지 않아요.")
        }
    }
}

@Composable fun UpdatePrompt(state: UpdateState, dismiss: () -> Unit, download: () -> Unit) {
    if (state.prompt) AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("update_prompt"),
        title = { Text("새 버전이 있어요") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("여백 ${state.release?.version} 업데이트를 다운로드할 수 있어요.")
            if (state.check == CheckStatus.ERROR) Small("이전 확인 결과예요. 지금은 GitHub에 연결하지 못했어요.")
            Small("APK는 Download 폴더에 저장돼요. 설치는 확인 후 직접 진행해 주세요.")
        } },
        confirmButton = { PaperButton("다운로드", "update_prompt_download", onClick = download) },
        dismissButton = { PaperButton("나중에", "update_prompt_later", outlined = true, onClick = dismiss) },
        containerColor = LocalPaper.current.raised)
}
