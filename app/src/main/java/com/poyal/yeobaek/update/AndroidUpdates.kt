package com.poyal.yeobaek.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private val Context.updateStore by preferencesDataStore("app_updates")

class AndroidUpdatePersistence(private val context: Context) : UpdatePersistence {
    private val key = stringPreferencesKey("appUpdate")
    override suspend fun read(): UpdateRecord {
        val value = context.updateStore.data.first()[key] ?: return UpdateRecord()
        return runCatching { updateJson.decodeFromString<UpdateRecord>(value) }.getOrDefault(UpdateRecord())
    }
    override suspend fun write(record: UpdateRecord) { context.updateStore.edit { it[key] = updateJson.encodeToString(record) } }
}

class AndroidUpdateDownloads(private val context: Context) : UpdateDownloads {
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    override suspend fun enqueue(release: ReleaseInfo): DownloadRecord = withContext(Dispatchers.IO) {
        // A unique suffix avoids clobbering a file the user already owns, including untracked downloads.
        val name = "yeobaek-${release.version}-${UUID.randomUUID().toString().take(8)}.apk"
        val request = DownloadManager.Request(release.apkUrl.toUri())
            .setTitle("여백 ${release.version}")
            .setDescription("다운로드 후 여백 정보에서 검증하고 설치할 수 있어요.")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(false)
        DownloadRecord(manager.enqueue(request), release, name)
    }
    override suspend fun progress(record: DownloadRecord): DownloadProgress = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(record.id)).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext DownloadProgress(TransferStatus.FAILED, message = "다운로드 파일을 찾을 수 없어요. 다시 다운로드해 주세요.")
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val bytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: record.release.bytes
            when (long(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress(TransferStatus.READY, bytes, total)
                DownloadManager.STATUS_FAILED -> DownloadProgress(TransferStatus.FAILED, bytes, total,
                    if (long(DownloadManager.COLUMN_REASON).toInt() == DownloadManager.ERROR_INSUFFICIENT_SPACE) "저장 공간이 부족해요. 공간을 확보한 뒤 다시 시도해 주세요."
                    else "다운로드하지 못했어요. 인터넷 연결과 저장 공간을 확인해 주세요.")
                DownloadManager.STATUS_PAUSED -> DownloadProgress(TransferStatus.DOWNLOADING, bytes, total, "연결을 기다리고 있어요. 연결되면 다운로드를 계속해요.")
                else -> DownloadProgress(TransferStatus.DOWNLOADING, bytes, total, "Download 폴더에 저장하고 있어요.")
            }
        }
    }
    override suspend fun verify(record: DownloadRecord) = withContext(Dispatchers.IO) {
        val uri = manager.getUriForDownloadedFile(record.id) ?: error("다운로드 파일을 찾을 수 없어요.")
        context.contentResolver.openInputStream(uri)?.use { verifyApkStream(it, null, record.release) }
            ?: error("APK를 읽을 수 없어요. 다시 다운로드해 주세요.")
    }
    override suspend fun installationCopy(record: DownloadRecord): String = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "verified-updates").apply { mkdirs() }
        val file = File(folder, "yeobaek-${record.release.version}-${record.release.sha256.take(12)}.apk")
        val temporary = File(folder, "${UUID.randomUUID()}.part")
        try {
            val uri = manager.getUriForDownloadedFile(record.id) ?: error("다운로드 파일을 찾을 수 없어요.")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> verifyApkStream(input, output, record.release) }
            } ?: error("APK를 읽을 수 없어요.")
            verifyPackage(temporary, record.release)
            // Only our private installation copy is replaced. Public Downloads is never removed here.
            check(temporary.renameTo(file)) { "설치 파일을 준비하지 못했어요. 저장 공간을 확인해 주세요." }
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file).toString()
        } finally { temporary.delete() }
    }
    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: ReleaseInfo) {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("유효한 Android 설치 파일이 아니에요.")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(candidate.packageName == context.packageName) { "현재 앱용 APK가 아니에요. 공식 릴리즈에서 설치 파일을 확인해 주세요." }
        require(candidate.versionName == release.version) { "APK 버전이 최신 확인 버전과 달라요. 업데이트를 다시 확인해 주세요." }
        require(candidate.longVersionCode > installed.longVersionCode) { "설치된 앱보다 새 빌드가 아니에요. 업데이트를 다시 확인해 주세요." }
        fun certificates(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }?.toSet().orEmpty()
        val original = certificates(installed)
        require(original.isNotEmpty() && certificates(candidate) == original) { "기존 앱과 서명이 달라요. 설치를 중단했어요." }
    }
    override suspend fun cancel(record: DownloadRecord) = withContext(Dispatchers.IO) {
        // remove() also deletes the file: never call it on a completed download.
        if (progress(record).status == TransferStatus.DOWNLOADING) {
            // Requery as close to cancellation as possible; retain an already completed file.
            if (progress(record).status == TransferStatus.DOWNLOADING) manager.remove(record.id)
        }
        Unit
    }
}

fun openExternal(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    true
} catch (_: android.content.ActivityNotFoundException) { false }
