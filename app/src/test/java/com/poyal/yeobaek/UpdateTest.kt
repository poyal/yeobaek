package com.poyal.yeobaek

import com.poyal.yeobaek.update.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val release = ReleaseInfo("1.0.4", "$RELEASES_URL/download/v1.0.4/yeobaek-1.0.4.apk", "yeobaek-1.0.4.apk", bytes.size.toLong(), hash, "$RELEASES_URL/download/v1.0.4/yeobaek-1.0.4.sha256")
    private class Memory(var value: UpdateRecord = UpdateRecord()) : UpdatePersistence {
        override suspend fun read() = value
        override suspend fun write(record: UpdateRecord) { value = record }
    }
    private class Downloads : UpdateDownloads {
        var enqueues = 0
        var verifications = 0
        var copies = 0
        var broken = false
        var cancelled = false
        var status = TransferStatus.DOWNLOADING
        var beforeEnqueue: suspend () -> Unit = {}
        var beforeProgress: suspend () -> Unit = {}
        var beforeVerify: suspend () -> Unit = {}
        var beforeCopy: suspend () -> Unit = {}
        override suspend fun enqueue(release: ReleaseInfo): DownloadRecord { beforeEnqueue(); return DownloadRecord((++enqueues).toLong(), release, "download-$enqueues.apk") }
        override suspend fun progress(record: DownloadRecord): DownloadProgress { beforeProgress(); return DownloadProgress(status, 3, 10) }
        override suspend fun verify(record: DownloadRecord) { beforeVerify(); verifications++; check(!broken) { "APK 체크섬이 일치하지 않아요." } }
        override suspend fun installationCopy(record: DownloadRecord): String { beforeCopy(); verify(record); copies++; return "content://verified" }
        override suspend fun cancel(record: DownloadRecord) { cancelled = status == TransferStatus.DOWNLOADING }
    }
    @Test fun numericVersionComparisonAndInvalidVersions() {
        assertTrue(compareVersions("v1.10.0", "1.9.9-debug") > 0)
        assertEquals(0, compareVersions("1.0.4", "v1.0.4"))
        assertTrue(compareVersions("1.0.3", "1.0.4") < 0)
        for (bad in listOf("1.2", "01.2.3", "1.2.3-beta", "1.2.3/evil", "99999999999.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { normalizedVersion(bad) }
        }
    }
    @Test fun checksumsMustMatchBothExactFilenamesAndGitHubDigest() {
        val sums = "$hash  yeobaek-1.0.4.apk\n"
        verifyChecksums(release, sums)
        for (bad in listOf(sums.replace(hash, "a".repeat(64)), sums + sums, sums.replace("yeobaek-1.0.4.apk", "../yeobaek-1.0.4.apk"), sums + "${"0".repeat(64)}  other.apk\n")) {
            assertThrows(IllegalArgumentException::class.java) { verifyChecksums(release, bad) }
        }
    }
    @Test fun verifyExactBytesAndCopyRejectsTamperingTruncationAndOversize() {
        val output = ByteArrayOutputStream()
        verifyApkStream(ByteArrayInputStream(bytes), output, release)
        assertArrayEquals(bytes, output.toByteArray())
        for (bad in listOf(bytes.dropLast(1).toByteArray(), bytes + 0, bytes.reversedArray())) {
            assertThrows(IllegalArgumentException::class.java) { verifyApkStream(ByteArrayInputStream(bad), null, release) }
        }
    }
    private fun json(digest: String = "sha256:$hash", state: String = "uploaded", prefix: String = RELEASES_URL) = """
        {"tag_name":"v1.0.4","draft":false,"prerelease":false,"assets":[
        {"name":"yeobaek-1.0.4.apk","state":"$state","size":${bytes.size},"digest":"$digest","browser_download_url":"$prefix/download/v1.0.4/yeobaek-1.0.4.apk"},
        {"name":"yeobaek-1.0.4.sha256","state":"uploaded","size":200,"browser_download_url":"$prefix/download/v1.0.4/yeobaek-1.0.4.sha256"}]}
    """.trimIndent()
    @Test fun onlyStableCompleteOfficialAssetsAreAccepted() {
        assertEquals(release, parseRelease(json()))
        for (bad in listOf(json(digest = ""), json(state = "new"), json(prefix = "https://evil.invalid"), json().replace("\"draft\":false", "\"draft\":true"), json().replace("\"prerelease\":false", "\"prerelease\":true"), json().replace("yeobaek-1.0.4.sha256", "other.txt"))) {
            assertThrows(IllegalArgumentException::class.java) { parseRelease(bad) }
        }
    }
    @Test fun automaticCheckIsDailyManualBypassesAndClockRollbackRecovers() {
        assertTrue(autoCheckDue(1, 0))
        assertFalse(autoCheckDue(2000, 1000))
        assertTrue(autoCheckDue(1000 + AUTO_CHECK_INTERVAL, 1000))
        assertTrue(autoCheckDue(500, 1000))
    }
    @Test fun noReleaseResultSurvivesRestartAndManualCheckFindsNewRelease() = runTest {
        val memory = Memory(UpdateRecord(checkedAt = 1000, attemptedAt = 1000))
        var requests = 0
        val controller = UpdateController("0.1.0", object : ReleaseSource {
            override suspend fun latest(): ReleaseInfo { requests++; return release }
        }, memory, Downloads(), backgroundScope) { 7000 }
        controller.startSession(); runCurrent()
        assertEquals(CheckStatus.NO_RELEASE, controller.state.value.check)
        assertFalse(controller.state.value.prompt)
        assertEquals(0, requests)
        controller.check()
        assertEquals(CheckStatus.AVAILABLE, controller.state.value.check)
        assertEquals(1, requests)
    }
    @Test fun startupPromptOncePerProcessButReturnsOnNextProcessFromCache() = runTest {
        val memory = Memory(UpdateRecord(release, 1000, 1000))
        var requests = 0
        val source = object : ReleaseSource { override suspend fun latest(): ReleaseInfo { requests++; return release } }
        fun controller(version: String = "1.0.3") = UpdateController(version, source, memory, Downloads(), backgroundScope) { 2000 }
        val first = controller(); first.startSession(); runCurrent()
        assertTrue(first.state.value.prompt); assertEquals(0, requests)
        first.dismissPrompt(); first.startSession(); runCurrent(); assertFalse(first.state.value.prompt)
        val next = controller(); next.startSession(); runCurrent(); assertTrue(next.state.value.prompt)
        val installed = controller("1.0.4"); installed.startSession(); runCurrent(); assertFalse(installed.state.value.prompt)
    }
    @Test fun failureKeepsLastSuccessWithoutClaimingCurrentAndManualRetryWorks() = runTest {
        val memory = Memory(UpdateRecord(release, 1000, 1000))
        var failed = true
        var now = AUTO_CHECK_INTERVAL + 2000
        var requests = 0
        val source = object : ReleaseSource { override suspend fun latest(): ReleaseInfo { requests++; if (failed) error("offline"); return release } }
        val controller = UpdateController("1.0.3", source, memory, Downloads(), backgroundScope) { now }
        controller.startSession(); runCurrent()
        assertEquals(CheckStatus.ERROR, controller.state.value.check)
        assertEquals(1000, controller.state.value.checkedAt)
        assertEquals(release, controller.state.value.release)
        assertTrue(controller.state.value.prompt)
        controller.check(); assertEquals(1, requests)
        now += 6000; failed = false; controller.check()
        assertEquals(CheckStatus.AVAILABLE, controller.state.value.check); assertEquals(2, requests)
    }
    @Test fun downloadsResumeReuseVerifiedBytesAndRecheckBeforeEveryInstall() = runTest {
        val memory = Memory(UpdateRecord(release, 1000, 1000))
        val downloads = Downloads()
        val source = object : ReleaseSource { override suspend fun latest() = release }
        fun controller() = UpdateController("1.0.3", source, memory, downloads, backgroundScope) { 2000 }
        val first = controller(); first.startSession(); runCurrent()
        first.download(); runCurrent(); first.download(); runCurrent()
        assertEquals(1, downloads.enqueues)
        downloads.status = TransferStatus.READY
        val resumed = controller(); resumed.startSession(); runCurrent()
        assertEquals(TransferStatus.READY, resumed.state.value.transfer)
        resumed.download(); runCurrent(); assertEquals(1, downloads.enqueues)
        assertEquals("content://verified", resumed.installationCopy()?.uri)
        downloads.broken = true
        assertNull(resumed.installationCopy()); assertEquals(TransferStatus.FAILED, resumed.state.value.transfer)
        assertFalse(downloads.cancelled)
        resumed.download(); runCurrent(); assertEquals(2, downloads.enqueues)
    }
    @Test fun cancellationNeverDeletesCompletedDownload() = runTest {
        val downloads = Downloads().apply { status = TransferStatus.READY }
        val memory = Memory(UpdateRecord(release, 1000, 1000, DownloadRecord(1, release, "existing.apk")))
        val controller = UpdateController("1.0.3", object : ReleaseSource { override suspend fun latest() = release }, memory, downloads, backgroundScope) { 2000 }
        controller.startSession(false); runCurrent()
        controller.cancelDownload(); runCurrent(); assertFalse(downloads.cancelled)
        assertNull(memory.value.download)
    }

    @Test fun installedOldDownloadNeverBecomesTheNewReleaseTransferEvenOfflineOrAfterRestart() = runTest {
        val latest = release.copy(version = "1.0.11")
        val old = DownloadRecord(42, release.copy(version = "1.0.10"), "yeobaek-1.0.10-old.apk")
        for (transfer in listOf(TransferStatus.READY, TransferStatus.DOWNLOADING, TransferStatus.FAILED, TransferStatus.CANCELLED)) {
            val memory = Memory(UpdateRecord(latest, 1000, 1000, old))
            val downloads = Downloads().apply { status = transfer }
            val source = object : ReleaseSource { override suspend fun latest(): ReleaseInfo = error("offline") }
            fun controller() = UpdateController("1.0.10", source, memory, downloads, backgroundScope) { 10000 }
            val first = controller(); first.startSession(false); runCurrent()
            assertEquals(TransferStatus.NONE, first.state.value.transfer)
            assertNull(first.state.value.fileName)
            assertEquals(0, downloads.verifications)
            first.check()
            assertEquals(CheckStatus.ERROR, first.state.value.check)
            assertEquals(TransferStatus.NONE, first.state.value.transfer)
            assertNull(first.installationCopy())
            assertTrue(first.state.value.transferMessage.contains("이전 버전의 다운로드 기록"))
            assertEquals(0, downloads.copies)
            assertEquals(old, memory.value.download)
            val restarted = controller(); restarted.startSession(false); runCurrent()
            assertEquals(TransferStatus.NONE, restarted.state.value.transfer)
            restarted.download(); runCurrent()
            assertEquals(1, downloads.enqueues)
            assertEquals(latest, memory.value.download!!.release)
            assertFalse(downloads.cancelled)
        }
    }
    @Test fun releaseIdentityIncludesUrlHashSizeAndInstalledReleaseHidesAllTransfers() = runTest {
        val old = DownloadRecord(42, release, "old.apk")
        for (latest in listOf(release.copy(apkUrl = release.apkUrl + "?changed"),
            release.copy(sha256 = "0".repeat(64)), release.copy(bytes = release.bytes + 1))) {
            val downloads = Downloads().apply { status = TransferStatus.READY }
            val controller = UpdateController("1.0.3", object : ReleaseSource { override suspend fun latest() = latest },
                Memory(UpdateRecord(latest, 1000, 1000, old)), downloads, backgroundScope) { 2000 }
            controller.startSession(false); runCurrent()
            assertEquals(TransferStatus.NONE, controller.state.value.transfer)
            assertNull(controller.state.value.fileName)
            controller.download(); runCurrent(); assertEquals(1, downloads.enqueues)
        }
        val downloads = Downloads().apply { status = TransferStatus.READY }
        val current = UpdateController(release.version, object : ReleaseSource { override suspend fun latest() = release },
            Memory(UpdateRecord(release, 1000, 1000, old)), downloads, backgroundScope) { 2000 }
        current.startSession(false); runCurrent(); current.download(); runCurrent()
        assertEquals(CheckStatus.CURRENT, current.state.value.check)
        assertEquals(TransferStatus.NONE, current.state.value.transfer)
        assertNull(current.state.value.fileName)
        assertEquals(0, downloads.enqueues)
    }
    @Test fun checkingANewerReleaseClearsOldProgressFailureFilenameAndInstallCallbacks() = runTest {
        var latest: ReleaseInfo? = release
        var now = 2000L
        val downloads = Downloads().apply { status = TransferStatus.READY }
        val memory = Memory(UpdateRecord(release, 1000, 1000, DownloadRecord(42, release, "old.apk")))
        val controller = UpdateController("1.0.3", object : ReleaseSource { override suspend fun latest() = latest }, memory, downloads, backgroundScope) { now }
        controller.startSession(false); runCurrent()
        val prepared = controller.installationCopy()!!
        assertTrue(controller.isInstallationCurrent(prepared))
        downloads.broken = true; controller.installationCopy()
        assertEquals(TransferStatus.FAILED, controller.state.value.transfer)
        latest = release.copy(version = "1.0.5"); now += 6000; controller.check()
        assertFalse(controller.isInstallationCurrent(prepared))
        controller.installationMessage(prepared.download.id, "old installer returned")
        controller.refreshDownload()
        assertEquals(TransferStatus.NONE, controller.state.value.transfer)
        assertNull(controller.state.value.fileName)
        assertEquals("", controller.state.value.transferMessage)
        assertNotNull(memory.value.download)
        latest = null; now += 6000; controller.check()
        assertNull(controller.state.value.release)
        assertEquals(TransferStatus.NONE, controller.state.value.transfer)
    }
    @Test fun lateProgressVerificationEnqueueAndInstallationResultsCannotOverwriteNewTarget() = runTest {
        for (operation in listOf("progress", "verify", "enqueue", "copy")) for (fail in listOf(false, true)) {
            var latest = release
            val barrier = CompletableDeferred<Unit>()
            val waiting: suspend () -> Unit = { barrier.await(); if (fail) error("이전 파일 실패") }
            val downloads = Downloads().apply { status = TransferStatus.READY }
            val old = if (operation == "enqueue") null else DownloadRecord(42, release, "old.apk")
            val memory = Memory(UpdateRecord(release, 1000, 1000, old))
            val controller = UpdateController("1.0.3", object : ReleaseSource { override suspend fun latest() = latest }, memory, downloads, backgroundScope) { 10000 }
            // Initialize before installing the suspension so this isn't a startup-check test.
            controller.startSession(false); runCurrent()
            when (operation) {
                "progress" -> downloads.beforeProgress = waiting
                "verify" -> downloads.beforeVerify = waiting
                "enqueue" -> downloads.beforeEnqueue = waiting
                "copy" -> downloads.beforeCopy = waiting
            }
            val result = backgroundScope.async {
                when (operation) {
                    "progress" -> { controller.refreshDownload(); null }
                    "verify", "enqueue" -> { controller.download(); null }
                    else -> controller.installationCopy()
                }
            }
            runCurrent()
            latest = release.copy(version = "1.0.5")
            controller.check()
            barrier.complete(Unit); runCurrent()
            assertNull(result.await())
            assertEquals(latest, controller.state.value.release)
            assertEquals(TransferStatus.NONE, controller.state.value.transfer)
            assertNull(controller.state.value.fileName)
            assertEquals("", controller.state.value.transferMessage)
            assertFalse(downloads.cancelled)
        }
    }
    @Test fun retryAndCancelAreOnlyForTheCurrentAttemptAndOldCallbacksAreIgnored() = runTest {
        val downloads = Downloads()
        val controller = UpdateController("1.0.3", object : ReleaseSource { override suspend fun latest() = release },
            Memory(UpdateRecord(release, 1000, 1000)), downloads, backgroundScope) { 2000 }
        controller.startSession(false); runCurrent()
        assertEquals(TransferStatus.NONE, controller.state.value.transfer)
        controller.download(); runCurrent()
        val oldId = controller.state.value.downloadId
        controller.cancelDownload(); runCurrent()
        assertTrue(downloads.cancelled)
        assertEquals(TransferStatus.CANCELLED, controller.state.value.transfer)
        controller.download(); runCurrent()
        controller.installationMessage(oldId, "stale callback")
        assertFalse(controller.state.value.transferMessage.contains("stale"))
        downloads.status = TransferStatus.FAILED
        controller.refreshDownload()
        assertEquals(TransferStatus.FAILED, controller.state.value.transfer)
        assertNull(controller.state.value.fileName)
    }
}
