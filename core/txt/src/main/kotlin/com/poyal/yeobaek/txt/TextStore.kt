package com.poyal.yeobaek.txt

import java.io.*
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

class TextFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
data class Preparation(val phase: String, val bytes: Long)

/** Canonical UTF-8 on disk. Index entries are fixed 24-byte records, never one object per line. */
class TextStore(private val root: File) {
    companion object {
        const val MAX_SOURCE = 100L * 1024 * 1024
        const val MAX_CANONICAL = 256L * 1024 * 1024
        const val MAX_INDEX = 32L * 1024 * 1024
        const val CACHE_BUDGET = 256L * 1024 * 1024
        val charsets = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "x-windows-949", "EUC-KR")
    }
    init { root.mkdirs() }

    fun cleanAbandoned() { root.listFiles()?.filter { it.name.startsWith("preparing-") }?.forEach { it.deleteRecursively() } }

    fun prepare(
        source: () -> InputStream,
        charsetOverride: String? = null,
        keepVersions: Set<String> = emptySet(),
        progress: (Preparation) -> Unit = {},
        check: () -> Unit = {},
    ): TextDocument {
        val work = File(root, "preparing-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val raw = File(work, "source")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source().use { input -> raw.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    check()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    size += n
                    if (size > MAX_SOURCE) throw TextFailure("100MiB보다 큰 파일은 아직 지원하지 않습니다.")
                    digest.update(buf, 0, n); out.write(buf, 0, n)
                    progress(Preparation("파일 확인 중", size))
                }
            } }
            val hash = digest.digest().hex()
            val head = raw.inputStream().use { input -> val bytes = ByteArray(4); val n = input.read(bytes); bytes.copyOf(n.coerceAtLeast(0)) }.map { it.toInt() and 255 }
            if (head == listOf(37, 80, 68, 70) || head.take(2) == listOf(80, 75) || head == listOf(137, 80, 78, 71))
                throw TextFailure("현재는 TXT 파일만 읽을 수 있습니다.")
            if (head.take(4) == listOf(255, 254, 0, 0) || head.take(4) == listOf(0, 0, 254, 255))
                throw TextFailure("UTF-32는 지원하지 않습니다. UTF-8 또는 UTF-16으로 저장해 주세요.")
            val bom = when {
                head.take(3) == listOf(239, 187, 191) -> "UTF-8"
                head.take(2) == listOf(255, 254) -> "UTF-16LE"
                head.take(2) == listOf(254, 255) -> "UTF-16BE"
                else -> null
            }
            val candidates = charsetOverride?.let { listOf(it) } ?: bom?.let { listOf(it) }
                ?: listOf("UTF-8", "x-windows-949", "EUC-KR")
            var lastError: Exception? = null
            for (encoding in candidates) {
                check()
                val version = sha256("$hash|$encoding|normalization-1".toByteArray())
                val destination = File(root, version)
                if (destination.isDirectory) {
                    try {
                        val cached = TextDocument(destination)
                        if (cached.version != version || cached.sourceHash != hash || cached.charset != encoding)
                            throw TextFailure("캐시 정보가 원본과 다릅니다.")
                        cached.validate(check)
                        destination.setLastModified(System.currentTimeMillis())
                        return cached
                    } catch (_: IOException) { destination.deleteRecursively() }
                    catch (_: IllegalArgumentException) { destination.deleteRecursively() }
                }
                val built = File(work, "canonical").apply { mkdirs() }
                try {
                    progress(Preparation("본문 준비 중", size))
                    decode(raw, built, encoding, hash, version, check)
                    val document = TextDocument(built)
                    document.validate(check)
                    check()
                    if (!built.renameTo(destination)) throw TextFailure("읽기 캐시를 저장하지 못했습니다. 저장 공간을 확인해 주세요.")
                    evict(keepVersions + version)
                    return TextDocument(destination)
                } catch (e: java.nio.charset.CharacterCodingException) {
                    lastError = e; built.deleteRecursively()
                } catch (e: InvalidText) {
                    lastError = e; built.deleteRecursively()
                }
            }
            throw TextFailure("글자 인코딩을 확인할 수 없습니다. 인코딩을 직접 선택해 주세요.", lastError)
        } finally { work.deleteRecursively() }
    }

    private class InvalidText : IOException()
    private fun decode(raw: File, dir: File, encoding: String, hash: String, version: String, check: () -> Unit) {
        val decoder = Charset.forName(encoding).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        var chars = 0L; var bytes = 0L; var records = 0
        var previousCR = false; var first = true; var controls = 0L
        val pending = StringBuilder(8194)
        val textFile = File(dir, "text.utf8")
        val indexFile = File(dir, "index.bin")
        textFile.outputStream().buffered().use { text ->
            DataOutputStream(indexFile.outputStream().buffered()).use { index ->
                fun flush(final: Boolean = false) {
                    var count = pending.length
                    if (!final && count > 0 && pending[count - 1].isHighSurrogate()) count--
                    if (count == 0) return
                    val value = pending.substring(0, count)
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    if (bytes + encoded.size > MAX_CANONICAL || (records + 1L) * 24 > MAX_INDEX)
                        throw TextFailure("본문이 안전한 읽기 용량을 초과했습니다.")
                    index.writeLong(chars); index.writeLong(bytes); index.writeInt(count); index.writeInt(encoded.size)
                    text.write(encoded)
                    chars += count; bytes += encoded.size; records++
                    pending.delete(0, count)
                }
                InputStreamReader(raw.inputStream(), decoder).buffered(8192).use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        check()
                        val n = reader.read(buffer)
                        if (n < 0) break
                        for (i in 0 until n) {
                            val c = buffer[i]
                            if (first) { first = false; if (c == '\uFEFF') continue }
                            if (c == '\u0000' || (c < ' ' && c !in "\r\n\t\u000C")) controls++
                            if (previousCR && c == '\n') { previousCR = false; continue }
                            previousCR = c == '\r'
                            pending.append(if (previousCR) '\n' else c)
                            if (pending.length >= 8192) flush()
                        }
                    }
                    flush(true)
                }
            }
        }
        if (controls > 0 && controls * 100 > chars) throw InvalidText()
        Properties().apply {
            setProperty("schema", "1"); setProperty("charset", encoding); setProperty("hash", hash)
            setProperty("version", version); setProperty("length", chars.toString())
            setProperty("bytes", bytes.toString()); setProperty("records", records.toString())
            setProperty("textHash", fileHash(textFile, check)); setProperty("indexHash", fileHash(indexFile, check))
        }.also { props -> File(dir, "meta.properties").outputStream().use { props.store(it, "Yeobaek canonical text v1") } }
    }

    fun evict(keep: Set<String>) {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith("preparing-") } ?: return
        fun size(file: File) = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var total = dirs.sumOf(::size)
        for (dir in dirs.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BUDGET) break
            if (dir.name in keep) continue
            val bytes = size(dir)
            if (dir.deleteRecursively()) total -= bytes
        }
    }
    fun clear(keep: Set<String> = emptySet()) {
        root.listFiles()?.filter { it.name !in keep && !it.name.startsWith("preparing-") }?.forEach { it.deleteRecursively() }
    }
}

