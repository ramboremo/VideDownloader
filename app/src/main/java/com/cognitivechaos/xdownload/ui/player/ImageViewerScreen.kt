package com.cognitivechaos.xdownload.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.cognitivechaos.xdownload.ui.files.DownloadFileActions
import com.cognitivechaos.xdownload.ui.files.FilesViewModel
import com.cognitivechaos.xdownload.ui.theme.Orange500
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
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
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(download?.fileName ?: "Image", color = Color.White) },
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
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = Orange500)
                error != null -> Text(error ?: "Image not found", color = Color.White)
                download == null -> Text("Image not found", color = Color.White)
                else -> {
                    val file = File(download!!.filePath)
                    AsyncImage(
                        model = file,
                        contentDescription = download!!.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .transformable(transformState)
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadViewerMenu(
    expanded: Boolean,
    download: com.cognitivechaos.xdownload.data.db.DownloadEntity?,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onSyncToGallery: () -> Unit,
    onMoveToPrivate: () -> Unit,
    onRemoveFromPrivate: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Open with") },
            onClick = onOpenWith,
            enabled = download?.status == "COMPLETED",
            leadingIcon = { Icon(Icons.Default.OpenInNew, null, tint = Orange500) }
        )
        DropdownMenuItem(
            text = { Text("Share") },
            onClick = onShare,
            enabled = download?.status == "COMPLETED",
            leadingIcon = { Icon(Icons.Default.Share, null) }
        )
        if (download?.isPrivate == true) {
            DropdownMenuItem(
                text = { Text("Remove from Private") },
                onClick = onRemoveFromPrivate,
                leadingIcon = { Icon(Icons.Default.LockOpen, null, tint = Orange500) }
            )
        } else {
            DropdownMenuItem(
                text = { Text("Sync to Gallery") },
                onClick = onSyncToGallery,
                enabled = download?.status == "COMPLETED",
                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, tint = Orange500) }
            )
            DropdownMenuItem(
                text = { Text("Move to Private") },
                onClick = onMoveToPrivate,
                enabled = download?.status == "COMPLETED",
                leadingIcon = { Icon(Icons.Default.Lock, null, tint = Orange500) }
            )
        }
    }
}
