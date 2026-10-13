package com.poyal.yeobaek.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface UpdatePersistence {
    suspend fun read(): UpdateRecord
    suspend fun write(record: UpdateRecord)
}
data class DownloadProgress(val status: TransferStatus, val bytes: Long = 0, val total: Long = 0, val message: String = "")
interface UpdateDownloads {
    suspend fun enqueue(release: ReleaseInfo): DownloadRecord
    suspend fun progress(record: DownloadRecord): DownloadProgress
    suspend fun verify(record: DownloadRecord)
    suspend fun installationCopy(record: DownloadRecord): String
    suspend fun cancel(record: DownloadRecord)
}

/** Application-owned: a rotation/background round trip cannot create a second startup prompt. */
class UpdateController(
    val installedVersion: String,
    private val source: ReleaseSource,
    private val persistence: UpdatePersistence,
    private val downloads: UpdateDownloads,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private val initLock = Mutex()
    private val recordLock = Mutex()
    private val checkLock = Mutex()
    private val downloadLock = Mutex()
    private var initialized = false
    private var record = UpdateRecord()
    private var started = false
    private var verifiedId: Long? = null
    private var failedId: Long? = null

    private suspend fun initialize() = initLock.withLock {
        if (!initialized) {
            record = persistence.read()
            mutableState.update { it.copy(release = record.release, checkedAt = record.checkedAt,
                check = record.release?.let(::status) ?: if (record.checkedAt > 0) CheckStatus.NO_RELEASE else CheckStatus.IDLE,
                message = record.release?.let(::versionMessage) ?: if (record.checkedAt > 0) "아직 공개된 정식 릴리즈가 없어요." else it.message) }
            initialized = true
        }
    }
    private suspend fun save(change: (UpdateRecord) -> UpdateRecord, publish: () -> Unit = {}) = recordLock.withLock {
        val next = change(record)
        persistence.write(next)
        record = next
        publish()
    }
    private fun status(release: ReleaseInfo) = if (compareVersions(release.version, installedVersion) > 0) CheckStatus.AVAILABLE else CheckStatus.CURRENT
    private fun versionMessage(release: ReleaseInfo) = when {
        compareVersions(release.version, installedVersion) > 0 -> "여백 ${release.version} 업데이트가 있어요."
        compareVersions(release.version, installedVersion) == 0 -> "최신 버전을 사용하고 있어요."
        else -> "현재 설치 버전이 최신 공개 버전보다 높아요."
    }
    private fun UpdateState.withoutTransfer() = copy(transfer = TransferStatus.NONE,
        transferRelease = null, downloadId = null, downloaded = 0, total = 0,
        fileName = null, transferMessage = "")
    private fun isTarget(release: ReleaseInfo) = state.value.release == release && status(release) == CheckStatus.AVAILABLE
    // Bind asynchronous results to the full release identity and the download ID.
    private fun updateTransfer(release: ReleaseInfo, id: Long?, change: (UpdateState) -> UpdateState) {
        mutableState.update {
            if (it.release == release && status(release) == CheckStatus.AVAILABLE &&
                it.transferRelease == release && it.downloadId == id) change(it) else it
        }
    }
    private fun selectDownload(download: DownloadRecord): Boolean {
        if (!isTarget(download.release) || record.download != download) return false
        mutableState.update {
            if (it.release == download.release) it.copy(transferRelease = download.release, downloadId = download.id) else it
        }
        return true
    }
    fun startSession(automatic: Boolean = true) {
        if (started) return
        started = true
        scope.launch {
            try {
                initialize()
                if (automatic) {
                    if (autoCheckDue(clock(), record.attemptedAt)) check()
                    mutableState.update { it.copy(prompt = it.release?.let { r -> status(r) == CheckStatus.AVAILABLE } == true) }
                }
                refreshDownload()
            } catch (e: Exception) { checkFailure(e) }
        }
    }
    fun dismissPrompt() { mutableState.update { it.copy(prompt = false) } }
    fun checkNow() { scope.launch { check() } }
    suspend fun check() {
        if (!checkLock.tryLock()) return
        try {
            initialize()
            if (state.value.check != CheckStatus.IDLE && record.attemptedAt != 0L && clock() - record.attemptedAt in 0..4999) return
            save({ it.copy(attemptedAt = clock()) })
            mutableState.update { it.copy(check = CheckStatus.CHECKING, message = "새 버전을 확인하고 있어요.") }
            val release = source.latest()
            save({ it.copy(release = release, checkedAt = clock()) }) {
                mutableState.update {
                    val current = if (release != it.transferRelease || release?.let(::status) != CheckStatus.AVAILABLE) it.withoutTransfer() else it
                    current.copy(release = release, checkedAt = record.checkedAt,
                        check = release?.let(::status) ?: CheckStatus.NO_RELEASE,
                        message = release?.let(::versionMessage) ?: "아직 공개된 정식 릴리즈가 없어요.",
                        prompt = it.prompt && release?.let { r -> status(r) == CheckStatus.AVAILABLE } == true)
                }
            }
        } catch (e: Exception) { checkFailure(e) }
        finally { checkLock.unlock() }
    }
    private fun checkFailure(e: Exception) {
        if (e is CancellationException) throw e
        mutableState.update { it.copy(check = CheckStatus.ERROR, message = friendly(e, "업데이트를 확인하지 못했어요. 인터넷 연결을 확인해 주세요.")) }
    }
    fun download() { scope.launch {
        if (!downloadLock.tryLock()) return@launch
        var target: ReleaseInfo? = null
        var targetId: Long? = null
        try {
            initialize()
            val release = state.value.release ?: return@launch
            if (!isTarget(release)) return@launch
            target = release
            val existing = record.download?.takeIf { it.release == release }
            targetId = existing?.id
            mutableState.update { if (it.release == release) it.withoutTransfer().copy(transferRelease = release, downloadId = targetId) else it }
            if (existing != null) {
                val progress = downloads.progress(existing)
                if (!isTarget(release)) return@launch
                if (progress.status == TransferStatus.DOWNLOADING) {
                    updateTransfer(release, existing.id) { it.copy(transfer = progress.status, downloaded = progress.bytes,
                        total = progress.total, fileName = existing.fileName, transferMessage = "이미 다운로드 중이에요.") }
                    return@launch
                }
                if (progress.status == TransferStatus.READY) {
                    try {
                        downloads.verify(existing)
                        verifiedId = existing.id; failedId = null
                        ready(existing)
                        return@launch
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep the original file and use a new filename. */ }
                }
            }
            if (!isTarget(release)) return@launch
            updateTransfer(release, targetId) { it.copy(downloadId = null, transfer = TransferStatus.DOWNLOADING,
                downloaded = 0, total = release.bytes, fileName = null, transferMessage = "다운로드를 준비하고 있어요.") }
            targetId = null
            val next = downloads.enqueue(release)
            try { save({ it.copy(download = next) }) }
            catch (e: Exception) { downloads.cancel(next); throw e }
            verifiedId = null; failedId = null
            updateTransfer(release, null) { it.copy(downloadId = next.id, fileName = next.fileName, transferMessage = "Download 폴더에 저장하고 있어요.") }
        } catch (e: Exception) { transferFailure(e, target, targetId) }
        finally { downloadLock.unlock() }
    } }
    suspend fun refreshDownload() {
        try { initialize() } catch (e: Exception) { checkFailure(e); return }
        if (!downloadLock.tryLock()) return
        var current: DownloadRecord? = null
        try {
            val download = record.download ?: return
            current = download
            if (!selectDownload(download) || failedId == download.id) return
            val progress = downloads.progress(download)
            updateTransfer(download.release, download.id) { it.copy(transfer = progress.status, downloaded = progress.bytes,
                total = progress.total, fileName = if (progress.status == TransferStatus.FAILED) null else download.fileName,
                transferMessage = progress.message) }
            if (progress.status == TransferStatus.READY && isTarget(download.release)) {
                if (verifiedId != download.id) {
                    updateTransfer(download.release, download.id) { it.copy(transfer = TransferStatus.VERIFYING, transferMessage = "APK 체크섬을 확인하고 있어요.") }
                    downloads.verify(download)
                    verifiedId = download.id
                }
                ready(download)
            }
        } catch (e: Exception) { failedId = current?.id; transferFailure(e, current?.release, current?.id) }
        finally { downloadLock.unlock() }
    }
    private fun ready(current: DownloadRecord) {
        updateTransfer(current.release, current.id) { it.copy(transfer = TransferStatus.READY, fileName = current.fileName,
            downloaded = current.release.bytes, total = current.release.bytes,
            transferMessage = "${current.release.version} APK 검증 완료 · Download 폴더에 보관돼요.") }
    }
    fun cancelDownload() {
        val requested = state.value
        scope.launch { downloadLock.withLock {
            var current: DownloadRecord? = null
            try {
                initialize()
                val download = record.download ?: return@withLock
                current = download
                // A queued cancel must never cancel a newer download or an unrelated old one.
                if (requested.release != download.release || (requested.downloadId != null && requested.downloadId != download.id) ||
                    !selectDownload(download)) return@withLock
                downloads.cancel(download)
                save({ it.copy(download = null) })
                verifiedId = null; failedId = null
                updateTransfer(download.release, download.id) { it.copy(transfer = TransferStatus.CANCELLED, fileName = null,
                    transferMessage = "다운로드를 취소했어요. 완료된 파일은 보존해요.") }
            } catch (e: Exception) { transferFailure(e, current?.release, current?.id) }
        } }
    }
    suspend fun installationCopy(): InstallationCopy? = downloadLock.withLock {
        var current: DownloadRecord? = null
        try {
            initialize()
            val download = record.download
            if (download == null || !selectDownload(download)) {
                mutableState.update { it.withoutTransfer().copy(transferRelease = it.release,
                    transferMessage = if (download == null) "APK를 먼저 다운로드해 주세요."
                        else "이전 버전의 다운로드 기록이에요. 최신 버전 APK를 다운로드해 주세요.") }
                return@withLock null
            }
            current = download
            updateTransfer(download.release, download.id) { it.copy(transfer = TransferStatus.VERIFYING, transferMessage = "설치 전에 파일을 다시 확인하고 있어요.") }
            val uri = downloads.installationCopy(download)
            val prepared = InstallationCopy(download, uri)
            if (!isInstallationCurrent(prepared)) return@withLock null
            ready(download)
            prepared
        } catch (e: Exception) { failedId = current?.id; transferFailure(e, current?.release, current?.id); null }
    }
    fun isInstallationCurrent(copy: InstallationCopy): Boolean = state.value.let {
        it.release == copy.download.release && it.transferRelease == copy.download.release &&
            it.downloadId == copy.download.id && status(copy.download.release) == CheckStatus.AVAILABLE
    }
    fun installationMessage(downloadId: Long?, text: String) {
        val release = state.value.release ?: return
        if (downloadId != null) updateTransfer(release, downloadId) { it.copy(transferMessage = text) }
    }
    private fun transferFailure(e: Exception, release: ReleaseInfo?, id: Long?) {
        if (e is CancellationException) throw e
        if (release != null) updateTransfer(release, id) { it.copy(transfer = TransferStatus.FAILED,
            transferMessage = friendly(e, "파일을 처리하지 못했어요. 저장 공간과 인터넷 연결을 확인해 주세요.")) }
    }
    private fun friendly(e: Exception, fallback: String) = e.message?.takeIf { it.any { c -> c in '가'..'힣' } } ?: fallback
}
