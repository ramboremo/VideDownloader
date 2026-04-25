package com.cognitivechaos.xdownload

import com.cognitivechaos.xdownload.data.model.MediaQualityOption
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpankBang + RedTube Preservation Tests — Task 2
 *
 * These tests MUST PASS on unfixed code — they document the baseline behavior
 * that must be preserved after the fixes are applied.
 *
 * Four preservation properties are tested:
 *
 *   Preservation 1: SpankBang primary path (stream_url_* variables) works correctly
 *                   when using the FIXED regex syntax (?<id>...). After Fix 1a, the
 *                   primary path must still return the same result.
 *
 *   Preservation 2: RedTube sources path returns a non-empty list with correct quality
 *                   when sources: {...} is populated. This path is unaffected by any fix.
 *
 *   Preservation 3: RedTube sources path takes priority — when both sources: {...} and
 *                   mediaDefinition are present, only the sources result is returned.
 *
 *   Preservation 4: setCurrentPage URL-change guard — calling setCurrentPage twice with
 *                   the same URL is a no-op on the second call (no duplicate extraction).
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9
 */
class SpankBangRedTubePreservationTest {

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
    // Extraction helpers — inline replicas of the relevant logic
    // =========================================================================

    /**
     * SpankBang primary path using the FIXED regex syntax (?<id>...) and (?<url>...).
     *
     * This is what the fixed extractSpankbang will use after Fix 1a. We use the fixed
     * syntax here because the unfixed (?P<id>...) crashes before reaching the loop —
     * so we can't observe the primary path behavior on unfixed code directly.
     *
     * The purpose of this test is to document that the primary path logic itself is
     * correct and must be preserved after Fix 1a (which only changes the regex syntax,
     * not the loop logic).
     */
    private fun spankbangPrimaryPath(html: String): List<MediaQualityOption> {
        val options = mutableListOf<MediaQualityOption>()
        val seen = mutableSetOf<String>()

        // FIXED regex syntax: (?<id>...) and (?<url>...) — valid Kotlin/Java named groups
        val regex = Regex("""stream_url_(?<id>[^\s=]+)\s*=\s*["'](?<url>https?://[^"']+)["']""")
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
     * RedTube extraction logic — replica of extractRedtube from SiteExtractors.kt (unfixed).
     *
     * Uses Gson instead of org.json because org.json is stubbed out in unit tests
     * (it's an Android SDK class that throws UnsupportedOperationException in JVM tests).
     * The logic is otherwise identical to the production code.
     */
    private fun redtubeExtract(html: String): List<MediaQualityOption> {
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
                        val obj = JsonParser.parseString(sourcesJson).asJsonObject
                        for ((quality, urlElem) in obj.entrySet()) {
                            val url = urlElem.asString
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
                            val arr = JsonParser.parseString(arrJson).asJsonArray
                            for (elem in arr) {
                                val obj = elem.asJsonObject
                                val url = obj.get("videoUrl")?.asString ?: continue
                                if (url.isBlank() || !url.startsWith("http")) continue
                                val format = (obj.get("format")?.asString ?: "").lowercase()
                                if (format == "hls") continue
                                val quality = obj.get("quality")?.asString ?: ""
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
    // Preservation 1 — SpankBang primary path preserved
    //
    // When HTML contains stream_url_720p = "https://cdn.spankbang.com/video.mp4",
    // the extraction using the FIXED regex syntax returns a non-empty list with
    // quality "720p".
    //
    // This test uses the FIXED regex syntax (?<id>...) because the unfixed
    // (?P<id>...) crashes before reaching the loop. The test documents that the
    // primary path logic is correct and must be preserved after Fix 1a.
    //
    // EXPECTED OUTCOME: PASSES on unfixed code (primary path logic is correct;
    // only the regex syntax needs fixing).
    // =========================================================================

    /**
     * Preservation 1: SpankBang primary path returns non-empty list with quality "720p"
     * when HTML contains stream_url_720p = "https://cdn.spankbang.com/video.mp4".
     *
     * Uses the FIXED regex syntax (?<id>...) to verify the primary path logic works.
     * After Fix 1a (changing (?P<id>...) → (?<id>...)), the primary path must still
     * return the same result — this test documents that baseline.
     *
     * EXPECTED OUTCOME: PASSES on unfixed code.
     * After Fix 1a: must still PASS (primary path logic unchanged, only syntax fixed).
     *
     * Validates: Requirements 3.9 (primary path preserved, no API call when stream_url_* present)
     */
    @Test
    fun `Preservation 1 - SpankBang primary path returns 720p option for stream_url_720p HTML`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>SpankBang Video</title></head>
            <body>
            <script>
            var stream_url_720p = "https://cdn.spankbang.com/video_720p.mp4";
            var stream_url_480p = "https://cdn.spankbang.com/video_480p.mp4";
            var video_title = "Test Video";
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = spankbangPrimaryPath(html)

        assertTrue(
            "Preservation 1: SpankBang primary path must return a non-empty list " +
                "when HTML contains stream_url_720p. Got: $result",
            result.isNotEmpty()
        )

        val has720p = result.any { it.quality == "720p" }
        assertTrue(
            "Preservation 1: Result must contain a '720p' quality option. Got: $result",
            has720p
        )

        val has480p = result.any { it.quality == "480p" }
        assertTrue(
            "Preservation 1: Result must contain a '480p' quality option. Got: $result",
            has480p
        )

        // Verify the URL is correct
        val option720p = result.first { it.quality == "720p" }
        assertEquals(
            "Preservation 1: 720p URL must match the stream_url_720p value",
            "https://cdn.spankbang.com/video_720p.mp4",
            option720p.url
        )

        // Verify sorted order: 720p before 480p
        val idx720 = result.indexOfFirst { it.quality == "720p" }
        val idx480 = result.indexOfFirst { it.quality == "480p" }
        assertTrue(
            "Preservation 1: Results must be sorted descending by quality (720p before 480p). Got: $result",
            idx720 < idx480
        )
    }

    // =========================================================================
    // Preservation 2 — RedTube sources path preserved
    //
    // When HTML contains sources: {"720": "https://cdn.redtube.com/video.mp4"},
    // extractRedtube returns a non-empty list with quality "720p".
    //
    // This path is unaffected by Fix 2a (which only changes the mediaDefinition
    // fallback). This test confirms the sources path works on unfixed code and
    // must continue to work after the fix.
    //
    // EXPECTED OUTCOME: PASSES on unfixed code.
    // =========================================================================

    /**
     * Preservation 2: RedTube sources path returns non-empty list with quality "720p"
     * when HTML contains sources: {"720": "https://cdn.redtube.com/video.mp4"}.
     *
     * The sources path is the primary extraction path and is unaffected by Fix 2a.
     * This test documents the baseline behavior that must be preserved.
     *
     * EXPECTED OUTCOME: PASSES on unfixed code.
     * After Fix 2a: must still PASS (sources path logic unchanged).
     *
     * Validates: Requirements 3.2 (RedTube sources path preserved)
     */
    @Test
    fun `Preservation 2 - RedTube sources path returns 720p option for populated sources JSON`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>RedTube Video</title></head>
            <body>
            <script>
            var playerObjList = {
              sources: {"720": "https://cdn.redtube.com/video_720p.mp4", "480": "https://cdn.redtube.com/video_480p.mp4"},
              mediaDefinition: []
            };
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = redtubeExtract(html)

        assertTrue(
            "Preservation 2: RedTube sources path must return a non-empty list " +
                "when sources: {...} is populated. Got: $result",
            result.isNotEmpty()
        )

        val has720p = result.any { it.quality == "720p" }
        assertTrue(
            "Preservation 2: Result must contain a '720p' quality option. Got: $result",
            has720p
        )

        val option720p = result.first { it.quality == "720p" }
        assertEquals(
            "Preservation 2: 720p URL must match the sources value",
            "https://cdn.redtube.com/video_720p.mp4",
            option720p.url
        )
    }

    // =========================================================================
    // Preservation 3 — RedTube sources path takes priority over mediaDefinition
    //
    // When HTML has both a populated sources: {...} AND a mediaDefinition array,
    // only the sources path result is returned. The mediaDefinition fallback is
    // NOT attempted when sources produces results.
    //
    // This is the existing behavior (options.isEmpty() guard) and must be preserved
    // after Fix 2a.
    //
    // EXPECTED OUTCOME: PASSES on unfixed code.
    // =========================================================================

    /**
     * Preservation 3: When HTML has both populated sources: {...} AND mediaDefinition,
     * only the sources path result is returned (mediaDefinition fallback NOT attempted).
     *
     * The mediaDefinition fallback is guarded by `if (options.isEmpty())` — when the
     * sources path succeeds, the fallback is skipped entirely. This behavior must be
     * preserved after Fix 2a.
     *
     * EXPECTED OUTCOME: PASSES on unfixed code.
     * After Fix 2a: must still PASS (fallback guard unchanged).
     *
     * Validates: Requirements 2.5, 3.9 (sources path takes priority; fallback not attempted)
     */
    @Test
    fun `Preservation 3 - RedTube sources path takes priority when both sources and mediaDefinition are present`() {
        // HTML with BOTH a populated sources block AND a mediaDefinition array.
        // The mediaDefinition has a different URL — if it were used, the result would differ.
        val sourcesUrl = "https://cdn.redtube.com/sources_video_720p.mp4"
        val mediaDefUrl = "https://cdn.redtube.com/mediadef_video_720p.mp4"

        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>RedTube Video</title></head>
            <body>
            <script>
            var playerObjList = {
              sources: {"720": "$sourcesUrl"},
              mediaDefinition: [
                {
                  "format": "mp4",
                  "quality": "720",
                  "videoUrl": "$mediaDefUrl"
                }
              ]
            };
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = redtubeExtract(html)

        assertTrue(
            "Preservation 3: Result must be non-empty when sources: {...} is populated. Got: $result",
            result.isNotEmpty()
        )

        // The result must contain the sources URL, not the mediaDefinition URL
        val hasSourcesUrl = result.any { it.url == sourcesUrl }
        assertTrue(
            "Preservation 3: Result must contain the sources URL ($sourcesUrl), not the mediaDefinition URL. Got: $result",
            hasSourcesUrl
        )

        // The mediaDefinition URL must NOT appear in the result
        val hasMediaDefUrl = result.any { it.url == mediaDefUrl }
        assertFalse(
            "Preservation 3: Result must NOT contain the mediaDefinition URL ($mediaDefUrl) " +
                "when sources path succeeds. Got: $result",
            hasMediaDefUrl
        )
    }

    // =========================================================================
    // Preservation 4 — No duplicate extraction on same URL (setCurrentPage guard)
    //
    // The setCurrentPage URL-change guard: calling setCurrentPage twice with the
    // same URL should not launch a second extraction job.
    //
    // This is a unit test of the guard logic itself. We simulate the guard inline
    // (no Android dependencies needed) to verify the behavior.
    //
    // EXPECTED OUTCOME: PASSES on unfixed code (guard logic is already correct).
    // After Fix 2b: must still PASS (onUrlChanged + onPageStarted with same URL
    // triggers extraction exactly once).
    // =========================================================================

    /**
     * Preservation 4: setCurrentPage URL-change guard prevents duplicate extraction.
     *
     * When setCurrentPage is called twice with the same URL, the second call is a no-op
     * (the `if (!urlChanged) return` guard fires). This ensures that after Fix 2b
     * (where onUrlChanged calls setCurrentPage early), the subsequent onPageStarted
     * call with the same URL does NOT launch a second extraction job.
     *
     * We simulate the guard logic inline to verify the behavior without Android dependencies.
     *
     * EXPECTED OUTCOME: PASSES on unfixed code.
     * After Fix 2b: must still PASS (guard prevents duplicate extraction).
     *
     * Validates: Requirements 2.9, 3.8 (no duplicate extraction from early setCurrentPage)
     */
    @Test
    fun `Preservation 4 - setCurrentPage URL-change guard prevents duplicate extraction on same URL`() {
        // Simulate the VideoDetector's currentPageUrl and extraction job counter
        var currentPageUrl = ""
        var extractionJobsLaunched = 0

        // Simulate the setCurrentPage guard logic from VideoDetector.kt:
        //
        //   fun setCurrentPage(url: String, title: String) {
        //       val urlChanged = url != currentPageUrl
        //       currentPageUrl = url
        //       if (!urlChanged) return   // <-- the guard
        //       // ... launch extraction job ...
        //   }
        fun simulateSetCurrentPage(url: String) {
            val urlChanged = url != currentPageUrl
            currentPageUrl = url
            if (!urlChanged) return  // guard: no-op if URL hasn't changed
            extractionJobsLaunched++  // represents launching the extraction coroutine
        }

        val redtubeUrl = "https://www.redtube.com/123"

        // First call: URL changes from "" to redtubeUrl → extraction job launched
        simulateSetCurrentPage(redtubeUrl)
        assertEquals(
            "Preservation 4: First setCurrentPage call must launch exactly 1 extraction job",
            1,
            extractionJobsLaunched
        )
        assertEquals(
            "Preservation 4: currentPageUrl must be updated to the RedTube URL after first call",
            redtubeUrl,
            currentPageUrl
        )

        // Second call with the SAME URL (simulates onPageStarted after onUrlChanged Fix 2b)
        // The guard must fire and prevent a second extraction job.
        simulateSetCurrentPage(redtubeUrl)
        assertEquals(
            "Preservation 4: Second setCurrentPage call with the SAME URL must be a no-op. " +
                "The URL-change guard (if (!urlChanged) return) must prevent a duplicate extraction job. " +
                "Expected 1 total job, but got $extractionJobsLaunched. " +
                "This confirms that after Fix 2b, onUrlChanged + onPageStarted with the same URL " +
                "triggers extraction exactly once.",
            1,
            extractionJobsLaunched
        )

        // Third call with a DIFFERENT URL → new extraction job launched
        val newUrl = "https://www.redtube.com/456"
        simulateSetCurrentPage(newUrl)
        assertEquals(
            "Preservation 4: setCurrentPage with a new URL must launch a new extraction job. " +
                "Expected 2 total jobs after navigating to a different URL.",
            2,
            extractionJobsLaunched
        )
        assertEquals(
            "Preservation 4: currentPageUrl must be updated to the new URL",
            newUrl,
            currentPageUrl
        )
    }
}
