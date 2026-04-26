package com.cognitivechaos.xdownload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

data class ForceUpdateState(
    val minVersionCode: Long,
    val title: String,
    val message: String,
    val playStoreUrl: String
)

class ForceUpdateChecker(
    private val context: Context,
    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
) {
    fun check(onResult: (ForceUpdateState?) -> Unit) {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(3600)
                .build()
        )
        remoteConfig.setDefaultsAsync(defaults())

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener {
                val minVersionCode = remoteConfig.getLong(KEY_MIN_VERSION_CODE)
                if (minVersionCode > BuildConfig.VERSION_CODE) {
                    onResult(
                        ForceUpdateState(
                            minVersionCode = minVersionCode,
                            title = remoteConfig.getString(KEY_TITLE).ifBlank { DEFAULT_TITLE },
                            message = remoteConfig.getString(KEY_MESSAGE).ifBlank { DEFAULT_MESSAGE },
                            playStoreUrl = remoteConfig.getString(KEY_PLAY_STORE_URL)
                                .ifBlank { defaultPlayStoreUrl() }
                        )
                    )
                } else {
                    onResult(null)
                }
            }
    }

    fun openPlayStore(state: ForceUpdateState?) {
        val packageName = context.packageName
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(state?.playStoreUrl?.ifBlank { defaultPlayStoreUrl() } ?: defaultPlayStoreUrl())
        )

        try {
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                context.startActivity(webIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Could not open Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun defaults(): Map<String, Any> = mapOf(
        KEY_MIN_VERSION_CODE to BuildConfig.VERSION_CODE.toLong(),
        KEY_TITLE to DEFAULT_TITLE,
        KEY_MESSAGE to DEFAULT_MESSAGE,
        KEY_PLAY_STORE_URL to defaultPlayStoreUrl()
    )

    private fun defaultPlayStoreUrl(): String =
        "https://play.google.com/store/apps/details?id=${context.packageName}"

    private companion object {
        private const val KEY_MIN_VERSION_CODE = "minimum_supported_version_code"
        private const val KEY_TITLE = "force_update_title"
        private const val KEY_MESSAGE = "force_update_message"
        private const val KEY_PLAY_STORE_URL = "force_update_play_store_url"

        private const val DEFAULT_TITLE = "Update required"
        private const val DEFAULT_MESSAGE =
            "Please update the app to continue. This version is no longer supported."
    }
}