class TextDocument(val directory: File) {
    private val meta = Properties().apply { File(directory, "meta.properties").inputStream().use(::load) }
    private fun required(key: String) = meta.getProperty(key) ?: throw TextFailure("캐시 정보가 손상되었습니다.")
    val length = required("length").toLong()
    val charset: String = required("charset")
    val sourceHash: String = required("hash")
    val version: String = required("version")
    val records = required("records").toInt()
    private val text = File(directory, "text.utf8")
    private val index = File(directory, "index.bin")

    fun validate(check: () -> Unit = {}) {
        if (meta.getProperty("schema") != "1" || length !in 0..TextStore.MAX_CANONICAL || records !in 0..(TextStore.MAX_INDEX / 24).toInt()
            || text.length() != meta.getProperty("bytes").toLong() || index.length() != records * 24L
            || (records == 0) != (length == 0L))
            throw TextFailure("캐시를 다시 준비해야 합니다.")
        if (records > 0) RandomAccessFile(index, "r").use { f ->
            if (f.readLong() != 0L || f.readLong() != 0L) throw TextFailure("인덱스 시작점이 손상되었습니다.")
            f.seek((records - 1L) * 24)
            val lastChar = f.readLong(); val lastByte = f.readLong()
            val chars = f.readInt(); val bytes = f.readInt()
            if (lastChar + chars != length || lastByte + bytes != text.length()) throw TextFailure("인덱스 끝점이 손상되었습니다.")
        }
        if (fileHash(text, check) != meta.getProperty("textHash") || fileHash(index, check) != meta.getProperty("indexHash"))
            throw TextFailure("읽기 캐시가 손상되었습니다.")
    }

