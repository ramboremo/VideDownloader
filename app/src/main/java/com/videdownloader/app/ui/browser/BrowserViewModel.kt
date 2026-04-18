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

    // Navigation counter — incremented ONLY by user-initiated navigateTo() calls.
    // WebView-internal navigation (back, forward, link clicks) does NOT touch this.
    private val _navigationVersion = MutableStateFlow(0)
    val navigationVersion: StateFlow<Int> = _navigationVersion.asStateFlow()

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
            detectedMedia
                .map { list -> list.asSequence().distinctBy { it.url }.toList() }
                .distinctUntilChanged()
                .collectLatest { list ->
                    if (list.isEmpty()) return@collectLatest

                    // Don't prefetch while the sheet is open/loading; click flow will handle it.
                    if (_showQualitySheet.value) return@collectLatest

                    delay(400) // debounce: let detection settle (reduces wasted work on noisy pages)

                    val candidate = list.maxByOrNull { scoreMediaCandidate(it) } ?: return@collectLatest
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

    fun onTitleChanged(title: String) {
        _currentTitle.value = title
        videoDetector.setCurrentPage(_currentUrl.value, title)
        updateActiveTab(title = title)
    }

    fun onPageStarted(url: String) {
        clearPageError()
        cancelQualityFetch()
        cancelQualityPrefetch()
        _isLoading.value = true
        _currentUrl.value = url
        videoDetector.clearDetectedMedia()
        videoDetector.setCurrentPage(url, _currentTitle.value)
    }

    fun onPageFinished(url: String) {
        _isLoading.value = false
        _currentUrl.value = url

        // Bug fix: Record history here (not onPageStarted) so the title is available
        if (!_isIncognito.value) {
            viewModelScope.launch {
                if (url.isNotEmpty() && url != "about:blank") {
                    historyDao.insertHistory(HistoryEntity(url = url, title = _currentTitle.value))
                }
            }
        }
    }

    fun onProgressChanged(progress: Int) {
        _loadingProgress.value = progress
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
        if (hasDetectedMedia.value) {
            val mediaList = detectedMedia.value
            if (mediaList.isNotEmpty()) {
                _showQualitySheet.value = true
                _qualityOptions.value = emptyList()
                _isLoadingQualities.value = true

                // Pick a best candidate immediately (no network calls) so the sheet can
                // show the right title/thumbnail while options load.
                val prioritized = mediaList
                    .asSequence()
                    .distinctBy { it.url }
                    .sortedByDescending { scoreMediaCandidate(it) }
                    .toList()

                _selectedMedia.value = prioritized.firstOrNull()

                cancelQualityFetch()
                qualityFetchJob = viewModelScope.launch(Dispatchers.IO) {
                    val startMs = System.currentTimeMillis()
                    Log.d(TAG, "QualitySheet: detected=${mediaList.size}, candidates=${prioritized.size}")

                    // Fetch options for only the top few candidates and stop as soon as we get
                    // a usable set. This avoids waiting on ads/noise resources.
                    val maxCandidatesToTry = minOf(3, prioritized.size)
                    var finalMedia: DetectedMedia? = null
                    var finalOptions: List<MediaQualityOption> = emptyList()

                    for (i in 0 until maxCandidatesToTry) {
                        val media = prioritized[i]
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

                    // Safety fallback: if fast-path found nothing, do the old full scan.
                    // This preserves download capability even on sites where the main video
                    // isn't among the top few candidates.
                    if (finalOptions.isEmpty() && prioritized.isNotEmpty()) {
                        Log.d(TAG, "QualitySheet: fast-path empty, falling back to full scan (${prioritized.size} items)")
                        val results = try {
                            withTimeoutOrNull(20_000L) {
                                coroutineScope {
                                    prioritized.map { media ->
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
                            .filter { it.fileSize == null || it.fileSize > 100_000L } // drop tiny noise files
                            .sortedByDescending {
                                val qstr = it.quality.lowercase()
                                val num = Regex("\\d+").find(qstr)?.value?.toInt() ?: 0
                                // Prefer entries where size is known, but don't require it.
                                num + if (it.fileSize != null && it.fileSize > 0L) 100000 else 0
                            }
                            .ifEmpty { finalOptions } // fallback: show all if filter removed everything
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

    fun startDownload(option: com.videdownloader.app.data.model.MediaQualityOption) {
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
            val updatedTabs = _tabs.value.mapIndexed { i, tab ->
                tab.copy(isActive = i == index)
            }
            _tabs.value = updatedTabs
            _activeTabIndex.value = index
            val tab = updatedTabs[index]
            _currentUrl.value = tab.url
            _currentTitle.value = tab.title
            // Switching tabs is a user-driven navigation event; trigger WebView load.
            _navigationVersion.value++
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

    private fun scoreMediaCandidate(media: DetectedMedia): Int {
        val url = media.url.lowercase()

        // Strongly prefer Pornhub get_media endpoints (fast + multiple MP4 qualities).
        val isPhGetMedia = (url.contains("get_media") &&
                (url.contains("pornhub.com") || url.contains("pornhub.net") || url.contains("pornhub.org")))

        val isDirectVideo = url.substringBefore("?").let { u ->
            u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m4v") || u.endsWith(".mov")
        }
        val isStream = url.contains(".m3u8") || url.contains(".mpd")

        // Known ad network domains and patterns — these should never be the main video.
        // File size is NOT a reliable signal (ads can be large, real videos can be small).
        val adPatterns = listOf(
            "vast", "vpaid", "preroll", "adserver", "doubleclick", "trafficjunky",
            "exoclick", "adnxs", "adsystem", "adtech", "advertising", "adform",
            "smartadserver", "rubiconproject", "openx", "pubmatic", "appnexus",
            "spotxchange", "spotx", "freewheel", "innovid", "tremor", "taboola",
            "outbrain", "revcontent", "mgid", "propellerads", "adsterra",
            "cdn77-vid",   // TrafficJunky pre-roll CDN
            "magsrv",      // MagSrv ad network
            "/ads/", "/ad/", "ad_tag", "adtag", "ad_unit", "adunit",
            "preroll", "midroll", "postroll",
            "vast.xml", "vast.php", "vast?", "vpaid.js"
        )
        val looksLikeAd = adPatterns.any { url.contains(it) }

        // Penalize tiny files (< 500 KB) — real videos are never this small.
        val fileSize = videoDetector.prefetchedFileSizes[media.url]
        val isTiny = fileSize != null && fileSize < 500_000L

        var score = 0
        if (isPhGetMedia) score += 10_000
        if (isDirectVideo) score += 5_000
        if (isStream) score += 3_000
        if (!media.thumbnailUrl.isNullOrBlank() && media.thumbnailUrl != "null") score += 200
        if (!media.title.isNullOrBlank()) score += 50
        if (looksLikeAd) score -= 8_000
        if (isTiny) score -= 8_000
        return score
    }
}
