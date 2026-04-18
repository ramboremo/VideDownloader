package com.videdownloader.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.videdownloader.app.MainActivity
import com.videdownloader.app.R
import com.videdownloader.app.data.db.DownloadDao
import com.videdownloader.app.data.db.DownloadEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.videdownloader.ACTION_START"
        const val ACTION_PAUSE = "com.videdownloader.ACTION_PAUSE"
        const val ACTION_RESUME = "com.videdownloader.ACTION_RESUME"
        const val ACTION_CANCEL = "com.videdownloader.ACTION_CANCEL"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_SOURCE_URL = "extra_source_url"
        const val EXTRA_SOURCE_TITLE = "extra_source_title"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"

        fun startDownload(
            context: Context,
            url: String,
            fileName: String,
            quality: String,
            sourceUrl: String = "",
            sourceTitle: String = "",
            mimeType: String = "video/mp4",
            thumbnailUrl: String = ""
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILENAME, fileName)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_SOURCE_TITLE, sourceTitle)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_THUMBNAIL_URL, thumbnailUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var downloadDao: DownloadDao

    @Inject
    lateinit var appPreferences: com.videdownloader.app.data.preferences.AppPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeDownloads = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILENAME) ?: "video_${System.currentTimeMillis()}"
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: ""
                val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: ""
                val sourceTitle = intent.getStringExtra(EXTRA_SOURCE_TITLE) ?: ""
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
                val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""

                startForeground(NOTIFICATION_ID, buildNotification("Downloading...", 0))
                startNewDownload(url, fileName, quality, sourceUrl, sourceTitle, mimeType, thumbnailUrl)
            }
            ACTION_CANCEL -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                cancelDownload(downloadId)
            }
            ACTION_PAUSE -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                pauseDownload(downloadId)
            }
            ACTION_RESUME -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                resumeDownload(downloadId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Checks if the device is currently on Wi-Fi.
     */
    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            return networkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /**
     * Returns a unique file: if "name.ext" already exists, tries "name (1).ext", "name (2).ext", etc.
     */
    private fun getUniqueFile(dir: File, baseName: String, extension: String): File {
        var file = File(dir, "$baseName.$extension")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "$baseName ($counter).$extension")
            counter++
        }
        return file
    }

    private fun startNewDownload(
        url: String,
        fileName: String,
        quality: String,
        sourceUrl: String,
        sourceTitle: String,
        mimeType: String,
        thumbnailUrl: String
    ) {
        // Bug fix #3: Enforce Wi-Fi-only preference
        serviceScope.launch {
            try {
                val wifiOnly = appPreferences.wifiOnly.first()
                if (wifiOnly && !isOnWifi()) {
                    updateNotification("Download blocked — Wi-Fi only mode", -1)
                    Log.w(TAG, "Download blocked: Wi-Fi only preference is ON but device is not on Wi-Fi")
                    delay(3000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (activeDownloads.isEmpty()) stopSelf()
                    return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read wifiOnly preference", e)
            }
            proceedWithDownload(url, fileName, quality, sourceUrl, sourceTitle, mimeType, thumbnailUrl)
        }
    }

    private fun proceedWithDownload(
        url: String,
        fileName: String,
        quality: String,
        sourceUrl: String,
        sourceTitle: String,
        mimeType: String,
        thumbnailUrl: String
    ) {
        val downloadId = UUID.randomUUID().toString()
        val downloadDir = getDownloadDirectory()
        val sanitizedName = sanitizeFileName(fileName)
        val extension = getExtension(mimeType, url)
        // Bug fix #11: Prevent filename collisions
        val file = getUniqueFile(downloadDir, sanitizedName, extension)

        Log.d(TAG, "proceedWithDownload: id=$downloadId")
        Log.d(TAG, "  name=${file.name}")
        Log.d(TAG, "  quality=$quality, mimeType=$mimeType")
        Log.d(TAG, "  URL=${url.take(200)}")

        val entity = DownloadEntity(
            id = downloadId,
            url = url,
            fileName = file.name,
            filePath = file.absolutePath,
            status = "DOWNLOADING",
            quality = quality,
            mimeType = mimeType,
            sourceUrl = sourceUrl,
            sourceTitle = sourceTitle,
            downloadSpeed = "",
            thumbnailUrl = thumbnailUrl
        )

        val job = serviceScope.launch {
            try {
                downloadDao.insertDownload(entity)
                performDownload(downloadId, url, file)
            } catch (e: CancellationException) {
                val currentStatus = downloadDao.getDownloadById(downloadId)?.status
                if (currentStatus != "PAUSED") {
                    downloadDao.updateStatus(downloadId, "CANCELLED")
                    Log.d(TAG, "Download cancelled: $downloadId")
                } else {
                    Log.d(TAG, "Download paused: $downloadId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download FAILED: $downloadId — ${e.message}", e)
                downloadDao.updateStatus(downloadId, "FAILED")
                updateNotification("Download failed: ${e.message?.take(50)}", -1)
            }
        }
        activeDownloads[downloadId] = job
    }



    private suspend fun performDownload(downloadId: String, url: String, file: File, isResume: Boolean = false) {
        Log.d(TAG, "performDownload START: id=$downloadId")
        Log.d(TAG, "  URL: ${url.take(300)}")
        Log.d(TAG, "  Target file: ${file.absolutePath}")

        val entity = downloadDao.getDownloadById(downloadId)
        val sourceUrl = entity?.sourceUrl ?: ""
        
        val builder = Request.Builder().url(url)
        var cookie = android.webkit.CookieManager.getInstance().getCookie(url)
        if (cookie.isNullOrEmpty() && sourceUrl.isNotEmpty()) {
            cookie = android.webkit.CookieManager.getInstance().getCookie(sourceUrl)
        }
        if (!cookie.isNullOrEmpty()) {
            builder.addHeader("Cookie", cookie)
        }
        builder.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        if (sourceUrl.isNotEmpty()) builder.addHeader("Referer", sourceUrl)
        
        var downloadedBytes = 0L
        val appendToFile = isResume && file.exists()
        
        if (appendToFile) {
            downloadedBytes = file.length()
            if (downloadedBytes > 0) {
                builder.addHeader("Range", "bytes=$downloadedBytes-")
                Log.d(TAG, "Resuming download from bytes=$downloadedBytes-")
            }
        }

        val request = builder.build()

        val response = okHttpClient.newCall(request).execute()

        val responseContentType = response.header("Content-Type") ?: "unknown"
        val responseContentLength = response.header("Content-Length") ?: "unknown"
        Log.d(TAG, "  Response: code=${response.code}, contentType=$responseContentType, contentLength=$responseContentLength")

        // Bug fix #2: Close response body on all error paths to prevent connection leaks
        if (!response.isSuccessful) {
            response.close()
            if (isResume && (response.code == 403 || response.code == 404)) {
                throw Exception("Link expired or file not found")
            }
            throw Exception("HTTP error ${response.code}")
        }
        
        if (appendToFile && downloadedBytes > 0 && response.code != 206) {
            response.close()
            Log.e(TAG, "Server doesn't support resuming or link is invalid. response code: ${response.code}")
            throw Exception("Server does not support resuming")
        }

        // Warn if response looks like HTML instead of video/audio
        if (responseContentType.contains("text/html", ignoreCase = true)) {
            Log.w(TAG, "  WARNING: Server returned HTML instead of media. The URL may require authentication or is blocked.")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val totalBytes = if (response.code == 206) {
            downloadedBytes + body.contentLength()
        } else {
            body.contentLength()
        }
        Log.d(TAG, "  Body contentLength=${body.contentLength()}, Total expected=$totalBytes")

        // Update total file size
        if (totalBytes > downloadedBytes) {
            val updatedEntity = downloadDao.getDownloadById(downloadId)
            if (updatedEntity != null) {
                downloadDao.updateDownload(updatedEntity.copy(fileSize = totalBytes))
            }
        }

        file.parentFile?.mkdirs()

        body.byteStream().use { input ->
            FileOutputStream(file, appendToFile).use { output ->
                val buffer = ByteArray(8192)
                var lastDownloadedBytes = downloadedBytes
                var lastNotifyTime = System.currentTimeMillis()

                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break

                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    // Update progress every 500ms
                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastNotifyTime
                    if (timeDiff > 500) {
                        val bytesSinceLast = downloadedBytes - lastDownloadedBytes
                        val speedBps = (bytesSinceLast * 1000) / timeDiff
                        val speedStr = "${formatBytes(speedBps)}/s"

                        downloadDao.updateProgress(downloadId, downloadedBytes, "DOWNLOADING", speedStr)

                        lastNotifyTime = now
                        lastDownloadedBytes = downloadedBytes

                        val progress = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }
                        updateNotification(
                            "Downloading: ${formatBytes(downloadedBytes)}${if (totalBytes > 0) " / ${formatBytes(totalBytes)}" else ""}",
                            progress
                        )
                    }
                }

                output.flush()

                // Use actual file size from disk
                val actualFileSize = file.length()
                Log.d(TAG, "Download finished: id=$downloadId, loopBytes=$downloadedBytes, diskBytes=$actualFileSize")

                // --- Detect failed downloads ---
                // Case 1: Server returned 0 bytes or connection was cut
                if (actualFileSize == 0L) {
                    Log.e(TAG, "Download FAILED: 0 bytes written to disk. Server likely blocked the request.")
                    file.delete()
                    downloadDao.updateStatus(downloadId, "FAILED")
                    updateNotification("Download failed — server blocked the request", -1)
                    delay(2000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (activeDownloads.isEmpty()) stopSelf()
                    return
                }

                // Case 2: Server returned an HTML error/challenge page instead of media
                val isHtmlResponse = responseContentType.contains("text/html", ignoreCase = true)
                if (isHtmlResponse && actualFileSize < 100_000) {
                    Log.e(TAG, "Download FAILED: Server returned HTML page ($actualFileSize bytes) instead of media.")
                    file.delete()
                    downloadDao.updateStatus(downloadId, "FAILED")
                    updateNotification("Download failed — video is protected", -1)
                    delay(2000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (activeDownloads.isEmpty()) stopSelf()
                    return
                }

                // Case 3: Downloaded an HLS/DASH playlist file instead of actual video
                // These are small text files (.m3u8 / .mpd) that list video segment URLs.
                // Our simple downloader can't stitch them — needs FFmpeg.
                val isStreamPlaylist = responseContentType.contains("mpegurl", ignoreCase = true) ||
                    responseContentType.contains("dash+xml", ignoreCase = true)
                val fileHeader = try {
                    file.bufferedReader().use { it.readLine()?.trim() ?: "" }
                } catch (_: Exception) { "" }
                val looksLikePlaylist = fileHeader.startsWith("#EXTM3U") ||
                    fileHeader.startsWith("<?xml") ||
                    (actualFileSize < 500_000 && fileHeader.startsWith("#"))

                if (isStreamPlaylist || looksLikePlaylist) {
                    Log.e(TAG, "Download FAILED: Downloaded HLS/DASH playlist ($actualFileSize bytes) instead of video. Header: ${fileHeader.take(50)}")
                    file.delete()
                    downloadDao.updateStatus(downloadId, "FAILED")
                    updateNotification("Download failed — video uses streaming (HLS)", -1)
                    delay(2000)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (activeDownloads.isEmpty()) stopSelf()
                    return
                }

                // Case 4: Binary file that isn't actually a valid video/audio
                // Only validate magic bytes for video/audio MIME types or known media file extensions.
                // Non-media files (PDFs, ZIPs, text files, etc.) are allowed through without validation.
                val downloadMimeType = entity?.mimeType ?: ""
                if (isMediaMimeType(downloadMimeType) || isMediaExtension(file.name)) {
                    if (!isValidMediaFile(file)) {
                        Log.e(TAG, "Download FAILED: File ($actualFileSize bytes) has no valid video/audio signature. Likely corrupted or not a media file.")
                        file.delete()
                        downloadDao.updateStatus(downloadId, "FAILED")
                        updateNotification("Download failed — file is not a valid video", -1)
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (activeDownloads.isEmpty()) stopSelf()
                        return
                    }
                }

                // --- Success ---
                // Bug fix #1: Remove from active downloads AFTER all validation is complete
                activeDownloads.remove(downloadId)
                Log.d(TAG, "Download COMPLETE: id=$downloadId, size=${formatBytes(actualFileSize)}")
                val completedEntity = downloadDao.getDownloadById(downloadId)
                if (completedEntity != null) {
                    downloadDao.updateDownload(completedEntity.copy(
                        fileSize = actualFileSize,
                        downloadedBytes = actualFileSize,
                        status = "COMPLETED",
                        downloadSpeed = "",
                        completedAt = System.currentTimeMillis()
                    ))
                } else {
                    downloadDao.updateProgress(downloadId, downloadedBytes, "COMPLETED", "")
                    downloadDao.updateStatus(downloadId, "COMPLETED", System.currentTimeMillis())
                }

                // Conditionally auto-sync and move based on user preference
                try {
                    val shouldSync = appPreferences.syncGallery.first()
                    if (shouldSync) {
                        val publicDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "VideDownloader"
                        )
                        if (!publicDir.exists()) publicDir.mkdirs()

                        val publicFile = File(publicDir, file.name)
                        file.copyTo(publicFile, overwrite = true)
                        if (file.exists() && publicFile.exists()) {
                            file.delete()
                        }

                        // Update db with new public path
                        val updatedEntity = downloadDao.getDownloadById(downloadId)
                        if (updatedEntity != null) {
                            downloadDao.updateDownload(updatedEntity.copy(filePath = publicFile.absolutePath))
                        }

                        android.media.MediaScannerConnection.scanFile(
                            this@DownloadService,
                            arrayOf(publicFile.absolutePath),
                            null,
                            null
                        )
                        Log.d(TAG, "Moved to public and MediaScanner scan requested (auto-sync ON): ${publicFile.absolutePath}")
                    } else {
                        Log.d(TAG, "MediaScanner scan skipped, file kept private (auto-sync OFF)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read sync_gallery preference or move file", e)
                }

                updateNotification("Download complete!", 100)

                // Stop foreground after a delay
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (activeDownloads.isEmpty()) stopSelf()
            }
        }
    }

    private fun cancelDownload(downloadId: String) {
        activeDownloads[downloadId]?.cancel()
        activeDownloads.remove(downloadId)
        serviceScope.launch {
            if (activeDownloads.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseDownload(downloadId: String) {
        val job = activeDownloads.remove(downloadId)
        serviceScope.launch {
            downloadDao.updateStatus(downloadId, "PAUSED")
            job?.cancel()
            updateNotification("Download paused", -1)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (activeDownloads.isEmpty()) stopSelf()
        }
    }

    private fun resumeDownload(downloadId: String) {
        serviceScope.launch {
            val entity = downloadDao.getDownloadById(downloadId) ?: return@launch
            if (entity.status == "PAUSED" || entity.status == "FAILED") {
                downloadDao.updateStatus(downloadId, "DOWNLOADING")
                startForeground(NOTIFICATION_ID, buildNotification("Resuming...", 0))
                
                val file = File(entity.filePath)
                val job = serviceScope.launch {
                    try {
                        performDownload(downloadId, entity.url, file, isResume = true)
                    } catch (e: CancellationException) {
                        val currentStatus = downloadDao.getDownloadById(downloadId)?.status
                        if (currentStatus != "PAUSED") {
                            downloadDao.updateStatus(downloadId, "CANCELLED")
                            Log.d(TAG, "Download cancelled: $downloadId")
                        } else {
                            Log.d(TAG, "Download paused: $downloadId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Download RESUME FAILED: $downloadId — ${e.message}", e)
                        downloadDao.updateStatus(downloadId, "FAILED")
                        updateNotification("Download failed for that particular file", -1)
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (activeDownloads.isEmpty()) stopSelf()
                    }
                }
                activeDownloads[downloadId] = job
            }
        }
    }

    private fun getDownloadDirectory(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val dir = File(baseDir, "VideDownloader")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .take(100)
            .trim()
            .ifEmpty { "video_${System.currentTimeMillis()}" }
    }

    private fun getExtension(mimeType: String, url: String): String {
        // Try to get from URL first
        val urlExt = url.substringBefore("?").substringAfterLast(".", "")
            .lowercase().take(5)
        if (urlExt in listOf("mp4", "webm", "mkv", "avi", "mov", "mp3", "m4a", "aac")) {
            return urlExt
        }
        // Fall back to MIME type
        return when {
            mimeType.contains("mp4") -> "mp4"
            mimeType.contains("webm") -> "webm"
            mimeType.contains("audio") -> "mp3"
            else -> "mp4"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Video download progress"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VideDownloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(progress in 0..99)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else if (progress == -1) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Returns true if the MIME type indicates a video or audio file.
     * Only "video/" and "audio/" MIME types should be validated against media magic bytes.
     */
    private fun isMediaMimeType(mimeType: String): Boolean =
        mimeType.startsWith("video/") || mimeType.startsWith("audio/")

    /**
     * Returns true if the file extension is a known media format.
     * Used as a fallback when the MIME type is generic (e.g., "application/octet-stream").
     */
    private fun isMediaExtension(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in setOf(
            "mp4", "webm", "mkv", "avi", "mov", "mp3", "m4a", "aac", "flac", "ogg", "wav"
        )

    /**
     * Checks if a file has valid video/audio magic bytes (file signature).
     * Returns true if the file header matches any known media format.
     */
    private fun isValidMediaFile(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            file.inputStream().use { it.read(header) }

            // MP4/MOV: "ftyp" at offset 4
            if (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) return true

            // WebM/MKV: EBML header 0x1A45DFA3
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) return true

            // FLV: "FLV"
            if (header[0] == 0x46.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x56.toByte()) return true

            // AVI / WAV: "RIFF"
            if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) return true

            // MPEG-TS: verify sync byte 0x47 at 188-byte intervals (need at least 2 sync points)
            // A single 0x47 byte is too prone to false positives (it's just the letter 'G')
            if (header[0] == 0x47.toByte()) {
                try {
                    val tsCheck = ByteArray(1)
                    file.inputStream().use { stream ->
                        stream.skip(188)
                        if (stream.read(tsCheck) == 1 && tsCheck[0] == 0x47.toByte()) return true
                    }
                } catch (_: Exception) { /* file too small for TS */ }
            }

            // MP3: ID3 tag
            if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() &&
                header[2] == 0x33.toByte()) return true

            // MP3: sync word (0xFFE0 or higher)
            if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) return true

            // OGG: "OggS"
            if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) return true

            // FLAC: "fLaC"
            if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) return true

            // AAC ADTS: sync word 0xFFF
            if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF0) == 0xF0) return true

            Log.d(TAG, "Unknown file header: ${header.take(8).joinToString(" ") { "%02X".format(it) }}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file header", e)
            false
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