    /** At most 64K UTF-16 units per request. Returned offsets always refer to canonical text. */
    fun read(start: Long, count: Int): String {
        require(start in 0..length && count in 0..65536)
        if (start == length || count == 0) return ""
        val wanted = minOf(count.toLong(), length - start).toInt()
        val out = StringBuilder(wanted)
        RandomAccessFile(index, "r").use { idx -> RandomAccessFile(text, "r").use { body ->
            var low = 0; var high = records - 1
            while (low < high) {
                val mid = (low + high + 1) ushr 1
                idx.seek(mid * 24L)
                if (idx.readLong() <= start) low = mid else high = mid - 1
            }
            var record = low
            var position = start
            while (out.length < wanted && record < records) {
                idx.seek(record * 24L)
                val charStart = idx.readLong(); val byteStart = idx.readLong()
                val charLength = idx.readInt(); val byteLength = idx.readInt()
                if (charLength !in 1..8192 || byteLength !in 1..32768) throw TextFailure("인덱스가 손상되었습니다.")
                val bytes = ByteArray(byteLength)
                body.seek(byteStart); body.readFully(bytes)
                val block = String(bytes, Charsets.UTF_8)
                val offset = (position - charStart).toInt()
                val n = minOf(wanted - out.length, block.length - offset)
                out.append(block, offset, offset + n)
                position += n; record++
            }
        } }
        return out.toString()
    }

    fun excerpt(offset: Long, count: Int = 80): String = read(offset.coerceIn(0, length), count).replace('\n', ' ')
    fun context(offset: Long): Pair<String, String> {
        val at = offset.coerceIn(0, length)
        return read((at - 32).coerceAtLeast(0), minOf(at, 32).toInt()) to read(at, 32)
    }
}

/** KMP over bounded windows; matches (including overlaps) are written to disk, not an unbounded list. */
fun TextDocument.search(query: String, output: File, check: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): SearchResult {
    require(query.isNotEmpty() && query.codePointCount(0, query.length) <= 256)
    val prefix = IntArray(query.length)
    var j = 0
    for (i in 1 until query.length) {
        while (j > 0 && query[i] != query[j]) j = prefix[j - 1]
        if (query[i] == query[j]) j++
        prefix[i] = j
    }
    var position = 0L; var count = 0L; j = 0
    try {
        DataOutputStream(output.outputStream().buffered()).use { result ->
            while (position < length) {
                check()
                val chunk = read(position, 32768)
                for (i in chunk.indices) {
                    while (j > 0 && chunk[i] != query[j]) j = prefix[j - 1]
                    if (chunk[i] == query[j]) j++
                    if (j == query.length) {
                        if ((count + 1) * 8 > TextStore.MAX_INDEX) throw TextFailure("검색 결과가 너무 많습니다. 더 긴 검색어를 입력해 주세요.")
                        result.writeLong(position + i - query.length + 1); count++; j = prefix[j - 1]
                    }
                }
                position += chunk.length
                result.flush()
                progress(position, count)
            }
        }
        return SearchResult(output, count)
    } catch (e: Exception) { output.delete(); throw e }
}

class SearchResult(val file: File, val count: Long) {
    fun at(index: Long): Long {
        require(index in 0 until count)
        return RandomAccessFile(file, "r").use { it.seek(index * 8); it.readLong() }
    }
    fun page(first: Long, size: Int = 100): List<Long> {
        require(size in 1..100)
        return (first until minOf(first + size, count)).map(::at)
    }
}

fun fileHash(file: File, check: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val bytes = ByteArray(65536)
        while (true) { check(); val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
    }
    return digest.digest().hex()
}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
