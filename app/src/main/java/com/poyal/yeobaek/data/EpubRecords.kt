package com.poyal.yeobaek.data

import androidx.room.*
import kotlinx.serialization.Serializable

@Entity(tableName = "epub_positions", primaryKeys = ["bookId", "version"])
@Serializable
data class EpubPosition(
    val bookId: String, val version: String, val locator: String,
    val progress: Double = 0.0, val completed: Boolean = false,
    val session: Long = 0, val sequence: Long = 0, val locatorVersion: Int = 1,
)

@Entity(tableName = "epub_bookmarks", indices = [Index(value = ["bookId", "version", "locationKey"], unique = true)])
@Serializable
data class EpubBookmark(
    @PrimaryKey val id: String, val bookId: String, val version: String,
    val locationKey: String, val locator: String, val title: String,
    val createdAt: Long = System.currentTimeMillis(), val locatorVersion: Int = 1,
)

enum class EpubMode { SCROLL, PAGED }
enum class EpubFont(val label: String) { ORIGINAL("원본 글꼴"), SERIF("기존 명조"), SANS("시스템 고딕"), MONOSPACE("고정폭") }
@Serializable
data class EpubReaderPreferences(
    val mode: EpubMode = EpubMode.SCROLL, val publisherStyles: Boolean = true,
    val font: EpubFont = EpubFont.ORIGINAL, val size: Float = 1f,
    val line: Float = 1.5f, val margin: Float = 1f,
    val paper: TxtPaper = TxtPaper.FOLLOW_APP, val canvas: PdfCanvas = PdfCanvas.AUTO,
)
