package com.cognitivechaos.xdownload.ui.browser

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONException

/**
 * Data class carrying playback/visibility/ad-UI signals for a single video element.
 */
data class VideoSignal(
    val url: String,
    val isPlaying: Boolean?,
    val isVisible: Boolean?,
    val hasAdUIPatterns: Boolean?
)

/**
 * JavaScript interface injected into the WebView as "Android".
 *
 * The JS snippet calls Android.reportVideoSignals(jsonString) on page load.
 * Each element in the JSON array has the shape:
 *   { "url": "...", "isPlaying": true, "isVisible": true, "hasAdUI": false }
 *
 * On successful parse the signals are forwarded to [onSignalsReceived].
 * Malformed JSON is caught and logged at debug level — no crash.
 */
class VideoSignalsJsInterface(
    private val onSignalsReceived: (List<VideoSignal>) -> Unit
) {
    companion object {
        private const val TAG = "VideoSignalsJs"
    }

    @JavascriptInterface
    fun reportVideoSignals(json: String) {
        try {
            val array = JSONArray(json)
            val signals = mutableListOf<VideoSignal>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").trim()
                if (url.isEmpty()) continue
                signals.add(
                    VideoSignal(
                        url = url,
                        isPlaying = if (obj.has("isPlaying")) obj.optBoolean("isPlaying") else null,
                        isVisible = if (obj.has("isVisible")) obj.optBoolean("isVisible") else null,
                        hasAdUIPatterns = if (obj.has("hasAdUI")) obj.optBoolean("hasAdUI") else null
                    )
                )
            }
            Log.d(TAG, "Received ${signals.size} video signals")
            onSignalsReceived(signals)
        } catch (e: JSONException) {
            Log.d(TAG, "Failed to parse video signals JSON: ${e.message}")
        }
    }
}
