@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek.epub

import android.content.Context
import android.util.Xml
import com.poyal.yeobaek.data.Book
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.*
import org.readium.r2.shared.util.*
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.*
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class EpubFailure(message: String) : IOException(message)

data class OpenEpub(val book: Book, val document: EpubDocument, val initial: Locator?, val token: String = UUID.randomUUID().toString(), val initialCompleted: Boolean = false)

class EpubDocument(val file: File, val publication: Publication, positions: List<Locator> = emptyList()) : java.io.Closeable {
    val fixed = publication.metadata.presentation.layout == EpubLayout.FIXED
    val outline = epubOutline(publication.tableOfContents.ifEmpty { publication.readingOrder })
    val closed = AtomicBoolean(false)
    private val starts = positions.groupBy { it.href.toString() }.mapValues { it.value.first().locations.totalProgression }
    fun resourceIndex(locator: Locator?) = publication.readingOrder.indexOfFirst { locator != null && sameEpubResource(it.url(), locator.href) }.coerceAtLeast(0)
    fun chapterTitle(index: Int) = epubChapterTitle(publication.readingOrder, outline, index)
    fun progress(locator: Locator?): Double {
        if (locator == null) return 0.0
        if (fixed) return if (publication.readingOrder.size <= 1) 0.0 else resourceIndex(locator).toDouble() / (publication.readingOrder.size - 1)
        val index = resourceIndex(locator)
        val count = publication.readingOrder.size
        val start = starts[publication.readingOrder[index].url().toString()] ?: index.toDouble() / count
        val end = publication.readingOrder.getOrNull(index + 1)?.let { starts[it.url().toString()] ?: (index + 1.0) / count } ?: 1.0
        return (start + (end - start) * (locator.locations.progression ?: 0.0)).coerceIn(0.0, 1.0)
    }
    override fun close() { if (closed.compareAndSet(false, true)) publication.close() }
}

/** The cache contains original bytes. The source URI is still required when reopening a book. */
class EpubStore(cache: File) {
    private val directory = File(cache, "epub").apply { mkdirs() }
    suspend fun prepare(open: () -> InputStream): File = withContext(Dispatchers.IO) {
        val staging = File(directory, "${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input -> staging.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            val target = File(directory, digest.digest().joinToString("") { "%02x".format(it) } + ".epub")
            if (target.exists()) staging.delete() else if (!staging.renameTo(target)) throw EpubFailure("EPUB 읽기 캐시를 만들지 못했습니다.")
            target.setLastModified(System.currentTimeMillis())
            target
        } finally { staging.delete() }
    }
    fun clean() { directory.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() } }
    fun clear(pinned: Set<File>) { directory.listFiles()?.filter { it !in pinned }?.forEach { it.delete() } }
    fun trim(pinned: Set<File>) {
        var used = pinned.sumOf { it.length() }
        directory.listFiles()?.filter { it.extension == "epub" && it !in pinned }?.sortedByDescending { it.lastModified() }?.forEach {
            if (used + it.length() <= 512L * 1024 * 1024) used += it.length() else it.delete()
        }
    }
}

