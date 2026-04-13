package com.videdownloader.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = "QUEUED",
    val quality: String = "",
    val mimeType: String = "video/mp4",
    val thumbnailUrl: String? = null,
    val duration: String? = null,
    val sourceUrl: String = "",
    val sourceTitle: String = "",
    val downloadSpeed: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String,
    val favicon: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String,
    val favicon: String? = null,
    val visitedAt: Long = System.currentTimeMillis()
)
