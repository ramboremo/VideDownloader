package com.videdownloader.app.ui.files

import android.app.Application
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videdownloader.app.data.db.DownloadDao
import com.videdownloader.app.data.db.DownloadEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    application: Application,
    private val downloadDao: DownloadDao
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FilesViewModel"
    }

    val completedDownloads = downloadDao.getDownloadsByStatus("COMPLETED")
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activeDownloads = downloadDao.getDownloadsByStatus("DOWNLOADING")
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allDownloads = downloadDao.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedTab = MutableStateFlow(0) // 0=Download, 1=Music, 2=Local Video
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun deleteDownload(download: DownloadEntity) {
        viewModelScope.launch {
            // Delete file
            val file = File(download.filePath)
            if (file.exists()) file.delete()
            // Delete from database
            downloadDao.deleteDownload(download)
        }
    }

    fun pauseDownload(download: DownloadEntity) {
        val application = getApplication<Application>()
        val intent = android.content.Intent(application, com.videdownloader.app.service.DownloadService::class.java).apply {
            action = com.videdownloader.app.service.DownloadService.ACTION_PAUSE
            putExtra(com.videdownloader.app.service.DownloadService.EXTRA_DOWNLOAD_ID, download.id)
        }
        application.startService(intent)
    }

    fun resumeDownload(download: DownloadEntity) {
        val application = getApplication<Application>()
        val intent = android.content.Intent(application, com.videdownloader.app.service.DownloadService::class.java).apply {
            action = com.videdownloader.app.service.DownloadService.ACTION_RESUME
            putExtra(com.videdownloader.app.service.DownloadService.EXTRA_DOWNLOAD_ID, download.id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
    }

    fun syncToGallery(download: DownloadEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(download.filePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            "File not found: ${download.fileName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val context = getApplication<Application>()
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "VideDownloader"
                )
                if (!publicDir.exists()) publicDir.mkdirs()

                // If already in public dir, just scan it
                if (file.absolutePath.contains(publicDir.absolutePath)) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(download.mimeType)
                    ) { _, _ -> }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Already safely synced to gallery ✓", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Move file to public directory
                val publicFile = File(publicDir, file.name)
                file.copyTo(publicFile, overwrite = true)
                // Bug fix #14: Verify file sizes match before deleting original to prevent data loss
                if (file.exists() && publicFile.exists() && publicFile.length() == file.length()) {
                    file.delete()
                } else if (publicFile.exists() && publicFile.length() != file.length()) {
                    Log.e(TAG, "Sync failed: copied file size mismatch (original=${file.length()}, copy=${publicFile.length()})")
                    publicFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Sync failed — not enough storage space", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Update database
                downloadDao.updateDownload(download.copy(filePath = publicFile.absolutePath))

                // Scan the new public file
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(publicFile.absolutePath),
                    arrayOf(download.mimeType)
                ) { _, _ -> }

                Log.d(TAG, "Moved to public and scanned: ${publicFile.absolutePath}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Synced to Gallery ✓", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync to gallery failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        "Sync failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun getStorageInfo(): Pair<Long, Long> {
        val stat = Environment.getExternalStorageDirectory()
        val totalBytes = stat.totalSpace
        val usedBytes = totalBytes - stat.freeSpace
        return Pair(usedBytes, totalBytes)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
