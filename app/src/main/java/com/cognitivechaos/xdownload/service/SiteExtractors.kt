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
}
