package com.poyal.yeobaek.txt

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.CancellationException

class TextStoreTest {
    private fun <T> temporary(block: (File) -> T): T {
        val dir = Files.createTempDirectory("yeobaek-test").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun encodingsNormalizationAndBoundaryReads() = temporary { dir ->
        val source = "\uFEFF첫 문장\r\n\r다음\n\t " + "한글👩‍👩‍👧‍👦é\r\n".repeat(4000) + "끝\r"
        val expected = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        for (encoding in listOf("UTF-8", "UTF-16LE", "UTF-16BE")) {
            val bytes = source.toByteArray(Charset.forName(encoding))
            val doc = TextStore(dir).prepare({ bytes.inputStream() })
            assertEquals(expected.length.toLong(), doc.length)
            val actual = buildString { var at = 0L; while (at < doc.length) { val text = doc.read(at, 997); append(text); at += text.length } }
            assertEquals(expected, actual)
            for (at in listOf(0, 1, 8191, 8192, 16383, expected.length - 1))
                assertEquals(expected.substring(at, minOf(at + 100, expected.length)), doc.read(at.toLong(), 100))
        }
    }
    @Test fun koreanLegacyEncoding() = temporary { dir ->
        val text = "가나다 똠 뷁 한글입니다.\n".repeat(3000)
        val bytes = text.toByteArray(Charset.forName("x-windows-949"))
        val doc = TextStore(dir).prepare({ bytes.inputStream() })
        assertEquals("x-windows-949", doc.charset)
        assertEquals(text.take(100), doc.read(0, 100))
    }
    @Test fun rejectsLateTruncationAndUtf32() = temporary { dir ->
        val bytes = "한글".repeat(10000).toByteArray() + byteArrayOf(0xE3.toByte(), 0x81.toByte())
        assertThrows(TextFailure::class.java) { TextStore(dir).prepare({ bytes.inputStream() }, "UTF-8") }
        assertThrows(TextFailure::class.java) { TextStore(dir).prepare({ byteArrayOf(-1, -2, 0, 0, 65).inputStream() }) }
        assertEquals(0, dir.listFiles()!!.size)
    }
    @Test fun emptyAndWhitespaceRemainDistinct() = temporary { dir ->
        for (text in listOf("", "\uFEFF", " \t\n\n")) {
            val doc = TextStore(dir).prepare({ text.toByteArray().inputStream() })
            assertEquals(text.removePrefix("\uFEFF"), doc.read(0, 50))
        }
    }
    @Test fun matchesAcrossEveryBlockAndOverlaps() = temporary { dir ->
        val source = "가".repeat(32766) + "한글검색" + "나".repeat(8190) + "한글검색aaaa"
        val doc = TextStore(dir).prepare({ source.toByteArray().inputStream() })
        val result = doc.search("한글검색", File(dir, "results"))
        assertEquals(2L, result.count)
        assertEquals(source.indexOf("한글검색").toLong(), result.at(0))
        assertEquals(source.lastIndexOf("한글검색").toLong(), result.at(1))
        assertEquals(3L, doc.search("aa", File(dir, "overlaps")).count)
    }
    @Test fun cancellationAndCorruptedCacheRecoverWithoutSourceChanges() = temporary { dir ->
        val source = "본문입니다.\n".repeat(10000).toByteArray()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            TextStore(dir).prepare({ source.inputStream() }, check = { if (++calls > 4) throw CancellationException() })
        }
        assertTrue(dir.listFiles()!!.isEmpty())
        val store = TextStore(dir)
        val doc = store.prepare({ source.inputStream() })
        File(doc.directory, "text.utf8").writeText("broken")
        val recovered = store.prepare({ source.inputStream() })
        assertEquals(doc.version, recovered.version)
        assertEquals("본문입니다.\n", recovered.read(0, 7))
    }
    @Test fun sameMetadataDifferentContentDifferentVersion() = temporary { dir ->
        val store = TextStore(dir)
        val a = store.prepare({ "abcdef".byteInputStream() })
        val b = store.prepare({ "abcdeg".byteInputStream() })
        assertNotEquals(a.version, b.version)
    }
    @Test fun corruptedNumericMetadataIsRebuiltBeforeLayout() = temporary { dir ->
        val text = "캐시 끝점까지 확인합니다.\n".repeat(500)
        val store = TextStore(dir)
        val original = store.prepare({ text.byteInputStream() })
        for (bad in listOf((text.length + 1).toString(), "not-a-number", "-1")) {
            val file = File(original.directory, "meta.properties")
            val properties = java.util.Properties().apply { file.inputStream().use(::load); setProperty("length", bad) }
            file.outputStream().use { properties.store(it, null) }
            val recovered = store.prepare({ text.byteInputStream() })
            assertEquals(text.length.toLong(), recovered.length)
            assertEquals(original.version, recovered.version)
            assertEquals(text.takeLast(40), recovered.read(recovered.length - 40, 40))
        }
    }
}
