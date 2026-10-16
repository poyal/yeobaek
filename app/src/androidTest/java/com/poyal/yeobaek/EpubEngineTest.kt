@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek

import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.epub.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import java.io.File

class EpubEngineTest {
    @Test fun opensEpub2And3SearchesKoreanAndDistinguishesFixedLayout() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = EpubEngine(context)
        for ((epub2, fixed) in listOf(true to false, false to false, false to true)) {
            val file = EpubFixtures.write(File(context.cacheDir, "epub-engine-$epub2-$fixed.epub"), fixed, epub2)
            val doc = engine.open(file)
            try {
                assertEquals(fixed, doc.fixed); assertEquals(3, doc.publication.readingOrder.size)
                assertEquals(3, doc.publication.tableOfContents.size)
                val search = doc.publication.search("검색구절")!!
                try {
                    var count = 0
                    while (true) { val page = search.next().getOrElse { error(it.message) } ?: break; count += page.locators.size }
                    assertEquals(if (fixed) 3 else 180, count)
                } finally { search.close() }
            } finally { doc.close(); file.delete() }
        }
    }
    @Test fun rejectsContentEncryptionButAllowsFontObfuscationAndRejectsBrokenZip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = EpubEngine(context)
        val encrypted = File(context.cacheDir, "epub-content-encrypted.epub")
        val font = File(context.cacheDir, "epub-font-obfuscated.epub")
        val broken = File(context.cacheDir, "epub-broken.epub")
        try {
            EpubFixtures.write(encrypted, encryption = "http://www.w3.org/2001/04/xmlenc#aes256-cbc")
            assertTrue(runCatching { engine.open(encrypted) }.exceptionOrNull()?.message.orEmpty().contains("DRM"))
            EpubFixtures.write(font, encryption = "http://www.idpf.org/2008/embedding")
            engine.open(font).close()
            // Readium closes ZIP handles asynchronously. Production cache files are immutable, too.
            broken.writeText("not an epub")
            assertTrue(runCatching { engine.open(broken) }.exceptionOrNull() is EpubFailure)
        } finally { encrypted.delete(); font.delete(); broken.delete() }
    }
}
