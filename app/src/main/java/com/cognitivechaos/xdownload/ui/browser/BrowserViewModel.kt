package com.cognitivechaos.xdownload.ui.browser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cognitivechaos.xdownload.data.db.BookmarkDao
import com.cognitivechaos.xdownload.data.db.BookmarkEntity
import com.cognitivechaos.xdownload.data.db.HistoryDao
import com.cognitivechaos.xdownload.data.db.HistoryEntity
import com.cognitivechaos.xdownload.data.model.BrowserTab
import com.cognitivechaos.xdownload.data.model.DetectedMedia
import com.cognitivechaos.xdownload.data.model.MediaQualityOption
import com.cognitivechaos.xdownload.data.preferences.AppPreferences
import com.cognitivechaos.xdownload.service.AdBlocker
import com.cognitivechaos.xdownload.service.DownloadService
import com.cognitivechaos.xdownload.service.VideoDetector
import com.cognitivechaos.xdownload.service.VideoRanker
import com.cognitivechaos.xdownload.ui.browser.VideoSignal
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
    private val downloadDao: com.cognitivechaos.xdownload.data.db.DownloadDao,
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

    // Navigation counter — incremented ONLY by user-initiated navigateTo() calls.
    // WebView-internal navigation (back, forward, link clicks) does NOT touch this.
    private val _navigationVersion = MutableStateFlow(0)
    val navigationVersion: StateFlow<Int> = _navigationVersion.asStateFlow()

    // Incremented when the active tab changes so the UI can swap WebViews.
    private val _tabSwitchVersion = MutableStateFlow(0)
    val tabSwitchVersion: StateFlow<Int> = _tabSwitchVersion.asStateFlow()

    // Delayed loading finish job for smooth progress bar fade-out
    private var loadingFinishJob: Job? = null

    // Error state
    private val _isNetworkError = MutableStateFlow(false)
    val isNetworkError: StateFlow<Boolean> = _isNetworkError.asStateFlow()

    private val _networkErrorCode = MutableStateFlow(0)
    val networkErrorCode: StateFlow<Int> = _networkErrorCode.asStateFlow()

    private val _networkErrorDescription = MutableStateFlow("")
    val networkErrorDescription: StateFlow<String> = _networkErrorDescription.asStateFlow()

    // Media detection
    val detectedMedia: StateFlow<List<DetectedMedia>> = videoDetector.detectedMedia
    val hasDetectedMedia: StateFlow<Boolean> = videoDetector.hasMedia

    // Continuously-ranked candidates — recomputed in the background whenever detectedMedia changes.
    // No ranking happens at click time; by the time the user taps the FAB the order is already known.
    val rankedCandidates: StateFlow<List<DetectedMedia>> = videoDetector.detectedMedia
        .map { candidates ->
            VideoRanker.rank(candidates, videoDetector.prefetchedFileSizes)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val topCandidate: StateFlow<DetectedMedia?> = rankedCandidates
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val otherCandidates: StateFlow<List<DetectedMedia>> = rankedCandidates
        .map { it.drop(1) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Quality options for bottom sheet
    private val _qualityOptions = MutableStateFlow<List<com.cognitivechaos.xdownload.data.model.MediaQualityOption>>(emptyList())
    val qualityOptions: StateFlow<List<com.cognitivechaos.xdownload.data.model.MediaQualityOption>> = _qualityOptions.asStateFlow()

    private val _selectedMedia = MutableStateFlow<com.cognitivechaos.xdownload.data.model.DetectedMedia?>(null)
    val selectedMedia: StateFlow<com.cognitivechaos.xdownload.data.model.DetectedMedia?> = _selectedMedia.asStateFlow()

    private val _isLoadingQualities = MutableStateFlow(false)
    val isLoadingQualities: StateFlow<Boolean> = _isLoadingQualities.asStateFlow()

    private val _showQualitySheet = MutableStateFlow(false)
    val showQualitySheet: StateFlow<Boolean> = _showQualitySheet.asStateFlow()

    private val _showNoVideoMessage = MutableStateFlow(false)
    val showNoVideoMessage: StateFlow<Boolean> = _showNoVideoMessage.asStateFlow()

    private val _showCopyrightBlockMessage = MutableStateFlow(false)
    val showCopyrightBlockMessage: StateFlow<Boolean> = _showCopyrightBlockMessage.asStateFlow()

    val isBlockedDomain: StateFlow<Boolean> = _currentUrl
        .map { videoDetector.isDownloadBlockedDomain() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Cancelable job for fetching quality options. Prevents stale/duplicated work
    // from keeping the quality sheet in a loading state.
    private var qualityFetchJob: Job? = null

    // Background prefetch so the sheet is instant on click.
    private var qualityPrefetchJob: Job? = null
    private var lastPrefetchedUrl: String? = null

    // Menu state
    private val _showMenu = MutableStateFlow(false)
    val showMenu: StateFlow<Boolean> = _showMenu.asStateFlow()

    // Tab manager
    private val _showTabManager = MutableStateFlow(false)
    val showTabManager: StateFlow<Boolean> = _showTabManager.asStateFlow()

    // Incognito mode
    private val _isIncognito = MutableStateFlow(false)
    val isIncognito: StateFlow<Boolean> = _isIncognito.asStateFlow()

    // History panel
    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    val history = historyDao.getAllHistory().stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

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

    init {
        // Prefetch quality options as soon as we have a strong candidate.
        // This avoids doing the heavy work at click time.
        viewModelScope.launch {
            rankedCandidates
                .collectLatest { ranked ->
                    if (ranked.isEmpty()) return@collectLatest

                    // Don't prefetch while the sheet is open/loading; click flow will handle it.
                    if (_showQualitySheet.value) return@collectLatest

                    delay(400) // debounce: let detection settle (reduces wasted work on noisy pages)

                    val candidate = ranked.first()
                    val url = candidate.url
                    if (url.isBlank() || url == lastPrefetchedUrl) return@collectLatest

                    lastPrefetchedUrl = url
                    qualityPrefetchJob?.cancel()
                    qualityPrefetchJob = launch(Dispatchers.IO) {
                        val startMs = System.currentTimeMillis()
                        // Keep this bounded; user will still be able to click and fetch on-demand.
                        withTimeoutOrNull(10_000L) { videoDetector.prefetchQualityOptions(candidate) }
                        Log.d(TAG, "Prefetch: ${url.take(80)} in ${System.currentTimeMillis() - startMs}ms")
                    }
                }
        }

        // Keep the top section synced with topCandidate while the sheet is open.
        // If ranking changes (e.g., main video detected after ad), update the displayed media.
        viewModelScope.launch {
            topCandidate.collect { top ->
                if (_showQualitySheet.value && top != null && _selectedMedia.value != top) {
                    _selectedMedia.value = top
                    // Re-fetch quality options for the new top candidate
                    cancelQualityFetch()
                    qualityFetchJob = launch(Dispatchers.IO) {
                        val options = try {
                            withTimeoutOrNull(8_000L) { videoDetector.fetchQualityOptions(top) } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        withContext(Dispatchers.Main) {
                            _qualityOptions.value = options
                                .filter { it.fileSize == null || it.fileSize > 100_000L }
                                .sortedByDescending {
                                    val num = Regex("\\d+").find(it.quality.lowercase())?.value?.toInt() ?: 0
                                    num + if (it.fileSize != null && it.fileSize > 0L) 100000 else 0
                                }
                                .ifEmpty { options }
                            _isLoadingQualities.value = false
                        }
                    }
                }
            }
        }
    }

    fun onUrlChanged(url: String) {
        // Bug fix #12: Clear error state on any URL change so the error screen
        // is dismissed when navigating away (e.g., Home button in bottom nav)
        clearPageError()
        cancelQualityFetch()
        cancelQualityPrefetch()
        _currentUrl.value = url
        videoDetector.clearDetectedMedia()
        updateActiveTab(url = url)
    }


    fun onPageStarted(tabId: String, url: String) {
        // Always update this specific tab's metadata
        updateTab(tabId, url = url)
        // Only update global UI state if this is the active tab
        if (!isActiveTab(tabId)) return
        clearPageError()
        cancelQualityFetch()
        cancelQualityPrefetch()
        loadingFinishJob?.cancel()
        _isLoading.value = true
        _loadingProgress.value = 0
        _currentUrl.value = url
        videoDetector.clearDetectedMedia()
        videoDetector.setCurrentPage(url, _currentTitle.value)
    }

    fun onPageFinished(tabId: String, url: String) {
        // Always update this specific tab's metadata
        updateTab(tabId, url = url)
        // Only update global UI state if this is the active tab
        if (!isActiveTab(tabId)) return
        _currentUrl.value = url
        // Delay hiding the loading bar so the progress animation can finish smoothly
        loadingFinishJob?.cancel()
        loadingFinishJob = viewModelScope.launch {
            delay(300)
            _isLoading.value = false
            _loadingProgress.value = 0
        }

        // Record history (always, regardless of active tab)
        if (!_isIncognito.value) {
            viewModelScope.launch {
                if (url.isNotEmpty() && url != "about:blank") {
                    historyDao.insertHistory(HistoryEntity(url = url, title = _currentTitle.value))
                }
            }
        }
    }

    fun onProgressChanged(tabId: String, progress: Int) {
        if (!isActiveTab(tabId)) return
        _loadingProgress.value = progress
    }

    fun onTitleChanged(tabId: String, title: String) {
        updateTab(tabId, title = title)
        if (!isActiveTab(tabId)) return
        _currentTitle.value = title
        videoDetector.setCurrentPage(_currentUrl.value, title)
    }

    /** Overload without tabId — used by non-WebView callers (menu Home, tab manager, etc.) */
    fun onTitleChanged(title: String) {
        _currentTitle.value = title
        updateActiveTab(title = title)
    }

    fun navigateTo(url: String) {
        clearPageError()
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
        // Signal the UI to call webView.loadUrl()
        _navigationVersion.value++
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
        // Block downloads on Google-owned domains (YouTube, etc.)
        if (videoDetector.isDownloadBlockedDomain()) {
            _showCopyrightBlockMessage.value = true
            viewModelScope.launch {
                delay(3000)
                _showCopyrightBlockMessage.value = false
            }
            return
        }

        if (hasDetectedMedia.value) {
            val ranked = rankedCandidates.value
            if (ranked.isNotEmpty()) {
                _showQualitySheet.value = true
                _qualityOptions.value = emptyList()
                _isLoadingQualities.value = true

                // Log score breakdown for each candidate (debug only)
                ranked.forEach { candidate ->
                    val breakdown = VideoRanker.score(candidate, videoDetector.prefetchedFileSizes[candidate.url])
                    Log.d(TAG, "Rank score: url=${candidate.url.take(100)} total=${breakdown.total} " +
                        "[type=${breakdown.typeScore} temporal=${breakdown.temporalBonus} " +
                        "size=${breakdown.fileSizeBonus} playback=${breakdown.playbackBonus} " +
                        "adUrl=${breakdown.adUrlPenalty} adUi=${breakdown.adUiPenalty} " +
                        "tiny=${breakdown.tinyFilePenalty}]")
                }

                // Top candidate is already known — show it immediately while options load
                // Use topCandidate.value instead of ranked.first() so it updates if ranking changes
                _selectedMedia.value = topCandidate.value

                cancelQualityFetch()
                qualityFetchJob = viewModelScope.launch(Dispatchers.IO) {
                    val startMs = System.currentTimeMillis()
                    Log.d(TAG, "QualitySheet: ranked candidates=${ranked.size}")

                    // Fetch options for the top few candidates and stop as soon as we get a usable set.
                    val maxCandidatesToTry = minOf(3, ranked.size)
                    var finalMedia: DetectedMedia? = null
                    var finalOptions: List<MediaQualityOption> = emptyList()

                    for (i in 0 until maxCandidatesToTry) {
                        val media = ranked[i]
                        val options = try {
                            withTimeoutOrNull(8_000L) { videoDetector.fetchQualityOptions(media) } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (options.isNotEmpty()) {
                            finalMedia = media
                            finalOptions = options
                            break
                        }
                    }

                    // Safety fallback: if fast-path found nothing, scan all ranked candidates.
                    if (finalOptions.isEmpty() && ranked.isNotEmpty()) {
                        Log.d(TAG, "QualitySheet: fast-path empty, falling back to full scan (${ranked.size} items)")
                        val results = try {
                            withTimeoutOrNull(20_000L) {
                                coroutineScope {
                                    ranked.map { media ->
                                        async {
                                            val options = try {
                                                videoDetector.fetchQualityOptions(media)
                                            } catch (_: Exception) {
                                                emptyList()
                                            }
                                            Pair(media, options)
                                        }
                                    }.awaitAll()
                                }
                            } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }

                        if (results.isNotEmpty()) {
                            val groupedResults = results
                                .filter { it.second.isNotEmpty() }
                                .groupBy { it.first.title }
                                .values
                                .map { groupList ->
                                    val combinedOptions = groupList
                                        .flatMap { it.second }
                                        .groupBy { it.quality }
                                        .map { entry ->
                                            entry.value.maxByOrNull { it.fileSize ?: 0L } ?: entry.value.first()
                                        }

                                    val maxFileSize = combinedOptions.maxOfOrNull { it.fileSize ?: 0L } ?: 0L
                                    val hasM3u8 = groupList.any { it.first.url.contains(".m3u8") }

                                    var score = maxFileSize
                                    if (combinedOptions.size > 1) score += 10_000_000_000L
                                    if (hasM3u8) score += 5_000_000_000L

                                    Triple(groupList.first().first, combinedOptions, score)
                                }

                            val bestGroup = groupedResults.maxByOrNull { it.third }
                            if (bestGroup != null) {
                                finalMedia = bestGroup.first
                                finalOptions = bestGroup.second
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (finalMedia != null) _selectedMedia.value = finalMedia
                        _qualityOptions.value = finalOptions
                            .filter { it.fileSize == null || it.fileSize > 100_000L }
                            .sortedByDescending {
                                val qstr = it.quality.lowercase()
                                val num = Regex("\\d+").find(qstr)?.value?.toInt() ?: 0
                                num + if (it.fileSize != null && it.fileSize > 0L) 100000 else 0
                            }
                            .ifEmpty { finalOptions }
                        _isLoadingQualities.value = false
                    }

                    Log.d(TAG, "QualitySheet: ready in ${System.currentTimeMillis() - startMs}ms options=${finalOptions.size}")
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
        cancelQualityFetch()
    }

    fun startDownload(option: com.cognitivechaos.xdownload.data.model.MediaQualityOption) {
        _showQualitySheet.value = false
        cancelQualityFetch()
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
        clearPageError()
        val newTab = BrowserTab(isActive = true)
        val updatedTabs = _tabs.value.map { it.copy(isActive = false) } + newTab
        _tabs.value = updatedTabs
        _activeTabIndex.value = updatedTabs.size - 1
        _currentUrl.value = ""
        _currentTitle.value = "New Tab"
        videoDetector.clearDetectedMedia()
    }

    fun switchToTab(index: Int) {
        clearPageError()
        if (index in _tabs.value.indices) {
            cancelQualityFetch()
            cancelQualityPrefetch()
            loadingFinishJob?.cancel()
            _isLoading.value = false
            _loadingProgress.value = 0
            videoDetector.clearDetectedMedia()
            val updatedTabs = _tabs.value.mapIndexed { i, tab ->
                tab.copy(isActive = i == index)
            }
            _tabs.value = updatedTabs
            _activeTabIndex.value = index
            val tab = updatedTabs[index]
            _currentUrl.value = tab.url
            _currentTitle.value = tab.title
            // Signal UI to swap to this tab's WebView (no reload needed).
            _tabSwitchVersion.value++
        }
    }

    fun closeTab(index: Int) {
        clearPageError()
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
        clearPageError()
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

    /** Update a specific tab's metadata by its ID (used by background WebView callbacks). */
    fun updateTab(tabId: String, url: String? = null, title: String? = null) {
        val updatedTabs = _tabs.value.toMutableList()
        val index = updatedTabs.indexOfFirst { it.id == tabId }
        if (index >= 0) {
            val tab = updatedTabs[index]
            updatedTabs[index] = tab.copy(
                url = url ?: tab.url,
                title = title ?: tab.title
            )
            _tabs.value = updatedTabs
        }
    }

    /** Check if a given tab ID is currently the active (foreground) tab. */
    fun isActiveTab(tabId: String): Boolean {
        if (tabId == "incognito") return _isIncognito.value
        val activeTab = _tabs.value.getOrNull(_activeTabIndex.value)
        return activeTab?.id == tabId
    }

    // Menu
    fun toggleMenu() { _showMenu.value = !_showMenu.value }
    fun dismissMenu() { _showMenu.value = false }

    // Tab Manager
    fun showTabManager() { _showTabManager.value = true }
    fun dismissTabManager() { _showTabManager.value = false }

    fun onPageError(errorCode: Int, description: String) {
        _networkErrorCode.value = errorCode
        _networkErrorDescription.value = description
        _isNetworkError.value = true
    }

    fun clearPageError() {
        _isNetworkError.value = false
        _networkErrorCode.value = 0
        _networkErrorDescription.value = ""
    }

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

    // Incognito
    fun toggleIncognito() {
        _isIncognito.value = !_isIncognito.value
    }

    // History
    fun showHistory() { _showHistory.value = true }
    fun dismissHistory() { _showHistory.value = false }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { historyDao.deleteHistoryById(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { historyDao.clearHistory() }
    }

    fun clearHistoryBefore(timestamp: Long) {
        viewModelScope.launch { historyDao.deleteHistoryBefore(timestamp) }
    }

    fun clearHistorySince(timestamp: Long) {
        viewModelScope.launch { historyDao.deleteHistorySince(timestamp) }
    }

    fun onVideoSignalsReceived(signals: List<VideoSignal>) {
        signals.forEach { signal ->
            videoDetector.updateCandidateSignals(
                url = signal.url,
                isPlaying = signal.isPlaying,
                isVisible = signal.isVisible,
                hasAdUIPatterns = signal.hasAdUIPatterns
            )
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

    private fun cancelQualityFetch() {
        qualityFetchJob?.cancel()
        qualityFetchJob = null
        _isLoadingQualities.value = false
    }

    private fun cancelQualityPrefetch() {
        qualityPrefetchJob?.cancel()
        qualityPrefetchJob = null
        lastPrefetchedUrl = null
    }
}