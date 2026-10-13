package com.poyal.yeobaek

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.poyal.yeobaek.ui.YeobaekTheme
import com.poyal.yeobaek.ui.UpdatesScreen
import com.poyal.yeobaek.update.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class UpdatesFlowTest {
    @get:Rule val ui = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val latest = ReleaseInfo("1.0.11", "fixture/1.0.11", "yeobaek-1.0.11.apk", 100, "a".repeat(64), "fixture")
    private class Memory(var record: UpdateRecord): UpdatePersistence {
        override suspend fun read() = record
        override suspend fun write(record: UpdateRecord) { this.record = record }
    }
    private class Downloads: UpdateDownloads {
        var status = TransferStatus.READY
        var enqueues = 0
        override suspend fun enqueue(release: ReleaseInfo): DownloadRecord {
            enqueues++; status = TransferStatus.DOWNLOADING
            return DownloadRecord(enqueues.toLong(), release, "new-$enqueues.apk")
        }
        override suspend fun progress(record: DownloadRecord) = DownloadProgress(status)
        override suspend fun verify(record: DownloadRecord) = Unit
        override suspend fun installationCopy(record: DownloadRecord) = "content://fixture"
        override suspend fun cancel(record: DownloadRecord) { status = TransferStatus.CANCELLED }
    }
    @After fun cleanup() { scope.cancel() }
    @Test fun offlineFailureAndManualRetryToNoReleaseRemainUsable() {
        var offline = true
        var now = 7000L
        val controller = UpdateController("0.1.0", object: ReleaseSource {
            override suspend fun latest(): ReleaseInfo? { if (offline) error("인터넷 연결을 확인해 주세요."); return null }
        }, Memory(UpdateRecord()), Downloads(), scope) { now }
        ui.setContent { YeobaekTheme(com.poyal.yeobaek.data.AppTheme.DARK) { UpdatesScreen(controller, {}) } }
        ui.onNodeWithTag("update_check").performScrollTo().performClick()
        ui.waitUntil(10000) { controller.state.value.check == CheckStatus.ERROR }
        ui.onNodeWithText("인터넷 연결을 확인해 주세요.").assertIsDisplayed()
        ui.onNodeWithTag("update_download").assertDoesNotExist()
        offline = false; now += 6000
        ui.onNodeWithTag("update_check").performClick()
        ui.waitUntil(10000) { controller.state.value.check == CheckStatus.NO_RELEASE }
        ui.onNodeWithText("아직 공개된 정식 릴리즈가 없어요.").assertIsDisplayed()
        ui.onNodeWithTag("update_install").assertDoesNotExist()
    }
    @Test fun oldDownloadedApkDoesNotShowRetryOrInstallAndLatestAttemptGetsVersionedActions() {
        val old = DownloadRecord(42, latest.copy(version = "1.0.10", apkUrl = "fixture/1.0.10"), "yeobaek-1.0.10-old.apk")
        val memory = Memory(UpdateRecord(latest, 1000, 1000, old))
        val downloads = Downloads()
        val controller = UpdateController("1.0.10", object: ReleaseSource { override suspend fun latest() = latest }, memory, downloads, scope) { 2000 }
        runBlocking { controller.refreshDownload() }
        ui.setContent { YeobaekTheme(com.poyal.yeobaek.data.AppTheme.LIGHT) { UpdatesScreen(controller, {}) } }
        ui.onNodeWithText("현재 버전 1.0.10").assertIsDisplayed()
        ui.onNodeWithText("1.0.11 APK 다운로드").performScrollTo().assertIsEnabled()
        ui.onNodeWithText("1.0.11 다시 다운로드").assertDoesNotExist()
        ui.onNodeWithText("1.0.11 업데이트 설치").assertDoesNotExist()
        ui.onNodeWithText("yeobaek-1.0.10-old.apk", substring = true).assertDoesNotExist()
        ui.onNodeWithText("1.0.11 APK 다운로드").performClick()
        ui.waitUntil(10000) { controller.state.value.downloadId == 1L }
        ui.onNodeWithText("1.0.11 APK 다운로드").assertIsNotEnabled()
        ui.onNodeWithText("다운로드 취소").performScrollTo().performClick()
        ui.waitUntil(10000) { controller.state.value.transfer == TransferStatus.CANCELLED }
        ui.onNodeWithText("1.0.11 다시 다운로드").performScrollTo().performClick()
        ui.waitUntil(10000) { downloads.enqueues == 2 }
        downloads.status = TransferStatus.READY
        runBlocking { controller.refreshDownload() }
        ui.onNodeWithText("1.0.11 업데이트 설치").performScrollTo().assertIsEnabled()
        ui.onNodeWithText("저장 파일: new-2.apk").performScrollTo().assertIsDisplayed()
    }
    @Test fun alreadyInstalledReleaseHasNoTransferOrInstallControls() {
        val download = DownloadRecord(42, latest, "old.apk")
        val controller = UpdateController(latest.version, object: ReleaseSource { override suspend fun latest() = latest },
            Memory(UpdateRecord(latest, 1000, 1000, download)), Downloads(), scope) { 2000 }
        runBlocking { controller.refreshDownload() }
        ui.setContent { YeobaekTheme(com.poyal.yeobaek.data.AppTheme.LIGHT) { UpdatesScreen(controller, {}) } }
        ui.onNodeWithText("최신 버전을 사용하고 있어요.").assertIsDisplayed()
        ui.onNodeWithText("1.0.11 APK 다운로드").assertDoesNotExist()
        ui.onNodeWithText("1.0.11 업데이트 설치").assertDoesNotExist()
        ui.onNodeWithText("저장 파일:", substring = true).assertDoesNotExist()
    }
}
