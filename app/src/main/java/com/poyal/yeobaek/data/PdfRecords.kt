package com.poyal.yeobaek.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_positions", primaryKeys = ["bookId", "version"])
@kotlinx.serialization.Serializable
data class PdfPosition(
    val bookId: String, val version: String, val page: Int = 0,
    val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f,
    val completed: Boolean = false, val session: Long = 0, val sequence: Long = 0,
)

@Entity(tableName = "pdf_bookmarks", indices = [Index(value = ["bookId", "version", "page"], unique = true)])
@kotlinx.serialization.Serializable
data class PdfBookmark(
    @PrimaryKey val id: String, val bookId: String, val version: String, val page: Int,
    val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f,
    val createdAt: Long = System.currentTimeMillis(),
)
