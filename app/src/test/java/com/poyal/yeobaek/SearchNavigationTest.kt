package com.poyal.yeobaek

import com.poyal.yeobaek.txt.TextStore
import com.poyal.yeobaek.txt.search
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SearchNavigationTest {
    private fun checkVisibleRow(pageStart: Long, selectedResult: Long, row: Int) {
        val root = Files.createTempDirectory("yeobaek-search-navigation").toFile()
        try {
            val text = (0 until 250).joinToString("\n") { "찾을 문장 $it" }
            val document = TextStore(File(root, "cache")).prepare({ text.byteInputStream() })
            val result = document.search("찾을 문장", File(root, "results"))
            val state = UiState(
                searchResult = result,
                searchPageStart = pageStart,
                searchIndex = selectedResult,
                searchHits = result.page(pageStart).map { it to document.excerpt(it, 12) },
            )
            assertEquals("목록에서 누른 문장과 이동할 문장이 같아야 합니다",
                state.searchHits[row].first, result.at(state.searchHitIndex(row)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nextAcrossHundredthMatchDoesNotRetargetVisibleRows() {
        checkVisibleRow(pageStart = 0, selectedResult = 100, row = 0)
    }

    @Test fun previousAcrossPageBoundaryDoesNotRetargetSecondPage() {
        checkVisibleRow(pageStart = 100, selectedResult = 99, row = 7)
    }
}
