package com.videdownloader.app.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.videdownloader.app.ui.theme.Orange500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val syncGallery by viewModel.syncGallery.collectAsState()
    val blockAds by viewModel.blockAds.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val hideToolbarLabel by viewModel.hideToolbarLabel.collectAsState()
    val storagePath by viewModel.storagePath.collectAsState()

    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
        ) {
            // ===== REGULAR SETUP =====
            SectionHeader("Regular setup")

            SettingsClickItem(
                title = "Language",
                subtitle = "English",
                onClick = { /* TODO: Language picker */ }
            )

            SettingsClickItem(
                title = "Themes",
                subtitle = themeMode,
                onClick = { showThemeDialog = true }
            )

            SettingsToggleItem(
                title = "Hide Toolbar Label",
                checked = hideToolbarLabel,
                onCheckedChange = { viewModel.setHideToolbarLabel(it) }
            )

            // ===== DOWNLOAD =====
            SectionHeader("Download")

            SettingsClickItem(
                title = "Storage location",
                subtitle = storagePath.ifEmpty { "Default" },
                onClick = { /* TODO: Folder picker */ }
            )

            SettingsToggleItem(
                title = "Download with Wi-Fi Only",
                checked = wifiOnly,
                onCheckedChange = { viewModel.setWifiOnly(it) }
            )

            SettingsToggleItem(
                title = "Sync to gallery",
                checked = syncGallery,
                onCheckedChange = { viewModel.setSyncGallery(it) }
            )

            // ===== BROWSER SETTINGS =====
            SectionHeader("Browser Settings")

            SettingsClickItem(
                title = "Search Engine",
                subtitle = searchEngine,
                onClick = { showSearchEngineDialog = true }
            )

            SettingsToggleItem(
                title = "Block ads",
                checked = blockAds,
                onCheckedChange = { viewModel.setBlockAds(it) }
            )

            SettingsClickItem(
                title = "Clear cache",
                onClick = { showClearCacheDialog = true }
            )

            SettingsClickItem(
                title = "Clear browser history",
                onClick = { showClearHistoryDialog = true }
            )

            // ===== HELP =====
            SectionHeader("Help")

            SettingsClickItem(
                title = "How to download videos?",
                onClick = { /* TODO: Tutorial */ }
            )

            SettingsClickItem(
                title = "Privacy policy",
                onClick = { /* TODO */ }
            )

            SettingsClickItem(
                title = "Share",
                onClick = { /* TODO */ }
            )

            // Version
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Version",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Search Engine Dialog
    if (showSearchEngineDialog) {
        val engines = listOf("Google", "Bing", "DuckDuckGo", "Yahoo")
        AlertDialog(
            onDismissRequest = { showSearchEngineDialog = false },
            title = { Text("Search Engine") },
            text = {
                Column {
                    engines.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setSearchEngine(engine)
                                    showSearchEngineDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = searchEngine == engine,
                                onClick = {
                                    viewModel.setSearchEngine(engine)
                                    showSearchEngineDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Orange500)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(engine, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchEngineDialog = false }) {
                    Text("Cancel", color = Orange500)
                }
            }
        )
    }

    // Theme Dialog
    if (showThemeDialog) {
        val themes = listOf("Light", "Dark", "System")
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    themes.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setThemeMode(theme)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == theme,
                                onClick = {
                                    viewModel.setThemeMode(theme)
                                    showThemeDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Orange500)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(theme, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Cancel", color = Orange500)
                }
            }
        )
    }

    // Clear Cache Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = { Text("Are you sure you want to clear the browser cache?") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    // Bug fix #10: Actually clear the WebView cache
                    android.webkit.WebView(context).apply {
                        clearCache(true)
                        destroy()
                    }
                    // Also clear WebStorage
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                }) {
                    Text("Clear", color = Orange500)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear History") },
            text = { Text("Are you sure you want to clear your browsing history?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearHistoryDialog = false
                }) {
                    Text("Clear", color = Orange500)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = Orange500,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsClickItem(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = Orange500,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}
