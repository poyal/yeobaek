package com.poyal.yeobaek.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

const val RELEASES_URL = "https://github.com/poyal/yeobaek/releases"
const val UPDATE_FEED = "https://api.github.com/repos/poyal/yeobaek/releases/latest"
const val AUTO_CHECK_INTERVAL = 24 * 60 * 60 * 1000L
val updateJson = Json { ignoreUnknownKeys = true }

@Serializable data class ReleaseInfo(
    val version: String, val apkUrl: String, val apkName: String,
    val bytes: Long, val sha256: String, val checksumsUrl: String,
) { val pageUrl get() = "$RELEASES_URL/tag/v$version" }

@Serializable data class DownloadRecord(val id: Long, val release: ReleaseInfo, val fileName: String)
data class InstallationCopy(val download: DownloadRecord, val uri: String)
@Serializable data class UpdateRecord(
    val release: ReleaseInfo? = null, val checkedAt: Long = 0,
    val attemptedAt: Long = 0, val download: DownloadRecord? = null,
)

enum class CheckStatus { IDLE, CHECKING, CURRENT, AVAILABLE, NO_RELEASE, ERROR }
enum class TransferStatus { NONE, DOWNLOADING, VERIFYING, READY, FAILED, CANCELLED }
data class UpdateState(
    val check: CheckStatus = CheckStatus.IDLE,
    val release: ReleaseInfo? = null, val checkedAt: Long = 0,
    val message: String = "GitHub에서 새 버전을 확인할 수 있어요.",
    val transfer: TransferStatus = TransferStatus.NONE,
    val transferRelease: ReleaseInfo? = null, val downloadId: Long? = null,
    val downloaded: Long = 0, val total: Long = 0,
    val fileName: String? = null, val transferMessage: String = "",
    val prompt: Boolean = false,
)

fun normalizedVersion(value: String): String = value.removeSuffix("-debug").removePrefix("v").also {
    require(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})").matches(it)) { "공개 버전 정보를 확인할 수 없어요." }
}
fun compareVersions(left: String, right: String): Int {
    val a = normalizedVersion(left).split('.').map(String::toLong)
    val b = normalizedVersion(right).split('.').map(String::toLong)
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
}
fun autoCheckDue(now: Long, lastAttempt: Long) = lastAttempt == 0L || now < lastAttempt || now - lastAttempt >= AUTO_CHECK_INTERVAL

/** Only this repository's exact, fully uploaded APK and checksums are accepted. */
fun parseRelease(raw: String): ReleaseInfo {
    val obj = updateJson.parseToJsonElement(raw).jsonObject
    require(obj["draft"]?.jsonPrimitive?.boolean == false && obj["prerelease"]?.jsonPrimitive?.boolean == false) { "정식 공개 버전이 아니에요." }
    val tag = obj.getValue("tag_name").jsonPrimitive.content
    val version = normalizedVersion(tag)
    require(tag == "v$version") { "공개 버전 형식을 확인할 수 없어요." }
    val assets = obj.getValue("assets").jsonArray.map { it.jsonObject }
    fun asset(name: String): JsonObject {
        val matches = assets.filter { it["name"]?.jsonPrimitive?.content == name }
        require(matches.size == 1) { "설치 파일 또는 체크섬이 아직 준비되지 않았어요." }
        return matches.single().also {
            require(it["state"]?.jsonPrimitive?.content == "uploaded" && it.getValue("size").jsonPrimitive.long > 0) { "업로드가 완료되지 않았어요." }
            require(it["browser_download_url"]?.jsonPrimitive?.content == "$RELEASES_URL/download/$tag/$name") { "공식 다운로드 주소가 아니에요." }
        }
    }
    val name = "yeobaek-$version.apk"
    val apk = asset(name)
    val sums = asset("yeobaek-$version.sha256")
    val digest = apk["digest"]?.jsonPrimitive?.content.orEmpty()
    require(Regex("sha256:[a-f0-9]{64}").matches(digest)) { "설치 파일의 체크섬이 아직 준비되지 않았어요." }
    return ReleaseInfo(version, apk.getValue("browser_download_url").jsonPrimitive.content, name,
        apk.getValue("size").jsonPrimitive.long, digest.removePrefix("sha256" + ":"), sums.getValue("browser_download_url").jsonPrimitive.content)
}
fun verifyChecksums(release: ReleaseInfo, text: String) {
    val expectedNames = setOf(release.apkName)
    val entries = text.lineSequence().filter(String::isNotBlank).map { line ->
        val match = Regex("([a-fA-F0-9]{64}) [ *]([^/\\\\\\s]+)").matchEntire(line)
        requireNotNull(match) { "체크섬 파일 형식이 올바르지 않아요." }
        match.groupValues[2] to match.groupValues[1].lowercase()
    }.toList()
    require(entries.size == 1 && entries.map { it.first }.toSet() == expectedNames) { "체크섬의 파일 목록이 일치하지 않아요." }
    require(entries.toMap()[release.apkName] == release.sha256) { "GitHub 파일 해시와 체크섬이 일치하지 않아요." }
}

/** Hash exactly the bytes copied to the private installation snapshot, never the public path twice. */
fun verifyApkStream(input: InputStream, output: OutputStream?, release: ReleaseInfo) {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        count += n
        require(count <= release.bytes) { "APK 크기가 공개 파일과 달라요. 다시 다운로드해 주세요." }
        hash.update(buffer, 0, n)
        output?.write(buffer, 0, n)
    }
    val digest = hash.digest().joinToString("") { "%02x".format(it) }
    require(count == release.bytes && digest == release.sha256) { "APK 체크섬이 일치하지 않아요. 다시 다운로드해 주세요." }
}
