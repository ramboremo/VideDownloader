package com.cognitivechaos.xdownload.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cognitivechaos.xdownload.data.db.DownloadEntity
import java.io.File

object DownloadFileActions {
    fun isImage(download: DownloadEntity): Boolean {
        val mimeType = download.mimeType.lowercase()
        val extension = File(download.filePath).extension.lowercase()
        return mimeType.startsWith("image/") ||
            extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    }

    fun isVideo(download: DownloadEntity): Boolean {
        val mimeType = download.mimeType.lowercase()
        val extension = File(download.filePath).extension.lowercase()
        return mimeType.startsWith("video/") ||
            extension in setOf("mp4", "webm", "mkv", "avi", "mov", "flv", "wmv", "m3u8")
    }

    fun openWith(context: Context, download: DownloadEntity) {
        val file = File(download.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found: ${download.fileName}", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mimeTypeFor(download, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, download: DownloadEntity) {
        val file = File(download.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found: ${download.fileName}", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(download, file)
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Share"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to share this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun mimeTypeFor(download: DownloadEntity, file: File): String {
        val fromEntity = download.mimeType.substringBefore(";").trim()
        if (fromEntity.isNotBlank() && fromEntity != "application/octet-stream") {
            return fromEntity
        }

        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when {
                isImage(download) -> "image/*"
                isVideo(download) -> "video/*"
                else -> "application/octet-stream"
            }
    }
}
