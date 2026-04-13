package com.videdownloader.app.data.model

data class DetectedMedia(
    val url: String,
    val title: String? = null,
    val mimeType: String? = null,
    val quality: String? = null,
    val fileSize: Long? = null,
    val sourcePageUrl: String = "",
    val sourcePageTitle: String = "",
    val thumbnailUrl: String? = null
)

data class MediaQualityOption(
    val url: String,
    val quality: String,
    val resolution: String? = null,
    val fileSize: Long? = null,
    val mimeType: String = "video/mp4",
    val bandwidth: Long? = null,
    val isAudioOnly: Boolean = false
)

data class MediaWithQualities(
    val media: DetectedMedia,
    val options: List<MediaQualityOption>
)

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "New Tab",
    val isActive: Boolean = false
)

/**
 * Download status enum. Currently the DB entity stores status as a raw String.
 * Use DownloadStatus.X.name for type-safe status comparisons instead of hardcoded
 * string literals like "DOWNLOADING", "PAUSED", etc.
 *
 * TODO: Add a Room TypeConverter to store this enum directly in the entity.
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
