package com.poyal.yeobaek

import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.epub.localEpubHtml
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubSettingsTest {
    @Test fun epubWinsOverGenericZipAndAppearsAsNextBook() {
        val epub = DocumentEntry("b", "content://test/b", "책.EPUB", false, 10, 1, "application/zip")
        assertTrue(epub.supported); assertTrue(epub.epub); assertEquals("EPUB", epub.formatLabel)
        assertEquals(epub, nextReadingEntry(listOf(epub.copy(id = "a", name = "a.txt", mime = "text/plain"), epub), "a") { false })
        assertTrue(epub.copy(name = "opaque", mime = "application/epub+zip").epub)
    }
    @Test fun epubSettingsInheritPerFieldAndDoNotReadTxtOverrides() {
        val values = ReaderSettings(folders = mapOf("book" to "folder"), overrides = mapOf(
            "common:EPUB" to mapOf("size" to "1.5", "mode" to "PAGED"),
            "folder:folder:EPUB" to mapOf("paper" to "DARK"), "book:book:EPUB" to mapOf("size" to "2.0"),
            "common:TXT" to mapOf("size" to "30")))
        val effective = ReaderSettingValues.epub(EpubReaderPreferences(), values.fields("EPUB", "book"))
        assertEquals(2f, effective.size); assertEquals(EpubMode.PAGED, effective.mode); assertEquals(TxtPaper.DARK, effective.paper)
        assertEquals(1.5f, ReaderSettingValues.epub(EpubReaderPreferences(), values.editingFields("EPUB", "book", SettingsScope.FOLDER)).size)
        assertEquals(1f, ReaderSettingValues.epub(EpubReaderPreferences(), mapOf("size" to "NaN", "mode" to "invalid")).size)
    }
    @Test fun backupV2RoundTripsAndReadsOldBackupWithoutEpubFields() {
        val book = Book("book", "source", "content://test/book", "책.epub", "folder", format = "EPUB")
        val at = """{"href":"EPUB/chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"cssSelector":"#p25"}}"""
        val backup = ReadingBackup(library = LibrarySnapshot(books = listOf(book), epubPositions = listOf(EpubPosition("book", "hash", at, .5)),
            epubBookmarks = listOf(EpubBookmark("mark", "book", "hash", "key", at, "문단"))), settings = ReaderSettings(), theme = AppTheme.SYSTEM)
        val json = Json { encodeDefaults = true }
        val copy = json.decodeFromString<ReadingBackup>(json.encodeToString(backup))
        validateBackup(copy); assertEquals(backup, copy)
        val old = json.decodeFromString<ReadingBackup>("""{"version":1,"library":{},"settings":{},"theme":"LIGHT"}""")
        validateBackup(old); assertTrue(old.library.epubPositions.isEmpty())
        assertFalse(validEpubLocator("""{"href":"https://example.com/x","type":"text/html"}"""))
        assertFalse(validEpubLocator("""{"href":"../secret","type":"text/html"}"""))
        assertTrue(runCatching { validateBackup(copy.copy(library = copy.library.copy(epubPositions = listOf(copy.library.epubPositions.single().copy(progress = Double.NaN))))) }.isFailure)
    }
    @Test fun localHtmlKeepsKoreanAndSvgButDoesNotRunPublicationScripts() {
        val input = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>책</title><style>p { color: red; }</style></head><body onload="track()"><p id="p1">여백의 문장 <a href="chapter2.xhtml#note">각주</a></p><svg xmlns="http://www.w3.org/2000/svg"><text>그림</text></svg><script src="https://example.com/track.js"></script></body></html>"""
        val result = localEpubHtml(input.toByteArray()).toString(Charsets.UTF_8)
        val doc = Jsoup.parse(result)
        assertTrue(doc.select("script").isEmpty()); assertFalse(doc.body().hasAttr("onload"))
        assertTrue(doc.text().contains("여백의 문장")); assertEquals("chapter2.xhtml#note", doc.selectFirst("a")!!.attr("href"))
        assertTrue(result.contains("<svg")); assertTrue(result.contains("connect-src 'none'"))
        assertTrue(result.contains("https://readium")); assertTrue(result.contains("color: red"))
    }
}
