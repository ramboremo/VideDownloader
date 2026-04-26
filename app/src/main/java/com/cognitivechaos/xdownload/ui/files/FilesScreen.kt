@file:Suppress("DEPRECATION")
package com.cognitivechaos.xdownload.ui.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cognitivechaos.xdownload.data.db.DownloadEntity
import com.cognitivechaos.xdownload.ui.theme.Orange500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenDownload: (DownloadEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val allDownloads by viewModel.allDownloads.collectAsState()
    val privateDownloads by viewModel.privateDownloads.collectAsState()
    val privateFolderPin by viewModel.privateFolderPin.collectAsState()

    var showPinSetup by remember { mutableStateOf(false) }
    var showPinEntry by remember { mutableStateOf(false) }
    // Bug fix #5: Use a saveable state block so unlock state survives configuration changes (rotation)
    var isPrivateUnlocked by rememberSaveable { mutableStateOf(false) }

    // Bug fix #17: Auto-lock private folder when app is backgrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                isPrivateUnlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val (usedBytes, totalBytes) = remember { viewModel.getStorageInfo() }
    val tabTitles = listOf("Download", "Music", "Video", "Private")

    // Bug fix #4: Trigger PIN dialogs based on selectedTab changes, not LaunchedEffect(Unit)
    // This effect fires every time the tab changes, so pressing Private tab always prompts.
    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) {
            if (privateFolderPin.isEmpty()) {
                showPinSetup = true
            } else if (!isPrivateUnlocked) {
                showPinEntry = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Files", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* Sort/filter */ }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Orange500,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Orange500
                        )
                    }
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Text(
                                title,
                                color = if (selectedTab == index) Orange500
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    )
                }
            }

            // Content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> DownloadsList(
                        downloads = allDownloads,
                        onDelete = { viewModel.deleteDownload(it) },
                        onPause = { viewModel.pauseDownload(it) },
                        onResume = { viewModel.resumeDownload(it) },
                        onSyncToGallery = { viewModel.syncToGallery(it) },
                        onMoveToPrivate = { viewModel.moveToPrivate(it) },
                        onRemoveFromPrivate = { viewModel.removeFromPrivate(it) },
                        onShare = { DownloadFileActions.share(context, it) },
                        onOpenWith = { DownloadFileActions.openWith(context, it) },
                        onOpenDownload = onOpenDownload,
                        formatSize = { viewModel.formatFileSize(it) }
                    )
                    1 -> EmptyTabContent("No music files", "Downloaded audio files will appear here")
                    2 -> EmptyTabContent("No local videos", "Videos from your device will appear here")
                    3 -> {
                        // Bug fix #4: PIN dialogs are now triggered by LaunchedEffect(selectedTab) above,
                        // so no inline LaunchedEffect(Unit) needed here.
                        if (privateFolderPin.isEmpty()) {
                            EmptyTabContent("Private Folder", "Setup a PIN to secure your files")
                        } else if (!isPrivateUnlocked) {
                            EmptyTabContent("Locked", "Enter PIN to view private files")
                        } else {
                            DownloadsList(
                                downloads = privateDownloads,
                                onDelete = { viewModel.deleteDownload(it) },
                                onPause = { viewModel.pauseDownload(it) },
                                onResume = { viewModel.resumeDownload(it) },
                                onSyncToGallery = { viewModel.syncToGallery(it) },
                                onMoveToPrivate = { viewModel.moveToPrivate(it) },
                                onRemoveFromPrivate = { viewModel.removeFromPrivate(it) },
                                onShare = { DownloadFileActions.share(context, it) },
                                onOpenWith = { DownloadFileActions.openWith(context, it) },
                                onOpenDownload = onOpenDownload,
                                formatSize = { viewModel.formatFileSize(it) }
                            )
                        }
                    }
                }
            }

            // Bottom storage bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${viewModel.formatFileSize(usedBytes)}/${viewModel.formatFileSize(totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Storage progress bar
                    LinearProgressIndicator(
                        progress = { if (totalBytes > 0) usedBytes.toFloat() / totalBytes.toFloat() else 0f },
                        modifier = Modifier
                            .width(120.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Orange500,
                        trackColor = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    if (showPinSetup) {
        var pinInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { 
                showPinSetup = false
                viewModel.selectTab(0)
            },
            title = { Text("Set Private PIN") },
            text = {
                Column {
                    Text("Create a PIN to secure your private files.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6) pinInput = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        placeholder = { Text("Enter 4-6 digits") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pinInput.length >= 4) {
                            viewModel.setPin(pinInput)
                            showPinSetup = false
                            isPrivateUnlocked = true
                        }
                    }
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showPinSetup = false
                    viewModel.selectTab(0)
                }) { Text("Cancel") }
            }
        )
    }

    if (showPinEntry) {
        var pinInput by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { 
                showPinEntry = false
                viewModel.selectTab(0)
            },
            title = { Text("Enter PIN") },
            text = {
                Column {
                    Text("Enter your PIN to access Private Folder.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { 
                            if (it.length <= 6) {
                                pinInput = it
                                isError = false
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = isError,
                        singleLine = true
                    )
                    if (isError) {
                        Text("Incorrect PIN", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Bug fix #2: Use hashed PIN verification instead of plaintext comparison
                        viewModel.verifyPin(pinInput) { matches ->
                            if (matches) {
                                isPrivateUnlocked = true
                                showPinEntry = false
                            } else {
                                isError = true
                            }
                        }
                    }
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showPinEntry = false
                    viewModel.selectTab(0)
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DownloadsList(
    downloads: List<DownloadEntity>,
    onDelete: (DownloadEntity) -> Unit,
    onPause: (DownloadEntity) -> Unit,
    onResume: (DownloadEntity) -> Unit,
    onSyncToGallery: (DownloadEntity) -> Unit,
    onMoveToPrivate: (DownloadEntity) -> Unit,
    onRemoveFromPrivate: (DownloadEntity) -> Unit,
    onShare: (DownloadEntity) -> Unit,
    onOpenWith: (DownloadEntity) -> Unit,
    onOpenDownload: (DownloadEntity) -> Unit,
    formatSize: (Long) -> String
) {
    if (downloads.isEmpty()) {
        EmptyTabContent("No items", "Your downloaded/private files will appear here")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(downloads, key = { it.id }) { download ->
                DownloadItem(
                    download = download,
                    onDelete = { onDelete(download) },
                    onPause = { onPause(download) },
                    onResume = { onResume(download) },
                    onSyncToGallery = { onSyncToGallery(download) },
                    onMoveToPrivate = { onMoveToPrivate(download) },
                    onRemoveFromPrivate = { onRemoveFromPrivate(download) },
                    onShare = { onShare(download) },
                    onOpenWith = { onOpenWith(download) },
                    onOpenDownload = { onOpenDownload(download) },
                    formatSize = formatSize
                )
            }
        }
    }
}

@Composable
fun DownloadItem(
    download: DownloadEntity,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSyncToGallery: () -> Unit,
    onMoveToPrivate: () -> Unit,
    onRemoveFromPrivate: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onOpenDownload: () -> Unit,
    formatSize: (Long) -> String
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { 
                if (download.status == "COMPLETED") {
                    onOpenDownload()
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail placeholder with duration badge
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val isImage = remember(download.mimeType, download.filePath) { DownloadFileActions.isImage(download) }
            val imageSource = remember(download) {
                if (download.status == "COMPLETED") {
                    java.io.File(download.filePath)
                } else if (!download.thumbnailUrl.isNullOrEmpty()) {
                    download.thumbnailUrl
                } else {
                    null
                }
            }
            
            if (imageSource != null) {
                val imageRequest = remember(imageSource) {
                    val req = coil.request.ImageRequest.Builder(context)
                        .data(imageSource)
                    if (download.status == "COMPLETED" && !isImage) {
                        req.setParameter("coil#video_frame_micros", 3000L * 1000L)
                    }
                    req.build()
                }
                
                coil.compose.AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                        if (isImage) Icons.Default.Image else Icons.Default.VideoFile
                    ),
                    error = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                        if (isImage) Icons.Default.Image else Icons.Default.VideoFile
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (DownloadFileActions.isImage(download)) Icons.Default.Image else Icons.Default.VideoFile,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Duration badge
            if (download.duration != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = Color.Black.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = download.duration,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Status badge for active/paused — NOT shown for FAILED (error shown in info area instead)
            if (download.status != "COMPLETED" && download.status != "FAILED") {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = when (download.status) {
                        "DOWNLOADING" -> Orange500
                        "PAUSED" -> Color(0xFF2196F3)
                        else -> Color.Gray
                    }
                ) {
                    Text(
                        text = download.status,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 8.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (download.status == "FAILED") {
                // Error message in red — replaces speed/size row for failed downloads
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFF44336)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (download.downloadedBytes == 0L)
                            "Download failed · Check your connection"
                        else
                            "Download failed · Tap ▶ to retry",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatSize(
                            if (download.status == "COMPLETED") download.fileSize
                            else download.downloadedBytes
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (download.quality.isNotEmpty()) {
                        Text(
                            text = " · ${download.quality}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (download.status == "DOWNLOADING" && download.downloadSpeed.isNotEmpty()) {
                        Text(
                            text = " · ${download.downloadSpeed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Orange500
                        )
                    }
                }

                // Download progress bar
                if (download.status == "DOWNLOADING") {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (download.fileSize > 0) {
                        LinearProgressIndicator(
                            progress = { download.downloadedBytes.toFloat() / download.fileSize.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Orange500,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Orange500,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        // Inline pause/resume and more options
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (download.status == "DOWNLOADING") {
                IconButton(onClick = onPause) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Pause",
                        tint = Orange500
                    )
                }
            } else if (download.status == "PAUSED" || download.status == "FAILED") {
                IconButton(onClick = onResume) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Resume",
                        tint = Orange500
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Orange500
                    )
                }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (!download.isPrivate) {
                    // Bug fix #11: Only show "Move to Private" for COMPLETED downloads.
                    // Moving an active download would corrupt the file.
                    if (download.status == "COMPLETED") {
                        DropdownMenuItem(
                            text = { Text("Move to Private") },
                            onClick = {
                                showMenu = false
                                onMoveToPrivate()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Lock,
                                    null,
                                    tint = Orange500
                                )
                            }
                        )
                    }
                    if (download.status == "COMPLETED") {
                        DropdownMenuItem(
                            text = { Text("Sync to Gallery") },
                            onClick = {
                                showMenu = false
                                onSyncToGallery()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    null,
                                    tint = Orange500
                                )
                            }
                        )
                    }
                } else {
                    DropdownMenuItem(
                        text = { Text("Remove from Private") },
                        onClick = {
                            showMenu = false
                            onRemoveFromPrivate()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LockOpen,
                                null,
                                tint = Orange500
                            )
                        }
                    )
                }
                if (download.status == "COMPLETED") {
                    DropdownMenuItem(
                        text = { Text("Open with") },
                        onClick = {
                            showMenu = false
                            onOpenWith()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.OpenInNew,
                                null,
                                tint = Orange500
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        showMenu = false
                        onShare()
                    },
                    enabled = download.status == "COMPLETED",
                    leadingIcon = { Icon(Icons.Default.Share, null) }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = Color(0xFFF44336)
                        )
                    }
                )
            }
        }
    }
    }

    HorizontalDivider(
        modifier = Modifier.padding(start = 108.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun EmptyTabContent(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
