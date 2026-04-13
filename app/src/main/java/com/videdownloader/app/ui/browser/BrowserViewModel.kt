package com.videdownloader.app.ui.browser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videdownloader.app.data.db.BookmarkDao
import com.videdownloader.app.data.db.BookmarkEntity
import com.videdownloader.app.data.db.HistoryDao
import com.videdownloader.app.data.db.HistoryEntity
import com.videdownloader.app.data.model.BrowserTab
import com.videdownloader.app.data.model.DetectedMedia
import com.videdownloader.app.data.model.MediaQualityOption
import com.videdownloader.app.data.preferences.AppPreferences
import com.videdownloader.app.service.AdBlocker
import com.videdownloader.app.service.DownloadService
import com.videdownloader.app.service.VideoDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    application: Application,
    val videoDetector: VideoDetector,
    val adBlocker: AdBlocker,
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    private val downloadDao: com.videdownloader.app.data.db.DownloadDao,
    private val preferences: AppPreferences
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BrowserViewModel"
    }

    // Tab management
    private val _tabs = MutableStateFlow(listOf(BrowserTab(isActive = true)))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    // URL state
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0)
    val loadingProgress: StateFlow<Int> = _loadingProgress.asStateFlow()

    // Media detection
    val detectedMedia: StateFlow<List<DetectedMedia>> = videoDetector.detectedMedia
    val hasDetectedMedia: StateFlow<Boolean> = videoDetector.hasMedia

    // Quality options for bottom sheet
    private val _qualityOptions = MutableStateFlow<List<com.videdownloader.app.data.model.MediaQualityOption>>(emptyList())
    val qualityOptions: StateFlow<List<com.videdownloader.app.data.model.MediaQualityOption>> = _qualityOptions.asStateFlow()

    private val _selectedMedia = MutableStateFlow<com.videdownloader.app.data.model.DetectedMedia?>(null)
    val selectedMedia: StateFlow<com.videdownloader.app.data.model.DetectedMedia?> = _selectedMedia.asStateFlow()

    private val _isLoadingQualities = MutableStateFlow(false)
    val isLoadingQualities: StateFlow<Boolean> = _isLoadingQualities.asStateFlow()

    private val _showQualitySheet = MutableStateFlow(false)
    val showQualitySheet: StateFlow<Boolean> = _showQualitySheet.asStateFlow()

    private val _showNoVideoMessage = MutableStateFlow(false)
    val showNoVideoMessage: StateFlow<Boolean> = _showNoVideoMessage.asStateFlow()

    // Menu state
    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    // Tab manager
    private val _showTabManager = MutableStateFlow(false)
    val showTabManager: StateFlow<Boolean> = _showTabManager.asStateFlow()

    // Bookmarks
    val bookmarks = bookmarkDao.getAllBookmarks().stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    private val _showBookmarks = MutableStateFlow(false)
    val showBookmarks: StateFlow<Boolean> = _showBookmarks.asStateFlow()

    // Preferences
    val blockAds = preferences.blockAds.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val searchEngine = preferences.searchEngine.stateIn(viewModelScope, SharingStarted.Lazily, "Google")

    val hasActiveDownloads = downloadDao.getDownloadsByStatus("DOWNLOADING")
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun onUrlChanged(url: String) {
        _currentUrl.value = url
        videoDetector.clearDetectedMedia()
        updateActiveTab(url = url)
    }

    fun onTitleChanged(title: String) {
        _currentTitle.value = title
        videoDetector.setCurrentPage(_currentUrl.value, title)
        updateActiveTab(title = title)
    }

    fun onPageStarted(url: String) {
        _isLoading.value = true
        _currentUrl.value = url
        videoDetector.clearDetectedMedia()
        videoDetector.setCurrentPage(url, _currentTitle.value)

        // Record history
        viewModelScope.launch {
            if (url.isNotEmpty() && url != "about:blank") {
                historyDao.insertHistory(HistoryEntity(url = url, title = _currentTitle.value))
            }
        }
    }

    fun onPageFinished(url: String) {
        _isLoading.value = false
        _currentUrl.value = url
    }

    fun onProgressChanged(progress: Int) {
        _loadingProgress.value = progress
    }

    fun navigateTo(url: String) {
        if (url.isBlank()) {
            _currentUrl.value = ""
            videoDetector.clearDetectedMedia()
            return
        }
        val finalUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> getSearchUrl(url)
        }
        _currentUrl.value = finalUrl
    }

    private fun getSearchUrl(query: String): String {
        return when (searchEngine.value) {
            "Google" -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            "Bing" -> "https://www.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            "DuckDuckGo" -> "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            "Yahoo" -> "https://search.yahoo.com/search?p=${java.net.URLEncoder.encode(query, "UTF-8")}"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }
    }

    fun onDownloadFabClicked() {
        if (hasDetectedMedia.value) {
            val mediaList = detectedMedia.value
            if (mediaList.isNotEmpty()) {
                _showQualitySheet.value = true
                _isLoadingQualities.value = true
                viewModelScope.launch(Dispatchers.IO) {
                    // Fetch qualities for all detected videos concurrently
                    val deferreds = mediaList.map { media ->
                        async {
                            val options = videoDetector.fetchQualityOptions(media)
                            Pair(media, options)
                        }
                    }
                    val results = deferreds.awaitAll()
                    
                    // Group results by title to combine individual MP4 qualities of the same video
                    val groupedResults = results.groupBy { it.first.title }.values.map { groupList ->
                        // Combine all options, preferring those with actual file sizes
                        val combinedOptions = groupList.flatMap { it.second }
                            .groupBy { it.quality }
                            .map { entry -> entry.value.maxByOrNull { it.fileSize ?: 0L } ?: entry.value.first() }
                            
                        val maxFileSize = combinedOptions.maxOfOrNull { it.fileSize ?: 0L } ?: 0L
                        val hasM3u8 = groupList.any { it.first.url.contains(".m3u8") }
                        
                        var score = maxFileSize
                        if (combinedOptions.size > 1) score += 10_000_000_000L
                        // Reduced m3u8 priority so actual MP4s with sizes win
                        if (hasM3u8) score += 5_000_000_000L
                        
                        Triple(groupList.first().first, combinedOptions, score)
                    }

                    val bestGroup = groupedResults.maxByOrNull { it.third }

                    if (bestGroup != null) {
                        _selectedMedia.value = bestGroup.first
                        _qualityOptions.value = bestGroup.second.sortedByDescending {
                            val qstr = it.quality.lowercase()
                            val num = Regex("\\d+").find(qstr)?.value?.toInt() ?: 0
                            num + if(it.fileSize != null) 100000 else 0
                        }
                    } else {
                        _qualityOptions.value = emptyList()
                    }
                    
                    _isLoadingQualities.value = false
                }
            }
        } else {
            // Show "play video first" message
            _showNoVideoMessage.value = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000)
                _showNoVideoMessage.value = false
            }
        }
    }

    fun dismissQualitySheet() {
        _showQualitySheet.value = false
    }

    fun startDownload(option: com.videdownloader.app.data.model.MediaQualityOption) {
        _showQualitySheet.value = false
        val media = _selectedMedia.value
        val baseTitle = media?.title?.takeIf { it.isNotBlank() } ?: _currentTitle.value.ifEmpty { "video_${System.currentTimeMillis()}" }
        
        DownloadService.startDownload(
            context = getApplication(),
            url = option.url,
            fileName = "${baseTitle}_${option.quality}",
            quality = option.quality,
            sourceUrl = media?.sourcePageUrl ?: _currentUrl.value,
            sourceTitle = baseTitle,
            mimeType = option.mimeType,
            thumbnailUrl = media?.thumbnailUrl ?: ""
        )
    }

    // Tab management
    fun addNewTab() {
        val newTab = BrowserTab(isActive = true)
        val updatedTabs = _tabs.value.map { it.copy(isActive = false) } + newTab
        _tabs.value = updatedTabs
        _activeTabIndex.value = updatedTabs.size - 1
        _currentUrl.value = ""
        _currentTitle.value = "New Tab"
        videoDetector.clearDetectedMedia()
    }

    fun switchToTab(index: Int) {
        if (index in _tabs.value.indices) {
            val updatedTabs = _tabs.value.mapIndexed { i, tab ->
                tab.copy(isActive = i == index)
            }
            _tabs.value = updatedTabs
            _activeTabIndex.value = index
            val tab = updatedTabs[index]
            _currentUrl.value = tab.url
            _currentTitle.value = tab.title
        }
    }

    fun closeTab(index: Int) {
        if (_tabs.value.size <= 1) {
            // Can't close last tab, just reset it
            _tabs.value = listOf(BrowserTab(isActive = true))
            _activeTabIndex.value = 0
            _currentUrl.value = ""
            _currentTitle.value = "New Tab"
            return
        }

        val updatedTabs = _tabs.value.toMutableList()
        updatedTabs.removeAt(index)

        val newActiveIndex = when {
            index >= updatedTabs.size -> updatedTabs.size - 1
            else -> index
        }

        _tabs.value = updatedTabs.mapIndexed { i, tab ->
            tab.copy(isActive = i == newActiveIndex)
        }
        _activeTabIndex.value = newActiveIndex
        val activeTab = _tabs.value[newActiveIndex]
        _currentUrl.value = activeTab.url
        _currentTitle.value = activeTab.title
    }

    fun closeAllTabs() {
        _tabs.value = listOf(BrowserTab(isActive = true))
        _activeTabIndex.value = 0
        _currentUrl.value = ""
        _currentTitle.value = "New Tab"
    }

    private fun updateActiveTab(url: String? = null, title: String? = null) {
        val index = _activeTabIndex.value
        if (index in _tabs.value.indices) {
            val updatedTabs = _tabs.value.toMutableList()
            val currentTab = updatedTabs[index]
            updatedTabs[index] = currentTab.copy(
                url = url ?: currentTab.url,
                title = title ?: currentTab.title
            )
            _tabs.value = updatedTabs
        }
    }

    // Menu
    fun toggleMenu() { _showMenu.value = !_showMenu.value }
    fun dismissMenu() { _showMenu.value = false }

    // Tab Manager
    fun showTabManager() { _showTabManager.value = true }
    fun dismissTabManager() { _showTabManager.value = false }

    // Bookmarks
    fun toggleBookmarks() { _showBookmarks.value = !_showBookmarks.value }
    fun dismissBookmarks() { _showBookmarks.value = false }

    fun addBookmark() {
        viewModelScope.launch {
            val url = _currentUrl.value
            val title = _currentTitle.value
            if (url.isNotEmpty()) {
                if (bookmarkDao.isBookmarked(url)) {
                    bookmarkDao.deleteByUrl(url)
                } else {
                    bookmarkDao.insertBookmark(BookmarkEntity(url = url, title = title))
                }
            }
        }
    }

    // Bug fix #7: Use a single, stable Flow instead of creating one per call.
    // The suspend bookmarkDao.isBookmarked() now runs safely on Dispatchers.IO.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCurrentPageBookmarked: StateFlow<Boolean> = _currentUrl
        .flatMapLatest { url ->
            kotlinx.coroutines.flow.flow {
                emit(if (url.isNotEmpty()) bookmarkDao.isBookmarked(url) else false)
            }.flowOn(Dispatchers.IO)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
}
