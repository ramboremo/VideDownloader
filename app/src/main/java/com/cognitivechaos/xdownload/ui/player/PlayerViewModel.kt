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
import kotlinx.coroutines.flow.catch
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
            if (downloadId.isBlank()) {
                _error.value = "File not found"
                _isLoading.value = false
                return@launch
            }

            downloadDao.observeDownloadById(downloadId)
                .catch { e ->
                    _error.value = e.message ?: "Failed to load file"
                    _isLoading.value = false
                }
                .collect { entity ->
                    _download.value = entity
                    _error.value = if (entity == null) "File not found" else null
                    _isLoading.value = false
                }
        }
    }
}
