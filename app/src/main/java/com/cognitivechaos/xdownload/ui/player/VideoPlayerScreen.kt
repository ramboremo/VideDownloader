@file:Suppress("DEPRECATION")
package com.cognitivechaos.xdownload.ui.player

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cognitivechaos.xdownload.ui.files.DownloadFileActions
import com.cognitivechaos.xdownload.ui.files.FilesViewModel
import com.cognitivechaos.xdownload.ui.theme.Orange500
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    downloadId: String,
    viewModel: PlayerViewModel = hiltViewModel(),
    filesViewModel: FilesViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val download by viewModel.download.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    // Fullscreen state
    var isFullscreen by remember { mutableStateOf(true) }
    val activity = context as? android.app.Activity
    val window = activity?.window

    // Bug fix #1: ExoPlayer lifecycle managed entirely via DisposableEffect
    // keyed to Unit so it lives exactly as long as this composable is in the tree.
    // No separate lifecycle observer — release happens in onDispose when navigating away.
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    // Track video size via Player.Listener so we can pick the correct orientation
    // when entering fullscreen (portrait vs. landscape).
    var videoSize by remember { mutableStateOf(exoPlayer.videoSize) }
    var manualOrientation by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Bug fix #3: Use Uri.fromFile() instead of Uri.parse() for filesystem paths
    LaunchedEffect(download) {
        download?.let {
            val file = File(it.filePath)
            val uri = if (file.exists()) {
                Uri.fromFile(file)
            } else {
                // Fallback: try parsing as-is (handles content:// URIs)
                Uri.parse(it.filePath)
            }
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    // Manage fullscreen system UI — hide/show system bars and force landscape
    LaunchedEffect(isFullscreen, manualOrientation, videoSize) {
        if (window != null && activity != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                val defaultOrientation = if (videoSize.height > videoSize.width)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                activity.requestedOrientation = manualOrientation ?: defaultOrientation
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                manualOrientation = null // reset when exiting fullscreen
            }
        }
    }

    // Handle back press in fullscreen — exit fullscreen first instead of navigating away
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // Bug fix #1: Proper lifecycle handling — pause on background, release on dispose.
    // The observer is keyed to the lifecycle owner and the exoPlayer instance, ensuring
    // no stale references survive navigation.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> {
                    // Only resume playback if we actually have media loaded
                    if (exoPlayer.mediaItemCount > 0) {
                        exoPlayer.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.stop()
            exoPlayer.release()
            // Restore orientation and system bars when leaving the player screen
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Scaffold(
        topBar = {
            // Hide top bar in fullscreen mode
            AnimatedVisibility(
                visible = !isFullscreen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                TopAppBar(
                    title = { Text(download?.fileName ?: "Video Player", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                            }
                            DownloadViewerMenu(
                                expanded = showMenu,
                                download = download,
                                onDismiss = { showMenu = false },
                                onOpenWith = {
                                    showMenu = false
                                    download?.let { DownloadFileActions.openWith(context, it) }
                                },
                                onShare = {
                                    showMenu = false
                                    download?.let { DownloadFileActions.share(context, it) }
                                },
                                onSyncToGallery = {
                                    showMenu = false
                                    download?.let { filesViewModel.syncToGallery(it) }
                                },
                                onMoveToPrivate = {
                                    showMenu = false
                                    download?.let { filesViewModel.moveToPrivate(it) }
                                },
                                onRemoveFromPrivate = {
                                    showMenu = false
                                    download?.let { filesViewModel.removeFromPrivate(it) }
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    )
                )
            }
        }
    ) { paddingValues ->
        // Bug fix #6: Apply Scaffold paddingValues so content isn't hidden behind TopAppBar
        // In fullscreen, skip padding so the player fills the entire screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isFullscreen) Modifier.padding(paddingValues) else Modifier)
                .background(Color.Black)
        ) {
            // Bug fix #18: Show loading/error states instead of empty player
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        color = Orange500,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = error ?: "Unknown error",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onBack,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange500)
                        ) {
                            Text("Go Back")
                        }
                    }
                }
                else -> {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                // Enable the built-in fullscreen button in the player controls
                                setFullscreenButtonClickListener { enterFullscreen ->
                                    isFullscreen = enterFullscreen
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Allow manual rotation in fullscreen via a small button
                    if (isFullscreen) {
                        IconButton(
                            onClick = {
                                val current = manualOrientation ?: if (videoSize.height > videoSize.width)
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                else
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                
                                manualOrientation = if (current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(24.dp)
                                .background(Color.Black.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh, 
                                contentDescription = "Rotate Screen", 
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
