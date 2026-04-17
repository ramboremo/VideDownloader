package com.videdownloader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.videdownloader.app.ui.browser.BrowserScreen
import com.videdownloader.app.ui.files.FilesScreen
import com.videdownloader.app.ui.player.VideoPlayerScreen
import com.videdownloader.app.ui.settings.SettingsScreen
import com.videdownloader.app.ui.theme.VideDownloaderTheme
import com.videdownloader.app.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
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

            VideDownloaderTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(initialUrl = initialUrl)
                }
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
fun AppNavigation(initialUrl: String? = null) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val sharedWebView = remember { WebView(context) }

    NavHost(
        navController = navController,
        startDestination = "browser"
    ) {
        composable("browser") {
            BrowserScreen(
                sharedWebView = sharedWebView,
                initialUrl = initialUrl,
                onNavigateToFiles = { navController.navigate("files") { launchSingleTop = true } },
                onNavigateToSettings = { navController.navigate("settings") { launchSingleTop = true } }
            )
        }
        composable("files") {
            FilesScreen(
                onBack = { navController.popBackStack() },
                onPlay = { downloadId -> navController.navigate("player/$downloadId") { launchSingleTop = true } }
            )
        }
        composable("player/{downloadId}") { backStackEntry ->
            val downloadId = backStackEntry.arguments?.getString("downloadId") ?: return@composable
            VideoPlayerScreen(
                downloadId = downloadId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onClearCache = { sharedWebView.clearCache(true) }
            )
        }
    }
}
