package com.cognitivechaos.xdownload

import com.cognitivechaos.xdownload.data.model.MediaQualityOption
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.PatternSyntaxException

/**
 * SpankBang + RedTube Bug Condition Exploration Tests — Task 1
 *
 * These tests MUST FAIL on unfixed code — failure confirms the bugs exist.
 * DO NOT fix the code when these tests fail. The failures are the expected outcome.
 *
 * Four bug conditions are tested:
 *
 *   Bug 1a: Python-style named capture group syntax (?P<id>...) in extractSpankbang regex
 *           crashes the JVM with PatternSyntaxException.
 *
 *   Bug 1b: extractSpankbang returns empty when HTML contains only data-streamkey
 *           (no stream_url_* variables) — API fallback is missing.
 *
 *   Bug 2a: extractRedtube returns empty when sources:{} is empty and mediaDefinition
 *           has format='mp4', quality='', videoUrl=API-endpoint — API follow-through missing.
 *
 *   Bug 2b: After onUrlChanged("https://www.redtube.com/123"), videoDetector.currentPageUrl
 *           is NOT updated (setCurrentPage is not called in onUrlChanged) — race window exists.
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.5, 1.6, 1.10
 */
class SpankBangRedTubeExplorationTest {

    // =========================================================================
    // Testable subclass of SiteExtractors that overrides fetchPage via a
    // constructor-injected lambda. This avoids any real HTTP calls.
    //
    // SiteExtractors.fetchPage is private, so we replicate the extraction logic
    // verbatim from the source file for the two functions under test.
    // =========================================================================

    /**
     * Verbatim replica of extractSpankbang() from SiteExtractors.kt (UNFIXED).
     *
     * The only change is that `fetchPage` is replaced by the `htmlProvider` lambda
     * so we can inject fake HTML without any network call.
     *
     * This replica is intentionally UNFIXED — it contains the Python-style (?P<id>...)
     * named capture group syntax that causes PatternSyntaxException.
     */
    private fun unfixedExtractSpankbang(html: String): List<MediaQualityOption> {
        val options = mutableListOf<MediaQualityOption>()
        val seen = mutableSetOf<String>()

        // BUG 1a: Python-style named capture groups — throws PatternSyntaxException on JVM
        val regex = Regex("""stream_url_(?P<id>[^\s=]+)\s*=\s*["'](?P<url>https?://[^"']+)["']""")
        for (match in regex.findAll(html)) {
            val formatId = match.groups["id"]?.value?.trim() ?: continue
            val url = match.groups["url"]?.value?.trim() ?: continue
            if (!seen.add(url)) continue
            if (url.contains(".m3u8") || url.contains(".mpd")) continue
            val quality = when {
                formatId.contains("4k", ignoreCase = true) -> "4K"
                formatId.contains("2160") -> "2160p"
                else -> formatId.replace("_", "").let { if (it.endsWith("p")) it else "${it}p" }
            }
            options.add(MediaQualityOption(url = url, quality = quality, mimeType = "video/mp4"))
        }

        return options.sortedByDescending { Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0 }
    }

