package com.cognitivechaos.xdownload.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
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
        private val PRIVATE_FOLDER_PIN = stringPreferencesKey("private_folder_pin")
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
    val privateFolderPin: Flow<String> = context.dataStore.data.map { it[PRIVATE_FOLDER_PIN] ?: "" }

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

    suspend fun setPrivateFolderPin(value: String) {
        context.dataStore.edit {
            if (value.isEmpty()) {
                it.remove(PRIVATE_FOLDER_PIN)
            } else {
                it[PRIVATE_FOLDER_PIN] = hashPin(value)
            }
        }
    }

    /**
     * Verifies a raw PIN input against the stored hash.
     * Handles migration from legacy plaintext storage: if the stored value
     * is shorter than 64 chars (a SHA-256 hex digest), it's treated as
     * plaintext, verified directly, and then re-stored as a hash.
     */
    suspend fun verifyPin(input: String): Boolean {
        val stored = context.dataStore.data.map { it[PRIVATE_FOLDER_PIN] ?: "" }.first()
        if (stored.isEmpty()) return false

        // Legacy migration: stored value < 64 chars means it's plaintext
        if (stored.length < 64) {
            if (input == stored) {
                setPrivateFolderPin(input) // Re-store as hash
                return true
            }
            return false
        }

        return stored == hashPin(input)
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
