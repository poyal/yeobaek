package com.poyal.yeobaek.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "image_positions", primaryKeys = ["bookId", "version"])
data class ImagePosition(val bookId: String, val version: String, val pageKey: String,
    val page: Int = 0, val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f,
    val completed: Boolean = false, val session: Long = 0, val sequence: Long = 0)

@Entity(tableName = "image_bookmarks", indices = [Index(value = ["bookId", "version", "pageKey"], unique = true)])
data class ImageBookmark(@PrimaryKey val id: String, val bookId: String, val version: String,
    val pageKey: String, val page: Int, val name: String,
    val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f,
    val createdAt: Long = System.currentTimeMillis())
