package com.poyal.yeobaek

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyal.yeobaek.update.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Run with scripts/test-updates.ps1: loopback server + tiny, higher-version debug APK, never GitHub. */
@RunWith(AndroidJUnit4::class)
class AndroidUpdateDownloadTest {
    @Test fun publicDownloadPrivateVerifiedCopyInstallerAndRetention() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<YeobaekApplication>()
        val bytes = instrumentation.context.assets.open("update-fixture.apk").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0)
        val serving = thread(isDaemon = true) {
            while (!server.isClosed) {
                try { server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } } catch (_: Exception) { if (server.isClosed) break }
            }
        }
        try {
            val downloads = AndroidUpdateDownloads(app)
            val release = ReleaseInfo("99.0.0", "http://127.0.0.1:${server.localPort}/update.apk", "yeobaek-99.0.0.apk", bytes.size.toLong(), hash, "fixture")
            val previous = downloads.enqueue(release.copy(version = "1.0.10", apkName = "yeobaek-1.0.10.apk"))
            withTimeout(45000) { while (downloads.progress(previous).status == TransferStatus.DOWNLOADING) delay(250) }
            assertEquals(TransferStatus.READY, downloads.progress(previous).status)
            val memory = object: UpdatePersistence {
                @Volatile var record = UpdateRecord(release, 1000, 1000, previous)
                override suspend fun read() = record
                override suspend fun write(record: UpdateRecord) { this.record = record }
            }
            val controller = UpdateController(BuildConfig.VERSION_NAME, object: ReleaseSource { override suspend fun latest() = release },
                memory, downloads, this) { 2000 }
            controller.refreshDownload()
            assertEquals(TransferStatus.NONE, controller.state.value.transfer)
            assertNull(controller.state.value.fileName)
            assertNull(controller.installationCopy())
            controller.download()
            withTimeout(45000) { while (controller.state.value.downloadId == null) delay(100) }
            val record = memory.record.download!!
            assertNotEquals(previous.id, record.id)
            withTimeout(45000) {
                while (controller.state.value.transfer != TransferStatus.READY) {
                    controller.refreshDownload()
                    check(controller.state.value.transfer != TransferStatus.FAILED) { controller.state.value.transferMessage }
                    delay(250)
                }
            }
            assertEquals(TransferStatus.READY, downloads.progress(record).status)
            val prepared = controller.installationCopy()!!
            assertTrue(controller.isInstallationCurrent(prepared))
            val uri = Uri.parse(prepared.uri)
            assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            val manager = app.getSystemService(DownloadManager::class.java)
            val publicUri = manager.getUriForDownloadedFile(record.id)
            assertNotNull(publicUri)
            assertNotNull("Previous APK must be retained", manager.getUriForDownloadedFile(previous.id))
            // A completed download must survive cancel/retry and installer cancellation.
            downloads.cancel(record)
            assertEquals(TransferStatus.READY, downloads.progress(record).status)
            fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
            // The host grants this before instrumentation. Android 11 kills the app when
            // REQUEST_INSTALL_PACKAGES changes, including when permission is granted.
            run {
                assertTrue(app.packageManager.canRequestPackageInstalls())
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var installerVisible = false
                while (SystemClock.elapsedRealtime() < deadline && !installerVisible) {
                    val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                    installerVisible = activePackage.contains("packageinstaller") || activePackage.contains("permissioncontroller")
                    if (!installerVisible) SystemClock.sleep(100)
                }
                assertTrue("Android installer should open", installerVisible)
                val folder = java.io.File(app.filesDir, "e2e-artifacts").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(folder, "android-installer.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                shell("input keyevent KEYCODE_BACK")
            }
            downloads.verify(record)
            assertNotNull(manager.getUriForDownloadedFile(record.id))
            // Tampering in public Downloads is detected; the private installation copy stays unchanged.
            app.contentResolver.openOutputStream(publicUri, "wt")!!.use { it.write("tampered".toByteArray()) }
            var rejected = false
            try { downloads.installationCopy(record) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Modified APK must not reach installer", rejected)
            assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertNotNull("Public file retained even after validation failure", manager.getUriForDownloadedFile(record.id))
            println("Verified stale download isolation, DownloadManager, private copy, Android installer cancellation, public retention and tamper rejection: ${record.fileName}")
        } finally { server.close(); serving.join(1000) }
    }
}