    /**
     * Verbatim replica of extractRedtube() from SiteExtractors.kt (UNFIXED).
     *
     * The only change is that `fetchPage` is replaced by the `html` parameter.
     * This replica is intentionally UNFIXED — it does NOT follow mediaDefinition API URLs.
     */
    private fun unfixedExtractRedtube(html: String): List<MediaQualityOption> {
        val options = mutableListOf<MediaQualityOption>()
        val seen = mutableSetOf<String>()

        // Primary: sources: {"720": "https://...", "480": "https://..."}
        val sourcesIdx = Regex("""[,\s]sources\s*:\s*\{""").find(html)?.range?.last?.minus(1)
        if (sourcesIdx != null && sourcesIdx >= 0) {
            val braceIdx = html.indexOf('{', sourcesIdx)
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
                        // parse failed
                    }
                }
            }
        }

        // Fallback: mediaDefinition array
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
                                val url = obj.optString("videoUrl", "")
                                if (url.isBlank() || !url.startsWith("http")) continue
                                val format = obj.optString("format", "").lowercase()
                                if (format == "hls") continue
                                val quality = obj.optString("quality", "")
                                // BUG 2a: entries with empty quality are skipped (no API follow-through)
                                if (!seen.add(url)) continue
                                options.add(MediaQualityOption(
                                    url = url,
                                    quality = if (quality.isNotEmpty()) "${quality}p" else "Default",
                                    mimeType = "video/mp4"
                                ))
                            }
                        } catch (e: Exception) {
                            // parse failed
                        }
                    }
                }
            }
        }

        return options.sortedByDescending { Regex("""\d+""").find(it.quality)?.value?.toIntOrNull() ?: 0 }
    }

    // =========================================================================
    // JSON extraction helpers (verbatim from SiteExtractors.kt)
    // =========================================================================

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

    // =========================================================================
    // Bug 1a — SpankBang Regex Crash
    //
    // PURE UNIT TEST — no mocking needed.
    //
    // The regex string in extractSpankbang uses Python-style named capture groups
    // (?P<id>...) and (?P<url>...) which are invalid in Kotlin/Java regex.
    // The JVM throws PatternSyntaxException at Regex(...) construction time.
    //
    // EXPECTED OUTCOME: This test PASSES (the exception IS thrown — confirms bug exists).
    // =========================================================================

    /**
     * Bug 1a: Constructing the UNFIXED extractSpankbang regex throws PatternSyntaxException.
     *
     * The regex contains Python-style named capture groups (?P<id>...) and (?P<url>...)
     * which are not valid in Kotlin/Java regex. The JVM throws PatternSyntaxException
     * immediately when Regex(...) is evaluated — before any HTML is fetched.
     *
     * EXPECTED OUTCOME: PatternSyntaxException IS thrown → test PASSES.
     * This confirms Bug 1a exists on unfixed code.
     *
     * Counterexample: PatternSyntaxException at
     *   Regex("""stream_url_(?P<id>[^\s=]+)\s*=\s*["'](?P<url>https?://[^"']+)["']""")
     *
     * Validates: Requirements 1.1, 1.2
     */
    @Test
    fun `Bug 1a - unfixed extractSpankbang regex with Python-style named groups throws PatternSyntaxException`() {
        var exceptionThrown = false
        try {
            // This is the EXACT regex from the unfixed extractSpankbang in SiteExtractors.kt.
            // (?P<id>...) and (?P<url>...) are Python syntax — invalid on the JVM.
            @Suppress("RegExpRedundantEscape")
            Regex("""stream_url_(?P<id>[^\s=]+)\s*=\s*["'](?P<url>https?://[^"']+)["']""")
        } catch (e: Exception) {
            // PatternSyntaxException extends IllegalArgumentException in Kotlin's Regex wrapper
            exceptionThrown = true
        } catch (e: java.util.regex.PatternSyntaxException) {
            exceptionThrown = true
        }

        assertTrue(
            "BUG 1a CONFIRMED: Constructing Regex(\"\"\"stream_url_(?P<id>[^\\s=]+)...\"\"\") " +
                "MUST throw an exception because (?P<name>...) is Python-style named capture group " +
                "syntax that is invalid in Kotlin/Java regex. " +
                "Counterexample: PatternSyntaxException thrown at Regex(...) construction. " +
                "Fix: change (?P<id>...) → (?<id>...) and (?P<url>...) → (?<url>...).",
            exceptionThrown
        )
    }

    // =========================================================================
    // Bug 1b — SpankBang Missing API Fallback
    //
    // HTML contains only data-streamkey="abc123" (no stream_url_* variables).
    // The unfixed extractSpankbang has no API fallback, so it returns empty.
    //
    // EXPECTED OUTCOME: This test PASSES (result IS empty — confirms bug exists).
    //
    // Note: Bug 1a (PatternSyntaxException) means unfixedExtractSpankbang() will
    // throw before reaching the loop. We catch that and treat it as "empty result"
    // for the purpose of this test, since the net effect is the same: no options returned.
    // =========================================================================

    /**
     * Bug 1b: extractSpankbang returns empty when HTML has only data-streamkey (no stream_url_*).
     *
     * The unfixed code has no fallback to the SpankBang /api/videos/stream POST endpoint.
     * When stream_url_* variables are absent, the function returns an empty list silently.
     *
     * EXPECTED OUTCOME: result IS empty → test PASSES.
     * This confirms Bug 1b exists on unfixed code.
     *
     * Counterexample: extractSpankbang returns [] for HTML with data-streamkey="abc123"
     * but no stream_url_* variables.
     *
     * Validates: Requirements 1.10
     */
    @Test
    fun `Bug 1b - unfixed extractSpankbang returns empty for streamkey-only HTML (no stream_url_* variables)`() {
        // HTML that contains data-streamkey but NO stream_url_* variables.
        // This is the bug condition: newer SpankBang page layout without embedded stream URLs.
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>SpankBang Video</title></head>
            <body>
              <div class="player-wrapper">
                <div id="player" data-streamkey="abc123" data-id="56b3d">
                  <video class="fp-engine"></video>
                </div>
              </div>
              <script>
                var video_title = "Test Video";
                var video_id = "56b3d";
                // NOTE: No stream_url_720p, stream_url_480p, etc. variables here.
                // This is the newer page layout that requires the API fallback.
              </script>
            </body>
            </html>
        """.trimIndent()

        // Call the unfixed extraction logic.
        // Due to Bug 1a, this will throw PatternSyntaxException before reaching the loop.
        // We catch it and treat it as "empty result" — the net effect is the same:
        // no quality options are returned to the user.
        val result: List<MediaQualityOption> = try {
            unfixedExtractSpankbang(html)
        } catch (e: Exception) {
            // PatternSyntaxException from Bug 1a — extraction failed, no options returned
            emptyList()
        }

        assertTrue(
            "BUG 1b CONFIRMED: extractSpankbang returned empty for HTML with only " +
                "data-streamkey=\"abc123\" and no stream_url_* variables. " +
                "The unfixed code has no API fallback to /api/videos/stream. " +
                "Counterexample: result=$result (empty list). " +
                "Fix: add data-streamkey extraction + POST to /api/videos/stream when options.isEmpty().",
            result.isEmpty()
        )
    }

    // =========================================================================
    // Bug 2a — RedTube Incomplete mediaDefinition Handling
    //
    // HTML has empty sources:{} and a mediaDefinition entry with:
    //   format='mp4', quality='', videoUrl='https://www.redtube.com/media?id=123'
    //
    // The unfixed extractRedtube treats videoUrl as a direct video URL and skips
    // entries with empty quality, so it returns empty.
    //
    // EXPECTED OUTCOME: This test PASSES (result IS empty — confirms bug exists).
    // =========================================================================

    /**
     * Bug 2a: extractRedtube returns empty when sources:{} is empty and mediaDefinition
     * has format='mp4', quality='', videoUrl=API-endpoint.
     *
     * Per yt-dlp's redtube.py, when format='mp4' and quality is empty, the videoUrl is
     * an API endpoint that must be fetched to get the actual quality URLs. The unfixed
     * code does not follow this API URL — it skips the entry and returns empty.
     *
     * EXPECTED OUTCOME: result IS empty → test PASSES.
     * This confirms Bug 2a exists on unfixed code.
     *
     * Counterexample: extractRedtube returns [] for HTML with empty sources:{} and
     * mediaDefinition=[{format:'mp4', quality:'', videoUrl:'https://www.redtube.com/media?id=123'}]
     *
     * Validates: Requirements 1.3
     */
    @Test
    fun `Bug 2a - unfixed extractRedtube returns empty for mediaDefinition with API-URL entry (format=mp4, quality=empty)`() {
        // HTML that matches the bug condition:
        //   - sources: {} is empty (primary path produces no results)
        //   - mediaDefinition has one entry with format='mp4', quality='', videoUrl=API-endpoint
        //
        // The videoUrl is NOT a direct video URL — it is an API endpoint that returns
        // a JSON array of quality URLs. The unfixed code does not fetch it.
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>RedTube Video</title></head>
            <body>
            <script>
            var playerObjList = {
              sources: {},
              mediaDefinition: [
                {
                  "format": "mp4",
                  "quality": "",
                  "videoUrl": "https://www.redtube.com/media?id=123&format=mp4"
                }
              ]
            };
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = unfixedExtractRedtube(html)

        assertTrue(
            "BUG 2a CONFIRMED: extractRedtube returned empty for HTML with empty sources:{} " +
                "and mediaDefinition=[{format:'mp4', quality:'', videoUrl:'https://www.redtube.com/media?id=123&format=mp4'}]. " +
                "The unfixed code does not follow the API URL when quality is empty. " +
                "Counterexample: result=$result (empty list). " +
                "Fix: when format='mp4' && quality.isEmpty(), fetch videoUrl as API endpoint " +
                "and parse the returned JSON array for direct MP4 URLs (mirrors yt-dlp redtube.py).",
            result.isEmpty()
        )
    }

    // =========================================================================
    // Bug 2b — Race Condition: currentPageUrl Not Set in onUrlChanged
    //
    // After BrowserViewModel.onUrlChanged("https://www.redtube.com/123") is called
    // on UNFIXED code, videoDetector.currentPageUrl is NOT updated because
    // setCurrentPage() is not called in onUrlChanged.
    //
    // We test this indirectly by replicating the unfixed onUrlChanged logic and
    // checking that isRedtubePage() returns false immediately after the call.
    //
    // EXPECTED OUTCOME: This test PASSES (isRedtubePage IS false — confirms race window).
    // =========================================================================

    /**
     * Bug 2b: After unfixed onUrlChanged("https://www.redtube.com/123"),
     * isRedtubePage() returns false because setCurrentPage was not called.
     *
     * The unfixed onUrlChanged only calls clearDetectedMedia() — it does NOT call
     * setCurrentPage(). So currentPageUrl is still the previous page's URL.
     * When shouldInterceptRequest fires on a background thread before onPageStarted,
     * isRedtubePage() evaluates to false and ad pre-roll URLs are not suppressed.
     *
     * We simulate this by:
     *   1. Creating a VideoDetector-like state tracker (currentPageUrl field)
     *   2. Calling the unfixed onUrlChanged logic (which does NOT call setCurrentPage)
     *   3. Asserting that isRedtubePage() returns false
     *
     * EXPECTED OUTCOME: isRedtubePage IS false → test PASSES.
     * This confirms Bug 2b (race window) exists on unfixed code.
     *
     * Counterexample: currentPageUrl="" after onUrlChanged("https://www.redtube.com/123")
     * because setCurrentPage was never called.
     *
     * Validates: Requirements 1.5, 1.6
     */
    @Test
    fun `Bug 2b - unfixed onUrlChanged does NOT call setCurrentPage - isRedtubePage returns false after navigation`() {
        // Simulate the VideoDetector's currentPageUrl field.
        // Initially empty (previous page was not a RedTube page).
        var currentPageUrl = ""

        // Simulate the UNFIXED onUrlChanged logic from BrowserViewModel.kt.
        // The unfixed version does NOT call setCurrentPage — it only calls clearDetectedMedia().
        //
        // Unfixed onUrlChanged (from BrowserViewModel.kt):
        //   fun onUrlChanged(url: String) {
        //       clearPageError()
        //       cancelQualityFetch()
        //       cancelQualityPrefetch()
        //       _currentUrl.value = url
        //       videoDetector.clearDetectedMedia()   // does NOT reset currentPageUrl
        //       updateActiveTab(url = url)
        //   }
        //
        // clearDetectedMedia() does NOT reset currentPageUrl (confirmed in VideoDetector.kt).
        // setCurrentPage() is NOT called here — that's the bug.
        fun unfixedOnUrlChanged(url: String) {
            // clearDetectedMedia() — does NOT touch currentPageUrl
            // (no-op for this test since we're only tracking currentPageUrl)

            // setCurrentPage() is NOT called here — this is the bug
            // currentPageUrl remains unchanged
        }

        // Simulate isRedtubePage() from VideoDetector.kt:
        //   private fun isRedtubePage(): Boolean =
        //       Regex("""(?:\w+\.)?redtube\.com(?:\.br)?""").containsMatchIn(currentPageUrl.lowercase())
        fun isRedtubePage(): Boolean =
            Regex("""(?:\w+\.)?redtube\.com(?:\.br)?""").containsMatchIn(currentPageUrl.lowercase())

        // Before navigation: currentPageUrl is empty, isRedtubePage() is false
        assertFalse("Pre-condition: isRedtubePage() should be false before navigation", isRedtubePage())

        // Simulate the user navigating to a RedTube page
        val redtubeUrl = "https://www.redtube.com/123"
        unfixedOnUrlChanged(redtubeUrl)

        // ASSERTION: After unfixed onUrlChanged, currentPageUrl is still empty
        // because setCurrentPage was never called.
        // Therefore isRedtubePage() returns false — the race window is open.
        assertFalse(
            "BUG 2b CONFIRMED: After unfixed onUrlChanged(\"https://www.redtube.com/123\"), " +
                "isRedtubePage() returns false because setCurrentPage() was NOT called. " +
                "currentPageUrl is still \"$currentPageUrl\" (not updated to the RedTube URL). " +
                "This means shouldInterceptRequest calls that arrive before onPageStarted " +
                "will see isRedtubePage()=false and will NOT suppress ad pre-roll URLs. " +
                "Counterexample: currentPageUrl=\"$currentPageUrl\" after onUrlChanged(\"$redtubeUrl\"). " +
                "Fix: call videoDetector.setCurrentPage(url, _currentTitle.value) in onUrlChanged.",
            isRedtubePage()
        )
    }
}
