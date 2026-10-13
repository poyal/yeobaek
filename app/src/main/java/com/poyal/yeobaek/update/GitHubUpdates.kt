package com.poyal.yeobaek.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

interface ReleaseSource { suspend fun latest(): ReleaseInfo? }

class GitHubUpdates : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val json = read(UPDATE_FEED, 1024 * 1024, allowMissing = true) ?: return@withContext null
        parseRelease(json).also { verifyChecksums(it, read(it.checksumsUrl, 16 * 1024)!!) }
    }
    private fun read(address: String, limit: Int, allowMissing: Boolean = false): String? {
        var uri = URI(address)
        val deadline = System.nanoTime() + 15_000_000_000L
        repeat(5) {
            require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "공식 다운로드 주소가 아니에요." }
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            require(remaining > 0) { "확인 시간이 초과됐어요. 다시 시도해 주세요." }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = minOf(remaining, 8000)
                connection.readTimeout = minOf(remaining, 8000)
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Yeobaek-UpdateChecker")
                connection.setRequestProperty("Accept", if (uri.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(connection.getHeaderField("Location") ?: error("다운로드 주소를 확인할 수 없어요."))
                } else {
                    if (code == 404 && allowMissing) return null
                    require(code != 403 && code != 429) { "GitHub 요청 제한에 도달했어요. 잠시 후 다시 확인해 주세요." }
                    require(code == 200) { "GitHub에 연결하지 못했어요. 잠시 후 다시 확인해 주세요." }
                    return connection.inputStream.use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            require(System.nanoTime() < deadline) { "확인 시간이 초과됐어요." }
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(bytes.size() + n <= limit) { "업데이트 응답이 너무 커요." }
                            bytes.write(buffer, 0, n)
                        }
                        bytes.toString("UTF-8")
                    }
                }
            } finally { connection.disconnect() }
        }
        error("다운로드 주소를 확인할 수 없어요.")
    }
}
