package com.videdownloader.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videdownloader.app.data.db.HistoryDao
import com.videdownloader.app.data.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val historyDao: HistoryDao
) : ViewModel() {

    val wifiOnly = preferences.wifiOnly.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val syncGallery = preferences.syncGallery.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val blockAds = preferences.blockAds.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val searchEngine = preferences.searchEngine.stateIn(viewModelScope, SharingStarted.Lazily, "Google")
    val themeMode = preferences.themeMode.stateIn(viewModelScope, SharingStarted.Lazily, "System")
    val hideToolbarLabel = preferences.hideToolbarLabel.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val storagePath = preferences.storagePath.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val privateFolderPin = preferences.privateFolderPin.stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun setWifiOnly(value: Boolean) = viewModelScope.launch { preferences.setWifiOnly(value) }
    fun setSyncGallery(value: Boolean) = viewModelScope.launch { preferences.setSyncGallery(value) }
    fun setBlockAds(value: Boolean) = viewModelScope.launch { preferences.setBlockAds(value) }
    fun setSearchEngine(value: String) = viewModelScope.launch { preferences.setSearchEngine(value) }
    fun setThemeMode(value: String) = viewModelScope.launch { preferences.setThemeMode(value) }
    fun setHideToolbarLabel(value: Boolean) = viewModelScope.launch { preferences.setHideToolbarLabel(value) }

    fun clearHistory() = viewModelScope.launch { historyDao.clearHistory() }

    // Bug fix #9: PIN change/reset methods
    fun verifyCurrentPin(input: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = preferences.verifyPin(input)
            onResult(result)
        }
    }

    fun setNewPin(pin: String) {
        viewModelScope.launch { preferences.setPrivateFolderPin(pin) }
    }

    fun resetPin() {
        viewModelScope.launch {
            preferences.setPrivateFolderPin("") // Clears the hashed PIN
        }
    }
}
