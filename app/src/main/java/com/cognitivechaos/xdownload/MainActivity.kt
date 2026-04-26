package com.cognitivechaos.xdownload

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cognitivechaos.xdownload.ui.browser.BrowserScreen
import com.cognitivechaos.xdownload.ui.files.DownloadFileActions
import com.cognitivechaos.xdownload.ui.files.FilesScreen
import com.cognitivechaos.xdownload.ui.onboarding.OnboardingScreen
import com.cognitivechaos.xdownload.ui.player.ImageViewerScreen
import com.cognitivechaos.xdownload.ui.player.VideoPlayerScreen
import com.cognitivechaos.xdownload.ui.settings.SettingsScreen
import com.cognitivechaos.xdownload.ui.theme.VideDownloaderTheme
import com.cognitivechaos.xdownload.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferences: AppPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled */ }

    // Bug fix: Observable state for deep link URLs so Compose reacts to onNewIntent
    private val _pendingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        // Set initial URL from cold-start intent
        _pendingUrl.value = intent?.dataString

        setContent {
            val themeMode by preferences.themeMode.collectAsState(initial = "System")
            val darkTheme = when (themeMode) {
                "Dark" -> true
                "Light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // Observe the pending URL (updated by both onCreate and onNewIntent)
            val initialUrl by _pendingUrl
            val forceUpdateChecker = remember { ForceUpdateChecker(this@MainActivity) }
            var forceUpdateState by remember { mutableStateOf<ForceUpdateState?>(null) }

            LaunchedEffect(Unit) {
                forceUpdateChecker.check { state ->
                    forceUpdateState = state
                }
            }

            VideDownloaderTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val onboardingSeen by preferences.onboardingSeen.collectAsState(initial = null)
                    val scope = rememberCoroutineScope()

                    when (onboardingSeen) {
                        null -> {
                            // Still loading preference — show nothing (avoids flash)
                            Box(modifier = Modifier.fillMaxSize())
                        }
                        false -> {
                            OnboardingScreen(
                                onFinished = {
                                    scope.launch { preferences.setOnboardingSeen() }
                                }
                            )
                        }
                        else -> {
                            AppNavigation(initialUrl = initialUrl)
                        }
                    }
                }

                ForceUpdateDialog(
                    state = forceUpdateState,
                    onUpdate = { forceUpdateChecker.openPlayStore(forceUpdateState) }
                )
            }
        }
    }

    // Bug fix: Handle subsequent VIEW intents when the activity is already running (singleTask)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUrl = intent.dataString
        if (!newUrl.isNullOrBlank()) {
            _pendingUrl.value = newUrl
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
private fun ForceUpdateDialog(
    state: ForceUpdateState?,
    onUpdate: () -> Unit
) {
    if (state == null) return

    AlertDialog(
        onDismissRequest = {},
        title = { Text(state.title) },
        text = { Text(state.message) },
        confirmButton = {
            Button(onClick = onUpdate) {
                Text("Update")
            }
        }
    )
}

@Composable
fun AppNavigation(initialUrl: String? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // Hold a reference to any WebView created by BrowserScreen for cache clearing.
    var anyWebViewRef by remember { mutableStateOf<WebView?>(null) }

    // Track the current destination to show/hide BrowserScreen.
    // BrowserScreen is active whenever we are NOT on a secondary screen.
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val secondaryRoutes = setOf("files", "settings") // player/{downloadId} handled below
    val isBrowserActive = currentRoute == null
        || currentRoute == "files_placeholder"
        || (!secondaryRoutes.contains(currentRoute) &&
            !currentRoute.startsWith("player/") &&
            !currentRoute.startsWith("image/"))

    Box(modifier = Modifier.fillMaxSize()) {
        // BrowserScreen is ALWAYS in the composition tree — never removed.
        // This prevents the AndroidView factory from re-running and the WebView
        // from being detached/re-attached (which caused the white flash + reload).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (isBrowserActive) 1f else 0f }
                .zIndex(if (isBrowserActive) 1f else 0f)
                // Block touch events when BrowserScreen is hidden behind another screen
                .then(
                    if (!isBrowserActive) Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(pass = PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    } else Modifier
                )
        ) {
            BrowserScreen(
                initialUrl = initialUrl,
                onNavigateToFiles = { navController.navigate("files") { launchSingleTop = true } },
                onNavigateToSettings = { navController.navigate("settings") { launchSingleTop = true } },
                onWebViewReady = { wv -> if (anyWebViewRef == null) anyWebViewRef = wv }
            )
        }

        // NavHost handles only the non-browser destinations
        NavHost(
            navController = navController,
            startDestination = "files_placeholder",
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (isBrowserActive) 0f else 1f)
        ) {
            // Invisible placeholder so NavHost has a valid start destination
            composable("files_placeholder") { /* never shown */ }
            composable("files") {
                FilesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDownload = { download ->
                        val route = when {
                            DownloadFileActions.isImage(download) -> "image/${download.id}"
                            DownloadFileActions.isVideo(download) -> "player/${download.id}"
                            else -> null
                        }
                        if (route != null) {
                            navController.navigate(route) { launchSingleTop = true }
                        } else {
                            DownloadFileActions.openWith(context, download)
                        }
                    }
                )
            }
            composable("player/{downloadId}") { backStackEntry ->
                val downloadId = backStackEntry.arguments?.getString("downloadId") ?: return@composable
                VideoPlayerScreen(
                    downloadId = downloadId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("image/{downloadId}") { backStackEntry ->
                val downloadId = backStackEntry.arguments?.getString("downloadId") ?: return@composable
                ImageViewerScreen(
                    downloadId = downloadId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onClearCache = { anyWebViewRef?.clearCache(true) }
                )
            }
        }
    }
}
