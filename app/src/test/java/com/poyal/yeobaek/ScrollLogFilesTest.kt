package com.poyal.yeobaek

import com.poyal.yeobaek.diagnostics.ScrollLogFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class ScrollLogFilesTest {
    @get:Rule val root = TemporaryFolder()

    @Test fun rotationIsBoundedChronologicalAndSurvivesReopening() {
        val dir = root.newFolder("diagnostics")
        val logs = ScrollLogFiles(dir, fileBytes = 256, count = 3)
        repeat(120) { logs.append("record-${it.toString().padStart(3, '0')} " + "x".repeat(30)) }
        assertTrue(dir.listFiles()!!.size <= 3)
        assertTrue(dir.listFiles()!!.all { it.length() <= 256 })
        val reopened = ScrollLogFiles(dir, fileBytes = 256, count = 3)
        reopened.append("record-120 final")
        val output = ByteArrayOutputStream()
        reopened.copyTo(output)
        val lines = output.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue(lines.last().contains("record-120 final"))
        assertFalse(lines.any { it.contains("record-000") })
        val numbers = lines.map { it.substringAfter("record-").substringBefore(' ').toInt() }
        assertEquals(numbers.sorted(), numbers)
    }

    @Test fun largeUnicodeRecordCannotExceedTheStorageCap() {
        val dir = root.newFolder("unicode")
        val logs = ScrollLogFiles(dir, fileBytes = 64, count = 1)
        repeat(3) { logs.append("한글🙂".repeat(100)) }
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.single().length() <= 64)
    }
}
