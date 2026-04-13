package com.videdownloader.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class AppPreferences @Inject constructor(
    // Bug fix #18: Explicitly require ApplicationContext to prevent Activity context leaks
    @ApplicationContext private val context: Context
) {
    companion object {
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val SYNC_GALLERY = booleanPreferencesKey("sync_gallery")
        private val BLOCK_ADS = booleanPreferencesKey("block_ads")
        private val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        private val DARK_THEME = stringPreferencesKey("dark_theme")
        private val HIDE_TOOLBAR_LABEL = booleanPreferencesKey("hide_toolbar_label")
        private val STORAGE_PATH = stringPreferencesKey("storage_path")
    }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[WIFI_ONLY] ?: false }
    val syncGallery: Flow<Boolean> = context.dataStore.data.map { it[SYNC_GALLERY] ?: false }
    val blockAds: Flow<Boolean> = context.dataStore.data.map { it[BLOCK_ADS] ?: true }
    val searchEngine: Flow<String> = context.dataStore.data.map { it[SEARCH_ENGINE] ?: "Google" }
    val themeMode: Flow<String> = context.dataStore.data.map { it[DARK_THEME] ?: "System" }
    val hideToolbarLabel: Flow<Boolean> = context.dataStore.data.map { it[HIDE_TOOLBAR_LABEL] ?: false }
    val storagePath: Flow<String> = context.dataStore.data.map {
        it[STORAGE_PATH] ?: context.getExternalFilesDir(null)?.absolutePath ?: ""
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[WIFI_ONLY] = value }
    }

    suspend fun setSyncGallery(value: Boolean) {
        context.dataStore.edit { it[SYNC_GALLERY] = value }
    }

    suspend fun setBlockAds(value: Boolean) {
        context.dataStore.edit { it[BLOCK_ADS] = value }
    }

    suspend fun setSearchEngine(value: String) {
        context.dataStore.edit { it[SEARCH_ENGINE] = value }
    }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[DARK_THEME] = value }
    }

    suspend fun setHideToolbarLabel(value: Boolean) {
        context.dataStore.edit { it[HIDE_TOOLBAR_LABEL] = value }
    }

    suspend fun setStoragePath(value: String) {
        context.dataStore.edit { it[STORAGE_PATH] = value }
    }
}
