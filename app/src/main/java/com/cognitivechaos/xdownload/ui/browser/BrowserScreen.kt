@file:Suppress("DEPRECATION")
package com.cognitivechaos.xdownload.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.splineBasedDecay
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.cognitivechaos.xdownload.data.db.HistoryEntity
import com.cognitivechaos.xdownload.data.model.ContextMenuTarget
import com.cognitivechaos.xdownload.data.model.PendingGeneralDownload
import com.cognitivechaos.xdownload.service.VideoDetector
import com.cognitivechaos.xdownload.ui.theme.Orange500
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

import androidx.compose.ui.res.painterResource
import com.cognitivechaos.xdownload.R

data class QuickAccessSite(
    val name: String,
    val url: String,
    val color: Color,
    val iconRes: Int
)

private data class PendingClosedTabUndo(
    val tab: com.cognitivechaos.xdownload.data.model.BrowserTab,
    val index: Int,
    val wasActive: Boolean
)

val quickAccessSites = listOf(
    QuickAccessSite("Google", "https://www.google.com", Color(0xFF4285F4), R.drawable.ic_google),
    QuickAccessSite("Vimeo", "https://www.vimeo.com", Color(0xFF1AB7EA), R.drawable.ic_vimeo),
    QuickAccessSite("Dailymotion", "https://www.dailymotion.com", Color(0xFF0066DC), R.drawable.ic_dailymotion),
    QuickAccessSite("Twitter", "https://www.twitter.com", Color(0xFF1DA1F2), R.drawable.ic_twitter),
    QuickAccessSite("Facebook", "https://www.facebook.com", Color(0xFF1877F2), R.drawable.ic_facebook),
    QuickAccessSite("Instagram", "https://www.instagram.com", Color(0xFFE4405F), R.drawable.ic_instagram),
)

