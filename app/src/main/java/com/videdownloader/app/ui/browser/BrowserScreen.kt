@file:Suppress("DEPRECATION")
package com.videdownloader.app.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.videdownloader.app.ui.theme.Orange500
import kotlinx.coroutines.delay
import org.json.JSONArray

data class QuickAccessSite(
    val name: String,
    val url: String,
    val color: Color,
    val icon: String
)

val quickAccessSites = listOf(
    QuickAccessSite("Google", "https://www.google.com", Color(0xFF4285F4), "G"),
    QuickAccessSite("Vimeo", "https://www.vimeo.com", Color(0xFF1AB7EA), "V"),
    QuickAccessSite("Dailymotion", "https://www.dailymotion.com", Color(0xFF0066DC), "D"),
    QuickAccessSite("Twitter", "https://www.twitter.com", Color(0xFF1DA1F2), "T"),
    QuickAccessSite("Facebook", "https://www.facebook.com", Color(0xFF1877F2), "F"),
    QuickAccessSite("Instagram", "https://www.instagram.com", Color(0xFFE4405F), "I"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    sharedWebView: WebView,
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val currentUrl by viewModel.currentUrl.collectAsState()
    val currentTitle by viewModel.currentTitle.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadingProgress by viewModel.loadingProgress.collectAsState()
    val hasMedia by viewModel.hasDetectedMedia.collectAsState()
    val detectedMedia by viewModel.detectedMedia.collectAsState()
    val showQualitySheet by viewModel.showQualitySheet.collectAsState()
    val qualityOptions by viewModel.qualityOptions.collectAsState()
    val isLoadingQualities by viewModel.isLoadingQualities.collectAsState()
    val showNoVideoMessage by viewModel.showNoVideoMessage.collectAsState()
    val showMenu by viewModel.showMenu.collectAsState()
    val showTabManager by viewModel.showTabManager.collectAsState()
    val showBookmarks by viewModel.showBookmarks.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val blockAds by viewModel.blockAds.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showHomePage by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var backPressedOnce by remember { mutableStateOf(false) }

    // Handle back press to intelligently dismiss overlays, navigate back, or confirm exit
    BackHandler(enabled = true) {
        when {
            showQualitySheet -> viewModel.dismissQualitySheet()
            showTabManager -> viewModel.dismissTabManager()
            showBookmarks -> viewModel.dismissBookmarks()
            webView?.canGoBack() == true -> webView?.goBack()
            !showHomePage -> {
                showHomePage = true
                urlInput = ""
                viewModel.navigateTo("")
                viewModel.onTitleChanged("New Tab")
            }
            else -> {
                if (backPressedOnce) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    backPressedOnce = true
                    android.widget.Toast.makeText(context, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Reset the exit confirmation after 2 seconds
    LaunchedEffect(backPressedOnce) {
        if (backPressedOnce) {
            kotlinx.coroutines.delay(2000)
            backPressedOnce = false
        }
    }

    // Navigate when URL changes
    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotEmpty() && currentUrl != webView?.url) {
            showHomePage = false
            webView?.loadUrl(currentUrl)
            urlInput = currentUrl
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== TOP BAR =====
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    // Search/URL bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Search bar
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            placeholder = {
                                Text(
                                    "Search or enter website",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = Orange500,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                if (urlInput.isNotEmpty()) {
                                    IconButton(onClick = { urlInput = "" }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (urlInput.isNotEmpty()) {
                                        viewModel.navigateTo(urlInput)
                                        focusManager.clearFocus()
                                    }
                                }
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Orange500,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Menu button
                        Box {
                            IconButton(onClick = { viewModel.toggleMenu() }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Menu",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Dropdown menu
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { viewModel.dismissMenu() }
                            ) {
                                // Menu header icons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    IconButton(onClick = {
                                        viewModel.dismissMenu()
                                        webView?.goForward()
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                                    }
                                    IconButton(onClick = {
                                        viewModel.dismissMenu()
                                        viewModel.navigateTo("")
                                        showHomePage = true
                                    }) {
                                        Icon(Icons.Default.Home, "Home")
                                    }
                                    IconButton(onClick = {
                                        viewModel.dismissMenu()
                                        viewModel.addBookmark()
                                    }) {
                                        Icon(Icons.Default.Star, "Bookmark")
                                    }
                                    IconButton(onClick = {
                                        viewModel.dismissMenu()
                                        webView?.reload()
                                    }) {
                                        Icon(Icons.Default.Refresh, "Reload")
                                    }
                                }

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = { Text("New Tab") },
                                    onClick = {
                                        viewModel.dismissMenu()
                                        viewModel.addNewTab()
                                        showHomePage = true
                                        urlInput = ""
                                    },
                                    leadingIcon = { Icon(Icons.Default.Add, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmarks") },
                                    onClick = {
                                        viewModel.dismissMenu()
                                        viewModel.toggleBookmarks()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Bookmarks, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        viewModel.dismissMenu()
                                        onNavigateToSettings()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) }
                                )
                            }
                        }
                    }

                    // Loading progress bar
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { loadingProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = Orange500,
                            trackColor = Color.Transparent
                        )
                    }
                }
            }

            // ===== CONTENT AREA =====
            Box(modifier = Modifier.weight(1f)) {
                if (showHomePage && currentUrl.isEmpty()) {
                    // Home page with quick access sites
                    HomePageContent(
                        onSiteClick = { site ->
                            urlInput = site.url
                            viewModel.navigateTo(site.url)
                            showHomePage = false
                            focusManager.clearFocus()
                        }
                    )
                } else {
                    // WebView
                    WebViewContent(
                        viewModel = viewModel,
                        sharedWebView = sharedWebView,
                        onWebViewCreated = { wv -> webView = wv },
                        onUrlChanged = { url ->
                            urlInput = url
                        }
                    )
                }

                // Floating download button
                FloatingDownloadButton(
                    hasMedia = hasMedia,
                    mediaCount = detectedMedia.size,
                    onClick = { viewModel.onDownloadFabClicked() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                )

                // "Play video first" message
                if (showNoVideoMessage) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF333333),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                tint = Orange500,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Play a video first, then download",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ===== BOTTOM NAVIGATION BAR =====
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavItem(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "Back",
                        onClick = { webView?.goBack() }
                    )
                    BottomNavItem(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        label = "Forward",
                        onClick = { webView?.goForward() }
                    )
                    BottomNavItem(
                        icon = Icons.Default.Home,
                        label = "Home",
                        onClick = {
                            showHomePage = true
                            urlInput = ""
                            viewModel.onUrlChanged("")
                            viewModel.onTitleChanged("New Tab")
                        }
                    )
                    val hasActiveDownloads by viewModel.hasActiveDownloads.collectAsState()
                    Box {
                        BottomNavItem(
                            icon = Icons.Default.Download,
                            label = "Downloads",
                            onClick = onNavigateToFiles
                        )
                        if (hasActiveDownloads) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = 16.dp, y = 4.dp)
                                    .size(10.dp),
                                shape = CircleShape,
                                color = Color(0xFFE53935)
                            ) {}
                        }
                    }
                    Box {
                        BottomNavItem(
                            icon = Icons.Default.Tab,
                            label = "Tabs",
                            onClick = { viewModel.showTabManager() }
                        )
                        // Tab count badge
                        if (tabs.size > 1) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 2.dp)
                                    .size(18.dp),
                                shape = CircleShape,
                                color = Orange500
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${tabs.size}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val selectedMedia by viewModel.selectedMedia.collectAsState()
        
        // Quality picker bottom sheet
        if (showQualitySheet) {
            QualityPickerSheet(
                title = selectedMedia?.title?.takeIf { it.isNotBlank() } ?: currentTitle,
                url = selectedMedia?.sourcePageUrl?.takeIf { it.isNotBlank() } ?: currentUrl,
                thumbnailUrl = selectedMedia?.thumbnailUrl,
                options = qualityOptions,
                isLoading = isLoadingQualities,
                onDismiss = { viewModel.dismissQualitySheet() },
                onDownload = { option -> viewModel.startDownload(option) }
            )
        }

        // Tab manager overlay
        if (showTabManager) {
            TabManagerOverlay(
                tabs = tabs,
                onTabClick = { index ->
                    viewModel.switchToTab(index)
                    viewModel.dismissTabManager()
                    showHomePage = false
                },
                onCloseTab = { index -> viewModel.closeTab(index) },
                onCloseAll = { viewModel.closeAllTabs() },
                onAddTab = {
                    viewModel.addNewTab()
                    viewModel.dismissTabManager()
                    showHomePage = true
                    urlInput = ""
                },
                onDone = { viewModel.dismissTabManager() }
            )
        }

        // Bookmarks overlay
        if (showBookmarks) {
            BookmarksOverlay(
                bookmarks = bookmarks,
                onBookmarkClick = { bookmark ->
                    viewModel.dismissBookmarks()
                    urlInput = bookmark.url
                    viewModel.navigateTo(bookmark.url)
                    showHomePage = false
                },
                onDismiss = { viewModel.dismissBookmarks() }
            )
        }
    }
}

