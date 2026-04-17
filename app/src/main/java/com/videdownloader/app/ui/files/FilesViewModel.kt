package com.videdownloader.app.ui.files

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videdownloader.app.data.db.DownloadDao
import com.videdownloader.app.data.db.DownloadEntity
import com.videdownloader.app.data.preferences.AppPreferences
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
    private val downloadDao: DownloadDao,
    private val appPreferences: AppPreferences
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FilesViewModel"
    }

    val completedDownloads = downloadDao.getDownloadsByStatus("COMPLETED", isPrivate = false)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activeDownloads = downloadDao.getDownloadsByStatus("DOWNLOADING", isPrivate = false)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allDownloads = downloadDao.getAllDownloads(isPrivate = false)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val privateDownloads = downloadDao.getAllDownloads(isPrivate = true)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val privateFolderPin = appPreferences.privateFolderPin
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val _selectedTab = MutableStateFlow(0) // 0=Download, 1=Music, 2=Local Video, 3=Private
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    // Bug fix #7: Run on Dispatchers.IO and clean up MediaStore entry
    fun deleteDownload(download: DownloadEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(download.filePath)
                if (file.exists()) {
                    file.delete()
                    // Remove stale MediaStore entry so it doesn't linger in the gallery
                    try {
                        val context = getApplication<Application>()
                        val resolver = context.contentResolver
                        val uri = android.provider.MediaStore.Files.getContentUri("external")
                        resolver.delete(
                            uri,
                            "${android.provider.MediaStore.Files.FileColumns.DATA} = ?",
                            arrayOf(file.absolutePath)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore cleanup failed (non-critical)", e)
                    }
                }
                downloadDao.deleteDownload(download)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting download", e)
            }
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
                // Bug fix #14: Use unique file name to prevent overwriting existing files
                val publicFile = getUniqueFile(publicDir, file.nameWithoutExtension, file.extension)
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

    suspend fun getDownload(downloadId: String): DownloadEntity? {
        return downloadDao.getDownloadById(downloadId)
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            appPreferences.setPrivateFolderPin(pin)
        }
    }

    // Bug fix #2 supplement: Async PIN verification using hashed comparison
    fun verifyPin(input: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = appPreferences.verifyPin(input)
            onResult(result)
        }
    }

    fun moveToPrivate(download: DownloadEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(download.filePath)
                // Bug fix #15: Show toast feedback when file doesn't exist
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
                val privateDir = File(context.filesDir, "private_media")
                if (!privateDir.exists()) privateDir.mkdirs()
                
                val noMediaFile = File(privateDir, ".nomedia")
                if (!noMediaFile.exists()) noMediaFile.createNewFile()

                // Bug fix #14: Use unique file name to prevent overwriting existing files
                val privateFile = getUniqueFile(privateDir, file.nameWithoutExtension, file.extension)
                file.copyTo(privateFile, overwrite = true)
                
                if (file.exists() && privateFile.exists() && privateFile.length() == file.length()) {
                    file.delete()
                    // Bug fix #7: Clean up MediaStore entry for the moved file
                    try {
                        val resolver = context.contentResolver
                        val uri = android.provider.MediaStore.Files.getContentUri("external")
                        resolver.delete(
                            uri,
                            "${android.provider.MediaStore.Files.FileColumns.DATA} = ?",
                            arrayOf(file.absolutePath)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore cleanup on move-to-private failed (non-critical)", e)
                    }
                    downloadDao.updateDownload(download.copy(filePath = privateFile.absolutePath, isPrivate = true))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Moved to Private Folder", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    privateFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Move failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error moving to private", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        "Move failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun removeFromPrivate(download: DownloadEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(download.filePath)
                // Bug fix #15: Show toast feedback when file doesn't exist
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

                // Bug fix #14: Use unique file name to prevent overwriting existing files
                val publicFile = getUniqueFile(publicDir, file.nameWithoutExtension, file.extension)
                file.copyTo(publicFile, overwrite = true)
                
                if (file.exists() && publicFile.exists() && publicFile.length() == file.length()) {
                    file.delete()
                    downloadDao.updateDownload(download.copy(filePath = publicFile.absolutePath, isPrivate = false))
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(publicFile.absolutePath),
                        arrayOf(download.mimeType)
                    ) { _, _ -> }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Removed from Private Folder", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    publicFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Remove failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing from private", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        "Remove failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Returns a unique file: if "name.ext" already exists, tries "name (1).ext", "name (2).ext", etc.
     * Bug fix #14: Prevents overwriting existing files during move operations.
     */
    private fun getUniqueFile(dir: File, baseName: String, extension: String): File {
        val ext = if (extension.isNotEmpty()) ".$extension" else ""
        var file = File(dir, "$baseName$ext")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "$baseName ($counter)$ext")
            counter++
        }
        return file
    }
}
