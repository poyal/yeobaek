package com.poyal.yeobaek

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small original books, generated locally; no third-party book content. */
internal object EpubFixtures {
    fun write(file: File, fixed: Boolean = false, epub2: Boolean = false, chapters: Int = 3, paragraphs: Int = 60, encryption: String? = null): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            if (encryption != null) entry("META-INF/encryption.xml", """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="$encryption"/><CipherData><CipherReference URI="EPUB/font.otf"/></CipherData></EncryptedData></encryption>""")
            val items = (1..chapters).joinToString("") { """<item id="c$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
            entry("EPUB/package.opf", """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="${if (epub2) "2.0" else "3.0"}"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:8fb0aa70-7447-4e58-a6a4-d5a2292c3c4e</dc:identifier><dc:title>여백 EPUB 검증</dc:title><dc:language>ko</dc:language><meta property="dcterms:modified">2026-10-17T00:00:00Z</meta>${if (fixed) "<meta property=\"rendition:layout\">pre-paginated</meta>" else ""}</metadata><manifest>$items<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx">$spine</spine></package>""")
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>목차</title></head><body><nav epub:type="toc"><ol>${(1..chapters).joinToString("") { "<li><a href=\"chapter$it.xhtml\">${it}장 · 여백</a></li>" }}</ol></nav></body></html>""")
            entry("EPUB/toc.ncx", """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:8fb0aa70-7447-4e58-a6a4-d5a2292c3c4e"/></head><docTitle><text>여백 EPUB 검증</text></docTitle><navMap>${(1..chapters).joinToString("") { "<navPoint id=\"nav$it\" playOrder=\"$it\"><navLabel><text>${it}장 · 여백</text></navLabel><content src=\"chapter$it.xhtml\"/></navPoint>" }}</navMap></ncx>""")
            for (chapter in 1..chapters) {
                val body = if (fixed) """<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800"><rect width="600" height="800" fill="#eee8dc"/><circle cx="300" cy="300" r="150" fill="#617255"/><text x="40" y="550" font-size="28">${chapter}쪽 · 여백의 그림책</text></svg><p id="p1">고정 지면 검색구절 $chapter</p>"""
                    else "<h1 id=\"heading\">${chapter}장 · 여백</h1>" + (1..paragraphs).joinToString("") { "<p id=\"p$it\">${chapter}장 ${it}번째 문단. 검색구절 여백의 독서는 문장을 천천히 따라갑니다. 한글과 English text, 숫자 123을 함께 읽습니다. 다음 문장도 같은 위치에서 이어집니다.</p>" } + "<p><a href=\"chapter${if (chapter == chapters) 1 else chapter + 1}.xhtml#p1\">각주로 이동</a></p>"
                entry("EPUB/chapter$chapter.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${chapter}장</title>${if (fixed) "<meta name=\"viewport\" content=\"width=600,height=800\"/>" else ""}<style>body { ${if (fixed) "margin:0;width:600px;height:800px;" else "font-family:serif;"} } p { line-height:1.8; }</style></head><body>$body</body></html>""")
            }
        }
        return file
    }
}
