package com.videdownloader.app.ui.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.videdownloader.app.data.db.DownloadEntity
import com.videdownloader.app.ui.theme.Orange500


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val allDownloads by viewModel.allDownloads.collectAsState()

    val (usedBytes, totalBytes) = remember { viewModel.getStorageInfo() }
    val tabTitles = listOf("Download", "Music", "Local Video")

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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Orange500,
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
                        formatSize = { viewModel.formatFileSize(it) }
                    )
                    1 -> EmptyTabContent("No music files", "Downloaded audio files will appear here")
                    2 -> EmptyTabContent("No local videos", "Videos from your device will appear here")
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
}

@Composable
fun DownloadsList(
    downloads: List<DownloadEntity>,
    onDelete: (DownloadEntity) -> Unit,
    onPause: (DownloadEntity) -> Unit,
    onResume: (DownloadEntity) -> Unit,
    onSyncToGallery: (DownloadEntity) -> Unit,
    formatSize: (Long) -> String
) {
    if (downloads.isEmpty()) {
        EmptyTabContent("No downloads yet", "Downloaded videos will appear here")
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
    formatSize: (Long) -> String
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* Open/play file */ }
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
                    if (download.status == "COMPLETED") {
                        req.setParameter("coil#video_frame_micros", 3000L * 1000L)
                    }
                    req.build()
                }
                
                coil.compose.AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.VideoFile),
                    error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.VideoFile),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.VideoFile,
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

            // Status badge for non-completed
            if (download.status != "COMPLETED") {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = when (download.status) {
                        "DOWNLOADING" -> Orange500
                        "PAUSED" -> Color(0xFF2196F3)
                        "FAILED" -> Color(0xFFF44336)
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
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { showMenu = false },
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