@Composable
fun HomePageContent(onSiteClick: (QuickAccessSite) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "VideDownloader",
            style = MaterialTheme.typography.headlineMedium,
            color = Orange500
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Browse & Download Videos",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Quick access grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickAccessSites) { site ->
                QuickAccessItem(site = site, onClick = { onSiteClick(site) })
            }
        }
    }
}

@Composable
fun QuickAccessItem(site: QuickAccessSite, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = site.color
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = site.icon,
                    color = Color.White,
                    fontSize = 24.sp,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = site.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewContent(
    viewModel: BrowserViewModel,
    sharedWebView: WebView,
    onWebViewCreated: (WebView) -> Unit,
    onUrlChanged: (String) -> Unit
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            sharedWebView.apply {
                if (parent != null) {
                    (parent as ViewGroup).removeView(this)
                }
                
                // Setup layout and settings
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let {
                            viewModel.onPageStarted(it)
                            onUrlChanged(it)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { viewModel.onPageFinished(it) }
                        view?.title?.let { viewModel.onTitleChanged(it) }

                        // Helper to run JS detection and process results
                        fun runVideoDetection(v: WebView, pageUrl: String) {
                            v.evaluateJavascript("document.querySelector('meta[property=\"og:image\"]')?.content || document.querySelector('link[rel=\"apple-touch-icon\"]')?.href || ''") { thumbUrl ->
                                val cleanThumb = thumbUrl?.trim('"')?.replace("\\\"", "\"") ?: ""
                                viewModel.videoDetector.setCurrentThumbnail(cleanThumb)
                            }
                            v.evaluateJavascript(viewModel.videoDetector.getVideoDetectionJs()) { result ->
                                if (result != null && result != "null" && result != "\"[]\"") {
                                    try {
                                        // Bug fix #13: Use proper JSON parsing instead of manual string splitting
                                        val cleaned = result.trim('"').replace("\\\"", "\"")
                                        val jsonArray = JSONArray(cleaned)
                                        for (i in 0 until jsonArray.length()) {
                                            val videoUrl = jsonArray.optString(i, "")
                                            if (videoUrl.startsWith("http")) {
                                                viewModel.videoDetector.onVideoElementDetected(
                                                    videoUrl,
                                                    pageUrl,
                                                    v.title ?: ""
                                                )
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        // Run JS detection immediately
                        if (view != null && url != null) {
                            runVideoDetection(view, url)

                            // For Pornhub: re-run after delay because flashvars are
                            // set by deferred scripts that run after onPageFinished
                            val isPornhub = url.lowercase().let { u ->
                                u.contains("pornhub.com") || u.contains("pornhub.net") || u.contains("pornhub.org")
                            }
                            if (isPornhub) {
                                view.postDelayed({ runVideoDetection(view, url) }, 2500)
                                view.postDelayed({ runVideoDetection(view, url) }, 5000)
                            }
                        }
                    }

                    // Bug fix #8: Removed deprecated shouldInterceptRequest(WebView?, String?)
                    // overload — on API 21+ both overloads fire, causing double-detection.

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        request?.url?.toString()?.let { requestUrl ->
                            // Ad blocking
                            if (viewModel.blockAds.value && viewModel.adBlocker.isAd(requestUrl)) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                            // Video detection with MIME type from Accept header
                            val accept = request.requestHeaders?.get("Accept") ?: ""
                            viewModel.videoDetector.onResourceRequest(requestUrl, accept.takeIf { it.contains("video") || it.contains("audio") })
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        viewModel.onProgressChanged(newProgress)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        title?.let { viewModel.onTitleChanged(it) }
                    }
                }

                onWebViewCreated(this)
            }
        },
        update = { wv ->
            // Update could be used for other properties if needed
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun FloatingDownloadButton(
    hasMedia: Boolean,
    mediaCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (hasMedia) Orange500 else Color(0xFFBDBDBD),
        label = "fab_color"
    )

    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = animatedColor,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape)
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Download",
                modifier = Modifier.size(28.dp)
            )
        }

        // Media count badge
        if (hasMedia && mediaCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp),
                shape = CircleShape,
                color = Color(0xFFE53935)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "$mediaCount",
                        color = Color.White,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerSheet(
    title: String,
    url: String,
    thumbnailUrl: String?,
    options: List<com.videdownloader.app.data.model.MediaQualityOption>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onDownload: (com.videdownloader.app.data.model.MediaQualityOption) -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!thumbnailUrl.isNullOrBlank() && thumbnailUrl != "null") {
                    coil.compose.AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = "Thumbnail",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.VideoFile),
                        error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.VideoFile),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    // Title
                    Text(
                        text = title.ifEmpty { "Video" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = url.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange500)
                }
            } else if (options.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text("No downloadable media found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Quality options
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedIndex = index }
                            .background(
                                if (selectedIndex == index)
                                    Orange500.copy(alpha = 0.1f)
                                else
                                    Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            colors = RadioButtonDefaults.colors(selectedColor = Orange500)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.quality,
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (option.resolution != null) {
                                Text(
                                    text = option.resolution,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (option.fileSize != null && option.fileSize > 0) {
                            Text(
                                text = formatBytes(option.fileSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // No file size = server didn't respond to HEAD request = likely won't download
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFFFF3E0)
                            ) {
                                Text(
                                    text = "May fail",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color(0xFFE65100),
                                    fontSize = 10.sp,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // Quality badge
                        val isHD = option.quality.contains("1080") || option.quality.contains("720") || option.quality.contains("4K") || option.quality.contains("2160")
                        if (isHD) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Orange500
                            ) {
                                Text(
                                    text = "HD",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Download button
                Button(
                    onClick = {
                        if (options.isNotEmpty() && selectedIndex < options.size) {
                            onDownload(options[selectedIndex])
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange500)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
fun TabManagerOverlay(
    tabs: List<com.videdownloader.app.data.model.BrowserTab>,
    onTabClick: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onCloseAll: () -> Unit,
    onAddTab: () -> Unit,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // Tab cards
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tabs.size) { index ->
                    val tab = tabs[index]
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clickable { onTabClick(index) },
                        shape = RoundedCornerShape(12.dp),
                        border = if (tab.isActive) BorderStroke(2.dp, Orange500) else null,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column {
                            // Tab header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (tab.isActive) Orange500.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onCloseTab(index) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close tab",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tab.title.ifEmpty { tab.url.ifEmpty { "New Tab" } },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Tab preview placeholder
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (tab.url.isEmpty()) {
                                    Text(
                                        text = "New Tab",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = tab.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom controls
            Surface(
                modifier = Modifier.fillMaxWidth(),
                // Bug fix #15: Use theme color instead of hardcoded dark
                color = MaterialTheme.colorScheme.inverseSurface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCloseAll) {
                        Text(
                            "CLOSE ALL",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    IconButton(onClick = onAddTab) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add tab",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    TextButton(onClick = onDone) {
                        Text(
                            "DONE",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksOverlay(
    bookmarks: List<com.videdownloader.app.data.db.BookmarkEntity>,
    onBookmarkClick: (com.videdownloader.app.data.db.BookmarkEntity) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.6f)
                .align(Alignment.Center),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Bookmarks",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No bookmarks yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        bookmarks.forEach { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onBookmarkClick(bookmark) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Orange500,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.title.ifEmpty { bookmark.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = bookmark.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
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