class EpubEngine(context: Context) {
    private val offline = object : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> = Try.failure(HttpError.IO(IOException("Local EPUB only")))
    }
    private val retriever = AssetRetriever(context.contentResolver, offline)
    private val opener = PublicationOpener(EpubParser(), onCreatePublication = {
        val html = (manifest.readingOrder + manifest.resources).filter { it.mediaType?.toString() in listOf("application/xhtml+xml", "text/html") }.map { it.url() }.toSet()
        container = TransformingContainer(container) { url, resource ->
            if (url !in html) resource else TransformingResource(resource) { bytes -> Try.success(localEpubHtml(bytes)) }
        }
    })
    suspend fun open(file: File): EpubDocument = withContext(Dispatchers.IO) {
        inspectPackage(file)
        val asset = retriever.retrieve(file.toUrl()).getOrElse { throw EpubFailure("EPUB 형식이 아니거나 파일이 손상되었습니다.") }
        var publication: Publication? = null
        try {
            val parsed = opener.open(asset, allowUserInteraction = false).getOrElse { throw EpubFailure("EPUB의 목차나 본문 정보를 읽지 못했습니다.") }
            publication = parsed
            if (parsed.isRestricted) throw EpubFailure("DRM으로 보호된 EPUB은 열 수 없습니다. DRM 없는 EPUB 파일을 선택해 주세요.")
            if (parsed.readingOrder.isEmpty()) throw EpubFailure("읽을 본문이 없는 EPUB입니다.")
            if (parsed.readingOrder.any { it.url().toString().contains(":") }) throw EpubFailure("외부 웹 본문에 의존하는 EPUB은 지원하지 않습니다.")
            val positions = parsed.positions() // Prepare off the UI thread before constructing the navigator.
            ensureActive()
            EpubDocument(file, parsed, positions)
        } catch (e: Throwable) {
            if (publication != null) publication.close() else asset.close()
            throw e
        }
    }
}

/** Font obfuscation is part of EPUB packaging, and does not imply a DRM-protected book. */
internal fun inspectPackage(file: File) {
    try {
        ZipFile(file).use { zip ->
            if (zip.getEntry("META-INF/container.xml") == null) throw EpubFailure("EPUB 형식이 아닌 파일입니다.")
            if (zip.getEntry("META-INF/license.lcpl") != null || zip.getEntry("META-INF/rights.xml") != null) throw EpubFailure("DRM으로 보호된 EPUB은 열 수 없습니다.")
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > 100_000 || entry.size > 256L * 1024 * 1024) throw EpubFailure("EPUB 내부 자료가 너무 큽니다.")
                if (entry.name.startsWith('/') || entry.name.split('/', '\\').contains("..")) throw EpubFailure("EPUB 내부 경로가 올바르지 않습니다.")
            }
            zip.getEntry("META-INF/encryption.xml")?.let { entry ->
                if (entry.size > 1024 * 1024) throw EpubFailure("EPUB 보호 정보를 읽지 못했습니다.")
                zip.getInputStream(entry).use { input ->
                    val parser = Xml.newPullParser().apply { setInput(input, null) }
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "EncryptionMethod") {
                            val algorithm = parser.getAttributeValue(null, "Algorithm")
                            if (algorithm !in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) throw EpubFailure("DRM으로 보호된 EPUB은 열 수 없습니다.")
                        }
                    }
                }
            }
        }
    } catch (e: EpubFailure) { throw e }
    catch (e: Exception) { throw EpubFailure("EPUB 파일이 손상되었거나 압축 정보를 읽을 수 없습니다.") }
}

/** Keep publication markup/styles; permit only bundled assets and Readium's own scripts. */
internal fun localEpubHtml(bytes: ByteArray): ByteArray {
    val doc = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
    doc.outputSettings().charset(Charsets.UTF_8).prettyPrint(false)
    doc.select("script, iframe, object, embed, base, meta[http-equiv=refresh], meta[http-equiv=Content-Security-Policy]").remove()
    doc.allElements.forEach { element ->
        element.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { element.removeAttr(it.key) }
    }
    doc.childNodes().filterIsInstance<org.jsoup.nodes.XmlDeclaration>().forEach { if (it.hasAttr("encoding")) it.attr("encoding", "utf-8") }
    val head = doc.selectFirst("head") ?: doc.selectFirst("html")?.prependElement("head")
    head?.prependElement("meta")?.attr("http-equiv", "Content-Security-Policy")?.attr("content",
        "default-src 'none'; script-src https://readium; style-src https://readium 'unsafe-inline'; img-src https://readium data: blob:; font-src https://readium data:; connect-src 'none'; base-uri 'none'; form-action 'none'")
    return doc.outerHtml().toByteArray(Charsets.UTF_8)
}
