package com.videdownloader.app.service

import android.util.Log
import com.videdownloader.app.data.model.DetectedMedia
import com.videdownloader.app.data.model.MediaQualityOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoDetector @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "VideoDetector"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".webm", ".mkv", ".avi", ".mov", ".flv",
            ".m4v", ".3gp", ".wmv", ".mpg", ".mpeg"
        )

        private val STREAM_EXTENSIONS = listOf(
            ".m3u8", ".mpd"
        )

        private val AUDIO_EXTENSIONS = listOf(
            ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".flac", ".wma"
        )

        private val MEDIA_MIME_TYPES = listOf(
            "video/", "audio/", "application/x-mpegurl",
            "application/vnd.apple.mpegurl", "application/dash+xml"
        )

        // URLs to ignore (ads, trackers, tiny files)
        private val IGNORE_PATTERNS = listOf(
            "googlevideo.com/videoplayback", // YouTube (needs special handling)
            "googleads", "doubleclick", "analytics", "adsystem", "adserver", "adsense",
            "facebook.com/tr", "pixel", "beacon", "trafficjunky", "magsrv", "exo", "popads",
            "propellerads", "adsterra", "exoclick", "spotx", "vpaid", "vast", "adx", "taboola",
            "outbrain", "rubicon", "smartadserver", "innovid", "tremor", "freewheel", "adstream",
            "cdn77-vid", // TrafficJunky CDN used for pre-roll ads
            ".gif", ".png", ".jpg", ".jpeg", ".svg", ".ico",
            ".css", ".js", ".woff", ".ttf"
        )

        // Pornhub host patterns
        private val PH_HOSTS = listOf("pornhub.com", "pornhub.net", "pornhub.org")

        // Resolution mapping for quality labels
        private val QUALITY_RESOLUTION_MAP = mapOf(
            2160 to "3840x2160",
            1080 to "1920x1080",
            720 to "1280x720",
            480 to "854x480",
            360 to "640x360",
            240 to "426x240",
            144 to "256x144"
        )
    }

    // Coroutine scope for async pre-fetching
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _detectedMedia = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val detectedMedia: StateFlow<List<DetectedMedia>> = _detectedMedia.asStateFlow()

    private val _hasMedia = MutableStateFlow(false)
    val hasMedia: StateFlow<Boolean> = _hasMedia.asStateFlow()

    private val detectedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var currentPageUrl = ""
    private var currentPageTitle = ""
    private var currentPageThumbnail = ""

    // Pre-fetched quality options from PH get_media API
    private val prefetchedOptions = java.util.Collections.synchronizedMap(
        mutableMapOf<String, List<MediaQualityOption>>()
    )

    fun setCurrentPage(url: String, title: String) {
        currentPageUrl = url
        currentPageTitle = title
    }

    fun setCurrentThumbnail(url: String) {
        if (url.isNotBlank() && url != "null") {
            currentPageThumbnail = url
        }
    }

    fun clearDetectedMedia() {
        detectedUrls.clear()
        _detectedMedia.value = emptyList()
        _hasMedia.value = false
        prefetchedOptions.clear()
        currentPageThumbnail = ""
    }

    /**
     * Check if the current page is a Pornhub page.
     */
    private fun isPornhubPage(): Boolean {
        return PH_HOSTS.any { currentPageUrl.lowercase().contains(it) }
    }

    /**
     * Check if a URL is a PH get_media API endpoint.
     * These return JSON with direct video URLs, not actual video data.
     */
    private fun isPornhubGetMediaUrl(url: String): Boolean {
        val lowUrl = url.lowercase()
        return PH_HOSTS.any { lowUrl.contains(it) } && lowUrl.contains("get_media")
    }

    fun onResourceRequest(url: String, mimeType: String? = null): Boolean {
        val lowUrl = url.lowercase()

        // Skip ignored patterns
        if (IGNORE_PATTERNS.any { lowUrl.contains(it) }) return false

        // === PH get_media interception (highest priority) ===
        // When PH's video player loads, it requests /video/get_media which returns
        // JSON containing direct MP4 download URLs. This is the golden URL.
        if (isPornhubGetMediaUrl(url) && url !in detectedUrls) {
            detectedUrls.add(url)
            val media = DetectedMedia(
                url = url,
                title = currentPageTitle.ifEmpty { "Video" },
                mimeType = "application/json",     // It's a JSON API, not a video
                quality = "Auto",
                sourcePageUrl = currentPageUrl,
                sourcePageTitle = currentPageTitle,
                thumbnailUrl = currentPageThumbnail
            )

            val currentList = _detectedMedia.value.toMutableList()
            currentList.add(media)
            _detectedMedia.value = currentList
            _hasMedia.value = true

            Log.d(TAG, "PH get_media intercepted: ${url.take(120)}")

            // Pre-fetch the direct MP4 URLs asynchronously
            // By the time user clicks the download FAB, results will be ready
            scope.launch {
                try {
                    val options = parsePornhubGetMedia(url)
                    if (options.isNotEmpty()) {
                        prefetchedOptions[url] = options
                        Log.d(TAG, "Pre-fetched ${options.size} PH quality options with file sizes")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Pre-fetch failed", e)
                }
            }
            return true
        }

        // === Standard media detection ===
        val isMedia = when {
            // Check MIME type
            mimeType != null && MEDIA_MIME_TYPES.any { mimeType.startsWith(it) } -> true
            // Check video extensions
            VIDEO_EXTENSIONS.any { lowUrl.substringBefore("?").endsWith(it) } -> true
            // Check stream extensions
            STREAM_EXTENSIONS.any { lowUrl.substringBefore("?").endsWith(it) } -> true
            // Check audio extensions
            AUDIO_EXTENSIONS.any { lowUrl.substringBefore("?").endsWith(it) } -> true
            // Check URL patterns (but skip generic /video/ on PH pages since get_media is handled above)
            lowUrl.contains("videoplayback") ||
            (!isPornhubPage() && lowUrl.contains("/video/")) ||
            (lowUrl.contains("manifest") && (lowUrl.contains(".m3u8") || lowUrl.contains(".mpd"))) -> true
            else -> false
        }

        if (isMedia && url !in detectedUrls) {
            detectedUrls.add(url)
            val quality = guessQuality(url)
            val media = DetectedMedia(
                url = url,
                title = currentPageTitle.ifEmpty { "Video" },
                mimeType = mimeType ?: guessMimeType(url),
                quality = quality,
                sourcePageUrl = currentPageUrl,
                sourcePageTitle = currentPageTitle,
                thumbnailUrl = currentPageThumbnail
            )

            val currentList = _detectedMedia.value.toMutableList()
            currentList.add(media)
            _detectedMedia.value = currentList
            _hasMedia.value = true

            Log.d(TAG, "Detected media: $quality - ${url.take(100)}")
            return true
        }
        return false
    }

    fun onVideoElementDetected(src: String, pageUrl: String, pageTitle: String) {
        if (src.isNotBlank() && src !in detectedUrls) {
            setCurrentPage(pageUrl, pageTitle)
            onResourceRequest(src)
        }
    }

    // ==========================================
    // Quality Options Fetching
    // ==========================================

    suspend fun fetchQualityOptions(media: DetectedMedia): List<MediaQualityOption> {
        return withContext(Dispatchers.IO) {
            try {
                val url = media.url.lowercase()
                when {
                    // PH get_media API endpoint → parse JSON for direct MP4 URLs
                    isPornhubGetMediaUrl(media.url) -> {
                        // Use pre-fetched results if available (instant response)
                        prefetchedOptions[media.url]
                            ?: parsePornhubGetMedia(media.url)
                    }
                    url.contains(".m3u8") -> parseM3u8(media.url)
                    url.contains(".mpd") -> parseMpd(media.url)
                    else -> listOf(
                        MediaQualityOption(
                            url = media.url,
                            quality = media.quality ?: "Default",
                            mimeType = media.mimeType ?: "video/mp4",
                            fileSize = getFileSizeSmart(media.url)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching quality options", e)
                listOf(
                    MediaQualityOption(
                        url = media.url,
                        quality = media.quality ?: "Default",
                        mimeType = media.mimeType ?: "video/mp4"
                    )
                )
            }
        }
    }

    // ==========================================
    // Pornhub-Specific: Parse get_media JSON
    // ==========================================

    /**
     * Fetches the PH /video/get_media endpoint and parses the JSON response
     * to extract direct MP4 download URLs with quality labels.
     *
     * The JSON response is an array like:
     *   [{"defaultQuality":false,"format":"mp4","quality":"720","videoUrl":"https://...mp4"},...]
     *
     * Some entries have format="hls" which point to m3u8 playlists - we skip those
     * since direct MP4 is preferred.
     */
    private fun parsePornhubGetMedia(getMediaUrl: String): List<MediaQualityOption> {
        val options = mutableListOf<MediaQualityOption>()
        try {
            val builder = Request.Builder().url(getMediaUrl)
            addPornhubHeaders(builder, getMediaUrl)

            val response = okHttpClient.newCall(builder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "PH get_media failed: HTTP ${response.code}")
                return options
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.e(TAG, "PH get_media returned empty body")
                return options
            }

            Log.d(TAG, "PH get_media response (first 500 chars): ${body.take(500)}")

            // Response can be a JSON array directly or wrapped
            val jsonArray = try {
                JSONArray(body)
            } catch (e: Exception) {
                // Try parsing as object with array inside
                try {
                    val obj = JSONObject(body)
                    // Some responses wrap in an object
                    obj.optJSONArray("mediaDefinitions")
                        ?: obj.optJSONArray("qualities")
                        ?: obj.optJSONArray("videos")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to parse PH get_media JSON", e2)
                    null
                }
            }

            if (jsonArray == null) {
                Log.e(TAG, "No JSON array found in PH get_media response")
                return options
            }

            // First pass: collect all entries, distinguishing get_media sub-URLs vs direct URLs
            val subGetMediaUrls = mutableListOf<String>()
            val directEntries = mutableListOf<Pair<String, Int>>() // url, quality

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val videoUrl = obj.optString("videoUrl", "").trim()
                val format = obj.optString("format", "").lowercase()
                val qualityStr = obj.optString("quality", "")
                val quality = qualityStr.replace("p", "").toIntOrNull() ?: 0

                if (videoUrl.isEmpty() || !videoUrl.startsWith("http")) continue

                // Skip HLS format - we want direct MP4 downloads
                if (format == "hls") continue

                // If this videoUrl itself is another get_media endpoint, we need to follow it
                if (videoUrl.contains("get_media")) {
                    subGetMediaUrls.add(videoUrl)
                } else if (quality > 0) {
                    directEntries.add(Pair(videoUrl, quality))
                }
            }

            // If we found sub get_media URLs, follow them (one level of indirection)
            for (subUrl in subGetMediaUrls) {
                try {
                    val subBuilder = Request.Builder().url(subUrl)
                    addPornhubHeaders(subBuilder, subUrl)
                    val subResponse = okHttpClient.newCall(subBuilder.build()).execute()
                    val subBody = subResponse.body?.string() ?: continue

                    val subArray = try { JSONArray(subBody) } catch (e: Exception) { continue }
                    for (j in 0 until subArray.length()) {
                        val subObj = subArray.optJSONObject(j) ?: continue
                        val subVideoUrl = subObj.optString("videoUrl", "").trim()
                        val subQualityStr = subObj.optString("quality", "")
                        val subQuality = subQualityStr.replace("p", "").toIntOrNull() ?: 0
                        val subFormat = subObj.optString("format", "").lowercase()

                        if (subVideoUrl.isNotEmpty() && subVideoUrl.startsWith("http")
                            && subQuality > 0 && subFormat != "hls"
                        ) {
                            directEntries.add(Pair(subVideoUrl, subQuality))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error following sub get_media URL", e)
                }
            }

            if (directEntries.isEmpty()) {
                Log.w(TAG, "No direct MP4 entries found in PH get_media response")
                return options
            }

            // Deduplicate by quality (keep first URL per quality level)
            val uniqueByQuality = directEntries
                .groupBy { it.second }
                .mapValues { it.value.first() }
                .values.toList()

            Log.d(TAG, "Found ${uniqueByQuality.size} unique PH quality options, fetching file sizes...")

            // Fetch file sizes for each quality option
            for ((videoUrl, quality) in uniqueByQuality) {
                val fileSize = getFileSizeSmart(videoUrl)
                options.add(
                    MediaQualityOption(
                        url = videoUrl,
                        quality = "${quality}p",
                        resolution = QUALITY_RESOLUTION_MAP[quality],
                        fileSize = fileSize,
                        mimeType = "video/mp4"
                    )
                )
            }

            Log.d(TAG, "PH quality options ready: ${options.map { "${it.quality}=${it.fileSize?.let { s -> "${s / 1_048_576}MB" } ?: "?"}" }}")

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PH get_media", e)
        }

        return options.sortedByDescending {
            Regex("\\d+").find(it.quality)?.value?.toIntOrNull() ?: 0
        }
    }

    /**
     * Add required headers for PH API requests.
     * Cookies (including age_verified) and Referer are critical.
     */
    private fun addPornhubHeaders(builder: Request.Builder, url: String) {
        // Get cookies from CookieManager (set by the WebView)
        var cookie = android.webkit.CookieManager.getInstance().getCookie(url)
        if (cookie.isNullOrEmpty() && currentPageUrl.isNotEmpty()) {
            cookie = android.webkit.CookieManager.getInstance().getCookie(currentPageUrl)
        }
        // Try the base domain if still empty
        if (cookie.isNullOrEmpty()) {
            cookie = android.webkit.CookieManager.getInstance().getCookie("https://www.pornhub.com")
        }
        if (!cookie.isNullOrEmpty()) {
            builder.addHeader("Cookie", cookie)
        }
        builder.addHeader("User-Agent", USER_AGENT)
        builder.addHeader("Referer", currentPageUrl.ifEmpty { "https://www.pornhub.com/" })
        builder.addHeader("X-Requested-With", "XMLHttpRequest")
        builder.addHeader("Accept", "application/json, */*")
    }

    // ==========================================
    // File Size Detection (Range-aware)
    // ==========================================

    /**
     * Smart file size detection that tries multiple methods:
     * 1. Range request (works on PH which blocks HEAD requests)
     * 2. HEAD request (works on most other servers)
     */
    private fun getFileSizeSmart(url: String): Long? {
        // Try Range request first (PH and many CDNs support this)
        val rangeSize = getFileSizeViaRange(url)
        if (rangeSize != null && rangeSize > 0) return rangeSize

        // Fallback to HEAD request
        return getFileSizeViaHead(url)
    }

    /**
     * Get file size via Range request: GET with Range: bytes=0-0
     * Server responds with Content-Range: bytes 0-0/TOTAL_SIZE
     * This works even when HEAD is blocked.
     */
    private fun getFileSizeViaRange(url: String): Long? {
        return try {
            val builder = Request.Builder().url(url)
                .addHeader("Range", "bytes=0-0")

            var cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (cookie.isNullOrEmpty() && currentPageUrl.isNotEmpty()) {
                cookie = android.webkit.CookieManager.getInstance().getCookie(currentPageUrl)
            }
            if (!cookie.isNullOrEmpty()) {
                builder.addHeader("Cookie", cookie)
            }
            builder.addHeader("User-Agent", USER_AGENT)
            if (currentPageUrl.isNotEmpty()) builder.addHeader("Referer", currentPageUrl)

            val response = okHttpClient.newCall(builder.build()).execute()

            // Parse Content-Range: bytes 0-0/305197530
            val contentRange = response.header("Content-Range")
            if (contentRange != null && contentRange.contains("/")) {
                val totalStr = contentRange.substringAfter("/").trim()
                if (totalStr != "*") {
                    val size = totalStr.toLongOrNull()
                    if (size != null && size > 0) {
                        response.close()
                        return size
                    }
                }
            }

            // Fallback: check Content-Length on a 200 response
            val contentLength = response.header("Content-Length")?.toLongOrNull()
            response.close()
            contentLength
        } catch (e: Exception) {
            Log.d(TAG, "Range request failed for ${url.take(80)}: ${e.message}")
            null
        }
    }

    /**
     * Traditional HEAD request for file size.
     * Works on most servers but blocked by PH.
     */
    private fun getFileSizeViaHead(url: String): Long? {
        return try {
            val builder = Request.Builder().url(url).head()
            var cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (cookie.isNullOrEmpty() && currentPageUrl.isNotEmpty()) {
                cookie = android.webkit.CookieManager.getInstance().getCookie(currentPageUrl)
            }
            if (!cookie.isNullOrEmpty()) {
                builder.addHeader("Cookie", cookie)
            }
            builder.addHeader("User-Agent", USER_AGENT)
            if (currentPageUrl.isNotEmpty()) builder.addHeader("Referer", currentPageUrl)

            val request = builder.build()
            val response = okHttpClient.newCall(request).execute()
            response.header("Content-Length")?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    // ==========================================
    // M3U8 / MPD Parsing (unchanged)
    // ==========================================

    private fun parseM3u8(url: String): List<MediaQualityOption> {
        val options = mutableListOf<MediaQualityOption>()
        try {
            val builder = Request.Builder().url(url)
            var cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (cookie.isNullOrEmpty() && currentPageUrl.isNotEmpty()) {
                cookie = android.webkit.CookieManager.getInstance().getCookie(currentPageUrl)
            }
            if (!cookie.isNullOrEmpty()) {
                builder.addHeader("Cookie", cookie)
            }
            builder.addHeader("User-Agent", USER_AGENT)
            if (currentPageUrl.isNotEmpty()) builder.addHeader("Referer", currentPageUrl)

            val request = builder.build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return options

            val lines = body.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)
                        ?.groupValues?.get(1)?.toLongOrNull()
                    val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)
                        ?.groupValues?.get(1)

                    val quality = when {
                        resolution != null -> {
                            val height = resolution.split("x").lastOrNull()?.toIntOrNull()
                            "${height}p"
                        }
                        bandwidth != null -> when {
                            bandwidth > 4_000_000 -> "1080p"
                            bandwidth > 2_000_000 -> "720p"
                            bandwidth > 1_000_000 -> "480p"
                            bandwidth > 500_000 -> "360p"
                            else -> "240p"
                        }
                        else -> "Unknown"
                    }

                    if (i + 1 < lines.size) {
                        val streamUrl = lines[i + 1].trim()
                        if (streamUrl.isNotEmpty() && !streamUrl.startsWith("#")) {
                            val fullUrl = if (streamUrl.startsWith("http")) {
                                streamUrl
                            } else {
                                val baseUrl = url.substringBeforeLast("/")
                                "$baseUrl/$streamUrl"
                            }
                            options.add(
                                MediaQualityOption(
                                    url = fullUrl,
                                    quality = quality,
                                    resolution = resolution,
                                    bandwidth = bandwidth,
                                    mimeType = "application/x-mpegurl"
                                )
                            )
                        }
                    }
                }
                i++
            }

            // If no variants found, it's a single stream
            if (options.isEmpty()) {
                options.add(
                    MediaQualityOption(
                        url = url,
                        quality = "Default",
                        mimeType = "application/x-mpegurl"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing M3U8", e)
            options.add(
                MediaQualityOption(
                    url = url,
                    quality = "Default",
                    mimeType = "application/x-mpegurl"
                )
            )
        }

        return options.sortedByDescending { it.bandwidth ?: 0 }
    }

    private fun parseMpd(url: String): List<MediaQualityOption> {
        // Basic MPD parsing - returns the URL as single option for now
        return listOf(
            MediaQualityOption(
                url = url,
                quality = "Default",
                mimeType = "application/dash+xml"
            )
        )
    }

    // ==========================================
    // Utility Methods
    // ==========================================

    private fun guessQuality(url: String): String {
        val lowUrl = url.lowercase()
        // Bug fix #9: Use word-boundary-aware patterns to avoid false positives
        //   e.g. "172080" should NOT match "720", "shared" should NOT match "hd"
        val qualityRegex = Regex("""[\W_](\d{3,4})p[\W_]""")
        val match = qualityRegex.find(lowUrl)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null && num in listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)) {
                return when (num) {
                    2160 -> "2160p (4K)"
                    else -> "${num}p"
                }
            }
        }
        return when {
            Regex("""[\W_]4k[\W_]""").containsMatchIn(lowUrl) -> "2160p (4K)"
            Regex("""[\W_]2160[\W_]""").containsMatchIn(lowUrl) -> "2160p (4K)"
            Regex("""[\W_]1080[\W_]""").containsMatchIn(lowUrl) -> "1080p"
            Regex("""[\W_]720[\W_]""").containsMatchIn(lowUrl) -> "720p"
            Regex("""[\W_]480[\W_]""").containsMatchIn(lowUrl) -> "480p"
            Regex("""[\W_]360[\W_]""").containsMatchIn(lowUrl) -> "360p"
            Regex("""[\W_]240[\W_]""").containsMatchIn(lowUrl) -> "240p"
            else -> "Default"
        }
    }

    private fun guessMimeType(url: String): String {
        val lowUrl = url.lowercase().substringBefore("?")
        return when {
            lowUrl.endsWith(".mp4") -> "video/mp4"
            lowUrl.endsWith(".webm") -> "video/webm"
            lowUrl.endsWith(".mkv") -> "video/x-matroska"
            lowUrl.endsWith(".m3u8") -> "application/x-mpegurl"
            lowUrl.endsWith(".mpd") -> "application/dash+xml"
            lowUrl.endsWith(".mp3") -> "audio/mpeg"
            lowUrl.endsWith(".m4a") -> "audio/mp4"
            lowUrl.endsWith(".ogg") -> "audio/ogg"
            else -> "video/mp4"
        }
    }

    /**
     * JavaScript code to inject into WebView to detect video elements.
     *
     * For Pornhub pages, this also extracts mediaDefinitions from flashvars_XXXX
     * which contain the video URLs and quality information.
     */
    fun getVideoDetectionJs(): String {
        return """
            (function() {
                var results = [];
                try {
                    // === Standard: Find <video> and <source> elements ===
                    var videos = document.querySelectorAll('video, source, iframe[src*="video"], embed[src*="video"]');
                    for (var i = 0; i < videos.length; i++) {
                        var el = videos[i];
                        var src = el.src || el.currentSrc || el.getAttribute('data-src') || '';
                        if (src && src.length > 0 && src.startsWith('http')) {
                            results.push(src);
                        }
                        var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
                        for (var j = 0; j < sources.length; j++) {
                            var sSrc = sources[j].src || sources[j].getAttribute('src') || '';
                            if (sSrc && sSrc.length > 0 && sSrc.startsWith('http')) {
                                results.push(sSrc);
                            }
                        }
                    }
                    
                    // === Pornhub-specific: Extract from flashvars_XXXX ===
                    // PH stores video data in a global var like: var flashvars_12345 = {...}
                    // The mediaDefinitions array inside contains get_media API URLs
                    try {
                        var ownKeys = Object.getOwnPropertyNames(window);
                        for (var k = 0; k < ownKeys.length; k++) {
                            var key = ownKeys[k];
                            if (/^flashvars_\d+$/.test(key)) {
                                try {
                                    var fv = window[key];
                                    if (fv && fv.mediaDefinitions && Array.isArray(fv.mediaDefinitions)) {
                                        for (var m = 0; m < fv.mediaDefinitions.length; m++) {
                                            var def = fv.mediaDefinitions[m];
                                            if (def && def.videoUrl && typeof def.videoUrl === 'string' && def.videoUrl.indexOf('http') === 0) {
                                                results.push(def.videoUrl);
                                            }
                                        }
                                    }
                                } catch(e) {}
                            }
                        }
                    } catch(e) {}
                    
                    // === Fallback: Scan inline scripts for flashvars JSON ===
                    // If the window variable was garbage collected, try parsing script text
                    if (results.length === 0) {
                        try {
                            var scripts = document.querySelectorAll('script:not([src])');
                            for (var s = 0; s < scripts.length; s++) {
                                var text = scripts[s].textContent || '';
                                var flashMatch = text.match(/var\s+flashvars_\d+\s*=\s*(\{[\s\S]*?\});\s*(?:var|\/\/|$)/);
                                if (flashMatch && flashMatch[1]) {
                                    try {
                                        var parsed = JSON.parse(flashMatch[1]);
                                        if (parsed.mediaDefinitions && Array.isArray(parsed.mediaDefinitions)) {
                                            for (var md = 0; md < parsed.mediaDefinitions.length; md++) {
                                                var mdef = parsed.mediaDefinitions[md];
                                                if (mdef && mdef.videoUrl && mdef.videoUrl.indexOf('http') === 0) {
                                                    results.push(mdef.videoUrl);
                                                }
                                            }
                                        }
                                    } catch(pe) {}
                                }
                            }
                        } catch(e) {}
                    }

                    // === Generic: Check quality_/media_/qualityItems_ globals ===
                    try {
                        var ownKeys2 = Object.getOwnPropertyNames(window);
                        for (var ki = 0; ki < ownKeys2.length; ki++) {
                            var key2 = ownKeys2[ki];
                            try {
                                if (typeof key2 === 'string') {
                                    if (key2.indexOf('quality_') === 0 || key2.indexOf('media_') === 0) {
                                        var val2 = window[key2];
                                        if (typeof val2 === 'string' && val2.startsWith('http')) {
                                            results.push(val2);
                                        }
                                    } else if (key2.indexOf('qualityItems_') === 0) {
                                        var val3 = window[key2];
                                        if (typeof val3 === 'string') {
                                            var items = JSON.parse(val3);
                                            for (var idx = 0; idx < items.length; idx++) {
                                                if (items[idx] && items[idx].url && items[idx].url.startsWith('http')) {
                                                    results.push(items[idx].url);
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e) {}
                        }
                    } catch(e) {}
                } catch(e) {}
                
                var unique = [];
                for(var i = 0; i < results.length; i++) {
                    if(unique.indexOf(results[i]) === -1) unique.push(results[i]);
                }
                
                return JSON.stringify(unique);
            })();
        """.trimIndent()
    }
}
