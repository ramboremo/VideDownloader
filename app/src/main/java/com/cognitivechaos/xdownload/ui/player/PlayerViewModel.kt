package com.cognitivechaos.xdownload.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cognitivechaos.xdownload.data.db.DownloadDao
import com.cognitivechaos.xdownload.data.db.DownloadEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val downloadDao: DownloadDao
) : ViewModel() {

    private val downloadId: String = savedStateHandle.get<String>("downloadId") ?: ""

    private val _download = MutableStateFlow<DownloadEntity?>(null)
    val download: StateFlow<DownloadEntity?> = _download.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadDownload()
    }

    private fun loadDownload() {
        viewModelScope.launch {
            try {
                val entity = downloadDao.getDownloadById(downloadId)
                _download.value = entity
                if (entity == null) {
                    _error.value = "Video not found"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load video"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
