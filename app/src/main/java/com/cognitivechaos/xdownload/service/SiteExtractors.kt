package com.cognitivechaos.xdownload.service

import android.util.Log
import com.cognitivechaos.xdownload.data.model.MediaQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Site-specific video extractors for XNXX and XVideos.
 *
 * Uses the same technique as yt-dlp: proactively fetches the video page HTML via OkHttp
 * and regex-scans for embedded JS function calls that contain direct video URLs.
 * This is triggered the moment the user navigates to a video page, so results are
 * cached before the user taps the download button.
 *
 * XHamster is handled separately in VideoDetector.onResourceRequest via WebView interception.
 * Pornhub is handled separately via get_media API interception — untouched by this class.
 */
class SiteExtractors(
    private val okHttpClient: OkHttpClient,
    private val cookieProvider: (String) -> String?
) {

    companion object {
        private const val TAG = "SiteExtractors"

        /**
         * Desktop Chrome User-Agent — required so sites serve the full HTML page
         * with embedded JS variables rather than a mobile-optimised or bot-detection page.
         */
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // ==========================================
    // Shared page-fetch helper
    // ==========================================

    /**
     * Fetches the HTML of a video page using OkHttp with the WebView's current cookies.
     * Returns null on any error (non-2xx, network exception, etc.) — callers treat null
     * as "extraction not possible" and return an empty list silently.
     */
    private suspend fun fetchPage(pageUrl: String): String? {
        return try {
            val cookie = cookieProvider(pageUrl)
            Log.d(TAG, "fetchPage: fetching ${pageUrl.take(100)}, hasCookie=${!cookie.isNullOrEmpty()}")
            val request = Request.Builder()
                .url(pageUrl)
                .apply {
                    if (!cookie.isNullOrEmpty()) addHeader("Cookie", cookie)
                }
                .addHeader("User-Agent", DESKTOP_USER_AGENT)
                .addHeader("Referer", pageUrl)
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = okHttpClient.newCall(request).execute()
            Log.d(TAG, "fetchPage: HTTP ${response.code} for ${pageUrl.take(80)}, contentType=${response.header("Content-Type")}")
            if (!response.isSuccessful) {
                Log.e(TAG, "Page fetch failed: HTTP ${response.code} for ${pageUrl.take(100)}")
                response.close()
                return null
            }
            val body = response.body?.string()
            Log.d(TAG, "fetchPage: body length=${body?.length ?: 0} for ${pageUrl.take(80)}")
            if (body.isNullOrBlank()) {
                Log.e(TAG, "Page fetch returned empty body for ${pageUrl.take(100)}")
                return null
            }
            // Log first 500 chars to see what we actually got
            Log.d(TAG, "fetchPage: body preview: ${body.take(500)}")
            body
        } catch (e: Exception) {
            Log.e(TAG, "Page fetch exception for ${pageUrl.take(100)}: ${e.message}")
            null
        }
    }

    // ==========================================
    // XNXX extractor (port of yt-dlp xnxx.py)
    // ==========================================

    /**
     * Extracts direct video URLs from an XNXX video page.
     *
     * yt-dlp technique: the page HTML contains JS calls like:
     *   setVideoUrlHigh('https://cdn.xnxx.com/video.mp4')
     *   setVideoUrlLow('https://cdn.xnxx.com/video_low.mp4')
     *   setVideoHLS('https://cdn.xnxx.com/hls.m3u8')
     *
     * We regex-scan for these and extract the URLs directly — no API call needed.
     */
    suspend fun extractXnxx(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            // Matches setVideoUrlHigh(...), setVideoUrlLow(...), setVideoHLS(...)
            val regex = Regex(
                """setVideo(?:Url(?:Low|High)|HLS)\s*\(\s*["'](?<url>(?:https?:)?//.+?)["']""",
                RegexOption.IGNORE_CASE
            )

            for (match in regex.findAll(html)) {
                val url = match.groups["url"]?.value?.trim() ?: continue
                if (!seen.add(url)) continue  // deduplicate

                // Determine quality from the function name
                val funcName = match.value
                    .substringAfter("setVideo")
                    .substringBefore("(")
                    .trim()
                    .lowercase()

                val quality = when {
                    funcName.startsWith("urlhigh") -> "HD"
                    funcName.startsWith("urllow")  -> "SD"
                    funcName == "hls"              -> "HLS"
                    else                           -> "Default"
                }
                val mimeType = if (funcName == "hls") "application/x-mpegurl" else "video/mp4"

                options.add(
                    MediaQualityOption(
                        url = url,
                        quality = quality,
                        mimeType = mimeType
                    )
                )
            }

            Log.d(TAG, "XNXX extracted ${options.size} options from ${pageUrl.take(80)}")
            options
        }

    // ==========================================
    // XHamster cipher (port of yt-dlp xhamster.py _ByteGenerator + _decipher_hex_string)
    // ==========================================

    /**
     * XHamster obfuscates video URLs as hex-encoded ciphertext.
     * Format: byte[0]=algoId, bytes[1..4]=seed (little-endian int32), bytes[5..]=ciphertext
     * Each ciphertext byte is XORed with the next byte from the PRNG stream.
     * 7 PRNG algorithms are supported (identified by algoId byte).
     */
    private fun int32(v: Long): Int = v.toInt()

    private fun xhNextByte(algoId: Int, state: IntArray): Int {
        var s = state[0].toLong() and 0xFFFFFFFFL
        val next: Long = when (algoId) {
            1 -> { // LCG
                val n = int32(s * 1664525L + 1013904223L).toLong() and 0xFFFFFFFFL
                state[0] = n.toInt(); n
            }
            2 -> { // xorshift32
                var x = s.toInt()
                x = x xor (x shl 13)
                x = x xor (x ushr 17)
                x = x xor (x shl 5)
                state[0] = x; x.toLong() and 0xFFFFFFFFL
            }
            3 -> { // Weyl + MurmurHash3 fmix32
                var x = int32(s + 0x9e3779b9L).toLong() and 0xFFFFFFFFL
                state[0] = x.toInt()
                x = x xor (x ushr 16)
                x = int32(x * 0x85ebca77L).toLong() and 0xFFFFFFFFL
                x = x xor (x ushr 13)
                x = int32(x * 0xc2b2ae3dL).toLong() and 0xFFFFFFFFL
                x xor (x ushr 16)
            }
            4 -> { // ROL-based
                var x = int32(s + 0x6d2b79f5L).toLong() and 0xFFFFFFFFL
                state[0] = x.toInt()
                x = ((x shl 7) or (x ushr 25)) and 0xFFFFFFFFL
                x = int32(x + 0x9e3779b9L).toLong() and 0xFFFFFFFFL
                x = x xor (x ushr 11)
                int32(x * 0x27d4eb2dL).toLong() and 0xFFFFFFFFL
            }
            5 -> { // xorshift variant
                var x = s.toInt()
                x = x xor (x shl 7)
                x = x xor (x ushr 9)
                x = x xor (x shl 8)
                x = int32(x.toLong() + 0xa5a5a5a5L)
                state[0] = x; x.toLong() and 0xFFFFFFFFL
            }
            6 -> { // LCG variant with variable shift
                val n = int32(s * 0x2c9277b5L + 0xac564b05L).toLong() and 0xFFFFFFFFL
                state[0] = n.toInt()
                val s2 = n xor (n ushr 18)
                val shift = ((n ushr 27) and 31L).toInt()
                (s2 ushr shift) and 0xFFFFFFFFL
            }
            7 -> { // Weyl + multiply-xor-shift
                val n = int32(s + 0x9e3779b9L).toLong() and 0xFFFFFFFFL
                state[0] = n.toInt()
                var e = n xor (n shl 5)
                e = int32(e * 0x7feb352dL).toLong() and 0xFFFFFFFFL
                e = e xor (e ushr 15)
                int32(e * 0x846ca68bL).toLong() and 0xFFFFFFFFL
            }
            else -> return -1 // unknown algo
        }
        return (next and 0xFF).toInt()
    }

    private fun decipherXhamsterUrl(hexStr: String): String? {
        return try {
            val bytes = hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (bytes.size < 6) return null
            val algoId = bytes[0].toInt() and 0xFF
            // seed: bytes[1..4] little-endian signed int32
            val seed = (bytes[1].toInt() and 0xFF) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                ((bytes[3].toInt() and 0xFF) shl 16) or
                ((bytes[4].toInt() and 0xFF) shl 24)
            val state = intArray(seed)
            val result = ByteArray(bytes.size - 5)
            for (i in result.indices) {
                val keyByte = xhNextByte(algoId, state)
                if (keyByte < 0) return null
                result[i] = (bytes[i + 5].toInt() xor keyByte).toByte()
            }
            String(result, Charsets.ISO_8859_1)
        } catch (e: Exception) {
            null
        }
    }

    private fun intArray(seed: Int) = intArrayOf(seed)

    private val HEX_RE = Regex("""^[0-9a-fA-F]{12,}$""")
    private val HEX_IN_PATH_RE = Regex("""^/([0-9a-fA-F]{12,})([/,].+)$""")

    private fun decipherXhamsterFormatUrl(rawUrl: String): String? {
        // Case 1: entire string is hex ciphertext
        if (HEX_RE.matches(rawUrl)) {
            return decipherXhamsterUrl(rawUrl)
        }
        // Case 2: already a plain URL
        if (rawUrl.startsWith("http")) return rawUrl
        // Case 3: URL with hex segment in path
        return try {
            val parsed = java.net.URL(rawUrl)
            val m = HEX_IN_PATH_RE.find(parsed.path) ?: return null
            val deciphered = decipherXhamsterUrl(m.groupValues[1]) ?: return null
            val remainder = m.groupValues[2]
            val port = if (parsed.port == -1) "" else ":${parsed.port}"
            "${parsed.protocol}://${parsed.host}$port/$deciphered$remainder"
        } catch (e: Exception) { null }
    }

    // ==========================================
    // XHamster extractor (port of yt-dlp xhamster.py)
    // ==========================================

    /**
     * Extracts direct video URLs from an XHamster video page.
     *
     * yt-dlp technique: the page HTML contains a `window.initials = {...}` JSON blob.
     * Inside it, `videoModel.sources` contains plain (non-obfuscated) MP4 URLs keyed
     * by quality label like "720p", "1080p", "480p", etc.
     *
     * We skip xplayerSettings.sources entirely — those are ciphered and unreliable.
     * videoModel.sources gives us direct downloadable MP4 URLs with no deciphering needed.
     */
    suspend fun extractXhamster(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            val initialsStart = html.indexOf("window.initials")
                .takeIf { it >= 0 } ?: run {
                    Log.e(TAG, "XHamster: window.initials not found")
                    return@withContext emptyList()
                }
            val braceStart = html.indexOf('{', initialsStart).takeIf { it >= 0 } ?: return@withContext emptyList()

            var depth = 0; var braceEnd = -1; var inString = false; var escape = false
            for (i in braceStart until html.length) {
                val c = html[i]
                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> { depth--; if (depth == 0) { braceEnd = i; break } }
                }
            }
            if (braceEnd < 0) return@withContext emptyList()

            try {
                val initials = org.json.JSONObject(html.substring(braceStart, braceEnd + 1))
                val xplayer = initials.optJSONObject("xplayerSettings") ?: run {
                    Log.e(TAG, "XHamster: xplayerSettings not found"); return@withContext emptyList()
                }
                val sources = xplayer.optJSONObject("sources") ?: run {
                    Log.e(TAG, "XHamster: xplayerSettings.sources not found"); return@withContext emptyList()
                }

                // Parse standard sources (h264, av1) — each is a list of format objects
                val standard = sources.optJSONObject("standard")
                if (standard != null) {
                    val codecKeys = standard.keys()
                    while (codecKeys.hasNext()) {
                        val codec = codecKeys.next()
                        val formatsList = standard.optJSONArray(codec) ?: continue
                        for (i in 0 until formatsList.length()) {
                            val fmt = formatsList.optJSONObject(i) ?: continue
                        val quality = fmt.optString("quality").ifEmpty { fmt.optString("label") }
                        if (quality.isEmpty()) continue
                        // Try url first, then fallback — find first valid deciphered MP4 URL
                        val rawUrl = fmt.optString("url").ifEmpty { fmt.optString("fallback") }
                        if (rawUrl.isEmpty()) continue
                        val resolvedUrl = decipherXhamsterFormatUrl(rawUrl) ?: continue
                        if (!resolvedUrl.startsWith("http")) continue
                        if (resolvedUrl.contains(".m3u8")) continue
                        if (!seen.add(resolvedUrl)) continue
                        options.add(MediaQualityOption(url = resolvedUrl, quality = quality, mimeType = "video/mp4"))
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "XHamster: parse error: ${e.message}")
                return@withContext emptyList()
            }

            val sorted = options.sortedByDescending {
                Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0
            }
            Log.d(TAG, "XHamster extracted ${sorted.size} options: ${sorted.map { it.quality }}")
            sorted
        }


    /**
     * Extracts direct video URLs from an XVideos video page.
     *
     * yt-dlp technique: same setVideo*(...) JS pattern as XNXX, plus a fallback
     * flv_url= query parameter that may appear in the page source.
     */
    suspend fun extractXvideos(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            // Primary: setVideo*(...) pattern — matches setVideoUrlHigh, setVideoUrlLow, setVideoHLS
            val regex = Regex(
                """setVideo([^(]+)\(["'](https?://.+?)["']\)""",
                RegexOption.IGNORE_CASE
            )

            for (match in regex.findAll(html)) {
                val kind = match.groupValues[1].trim().lowercase()
                val url  = match.groupValues[2].trim()
                if (!seen.add(url)) continue  // deduplicate

                val quality = when (kind) {
                    "urlhigh" -> "HD"
                    "urllow"  -> "SD"
                    "hls"     -> "HLS"
                    else      -> kind.ifEmpty { "Default" }
                }
                val mimeType = if (kind == "hls") "application/x-mpegurl" else "video/mp4"

                options.add(
                    MediaQualityOption(
                        url = url,
                        quality = quality,
                        mimeType = mimeType
                    )
                )
            }

            // Fallback: flv_url= in page source (legacy FLV format)
            val flvMatch = Regex("""flv_url=([^&"'\s]+)""").find(html)
            if (flvMatch != null) {
                try {
                    val flvUrl = URLDecoder.decode(flvMatch.groupValues[1], "UTF-8")
                    if (flvUrl.startsWith("http") && seen.add(flvUrl)) {
                        options.add(
                            MediaQualityOption(
                                url = flvUrl,
                                quality = "FLV",
                                mimeType = "video/x-flv"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "XVideos flv_url decode failed: ${e.message}")
                }
            }

            Log.d(TAG, "XVideos extracted ${options.size} options from ${pageUrl.take(80)}")
            options
        }

    // ==========================================
    // JSON extraction helpers (brace/bracket counter)
    // Safely extracts a complete JSON object or array from HTML
    // without being fooled by nested structures.
    // ==========================================

    private fun extractJsonObject(html: String, braceStart: Int): String? {
        var depth = 0; var end = -1; var inString = false; var escape = false
        for (i in braceStart until html.length) {
            val c = html[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        return if (end >= 0) html.substring(braceStart, end + 1) else null
    }

    private fun extractJsonArray(html: String, bracketStart: Int): String? {
        var depth = 0; var end = -1; var inString = false; var escape = false
        for (i in bracketStart until html.length) {
            val c = html[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        return if (end >= 0) html.substring(bracketStart, end + 1) else null
    }

    // ==========================================
    // RedTube extractor (port of yt-dlp redtube.py)
    // Technique: regex for `sources: {...}` JSON object embedded in page HTML.
    // Plain URLs keyed by quality number — no cipher, no API call needed.
    // ==========================================

    suspend fun extractRedtube(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            // Primary: sources: {"720": "https://...", "480": "https://..."}
            // Mirror yt-dlp: r'sources\s*:\s*({.+?})' — no prefix requirement.
            // RedTube pages use "sources":{"720":"..."} (quoted key), so [,\s] prefix fails.
            val sourcesMatch = Regex("""sources\s*:\s*\{""").find(html)
            if (sourcesMatch != null) {
                val braceIdx = html.indexOf('{', sourcesMatch.range.first)
                if (braceIdx >= 0) {
                    val sourcesJson = extractJsonObject(html, braceIdx)
                    if (sourcesJson != null) {
                        try {
                            val obj = org.json.JSONObject(sourcesJson)
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val quality = keys.next()
                                val url = obj.optString(quality, "")
                                if (url.isBlank() || !url.startsWith("http")) continue
                                if (!seen.add(url)) continue
                                options.add(MediaQualityOption(url = url, quality = "${quality}p", mimeType = "video/mp4"))
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "RedTube sources parse failed: ${e.message}")
                        }
                    }
                }
            }

            // Fallback: mediaDefinition array — find the array and parse it
            if (options.isEmpty()) {
                val mdIdx = html.indexOf("mediaDefinition")
                if (mdIdx >= 0) {
                    val arrIdx = html.indexOf('[', mdIdx)
                    if (arrIdx >= 0) {
                        val arrJson = extractJsonArray(html, arrIdx)
                        if (arrJson != null) {
                            try {
                                val arr = org.json.JSONArray(arrJson)
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    val videoUrl = obj.optString("videoUrl", "")
                                    if (videoUrl.isBlank() || !videoUrl.startsWith("http")) continue
                                    val format = obj.optString("format", "").lowercase()
                                    val quality = obj.optString("quality", "")

                                    // yt-dlp redtube.py: when format='mp4' and quality is empty,
                                    // videoUrl is an API endpoint — fetch it to get direct quality URLs.
                                    if (format == "mp4" && quality.isEmpty()) {
                                        try {
                                            val apiReq = Request.Builder()
                                                .url(videoUrl)
                                                .addHeader("User-Agent", DESKTOP_USER_AGENT)
                                                .addHeader("Referer", pageUrl)
                                                .build()
                                            val apiResp = okHttpClient.newCall(apiReq).execute()
                                            val apiBody = if (apiResp.isSuccessful) apiResp.body?.string() else null
                                            apiResp.close()
                                            if (!apiBody.isNullOrBlank()) {
                                                val apiArr = org.json.JSONArray(apiBody)
                                                for (j in 0 until apiArr.length()) {
                                                    val item = apiArr.optJSONObject(j) ?: continue
                                                    val itemFormat = item.optString("format", "").lowercase()
                                                    if (itemFormat == "hls") continue
                                                    val itemUrl = item.optString("videoUrl", "")
                                                    if (itemUrl.isBlank() || !itemUrl.startsWith("http")) continue
                                                    if (itemUrl.contains(".m3u8")) continue
                                                    if (!seen.add(itemUrl)) continue
                                                    val itemQuality = item.optString("quality", "")
                                                    options.add(MediaQualityOption(
                                                        url = itemUrl,
                                                        quality = if (itemQuality.isNotEmpty()) "${itemQuality}p" else "Default",
                                                        mimeType = "video/mp4"
                                                    ))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.d(TAG, "RedTube mediaDefinition API fetch failed: ${e.message}")
                                        }
                                        continue  // this entry was an API URL, not a direct video URL
                                    }

                                    // Direct entry: format='mp4' with a quality value
                                    if (format == "hls") continue
                                    if (!seen.add(videoUrl)) continue
                                    options.add(MediaQualityOption(
                                        url = videoUrl,
                                        quality = if (quality.isNotEmpty()) "${quality}p" else "Default",
                                        mimeType = "video/mp4"
                                    ))
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "RedTube mediaDefinition parse failed: ${e.message}")
                            }
                        }
                    }
                }
            }

            val sorted = options.sortedByDescending { Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0 }
            Log.d(TAG, "RedTube extracted ${sorted.size} options from ${pageUrl.take(80)}")
            sorted
        }

    // ==========================================
    // SpankBang extractor (port of yt-dlp spankbang.py)
    // Technique: regex for stream_url_* variables embedded in page HTML.
    // e.g. stream_url_720p = "https://..." — plain URLs, no cipher.
    // ==========================================

    suspend fun extractSpankbang(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            // stream_url_480p = "https://...", stream_url_720p = "https://...", etc.
            val regex = Regex("""stream_url_(?<id>[^\s=]+)\s*=\s*["'](?<url>https?://[^"']+)["']""")
            for (match in regex.findAll(html)) {
                val formatId = match.groups["id"]?.value?.trim() ?: continue
                val url = match.groups["url"]?.value?.trim() ?: continue
                if (!seen.add(url)) continue
                // Skip m3u8/mpd — we want direct MP4
                if (url.contains(".m3u8") || url.contains(".mpd")) continue
                // Quality label from format id: "720p" → "720p", "4k" → "4K"
                val quality = when {
                    formatId.contains("4k", ignoreCase = true) -> "4K"
                    formatId.contains("2160") -> "2160p"
                    else -> formatId.replace("_", "").let { if (it.endsWith("p")) it else "${it}p" }
                }
                options.add(MediaQualityOption(url = url, quality = quality, mimeType = "video/mp4"))
            }

            // Fallback: data-streamkey + POST /api/videos/stream (mirrors yt-dlp spankbang.py)
            // Used when stream_url_* variables are absent (newer page layouts).
            if (options.isEmpty()) {
                val streamKey = Regex("""data-streamkey\s*=\s*["'](?<value>[^"']+)["']""")
                    .find(html)?.groups?.get("value")?.value
                if (streamKey != null) {
                    try {
                        val body = okhttp3.FormBody.Builder()
                            .add("id", streamKey)
                            .add("data", "0")
                            .build()
                        val req = Request.Builder()
                            .url("https://spankbang.com/api/videos/stream")
                            .post(body)
                            .addHeader("User-Agent", DESKTOP_USER_AGENT)
                            .addHeader("Referer", pageUrl)
                            .addHeader("X-Requested-With", "XMLHttpRequest")
                            .build()
                        val resp = okHttpClient.newCall(req).execute()
                        val streamJson = if (resp.isSuccessful) resp.body?.string() else null
                        resp.close()
                        if (!streamJson.isNullOrBlank()) {
                            val streamObj = org.json.JSONObject(streamJson)
                            val keys = streamObj.keys()
                            while (keys.hasNext()) {
                                val formatId = keys.next()
                                // yt-dlp: format_url may be a list; take first element
                                val rawVal = streamObj.opt(formatId)
                                val url = when (rawVal) {
                                    is org.json.JSONArray -> rawVal.optString(0, "")
                                    is String -> rawVal
                                    else -> ""
                                }
                                if (url.isBlank() || !url.startsWith("http")) continue
                                if (url.contains(".m3u8") || url.contains(".mpd")) continue
                                if (!seen.add(url)) continue
                                val quality = when {
                                    formatId.contains("4k", ignoreCase = true) -> "4K"
                                    formatId.contains("2160") -> "2160p"
                                    else -> formatId.replace("_", "").let {
                                        if (it.endsWith("p")) it else "${it}p"
                                    }
                                }
                                options.add(MediaQualityOption(url = url, quality = quality, mimeType = "video/mp4"))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SpankBang API fallback failed: ${e.message}")
                    }
                }
            }

            val sorted = options.sortedByDescending { Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0 }
            Log.d(TAG, "SpankBang extracted ${sorted.size} options from ${pageUrl.take(80)}")
            sorted
        }

    // ==========================================
    // YouPorn extractor (port of yt-dlp youporn.py)
    // Technique: extract playervars.mediaDefinitions from page HTML,
    // then follow the mp4 API URL to get direct download URLs.
    // ==========================================

    suspend fun extractYouporn(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            // YouPorn requires age_verified cookie
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            // Extract playervars JSON from page HTML using brace-counter
            val pvIdx = html.indexOf("playervars")
            if (pvIdx < 0) {
                Log.e(TAG, "YouPorn: playervars not found")
                return@withContext emptyList()
            }
            val pvBraceIdx = html.indexOf('{', pvIdx)
            if (pvBraceIdx < 0) return@withContext emptyList()
            val playerVarsJson = extractJsonObject(html, pvBraceIdx) ?: run {
                Log.e(TAG, "YouPorn: could not extract playervars JSON")
                return@withContext emptyList()
            }

            try {
                val playerVars = org.json.JSONObject(playerVarsJson)
                val definitions = playerVars.optJSONArray("mediaDefinitions") ?: run {
                    Log.e(TAG, "YouPorn: mediaDefinitions not found")
                    return@withContext emptyList()
                }

                // Find the mp4 API URL (format="mp4" with no quality = the index URL)
                var mp4ApiUrl: String? = null
                for (i in 0 until definitions.length()) {
                    val def = definitions.optJSONObject(i) ?: continue
                    val format = def.optString("format", "").lowercase()
                    val quality = def.optString("quality", "")
                    val videoUrl = def.optString("videoUrl", "")
                    if (format == "mp4" && quality.isEmpty() && videoUrl.startsWith("http")) {
                        mp4ApiUrl = videoUrl
                        break
                    }
                }

                if (mp4ApiUrl == null) {
                    Log.e(TAG, "YouPorn: mp4 API URL not found in mediaDefinitions")
                    return@withContext emptyList()
                }

                // Fetch the mp4 API URL — returns JSON array of direct MP4 URLs
                val apiResponse = try {
                    val req = Request.Builder().url(mp4ApiUrl)
                        .addHeader("User-Agent", DESKTOP_USER_AGENT)
                        .addHeader("Referer", pageUrl)
                        .build()
                    val resp = okHttpClient.newCall(req).execute()
                    if (!resp.isSuccessful) { resp.close(); null }
                    else resp.body?.string()
                } catch (e: Exception) { null }

                if (apiResponse.isNullOrBlank()) {
                    Log.e(TAG, "YouPorn: mp4 API returned empty response")
                    return@withContext emptyList()
                }

                val apiArray = try { org.json.JSONArray(apiResponse) } catch (e: Exception) {
                    Log.e(TAG, "YouPorn: failed to parse mp4 API JSON: ${e.message}")
                    return@withContext emptyList()
                }

                for (i in 0 until apiArray.length()) {
                    val item = apiArray.optJSONObject(i) ?: continue
                    val format = item.optString("format", "").lowercase()
                    if (format != "mp4") continue
                    val videoUrl = item.optString("videoUrl", "")
                    if (videoUrl.isBlank() || !videoUrl.startsWith("http")) continue
                    if (!seen.add(videoUrl)) continue
                    val quality = item.optString("quality", "")
                    options.add(MediaQualityOption(
                        url = videoUrl,
                        quality = if (quality.isNotEmpty()) "${quality}p" else "Default",
                        mimeType = "video/mp4"
                    ))
                }

            } catch (e: Exception) {
                Log.e(TAG, "YouPorn: parse error: ${e.message}")
                return@withContext emptyList()
            }

            val sorted = options.sortedByDescending { Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0 }
            Log.d(TAG, "YouPorn extracted ${sorted.size} options from ${pageUrl.take(80)}")
            sorted
        }

    // ==========================================
    // Eporner extractor (port of yt-dlp eporner.py)
    // Technique: extract hash from page HTML, compute derived hash,
    // then hit /xhr/video/{id} API for direct MP4 URLs.
    // ==========================================

    suspend fun extractEporner(pageUrl: String): List<MediaQualityOption> =
        withContext(Dispatchers.IO) {
            val html = fetchPage(pageUrl) ?: return@withContext emptyList()
            val options = mutableListOf<MediaQualityOption>()
            val seen = mutableSetOf<String>()

            // Extract video ID — try from URL first, then from page HTML
            // yt-dlp re-extracts from the final redirected URL; we also check the HTML
            val videoId = Regex("""eporner\.com/(?:hd-porn|embed|video-)([\w]+)""").find(pageUrl)?.groupValues?.get(1)
                ?: Regex("""['"]/(?:hd-porn|embed|video-)([A-Za-z0-9]+)/""").find(html)?.groupValues?.get(1)
                ?: run {
                    Log.e(TAG, "Eporner: could not extract video ID")
                    return@withContext emptyList()
                }

            // Extract hash from page HTML
            val vidHash = Regex("""hash\s*[:=]\s*["']([\da-f]{32})["']""").find(html)?.groupValues?.get(1)
                ?: run {
                    Log.e(TAG, "Eporner: hash not found in page HTML")
                    return@withContext emptyList()
                }

            // Compute derived hash: base-36 encode each 8-char hex chunk
            fun encodeBase36(n: Long): String {
                if (n == 0L) return "0"
                val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
                var num = n; val sb = StringBuilder()
                while (num > 0) { sb.insert(0, chars[(num % 36).toInt()]); num /= 36 }
                return sb.toString()
            }
            val derivedHash = (0 until 32 step 8).joinToString("") { lb ->
                encodeBase36(vidHash.substring(lb, lb + 8).toLong(16))
            }

            // Hit the API
            val apiUrl = "https://www.eporner.com/xhr/video/$videoId?hash=$derivedHash&device=generic&domain=www.eporner.com&fallback=false"
            val apiResponse = try {
                val req = Request.Builder().url(apiUrl)
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Referer", pageUrl)
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                if (!resp.isSuccessful) { resp.close(); null }
                else resp.body?.string()
            } catch (e: Exception) { null }

            if (apiResponse.isNullOrBlank()) {
                Log.e(TAG, "Eporner: API returned empty response")
                return@withContext emptyList()
            }

            try {
                val apiJson = org.json.JSONObject(apiResponse)
                if (apiJson.has("available") && !apiJson.optBoolean("available", true)) {
                    Log.e(TAG, "Eporner: video not available: ${apiJson.optString("message")}")
                    return@withContext emptyList()
                }

                val sources = apiJson.optJSONObject("sources") ?: run {
                    Log.e(TAG, "Eporner: sources not found in API response")
                    return@withContext emptyList()
                }

                val sourceKeys = sources.keys()
                while (sourceKeys.hasNext()) {
                    val kind = sourceKeys.next()
                    if (kind == "hls") continue  // skip HLS, want direct MP4
                    val formatsDict = sources.optJSONObject(kind) ?: continue
                    val fmtKeys = formatsDict.keys()
                    while (fmtKeys.hasNext()) {
                        val formatId = fmtKeys.next()
                        val fmtObj = formatsDict.optJSONObject(formatId) ?: continue
                        val src = fmtObj.optString("src", "")
                        if (src.isBlank() || !src.startsWith("http")) continue
                        if (!seen.add(src)) continue
                        // formatId looks like "720p", "1080p", "480p30fps"
                        val quality = Regex("""(\d{3,4})[pP]""").find(formatId)?.groupValues?.get(1)?.let { "${it}p" } ?: formatId
                        options.add(MediaQualityOption(url = src, quality = quality, mimeType = "video/mp4"))
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Eporner: API parse error: ${e.message}")
                return@withContext emptyList()
            }

            val sorted = options.sortedByDescending { Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0 }
            Log.d(TAG, "Eporner extracted ${sorted.size} options from ${pageUrl.take(80)}")
            sorted
        }
}