private const val WEBVIEW_VIDEO_HIT_TYPE = 10
private const val INTERNAL_BLANK_URL = "about:blank"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
    initialUrl: String? = null,
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onWebViewReady: (WebView) -> Unit = {}
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
    val showCopyrightBlockMessage by viewModel.showCopyrightBlockMessage.collectAsState()
    val isBlockedDomain by viewModel.isBlockedDomain.collectAsState()
    val showMenu by viewModel.showMenu.collectAsState()
    val showTabManager by viewModel.showTabManager.collectAsState()
    val showBookmarks by viewModel.showBookmarks.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val isIncognito by viewModel.isIncognito.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTabIndex by viewModel.activeTabIndex.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()
    val isNetworkError by viewModel.isNetworkError.collectAsState()
    val networkErrorCode by viewModel.networkErrorCode.collectAsState()
    val networkErrorDescription by viewModel.networkErrorDescription.collectAsState()
    val contextMenuTarget by viewModel.contextMenuTarget.collectAsState()
    val pendingGeneralDownload by viewModel.pendingGeneralDownload.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var tabSwitcherListMode by remember { mutableStateOf(false) }
    var newestTabId by remember { mutableStateOf<String?>(null) }
    var pendingClosedTabUndo by remember { mutableStateOf<PendingClosedTabUndo?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // --- Per-tab WebView management ---
    // Each tab gets its own WebView instance so it retains its own back stack,
    // scroll position, and page content independently.
    val tabWebViews = remember { mutableStateMapOf<String, WebView>() }
    val tabPreviewBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val activeTab = tabs.getOrNull(activeTabIndex)
    val activeTabId = activeTab?.id ?: ""
    val showHomePage = currentUrl.isEmpty()

    // Provide a WebView for the active tab (create lazily)
    val activeWebView = remember(activeTabId) {
        if (activeTabId.isNotEmpty()) {
            tabWebViews.getOrPut(activeTabId) {
                WebView(context).also { onWebViewReady(it) }
            }
        } else null
    }
    // Convenience reference for the current "usable" webView
    val webView = activeWebView

    // Clean up WebViews for closed tabs
    LaunchedEffect(tabs, pendingClosedTabUndo?.tab?.id) {
        val liveIds = tabs.map { it.id }.toSet()
        val preservedIds = setOfNotNull(pendingClosedTabUndo?.tab?.id)
        tabWebViews.keys.filter { it !in liveIds && it !in preservedIds }.forEach { closedId ->
            tabWebViews.remove(closedId)?.destroy()
        }
        tabPreviewBitmaps.keys
            .filter { it !in liveIds && it !in preservedIds }
            .forEach { removedId ->
                tabPreviewBitmaps.remove(removedId)?.recycle()
            }
    }

    fun closeTabWithUndo(index: Int) {
        val closedTab = tabs.getOrNull(index) ?: return
        if (tabs.size <= 1) {
            viewModel.closeTab(index)
            return
        }

        pendingClosedTabUndo = null
        snackbarHostState.currentSnackbarData?.dismiss()

        val undoData = PendingClosedTabUndo(
            tab = closedTab,
            index = index,
            wasActive = index == activeTabIndex
        )
        pendingClosedTabUndo = undoData
        viewModel.closeTab(index)

        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Tab ${undoData.index + 1} dismissed",
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            val pending = pendingClosedTabUndo
            if (pending?.tab?.id == undoData.tab.id) {
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.restoreClosedTab(
                        tab = pending.tab,
                        index = pending.index,
                        makeActive = pending.wasActive
                    )
                }
                pendingClosedTabUndo = null
            }
        }
    }

    fun pauseMediaInAllTabs() {
        val pauseMediaJs = """
            (function() {
              document.querySelectorAll('video,audio').forEach(function(media) {
                try { media.pause(); } catch (e) {}
              });
            })();
        """.trimIndent()
        tabWebViews.values.forEach { wv ->
            wv.evaluateJavascript(pauseMediaJs, null)
            wv.onPause()
        }
    }

    // Pause background WebViews, resume the active one.
    // onPause() stops JS execution and timers, preventing background tabs from
    // firing callbacks that overwrite the active tab's URL bar and loading state.
    LaunchedEffect(activeTabId, showTabManager) {
        if (showTabManager) {
            pauseMediaInAllTabs()
        } else {
            tabWebViews.forEach { (id, wv) ->
                if (id == activeTabId) wv.onResume() else wv.onPause()
            }
        }
    }

    LaunchedEffect(activeTabId, isIncognito, currentUrl) {
        if (currentUrl.isEmpty()) {
            activeWebView?.apply {
                stopLoading()
                clearHistory()
                clearFormData()
                if (isIncognito) clearCache(true)
            }
        }
    }

    // Blank tabs keep their WebView state reset so private and normal history
    // cannot leak through when the same tab changes browsing mode.
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            urlInput = initialUrl
            viewModel.navigateTo(initialUrl)
        }
    }

    // --- Fullscreen Video State ---
    var fullScreenCustomView by remember { mutableStateOf<android.view.View?>(null) }
    var fullScreenCustomViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // Toggle system UI for true fullscreen padding override
    val activity = context as? android.app.Activity
    val window = activity?.window
    LaunchedEffect(fullScreenCustomView) {
        if (window != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            val isFullscreen = fullScreenCustomView != null

            if (isFullscreen) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                // Extra fallback for devices/Android versions where insets controller
                // doesn't fully hide bars for WebView video.
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    var backPressedOnce by remember { mutableStateOf(false) }

    // Handle back press to intelligently dismiss overlays, navigate back, or confirm exit
    BackHandler(enabled = true) {
        val activeWv = webView
        when {
            contextMenuTarget != null -> viewModel.dismissContextMenu()
            pendingGeneralDownload != null -> viewModel.dismissGeneralDownload()
            showQualitySheet -> viewModel.dismissQualitySheet()
            showTabManager -> viewModel.dismissTabManager()
            showBookmarks -> viewModel.dismissBookmarks()
            showHistory -> viewModel.dismissHistory()
            activeWv?.url == INTERNAL_BLANK_URL -> {
                activeWv.clearHistory()
                urlInput = ""
                viewModel.navigateTo("")
                viewModel.onTitleChanged(if (isIncognito) "Incognito" else "New Tab")
            }
            activeWv?.canGoBack() == true -> activeWv.goBack()
            !showHomePage -> {
                urlInput = ""
                viewModel.navigateTo("")
                viewModel.onTitleChanged(if (isIncognito) "Incognito" else "New Tab")
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

    // Navigate ONLY when the user explicitly triggers navigation (URL bar, quick access, etc.)
    val navigationVersion by viewModel.navigationVersion.collectAsState()
    LaunchedEffect(navigationVersion, activeTabId, activeWebView) {
        if (currentUrl.isNotEmpty()) {
            val activeWv = webView
            if (activeWv != null && currentUrl != activeWv.url) {
                activeWv.loadUrl(currentUrl)
            }
            urlInput = currentUrl
        } else {
            urlInput = ""
        }
    }

    // Sync URL bar when switching tabs (no page reload needed — each tab has its own WebView)
    val tabSwitchVersion by viewModel.tabSwitchVersion.collectAsState()
    LaunchedEffect(tabSwitchVersion) {
        urlInput = currentUrl
    }

    // Re-run JS video detection on the active tab's WebView after a tab switch.
    // The page is already loaded — we just re-evaluate the detection JS to find
    // <video> elements and og:image thumbnails. Site-specific extractors (XNXX,
    // XVideos, etc.) are re-triggered from switchToTab() via setCurrentPage().
    val redetectVersion by viewModel.redetectVersion.collectAsState()
    LaunchedEffect(redetectVersion) {
        val wv = activeWebView ?: return@LaunchedEffect
        val pageUrl = currentUrl
        if (pageUrl.isEmpty()) return@LaunchedEffect

        // Small delay to let the WebView fully resume after tab switch
        kotlinx.coroutines.delay(300)

        // Extract thumbnail from og:image meta tag
        wv.evaluateJavascript(
            "document.querySelector('meta[property=\"og:image\"]')?.content || document.querySelector('link[rel=\"apple-touch-icon\"]')?.href || ''"
        ) { thumbUrl ->
            val cleanThumb = thumbUrl?.trim('"')?.replace("\\\"", "\"") ?: ""
            viewModel.videoDetector.setCurrentThumbnail(cleanThumb)
        }

        // Run JS-based video element detection
        wv.evaluateJavascript(viewModel.videoDetector.getVideoDetectionJs()) { result ->
            if (result != null && result != "null" && result != "\"[]\"") {
                try {
                    val cleaned = result.trim('"').replace("\\\"", "\"")
                    val jsonArray = org.json.JSONArray(cleaned)
                    for (i in 0 until jsonArray.length()) {
                        val videoUrl = jsonArray.optString(i, "")
                        if (videoUrl.startsWith("http")) {
                            viewModel.videoDetector.onVideoElementDetected(
                                videoUrl,
                                pageUrl,
                                wv.title ?: ""
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tabPreviewBitmaps.values.forEach { it.recycle() }
            tabPreviewBitmaps.clear()
        }
    }

    fun capturePreviewBitmap(source: WebView): Bitmap? {
        val sourceWidth = source.width.takeIf { it > 0 } ?: source.measuredWidth
        val sourceHeight = source.height.takeIf { it > 0 } ?: source.measuredHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val targetWidth = 420
        val scale = if (sourceWidth > targetWidth) targetWidth.toFloat() / sourceWidth else 1f
        val bitmapWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        source.draw(canvas)
        return bitmap
    }

    fun refreshTabPreviewsForSwitcher() {
        tabs.forEach { tab ->
            val preview = tabWebViews[tab.id]?.let(::capturePreviewBitmap)
            if (preview != null) {
                tabPreviewBitmaps.remove(tab.id)?.recycle()
                tabPreviewBitmaps[tab.id] = preview
            }
        }
    }

    LaunchedEffect(showTabManager, tabs, isIncognito) {
        if (!showTabManager) return@LaunchedEffect
        refreshTabPreviewsForSwitcher()
    }

    val topBarAlpha by animateFloatAsState(
        targetValue = if (showTabManager) 0f else 1f,
        animationSpec = tween(360, easing = LinearOutSlowInEasing),
        label = "topBarAlpha"
    )
    val topBarOffset by animateDpAsState(
        targetValue = if (showTabManager) (-28).dp else 0.dp,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "topBarOffset"
    )
    val bottomBarAlpha by animateFloatAsState(
        targetValue = if (showTabManager) 0f else 1f,
        animationSpec = tween(360, easing = LinearOutSlowInEasing),
        label = "bottomBarAlpha"
    )
    val bottomBarOffset by animateDpAsState(
        targetValue = if (showTabManager) 110.dp else 0.dp,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "bottomBarOffset"
    )
    val browserScale by animateFloatAsState(
        targetValue = if (showTabManager) 0.92f else 1f,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "browserScale"
    )
    val browserOffsetY by animateDpAsState(
        targetValue = if (showTabManager) 10.dp else 0.dp,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "browserOffsetY"
    )
    val browserAlpha by animateFloatAsState(
        targetValue = if (showTabManager) 0.05f else 1f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "browserAlpha"
    )
    val browserCornerRadius by animateDpAsState(
        targetValue = if (showTabManager) 24.dp else 0.dp,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "browserCornerRadius"
    )
    val switcherScrimAlpha by animateFloatAsState(
        targetValue = if (showTabManager) 0.74f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "switcherScrimAlpha"
    )
    val fabAlpha by animateFloatAsState(
        targetValue = if (showTabManager) 0f else 1f,
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "fabAlpha"
    )
    val fabScale by animateFloatAsState(
        targetValue = if (showTabManager) 0.86f else 1f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "fabScale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== TOP BAR =====
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(3f)
                    .graphicsLayer {
                        alpha = topBarAlpha
                        translationY = topBarOffset.toPx()
                    },
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
                                        activeWebView?.goForward()
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                                    }
                                    IconButton(onClick = {
                                        viewModel.dismissMenu()
                                        urlInput = ""
                                        viewModel.navigateTo("")
                                        viewModel.onTitleChanged(if (isIncognito) "Incognito" else "New Tab")
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
                                        activeWebView?.reload()
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
                                        urlInput = ""
                                    },
                                    leadingIcon = { Icon(Icons.Default.Add, null) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (isIncognito) "Exit Incognito" else "Incognito Mode")
                                            if (isIncognito) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFF6C5CE7)
                                                ) {
                                                    Text(
                                                        "ON",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.dismissMenu()
                                        viewModel.toggleIncognito()
                                        if (!isIncognito) {
                                            // Entering incognito — go to home
                                            urlInput = ""
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isIncognito) Icons.Default.VisibilityOff else Icons.Default.Security,
                                            null,
                                            tint = if (isIncognito) Color(0xFF6C5CE7) else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("History") },
                                    onClick = {
                                        viewModel.dismissMenu()
                                        viewModel.showHistory()
                                    },
                                    leadingIcon = { Icon(Icons.Default.History, null) }
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

                    // Loading progress bar — always in layout to prevent visual shifts.
                    // Uses AnimatedVisibility so it fades in/out smoothly (Chrome-style).
                    val animatedProgress by animateFloatAsState(
                        targetValue = if (isLoading && loadingProgress > 0) loadingProgress / 100f else if (isLoading) 0.05f else 1f,
                        animationSpec = tween(durationMillis = 250),
                        label = "progress"
                    )
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = fadeIn(tween(150)) + expandVertically(),
                        exit = fadeOut(tween(400))
                    ) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = if (isIncognito) Color(0xFF6C5CE7) else Orange500,
                            trackColor = Color.Transparent
                        )
                    }

                    // Incognito indicator bar
                    AnimatedVisibility(
                        visible = isIncognito,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2D2D3A))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFF6C5CE7),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "You're in Incognito",
                                color = Color(0xFFB0B0C0),
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "History won't be saved",
                                color = Color(0xFF8080A0),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // ===== CONTENT AREA =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
                    .zIndex(0f)
                    .graphicsLayer {
                        alpha = browserAlpha
                        scaleX = browserScale
                        scaleY = browserScale
                        translationY = browserOffsetY.toPx()
                    }
                    .then(
                        if (showTabManager) {
                            Modifier
                                .shadow(14.dp, RoundedCornerShape(browserCornerRadius))
                                .clip(RoundedCornerShape(browserCornerRadius))
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (showHomePage) {
                    // Home page with quick access sites
                    HomePageContent(
                        onSiteClick = { site ->
                            urlInput = site.url
                            viewModel.navigateTo(site.url)
                            focusManager.clearFocus()
                        }
                    )
                } else {
                    // WebView — use separate incognito WebView when in incognito mode,
                    // otherwise use the active tab's own WebView.
                    val displayWebView = activeWebView
                    if (displayWebView != null) {
                        // key() forces Compose to swap the AndroidView when the tab changes
                        key(activeTabId) {
                            WebViewContent(
                                viewModel = viewModel,
                                webViewInstance = displayWebView,
                                tabId = activeTabId,
                                isPrivate = isIncognito,
                                onUrlChanged = { url -> urlInput = url },
                                onShowCustomView = { view, callback ->
                                    fullScreenCustomView = view
                                    fullScreenCustomViewCallback = callback
                                },
                                onHideCustomView = {
                                    fullScreenCustomView = null
                                    fullScreenCustomViewCallback?.onCustomViewHidden()
                                    fullScreenCustomViewCallback = null
                                }
                            )
                        }
                    }

                    // Error overlay — positioned within the content area (below URL bar)
                    if (isNetworkError) {
                        NetworkErrorScreen(
                            errorCode = networkErrorCode,
                            description = networkErrorDescription,
                            onRetry = {
                                viewModel.clearPageError()
                                activeWebView?.reload()
                            }
                        )
                    }
                }

                DownloadBanner(
                    pendingDownload = pendingGeneralDownload,
                    typeLabel = pendingGeneralDownload?.let { viewModel.mimeTypeToLabel(it.mimeType) } ?: "File",
                    onDownload = { viewModel.confirmGeneralDownload() },
                    onDismiss = { viewModel.dismissGeneralDownload() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )

                // Floating download button
                FloatingDownloadButton(
                    hasMedia = hasMedia && !isBlockedDomain,
                    mediaCount = detectedMedia.size,
                    thumbnailUrl = detectedMedia.firstOrNull()?.thumbnailUrl,
                    onClick = { viewModel.onDownloadFabClicked() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                        .graphicsLayer {
                            alpha = fabAlpha
                            scaleX = fabScale
                            scaleY = fabScale
                        }
                )

                // Copyright block message (Google/YouTube)
                if (showCopyrightBlockMessage) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Downloading from this website is not possible due to copyright restrictions",
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // "Play video first" message — Bug fix: use theme-aware colors instead of hardcoded dark
                if (showNoVideoMessage) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
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
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ===== BOTTOM NAVIGATION BAR =====
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(3f)
                    .graphicsLayer {
                        alpha = bottomBarAlpha
                        translationY = bottomBarOffset.toPx()
                    },
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
                        onClick = {
                            activeWebView?.goBack()
                        }
                    )
                    BottomNavItem(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        label = "Forward",
                        onClick = {
                            activeWebView?.goForward()
                        }
                    )
                    BottomNavItem(
                        icon = Icons.Default.Home,
                        label = "Home",
                        onClick = {
                            urlInput = ""
                            viewModel.navigateTo("")
                            viewModel.onTitleChanged(if (isIncognito) "Incognito" else "New Tab")
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
                            onClick = {
                                pauseMediaInAllTabs()
                                refreshTabPreviewsForSwitcher()
                                viewModel.showTabManager()
                            }
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
        val otherCandidates by viewModel.otherCandidates.collectAsState()
        
        // Quality picker bottom sheet
        if (showQualitySheet) {
            QualityPickerSheet(
                title = selectedMedia?.title?.takeIf { it.isNotBlank() } ?: currentTitle,
                url = selectedMedia?.sourcePageUrl?.takeIf { it.isNotBlank() } ?: currentUrl,
                thumbnailUrl = selectedMedia?.thumbnailUrl,
                options = qualityOptions,
                isLoading = isLoadingQualities,
                otherCandidates = otherCandidates,
                videoDetector = viewModel.videoDetector,
                onDismiss = { viewModel.dismissQualitySheet() },
                onDownload = { option -> viewModel.startDownload(option) }
            )
        }

        ContextMenuBottomSheet(
            target = contextMenuTarget,
            onDismiss = { viewModel.dismissContextMenu() },
            onOpenUrl = { url -> viewModel.openUrlInNewTab(url) },
            onDownloadUrl = { url, mimeType -> viewModel.downloadContextMenuUrl(url, mimeType) },
            onCopyUrl = { label, url ->
                clipboardManager.setText(AnnotatedString(url))
                android.widget.Toast.makeText(context, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
                viewModel.dismissContextMenu()
            },
            onShareUrl = { url ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share"))
                viewModel.dismissContextMenu()
            }
        )

        if (switcherScrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = switcherScrimAlpha))
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(6f)
                .padding(horizontal = 16.dp)
                .padding(bottom = if (showTabManager) 116.dp else 88.dp)
                .navigationBarsPadding()
        ) { data ->
            Snackbar(
                action = {
                    data.visuals.actionLabel?.let { actionLabel ->
                        TextButton(onClick = { data.performAction() }) {
                            Text(actionLabel)
                        }
                    }
                },
                dismissAction = {
                    IconButton(onClick = { data.dismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            ) {
                Text(data.visuals.message)
            }
        }

        // Tab manager overlay
        AnimatedVisibility(
            visible = showTabManager,
            enter = fadeIn(tween(380)),
            exit = fadeOut(tween(320))
        ) {
            TabManagerOverlay(
                tabs = tabs,
                activeTabIndex = activeTabIndex,
                isListMode = tabSwitcherListMode,
                previewBitmaps = tabPreviewBitmaps,
                onTabClick = { index ->
                    viewModel.switchToTab(index)
                    viewModel.dismissTabManager()
                },
                onCloseTab = { index -> closeTabWithUndo(index) },
                onCloseAll = { viewModel.closeAllTabs() },
                newestTabId = newestTabId,
                onAddTab = {
                    viewModel.addNewTab()
                    refreshTabPreviewsForSwitcher()
                    newestTabId = viewModel.tabs.value.lastOrNull()?.id
                },
                onDone = { viewModel.dismissTabManager() },
                onToggleViewMode = { tabSwitcherListMode = !tabSwitcherListMode },
                onShowHistory = {
                    viewModel.dismissTabManager()
                    viewModel.showHistory()
                },
                onShowMore = {
                    viewModel.dismissTabManager()
                    viewModel.toggleMenu()
                }
            )
        }

        // History overlay
        if (showHistory) {
            HistoryOverlay(
                history = history,
                onHistoryItemClick = { item ->
                    viewModel.dismissHistory()
                    urlInput = item.url
                    viewModel.navigateTo(item.url)
                },
                onDeleteItem = { id -> viewModel.deleteHistoryItem(id) },
                onClearAll = { viewModel.clearAllHistory() },
                onClearSince = { timestamp -> viewModel.clearHistorySince(timestamp) },
                onDismiss = { viewModel.dismissHistory() }
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
                },
                onDismiss = { viewModel.dismissBookmarks() }
            )
        }

        // Fullscreen Custom View Overlay
        // Bug fix: Track the FrameLayout container so we can properly remove the custom view on exit
        val fullScreenContainer = remember { mutableStateOf<android.widget.FrameLayout?>(null) }
        if (fullScreenCustomView != null) {
            BackHandler {
                // Remove the custom view from container before nulling state
                fullScreenContainer.value?.removeAllViews()
                fullScreenCustomViewCallback?.onCustomViewHidden()
                fullScreenCustomViewCallback = null
                fullScreenCustomView = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        android.widget.FrameLayout(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(android.graphics.Color.BLACK)
                            fullScreenContainer.value = this
                        }
                    },
                    update = { container ->
                        // Ensure the custom view is properly parented
                        container.removeAllViews()
                        val view = fullScreenCustomView
                        if (view != null) {
                            (view.parent as? ViewGroup)?.removeView(view)
                            container.addView(view)
                        }
                        fullScreenContainer.value = container
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
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
            text = "XDownload",
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
            color = site.color.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = site.iconRes),
                    contentDescription = site.name,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
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
    webViewInstance: WebView,
    tabId: String,
    isPrivate: Boolean = false,
    onUrlChanged: (String) -> Unit,
    onShowCustomView: (android.view.View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    onHideCustomView: () -> Unit = {}
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            webViewInstance.apply {
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
                    cacheMode = if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // Inject JS interface for video signals (ad detection, playback state)
                addJavascriptInterface(
                    VideoSignalsJsInterface { signals -> viewModel.onVideoSignalsReceived(signals) },
                    "Android"
                )

                setOnLongClickListener {
                    val result = hitTestResult
                    when (result.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                        WebView.HitTestResult.IMAGE_TYPE,
                        WEBVIEW_VIDEO_HIT_TYPE -> {
                            viewModel.onLongPress(result)
                            true
                        }
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            val handler = Handler(Looper.getMainLooper()) { message ->
                                val data: Bundle = message.data
                                val linkUrl = data.getString("url").orEmpty()
                                val imageUrl = data.getString("src").orEmpty()
                                viewModel.showLinkAndImageContextMenu(linkUrl, imageUrl)
                                true
                            }
                            val message = Message.obtain(handler)
                            requestFocusNodeHref(message)
                            true
                        }
                        WebView.HitTestResult.UNKNOWN_TYPE,
                        WebView.HitTestResult.EDIT_TEXT_TYPE -> {
                            viewModel.dismissContextMenu()
                            false
                        }
                        else -> {
                            viewModel.dismissContextMenu()
                            false
                        }
                    }
                }

                setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                    viewModel.onGeneralDownloadIntercepted(
                        url = url.orEmpty(),
                        contentDisposition = contentDisposition,
                        mimeType = mimeType ?: "application/octet-stream",
                        contentLength = contentLength
                    )
                }

                webViewClient = object : WebViewClient() {

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url == INTERNAL_BLANK_URL) return
                        url?.let {
                            viewModel.onPageStarted(tabId, it)
                            if (viewModel.isActiveTab(tabId)) onUrlChanged(it)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url == INTERNAL_BLANK_URL) {
                            if (viewModel.isActiveTab(tabId)) {
                                view?.clearHistory()
                                viewModel.navigateTo("")
                            }
                            return
                        }
                        url?.let { viewModel.onPageFinished(tabId, it) }
                        view?.title?.let { viewModel.onTitleChanged(tabId, it) }

                        // Only run JS detection and thumbnail capture for the active tab.
                        // Background tabs' onPageFinished (and their postDelayed callbacks)
                        // must not overwrite the active tab's currentPageThumbnail.
                        if (!viewModel.isActiveTab(tabId)) return

                        // Inject JS to collect playback/visibility/ad-UI signals from video elements
                        if (view != null) {
                            val videoSignalsJs = """
                                (function() {
                                  try {
                                    var videos = document.querySelectorAll('video');
                                    var results = [];
                                    videos.forEach(function(v) {
                                      var src = v.currentSrc || v.src;
                                      if (!src || !src.startsWith('http')) return;
                                      var rect = v.getBoundingClientRect();
                                      var isVisible = rect.width > 0 && rect.height > 0
                                          && rect.top < window.innerHeight && rect.bottom > 0
                                          && getComputedStyle(v).display !== 'none'
                                          && getComputedStyle(v).visibility !== 'hidden';
                                      var isPlaying = !v.paused && !v.ended && v.readyState > 2;
                                      var hasAdUI = false;
                                      var node = v.parentElement;
                                      for (var i = 0; i < 3 && node; i++) {
                                        var text = (node.innerText || '').toLowerCase();
                                        if (/skip|advertisement|sponsored|\bad\b|ad ends in/.test(text)) {
                                          hasAdUI = true; break;
                                        }
                                        node = node.parentElement;
                                      }
                                      results.push({url: src, isPlaying: isPlaying, isVisible: isVisible, hasAdUI: hasAdUI});
                                    });
                                    Android.reportVideoSignals(JSON.stringify(results));
                                  } catch(e) {}
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(videoSignalsJs, null)
                        }

                        // Helper to run JS detection and process results
                        fun runVideoDetection(v: WebView, pageUrl: String) {
                            v.evaluateJavascript("document.querySelector('meta[property=\"og:image\"]')?.content || document.querySelector('link[rel=\"apple-touch-icon\"]')?.href || ''") { thumbUrl ->
                                // Guard: tab may have switched while JS was executing
                                if (!viewModel.isActiveTab(tabId)) return@evaluateJavascript
                                val cleanThumb = thumbUrl?.trim('"')?.replace("\\\"", "\"") ?: ""
                                viewModel.videoDetector.setCurrentThumbnail(cleanThumb)
                            }
                            v.evaluateJavascript(viewModel.videoDetector.getVideoDetectionJs()) { result ->
                                // Guard: tab may have switched while JS was executing
                                if (!viewModel.isActiveTab(tabId)) return@evaluateJavascript
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

                            // Re-run detection after a delay for sites that load video URLs
                            // via deferred scripts (runs after onPageFinished).
                            val needsDelayedDetection = url.lowercase().let { u ->
                                u.contains("pornhub.com") || u.contains("pornhub.net") || u.contains("pornhub.org") ||
                                u.contains("porndr.com") || u.contains("xvideos.com") || u.contains("xnxx.com") ||
                                u.contains("xhamster.com") || u.contains("redtube.com") || u.contains("youporn.com") ||
                                u.contains("tube8.com") || u.contains("spankbang.com") || u.contains("eporner.com") ||
                                u.contains("tnaflix.com") || u.contains("pornone.com") || u.contains("hclips.com") ||
                                // Generic: any page with /video/ or /watch/ in the path likely has deferred video loading
                                (u.contains("/video/") || u.contains("/watch/") || u.contains("/videos/"))
                            }
                            if (needsDelayedDetection) {
                                // Inner isActiveTab guards: the tab could switch between now
                                // and when the callback fires, so re-check at execution time.
                                view.postDelayed({
                                    if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
                                }, 2500)
                                view.postDelayed({
                                    if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
                                }, 5000)
                            }
                        }
                    }

                    // Only handle true network-level errors (DNS, timeout, SSL, etc.)
                    // Do NOT call stopLoading() — it corrupts the WebView's back stack.
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && viewModel.isActiveTab(tabId)) {
                            val errorCode = error?.errorCode ?: 0
                            val description = error?.description?.toString() ?: "Unknown error"
                            viewModel.onPageError(errorCode, description)
                        }
                    }

                    // NOTE: Deprecated overload removed — on API 21+ both overloads fire,
                    // causing duplicate error handling and state corruption.

                    // NOTE: onReceivedHttpError removed — sites legitimately return 403/404/451
                    // with usable content (custom 404 pages, consent screens, geo-blocks).
                    // stopLoading() was killing those pages and corrupting the back stack.

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        request?.url?.toString()?.let { requestUrl ->
                            // Ad blocking (always, even for background tabs)
                            if (viewModel.blockAds.value && viewModel.adBlocker.isAd(requestUrl)) {
                                return WebResourceResponse("text/plain", "UTF-8", null)
                            }
                            // Video detection — only for the active tab so background tabs
                            // don't leak detected media into the foreground.
                            if (viewModel.isActiveTab(tabId)) {
                                val accept = request.requestHeaders?.get("Accept") ?: ""
                                viewModel.videoDetector.onResourceRequest(requestUrl, accept.takeIf { it.contains("video") || it.contains("audio") })
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        viewModel.onProgressChanged(tabId, newProgress)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        title?.let { viewModel.onTitleChanged(tabId, it) }
                    }

                    override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                        if (view != null && callback != null) {
                            onShowCustomView(view, callback)
                        }
                    }

                    override fun onHideCustomView() {
                        onHideCustomView()
                    }
                }

            }
        },
        update = { wv ->
            wv.settings.cacheMode = if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        },
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuBottomSheet(
    target: ContextMenuTarget?,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDownloadUrl: (String, String) -> Unit,
    onCopyUrl: (String, String) -> Unit,
    onShareUrl: (String) -> Unit
) {
    if (target == null) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            when (target) {
                is ContextMenuTarget.Link -> {
                    ContextMenuUrlSubtitle(url = target.url)
                    LinkActionGroup(
                        url = target.url,
                        onOpenUrl = onOpenUrl,
                        onDownloadUrl = onDownloadUrl,
                        onCopyUrl = onCopyUrl,
                        onShareUrl = onShareUrl
                    )
                }
                is ContextMenuTarget.Image -> {
                    ContextMenuImagePreview(imageUrl = target.imageUrl)
                    ImageActionGroup(
                        imageUrl = target.imageUrl,
                        onOpenUrl = onOpenUrl,
                        onDownloadUrl = onDownloadUrl,
                        onCopyUrl = onCopyUrl,
                        onShareUrl = onShareUrl
                    )
                }
                is ContextMenuTarget.LinkAndImage -> {
                    ContextMenuUrlSubtitle(url = target.linkUrl)
                    LinkActionGroup(
                        url = target.linkUrl,
                        onOpenUrl = onOpenUrl,
                        onDownloadUrl = onDownloadUrl,
                        onCopyUrl = onCopyUrl,
                        onShareUrl = onShareUrl
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    ContextMenuImagePreview(imageUrl = target.imageUrl)
                    ImageActionGroup(
                        imageUrl = target.imageUrl,
                        onOpenUrl = onOpenUrl,
                        onDownloadUrl = onDownloadUrl,
                        onCopyUrl = onCopyUrl,
                        onShareUrl = onShareUrl
                    )
                }
                is ContextMenuTarget.Video -> {
                    ContextMenuUrlSubtitle(url = target.videoUrl)
                    ContextMenuActionRow(
                        icon = Icons.Default.Download,
                        label = "Download video",
                        onClick = { onDownloadUrl(target.videoUrl, "video/mp4") }
                    )
                    ContextMenuActionRow(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy video URL",
                        onClick = { onCopyUrl("Video URL", target.videoUrl) }
                    )
                    ContextMenuActionRow(
                        icon = Icons.Default.Share,
                        label = "Share video",
                        onClick = { onShareUrl(target.videoUrl) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuUrlSubtitle(url: String) {
    Text(
        text = url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ContextMenuImagePreview(imageUrl: String) {
    AsyncImage(
        model = imageUrl,
        contentDescription = "Image preview",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun LinkActionGroup(
    url: String,
    onOpenUrl: (String) -> Unit,
    onDownloadUrl: (String, String) -> Unit,
    onCopyUrl: (String, String) -> Unit,
    onShareUrl: (String) -> Unit
) {
    ContextMenuActionRow(
        icon = Icons.Default.OpenInNew,
        label = "Open in new tab",
        onClick = { onOpenUrl(url) }
    )
    ContextMenuActionRow(
        icon = Icons.Default.ContentCopy,
        label = "Copy link address",
        onClick = { onCopyUrl("Link address", url) }
    )
    ContextMenuActionRow(
        icon = Icons.Default.Download,
        label = "Download link",
        onClick = { onDownloadUrl(url, "application/octet-stream") }
    )
    ContextMenuActionRow(
        icon = Icons.Default.Share,
        label = "Share link",
        onClick = { onShareUrl(url) }
    )
}

@Composable
private fun ImageActionGroup(
    imageUrl: String,
    onOpenUrl: (String) -> Unit,
    onDownloadUrl: (String, String) -> Unit,
    onCopyUrl: (String, String) -> Unit,
    onShareUrl: (String) -> Unit
) {
    ContextMenuActionRow(
        icon = Icons.Default.Image,
        label = "Open image in new tab",
        onClick = { onOpenUrl(imageUrl) }
    )
    ContextMenuActionRow(
        icon = Icons.Default.ContentCopy,
        label = "Copy image URL",
        onClick = { onCopyUrl("Image URL", imageUrl) }
    )
    ContextMenuActionRow(
        icon = Icons.Default.Download,
        label = "Download image",
        onClick = { onDownloadUrl(imageUrl, "image/*") }
    )
    ContextMenuActionRow(
        icon = Icons.Default.Share,
        label = "Share image",
        onClick = { onShareUrl(imageUrl) }
    )
}

@Composable
private fun ContextMenuActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Orange500,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBanner(
    pendingDownload: PendingGeneralDownload?,
    typeLabel: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = pendingDownload != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        val download = pendingDownload ?: return@AnimatedVisibility
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value != SwipeToDismissBoxValue.Settled) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
        )

        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {},
            content = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = Orange500,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = download.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$typeLabel - ${download.fileSize?.let { formatBytes(it) } ?: "Unknown size"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = onDownload) {
                            Text("Download", color = Orange500, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun FloatingDownloadButton(
    hasMedia: Boolean,
    mediaCount: Int,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (hasMedia) Orange500 else Color(0xFFBDBDBD),
        animationSpec = tween(400),
        label = "fab_color"
    )

    // --- Animation state ---
    val arrowAlpha = remember { Animatable(1f) }
    val arrowOffsetY = remember { Animatable(0f) }
    val thumbAlpha = remember { Animatable(0f) }
    val thumbScale = remember { Animatable(0.5f) }
    val thumbOffsetY = remember { Animatable(0f) }
    val fabScale = remember { Animatable(1f) }
    val glowRingScale = remember { Animatable(1f) }
    val glowRingAlpha = remember { Animatable(0f) }
    val badgeScale = remember { Animatable(0f) }

    // Trigger animation when media is first detected
    LaunchedEffect(hasMedia) {
        if (hasMedia && !thumbnailUrl.isNullOrBlank() && thumbnailUrl != "null") {
            // --- Glow ring shockwave + FAB bump (fire-and-forget parallel) ---
            coroutineScope {
                launch {
                    glowRingAlpha.snapTo(0.55f)
                    glowRingScale.snapTo(1f)
                    coroutineScope {
                        launch { glowRingAlpha.animateTo(0f, tween(700, easing = FastOutSlowInEasing)) }
                        launch { glowRingScale.animateTo(2.2f, tween(700, easing = FastOutSlowInEasing)) }
                    }
                }
                launch {
                    fabScale.animateTo(1.12f, tween(140, easing = FastOutSlowInEasing))
                    fabScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 400f))
                }
                // Phase 1: Download arrow slides UP and fades out (parallel alpha + offset)
                launch { arrowAlpha.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
                launch { arrowOffsetY.animateTo(-28f, tween(220, easing = FastOutSlowInEasing)) }
            }

            delay(60)

            // Phase 2: Thumbnail pops in with satisfying spring
            thumbOffsetY.snapTo(0f)
            thumbScale.snapTo(0.35f)
            coroutineScope {
                launch { thumbAlpha.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
                launch { thumbScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
            }

            // Phase 3: Hold — let the user admire the thumbnail
            delay(1600)

            // Phase 4: Thumbnail sinks DOWN and fades out
            coroutineScope {
                launch { thumbAlpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                launch { thumbOffsetY.animateTo(35f, tween(280, easing = FastOutSlowInEasing)) }
            }

            delay(80)

            // Phase 5: Download arrow bounces back in from above
            arrowOffsetY.snapTo(-28f)
            coroutineScope {
                launch { arrowAlpha.animateTo(1f, tween(280)) }
                launch { arrowOffsetY.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 280f)) }
            }

            // Badge pop-in
            badgeScale.snapTo(0f)
            badgeScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))

            // Reset thumbnail state for next detection
            thumbScale.snapTo(0.5f)
            thumbOffsetY.snapTo(0f)
        } else if (hasMedia) {
            // Media detected but no thumbnail — just show badge
            arrowAlpha.snapTo(1f)
            arrowOffsetY.snapTo(0f)
            badgeScale.snapTo(0f)
            badgeScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))
        } else {
            // No media — reset everything
            arrowAlpha.snapTo(1f)
            arrowOffsetY.snapTo(0f)
            thumbAlpha.snapTo(0f)
            thumbScale.snapTo(0.5f)
            thumbOffsetY.snapTo(0f)
            fabScale.snapTo(1f)
            glowRingAlpha.snapTo(0f)
            badgeScale.snapTo(0f)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Glow ring behind the FAB
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = glowRingScale.value
                    scaleY = glowRingScale.value
                    alpha = glowRingAlpha.value
                }
                .border(2.5.dp, Orange500, CircleShape)
        )

        // Main FAB surface — CircleShape clips children so
        // the arrow "slides through the ceiling" and the
        // thumbnail "sinks through the floor"
        FloatingActionButton(
            onClick = onClick,
            containerColor = animatedColor,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = fabScale.value
                    scaleY = fabScale.value
                }
                .shadow(8.dp, CircleShape)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Download arrow icon
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            alpha = arrowAlpha.value
                            translationY = arrowOffsetY.value
                        }
                )

                // Thumbnail overlay — clipped by FAB's CircleShape
                if (thumbAlpha.value > 0f) {
                    coil.compose.AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = "Detected video",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = thumbAlpha.value
                                scaleX = thumbScale.value
                                scaleY = thumbScale.value
                                translationY = thumbOffsetY.value
                            }
                    )
                }
            }
        }

        // Media count badge with pop-in animation
        if (hasMedia && mediaCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = badgeScale.value
                        scaleY = badgeScale.value
                    },
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
    options: List<com.cognitivechaos.xdownload.data.model.MediaQualityOption>,
    isLoading: Boolean,
    otherCandidates: List<com.cognitivechaos.xdownload.data.model.DetectedMedia> = emptyList(),
    videoDetector: VideoDetector,
    onDismiss: () -> Unit,
    onDownload: (com.cognitivechaos.xdownload.data.model.MediaQualityOption) -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var otherVideosExpanded by remember { mutableStateOf(false) }
    // Track which "other" candidate has its quality options expanded (-1 = none)
    var expandedOtherIndex by remember { mutableIntStateOf(-1) }
    // Track loading state and options for each "other" candidate
    val otherCandidateStates = remember { mutableStateMapOf<Int, Pair<Boolean, List<com.cognitivechaos.xdownload.data.model.MediaQualityOption>>>() }
    val coroutineScope = rememberCoroutineScope()

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

                // "Other Videos" accordion — only shown when there are other detected candidates
                if (otherCandidates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()

                    // Header row — tap to expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { otherVideosExpanded = !otherVideosExpanded }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Other Videos (${otherCandidates.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (otherVideosExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (otherVideosExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Expanded list — accordion: only one candidate open at a time
                    AnimatedVisibility(visible = otherVideosExpanded) {
                        Column {
                            otherCandidates.forEachIndexed { idx, candidate ->
                                val isExpanded = expandedOtherIndex == idx
                                val (isLoadingOther, otherOptions) = otherCandidateStates[idx] ?: Pair(false, emptyList())
                                
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isExpanded) MaterialTheme.colorScheme.surfaceVariant
                                            else Color.Transparent
                                        )
                                ) {
                                    // Candidate title row
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isExpanded) {
                                                    expandedOtherIndex = -1
                                                } else {
                                                    expandedOtherIndex = idx
                                                    // Fetch quality options for this candidate
                                                    if (!otherCandidateStates.containsKey(idx)) {
                                                        otherCandidateStates[idx] = Pair(true, emptyList())
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            val fetchedOptions = try {
                                                                withTimeoutOrNull(8_000L) {
                                                                    videoDetector.fetchQualityOptions(candidate)
                                                                } ?: emptyList()
                                                            } catch (_: Exception) {
                                                                emptyList()
                                                            }
                                                            withContext(Dispatchers.Main) {
                                                                otherCandidateStates[idx] = Pair(false, fetchedOptions
                                                                    .filter { it.fileSize == null || it.fileSize > 100_000L }
                                                                    .sortedByDescending {
                                                                        val num = Regex("\\d+").find(it.quality.lowercase())?.value?.toInt() ?: 0
                                                                        num + if (it.fileSize != null && it.fileSize > 0L) 100000 else 0
                                                                    }
                                                                    .ifEmpty { fetchedOptions })
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.VideoFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = candidate.title?.takeIf { it.isNotBlank() } ?: candidate.url.take(50),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Inline quality options for this candidate
                                    AnimatedVisibility(visible = isExpanded) {
                                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            if (isLoadingOther) {
                                                Box(
                                                    modifier = Modifier.fillMaxWidth().height(80.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(color = Orange500, modifier = Modifier.size(24.dp))
                                                }
                                            } else if (otherOptions.isEmpty()) {
                                                Text(
                                                    "No options available",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(vertical = 8.dp)
                                                )
                                            } else {
                                                otherOptions.forEach { option ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable { onDownload(option) }
                                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Download,
                                                            contentDescription = null,
                                                            tint = Orange500,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = option.quality,
                                                                style = MaterialTheme.typography.bodyMedium
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
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (idx < otherCandidates.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabManagerOverlay(
    tabs: List<com.cognitivechaos.xdownload.data.model.BrowserTab>,
    activeTabIndex: Int,
    isListMode: Boolean,
    previewBitmaps: Map<String, Bitmap>,
    newestTabId: String?,
    onTabClick: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onCloseAll: () -> Unit,
    onAddTab: () -> Unit,
    onDone: () -> Unit,
    onToggleViewMode: () -> Unit,
    onShowHistory: () -> Unit,
    onShowMore: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val compactCardWidth = (configuration.screenWidthDp.dp - 84.dp).coerceAtLeast(220.dp)
    val compactListState = rememberLazyListState(
        initialFirstVisibleItemIndex = activeTabIndex.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
    )
    val compactFlingBehavior = rememberSnapFlingBehavior(lazyListState = compactListState)

    LaunchedEffect(activeTabIndex, tabs.size, isListMode) {
        if (!isListMode && tabs.isNotEmpty()) {
            compactListState.animateScrollToItem(activeTabIndex.coerceIn(0, tabs.lastIndex))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tabs",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "${tabs.size}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) {
                    Text("Done", color = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isListMode) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                            TabSwitcherPreviewCard(
                                tab = tab,
                                isActive = index == activeTabIndex,
                                isPrivate = tab.isIncognito,
                                compact = false,
                                previewBitmap = previewBitmaps[tab.id],
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .animateItem(
                                        fadeInSpec = tween(320),
                                        placementSpec = tween(380, easing = FastOutSlowInEasing),
                                        fadeOutSpec = tween(200)
                                    ),
                                onClick = { onTabClick(index) },
                                onClose = { onCloseTab(index) }
                            )
                        }
                    }
                } else {
                    LazyRow(
                        state = compactListState,
                        modifier = Modifier.fillMaxSize(),
                        flingBehavior = compactFlingBehavior,
                        contentPadding = PaddingValues(horizontal = 42.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                            val isNewest = tab.id == newestTabId
                            val introScale = remember(tab.id) { Animatable(if (isNewest) 0.82f else 1f) }
                            val introAlpha = remember(tab.id) { Animatable(if (isNewest) 0f else 1f) }
                            val visibleItemInfo = compactListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tab.id }
                            val viewportCenter =
                                (compactListState.layoutInfo.viewportStartOffset + compactListState.layoutInfo.viewportEndOffset) / 2f
                            val itemCenter = visibleItemInfo?.let { it.offset + (it.size / 2f) } ?: viewportCenter
                            val itemWidth = visibleItemInfo?.size?.toFloat() ?: compactCardWidth.value
                            val pageOffset = ((itemCenter - viewportCenter).absoluteValue / itemWidth)
                                .coerceIn(0f, 1f)

                            LaunchedEffect(tab.id) {
                                if (isNewest) {
                                    launch { introScale.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
                                    launch { introAlpha.animateTo(1f, tween(350)) }
                                }
                            }

                            TabSwitcherPreviewCard(
                                tab = tab,
                                isActive = index == activeTabIndex,
                                isPrivate = tab.isIncognito,
                                compact = true,
                                previewBitmap = previewBitmaps[tab.id],
                                modifier = Modifier
                                    .width(compactCardWidth)
                                    .fillMaxHeight()
                                    .animateItem(
                                        fadeInSpec = tween(320),
                                        placementSpec = tween(380, easing = FastOutSlowInEasing),
                                        fadeOutSpec = tween(220)
                                    )
                                    .graphicsLayer {
                                        val pageScale = 1f - (pageOffset * 0.1f)
                                        val pageAlpha = 1f - (pageOffset * 0.28f)
                                        scaleX = pageScale * introScale.value
                                        scaleY = pageScale * introScale.value
                                        alpha = pageAlpha * introAlpha.value
                                    },
                                onClick = { onTabClick(index) },
                                onClose = { onCloseTab(index) }
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (isListMode) Icons.Default.ViewCarousel else Icons.Default.ViewList,
                            contentDescription = "Toggle tab layout",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onShowHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "History",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = CircleShape,
                        color = Orange500
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onAddTab),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add tab",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    TextButton(onClick = onCloseAll) {
                        Text(
                            text = "Close all",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onShowMore) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabSwitcherPreviewCard(
    tab: com.cognitivechaos.xdownload.data.model.BrowserTab,
    isActive: Boolean,
    isPrivate: Boolean,
    compact: Boolean,
    previewBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val accentColor = if (isPrivate) Color(0xFF6C5CE7) else Orange500
    val cardColor = if (isPrivate) Color(0xFF1E1B2A) else Color(0xFF232323)
    val inactiveHeaderColor = if (isPrivate) Color(0xFF262236) else Color(0xFF2C2C2C)
    val activeHeaderColor = if (isPrivate) Color(0xFF342A56) else Color(0xFF5A3C19)
    var dragOffsetY by remember(tab.id) { mutableFloatStateOf(0f) }
    var dragAlpha by remember(tab.id) { mutableFloatStateOf(1f) }
    var settleJob by remember(tab.id) { mutableStateOf<Job?>(null) }
    var cardHeightPx by remember { mutableFloatStateOf(0f) }
    fun alphaForOffset(offset: Float): Float {
        val progress = (-offset / 300f).coerceIn(0f, 1f)
        return 1f - progress * 0.25f
    }

    Surface(
        modifier = modifier
            .onSizeChanged { size -> cardHeightPx = size.height.toFloat() }
            .graphicsLayer {
                translationY = dragOffsetY
                alpha = dragAlpha
            }
            .pointerInput(tab.id) {
                val decay = splineBasedDecay<Float>(this)
                coroutineScope {
                    while (true) {
                        // Wait for finger down — don't consume yet
                        val down = awaitPointerEventScope {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        settleJob?.cancel()

                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        var currentOffset = dragOffsetY

                        // Only consume if gesture is confirmed vertical — lets HorizontalPager handle horizontal swipes
                        val drag = awaitPointerEventScope {
                            awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val deltaY = change.positionChange().y
                                val nextOffset = currentOffset + deltaY
                                currentOffset = if (nextOffset > 0f) {
                                    nextOffset * 0.3f
                                } else {
                                    nextOffset
                                }
                                dragOffsetY = currentOffset
                                dragAlpha = alphaForOffset(currentOffset)
                                change.consume()
                            }
                        } ?: continue  // horizontal or cancelled — pass to Pager

                        // Confirmed vertical — seed the tracker and apply first movement
                        velocityTracker.addPosition(drag.uptimeMillis, drag.position)

                        awaitPointerEventScope {
                            verticalDrag(drag.id) { change ->
                                val deltaY = change.positionChange().y
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                currentOffset = if (currentOffset + deltaY > 0f) {
                                    (currentOffset + deltaY) * 0.3f
                                } else {
                                    currentOffset + deltaY
                                }
                                dragOffsetY = currentOffset
                                dragAlpha = alphaForOffset(currentOffset)
                                change.consume()
                            }
                        }

                        // Finger lifted — decide dismiss or snap back
                        val velocity = velocityTracker.calculateVelocity().y
                        val offset = currentOffset
                        val targetOffset = decay.calculateTargetValue(offset, velocity)
                        val dragRatio = if (cardHeightPx > 0f) (-offset / cardHeightPx) else 0f
                        val flickDismiss =
                            velocity < -220f ||
                            (velocity < -120f && targetOffset < -(cardHeightPx * 0.12f))

                        val shouldDismiss =
                            dragRatio >= 0.38f ||
                            targetOffset < -(cardHeightPx * 0.32f) ||
                            flickDismiss

                        if (shouldDismiss) {
                            val dismissTarget = -((cardHeightPx + 96f).coerceAtLeast(600f))
                            val remainingDistance = dismissTarget - offset
                            val isFastFlick = velocity < -220f
                            val animDuration = if (isFastFlick) 150
                            else ((remainingDistance.absoluteValue / dismissTarget.absoluteValue) * 180f).toInt().coerceIn(80, 180)
                            settleJob = launch {
                                launch {
                                    animate(
                                        initialValue = dragAlpha,
                                        targetValue = 0f,
                                        animationSpec = tween((animDuration - 30).coerceAtLeast(1))
                                    ) { value, _ ->
                                        dragAlpha = value
                                    }
                                }
                                animate(
                                    initialValue = dragOffsetY,
                                    targetValue = dismissTarget,
                                    animationSpec = tween(animDuration, easing = LinearOutSlowInEasing)
                                ) { value, _ ->
                                    dragOffsetY = value
                                }
                                onClose()
                            }
                        } else {
                            settleJob = launch {
                                launch {
                                    animate(
                                        initialValue = dragAlpha,
                                        targetValue = 1f,
                                        animationSpec = tween(80)
                                    ) { value, _ ->
                                        dragAlpha = value
                                    }
                                }
                                animate(
                                    initialValue = dragOffsetY,
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ) { value, _ ->
                                    dragOffsetY = value
                                }
                            }
                        }
                    }
                }
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(if (compact) 28.dp else 20.dp),
        border = if (isActive) BorderStroke(2.dp, accentColor) else BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        color = cardColor,
        shadowElevation = if (isActive) 12.dp else 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isActive) activeHeaderColor else inactiveHeaderColor
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(18.dp),
                    shape = CircleShape,
                    color = if (isActive) accentColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPrivate) Icons.Default.VisibilityOff else Icons.Default.Language,
                            contentDescription = null,
                            tint = if (isActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPrivate && tab.url.isBlank()) "Incognito" else tab.title.ifEmpty { "New Tab" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (tab.url.isNotEmpty()) {
                        Text(
                            text = tab.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close tab",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(cardColor)
                    .padding(horizontal = if (compact) 16.dp else 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Tab preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.08f),
                                            Color.Black.copy(alpha = 0.28f)
                                        )
                                    )
                                )
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.55f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Tab,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isPrivate) "Incognito tab" else if (isActive) "Current tab" else "Tap to open",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                } else if (tab.url.isBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(if (compact) 54.dp else 64.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = accentColor.copy(alpha = 0.14f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isPrivate) Icons.Default.VisibilityOff else Icons.Default.Home,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(if (compact) 26.dp else 30.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isPrivate) "Incognito" else "New Tab",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isPrivate) "Private browsing session" else "Open a site or search from the browser bar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = tab.title.ifEmpty { tab.url },
                                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = tab.url,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (compact) 2 else 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Tab,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isPrivate) "Incognito tab" else if (isActive) "Current tab" else "Tap to open",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksOverlay(
    bookmarks: List<com.cognitivechaos.xdownload.data.db.BookmarkEntity>,
    onBookmarkClick: (com.cognitivechaos.xdownload.data.db.BookmarkEntity) -> Unit,
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

private fun formatDateHeader(timestamp: Long): String {
    val cal = Calendar.getInstance()
    val today = Calendar.getInstance()
    cal.timeInMillis = timestamp

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryOverlay(
    history: List<HistoryEntity>,
    onHistoryItemClick: (HistoryEntity) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onClearAll: () -> Unit,
    onClearSince: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filteredHistory = remember(history, searchQuery) {
        if (searchQuery.isBlank()) history
        else history.filter {
            it.url.contains(searchQuery, ignoreCase = true) ||
                    it.title.contains(searchQuery, ignoreCase = true)
        }
    }

    // Group by date
    val groupedHistory = remember(filteredHistory) {
        filteredHistory.groupBy { formatDateHeader(it.visitedAt) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // Top bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }

                        if (showSearch) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                placeholder = {
                                    Text(
                                        "Search history...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Orange500,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            )
                        } else {
                            Text(
                                "History",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                            Icon(
                                if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                "Search"
                            )
                        }

                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear history", tint = Orange500)
                        }
                    }
                }
            }

            // History list
            if (filteredHistory.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "No results found"
                            else "No browsing history",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "Try a different search term"
                            else "Websites you visit will appear here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    groupedHistory.forEach { (dateHeader, items) ->
                        stickyHeader {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = dateHeader,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Orange500,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }

                        items(
                            items = items,
                            key = { it.id }
                        ) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onHistoryItemClick(item) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .animateItem(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Website icon placeholder
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Language,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifEmpty { item.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatTime(item.visitedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "  •  ",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 8.sp
                                        )
                                        Text(
                                            text = item.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(40),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Delete individual item
                                IconButton(
                                    onClick = { onDeleteItem(item.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Clear History Dialog
    if (showClearDialog) {
        var selectedOption by remember { mutableIntStateOf(2) } // Default to "All time"
        val options = listOf("Last hour", "Last 24 hours", "All time")

        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = Orange500,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Clear browsing history")
            },
            text = {
                Column {
                    Text(
                        "Choose a time range to clear:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedOption = index }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedOption == index,
                                onClick = { selectedOption = index },
                                colors = RadioButtonDefaults.colors(selectedColor = Orange500)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (selectedOption) {
                        0 -> {
                            val oneHourAgo = System.currentTimeMillis() - 3_600_000L
                            onClearSince(oneHourAgo)
                        }
                        1 -> {
                            val oneDayAgo = System.currentTimeMillis() - 86_400_000L
                            onClearSince(oneDayAgo)
                        }
                        2 -> onClearAll()
                    }
                    showClearDialog = false
                }) {
                    Text("Clear", color = Orange500, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun NetworkErrorScreen(
    errorCode: Int,
    description: String,
    onRetry: () -> Unit
) {
    // Map WebViewClient error codes to user-friendly content
    data class ErrorInfo(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val title: String,
        val subtitle: String,
        val accentColor: Color
    )

    val errorInfo = remember(errorCode, description) {
        val descLower = description.lowercase()
        when {
            // No internet / disconnected
            errorCode == WebViewClient.ERROR_HOST_LOOKUP && descLower.contains("internet") ->
                ErrorInfo(Icons.Default.WifiOff, "You're Offline", "Check your internet connection and try again.", Color(0xFFFF6B6B))
            descLower.contains("internet") || descLower.contains("err_internet_disconnected") ->
                ErrorInfo(Icons.Default.WifiOff, "You're Offline", "Check your internet connection and try again.", Color(0xFFFF6B6B))
            // DNS / host not found
            errorCode == WebViewClient.ERROR_HOST_LOOKUP ->
                ErrorInfo(Icons.Default.CloudOff, "Site Not Found", "The server's DNS address could not be found. Check the URL or try again later.", Color(0xFF74B9FF))
            // Connection refused / failed
            errorCode == WebViewClient.ERROR_CONNECT ->
                ErrorInfo(Icons.Default.LinkOff, "Connection Refused", "The site refused to connect. It may be down or blocking your request.", Color(0xFFFFA502))
            // Timeout
            errorCode == WebViewClient.ERROR_TIMEOUT ->
                ErrorInfo(Icons.Default.HourglassBottom, "Connection Timed Out", "The server took too long to respond. Try again later.", Color(0xFFA29BFE))
            // SSL / security
            errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                ErrorInfo(Icons.Default.GppBad, "Connection Not Secure", "A secure connection to this site could not be established.", Color(0xFFE17055))
            // Too many redirects
            errorCode == WebViewClient.ERROR_REDIRECT_LOOP ->
                ErrorInfo(Icons.Default.Loop, "Too Many Redirects", "This page has a redirect loop. Try clearing your cookies for this site.", Color(0xFFFDCB6E))
            // Bad URL
            errorCode == WebViewClient.ERROR_BAD_URL ->
                ErrorInfo(Icons.Default.BrokenImage, "Invalid URL", "The URL you entered is not valid. Please check and try again.", Color(0xFFE056A0))
            // Unsupported scheme
            errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME ->
                ErrorInfo(Icons.Default.Block, "Unsupported Protocol", "This app doesn't support the protocol used by this URL.", Color(0xFF636E72))
            // I/O error
            errorCode == WebViewClient.ERROR_IO ->
                ErrorInfo(Icons.Default.ReportProblem, "Network Error", "A network error occurred while loading the page.", Color(0xFFFF7675))
            // File not found
            errorCode == WebViewClient.ERROR_FILE_NOT_FOUND ->
                ErrorInfo(Icons.Default.SearchOff, "Page Not Found", "The requested page could not be found on this server.", Color(0xFF81ECEC))
            // Generic fallback
            else ->
                ErrorInfo(Icons.Default.ErrorOutline, "Something Went Wrong", "An unexpected error occurred while loading this page.", Orange500)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "errorPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated pulsing icon with glow rings
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(errorInfo.accentColor.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp * scale)
                        .clip(CircleShape)
                        .background(errorInfo.accentColor.copy(alpha = glowAlpha)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(errorInfo.accentColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            errorInfo.icon,
                            contentDescription = errorInfo.title,
                            modifier = Modifier.size(36.dp),
                            tint = errorInfo.accentColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = errorInfo.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = errorInfo.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = errorInfo.accentColor),
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Try Again",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Show technical detail in subtle text
            Text(
                text = "Error Code: $errorCode",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}
