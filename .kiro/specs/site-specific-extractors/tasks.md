# Implementation Plan: Site-Specific Extractors

## Overview

Implement gold-standard video extraction for XNXX, XVideos, and XHamster. The work splits into three focused areas: (1) a new `SiteExtractors.kt` file with the shared page-fetch helper and both proactive extractors, (2) targeted modifications to `VideoDetector.kt` to wire in site detection, job management, suppression, and quality-option routing, and (3) property-based and unit tests covering all eight correctness properties.

The PH implementation is **never touched** except for the suppression guard that was already implied by the architecture.

---

## Tasks

- [x] 1. Create `SiteExtractors.kt` — page-fetch helper
  - Create `app/src/main/java/com/cognitivechaos/xdownload/service/SiteExtractors.kt`
  - Declare `class SiteExtractors(private val okHttpClient: OkHttpClient, private val cookieProvider: (String) -> String?)`
  - Add `companion object` with `DESKTOP_USER_AGENT` constant (`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36`) and `TAG`
  - Implement `private suspend fun fetchPage(pageUrl: String): String?` — builds `Request` with `Cookie` (from `cookieProvider`), `User-Agent`, `Referer` (= `pageUrl`), `Accept-Language: en-US,en;q=0.9`; returns `null` on non-2xx or exception, logs error
  - _Requirements: 9.1, 9.3, 10.1, 10.2, 10.3, 10.4_

- [x] 2. Implement `XnxxExtractor` in `SiteExtractors.kt`
  - Add `suspend fun extractXnxx(pageUrl: String): List<MediaQualityOption>` using `withContext(Dispatchers.IO)`
  - Call `fetchPage(pageUrl)`; return `emptyList()` if null
  - Apply regex `setVideo(?:Url(?:Low|High)|HLS)\s*\(\s*["'](?<url>(?:https?:)?//.+?)["']` via `Regex.findAll`
  - Map each match to `MediaQualityOption`: `quality = "High"/"Low"/"HLS"`, `mimeType = "application/x-mpegurl"` for HLS else `"video/mp4"`
  - Deduplicate via a `seen: MutableSet<String>` — skip any URL already in the set
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 3. Implement `XvideosExtractor` in `SiteExtractors.kt`
  - Add `suspend fun extractXvideos(pageUrl: String): List<MediaQualityOption>` using `withContext(Dispatchers.IO)`
  - Call `fetchPage(pageUrl)`; return `emptyList()` if null
  - Apply primary regex `setVideo([^(]+)\(["'](https?://.+?)["']\)` via `Regex.findAll`; map `kind` to `"High"/"Low"/"HLS"` quality labels; set `mimeType = "application/x-mpegurl"` for HLS
  - Apply fallback regex `flv_url=([^&"'\s]+)` — URL-decode the captured group with `java.net.URLDecoder.decode(..., "UTF-8")`; add `MediaQualityOption(quality = "FLV", mimeType = "video/x-flv")` if URL starts with `"http"` and is not already seen
  - Deduplicate via `seen: MutableSet<String>`
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9_

- [x] 4. Add site-detection helpers and `activeExtractionJob` to `VideoDetector.kt`
  - Instantiate `siteExtractors` field: `private val siteExtractors = SiteExtractors(okHttpClient) { url -> android.webkit.CookieManager.getInstance().getCookie(url) ?: android.webkit.CookieManager.getInstance().getCookie(currentPageUrl) }`
  - Add `private var activeExtractionJob: Job? = null`
  - Add five private helper functions exactly as specified in the design:
    - `isXnxxPage()` — regex `(?:video|www)\.xnxx3?\.com`
    - `isXvideosPage()` — regex `(?:[^.]+\.)?xvideos2?\.com` OR `(?:www\.)?xvideos\.es`
    - `isXhamsterPage()` — regex `(?:[^.]+\.)?(?:xhamster\.(?:com|one|desi)|xhms\.pro|xhamster\d+\.(?:com|desi)|xhday\.com|xhvid\.com)`
    - `isXnxxVideoPage()` — `isXnxxPage() && currentPageUrl.contains("/video-", ignoreCase = true)`
    - `isXvideosVideoPage()` — `isXvideosPage() && currentPageUrl.contains("/video", ignoreCase = true)`
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.7, 9.2_

- [x] 5. Modify `VideoDetector.setCurrentPage()` to trigger site-specific extraction
  - After assigning `currentPageUrl` and `currentPageTitle`, add `activeExtractionJob?.cancel(); activeExtractionJob = null`
  - Capture `val triggeredForUrl = url`
  - Add `when` block: `isXnxxVideoPage()` → launch coroutine calling `siteExtractors.extractXnxx(triggeredForUrl)`, guard with `if (currentPageUrl != triggeredForUrl) return@launch`, then call `registerSiteExtractorResult(triggeredForUrl, options)`; same pattern for `isXvideosVideoPage()` → `siteExtractors.extractXvideos`
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 7.1_

- [x] 6. Add `registerSiteExtractorResult()` private helper to `VideoDetector.kt`
  - Implement `private fun registerSiteExtractorResult(triggeredForUrl: String, options: List<MediaQualityOption>)` exactly as in the design
  - Guard: `if (options.isEmpty()) return`
  - Store `prefetchedOptions[triggeredForUrl] = options`
  - If `triggeredForUrl !in detectedUrls`: add to `detectedUrls`, build `DetectedMedia(url = triggeredForUrl, mimeType = "video/mp4", quality = options.first().quality, sourcePageUrl = triggeredForUrl, ...)`, append to `_detectedMedia`, set `_hasMedia = true`
  - Launch background coroutine to fetch file sizes concurrently (`async`/`awaitAll`, 5000ms timeout per URL via `withTimeoutOrNull`), then update `prefetchedOptions[triggeredForUrl]` with size-enriched options
  - _Requirements: 7.2, 7.4, 7.5, 8.1, 8.2, 8.3, 8.4_

- [x] 7. Modify `VideoDetector.clearDetectedMedia()` to cancel the active extraction job
  - Add `activeExtractionJob?.cancel(); activeExtractionJob = null` at the top of `clearDetectedMedia()`
  - All existing clear logic (detectedUrls, counter, flows, prefetchedOptions, etc.) remains unchanged
  - _Requirements: 2.5, 6.10, 7.6_

- [x] 8. Add gold-standard suppression block to `VideoDetector.onResourceRequest()`
  - Locate the existing `get_media` interception block (ends with `return true`)
  - Immediately after that block (before the `// === Standard media detection ===` comment), insert the suppression guard:
    ```kotlin
    // Gold-standard suppression — keep generic detection off for sites with dedicated extractors
    if (isPornhubPage()) return false
    if (isXnxxPage() || isXvideosPage()) return false
    if (isXhamsterPage()) {
        if (!lowUrl.contains("xhcdn.com")) return false
        val isVideoFile = VIDEO_EXTENSIONS.any { lowUrl.substringBefore("?").endsWith(it) } ||
            STREAM_EXTENSIONS.any { lowUrl.substringBefore("?").endsWith(it) } ||
            (mimeType != null && (mimeType.startsWith("video/") || mimeType.startsWith("application/x-mpegurl")))
        if (!isVideoFile) return false
        // fall through to standard detection for xhcdn.com video URLs
    }
    ```
  - No other changes to `onResourceRequest` — the PH `get_media` block, standard detection block, and all existing logic are untouched
  - _Requirements: 1.4, 1.5, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9_

- [x] 9. Modify `VideoDetector.fetchQualityOptions()` to check `prefetchedOptions` by `sourcePageUrl`
  - At the top of the `withContext(Dispatchers.IO)` block, before the existing `prefetchedQualityOptions` cache check, add:
    ```kotlin
    prefetchedOptions[media.sourcePageUrl]?.let { cached ->
        if (cached.isNotEmpty()) return@withContext cached
    }
    prefetchedOptions[media.url]?.let { cached ->
        if (cached.isNotEmpty()) return@withContext cached
    }
    ```
  - The existing `isPornhubGetMediaUrl` branch and all other branches remain unchanged
  - _Requirements: 7.2, 7.3_

- [x] 10. Checkpoint — verify compilation and existing tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 11. Write property-based tests — Properties 1–4 (suppression, stale results, idempotency, regex coverage)
  - Create `app/src/test/java/com/cognitivechaos/xdownload/SiteExtractorsPropertyTest.kt`
  - Use kotest-property `forAll` / `checkAll` with minimum 100 iterations each
  - [ ]* 11.1 Property 1 — Suppression completeness
    - **Property 1: Suppression completeness**
    - Generator: `Arb.string()` for intercepted URL × fixed set of gold-standard `currentPageUrl` values (PH, XNXX, XVideos, XHamster)
    - Assertion: for each gold-standard page, `onResourceRequest(url)` returns `false` and `_detectedMedia` does not grow, except for the `get_media` / `xhcdn.com` video carve-outs
    - **Validates: Requirements 1.4, 1.5, 6.1, 6.2, 6.3, 6.4, 6.8**
  - [ ]* 11.2 Property 2 — No stale results after clear
    - **Property 2: No stale results after clear**
    - Generator: `Arb.string().filter { it.startsWith("http") }` for XNXX/XVideos page URLs; simulate in-flight coroutine by calling `registerSiteExtractorResult` after `clearDetectedMedia()`
    - Assertion: `_detectedMedia` remains empty; `_hasMedia` remains `false`
    - **Validates: Requirements 2.5, 6.10**
  - [ ]* 11.3 Property 3 — Idempotent extraction
    - **Property 3: Idempotent extraction**
    - Generator: `Arb.string()` for XNXX/XVideos video page URLs
    - Assertion: calling `setCurrentPage` twice with the same URL (without `clearDetectedMedia`) results in `_detectedMedia.count { it.url == url } <= 1`
    - **Validates: Requirements 2.6, 8.4**
  - [ ]* 11.4 Property 4 — Regex coverage for site detection
    - **Property 4: Regex coverage for site detection**
    - Input: fixed list of yt-dlp canonical test URLs for XNXX, XVideos, XHamster, plus a set of non-matching URLs
    - Assertion: `isXnxxPage()` / `isXvideosPage()` / `isXhamsterPage()` return `true` for matching URLs and `false` for all others
    - **Validates: Requirements 2.7**
  - _Requirements: 1.4, 1.5, 2.5, 2.6, 2.7, 6.1–6.4, 6.8, 6.10, 8.4_

- [ ] 12. Write property-based tests — Properties 5–8 (generic detection, extraction completeness, round-trip)
  - Add to `SiteExtractorsPropertyTest.kt` (or a companion file)
  - [ ]* 12.1 Property 5 — Generic detection unchanged
    - **Property 5: Generic detection unchanged**
    - Generator: `Arb.string()` for non-gold-standard `currentPageUrl` × `Arb.string()` for intercepted URL × `Arb.string().orNull()` for MIME type
    - Assertion: `onResourceRequest` return value and `_detectedMedia` delta match a reference snapshot of the pre-feature logic (use a second `VideoDetector` instance with no site-extractor wiring as the reference)
    - **Validates: Requirements 6.5**
  - [ ]* 12.2 Property 6 — XNXX regex extraction completeness
    - **Property 6: XNXX regex extraction completeness**
    - Generator: `Arb.list(Arb.string(minSize=10).filter { it.startsWith("http") }, 1..10)` for URL lists; build synthetic HTML by embedding each URL in `setVideoUrlHigh(...)`, `setVideoUrlLow(...)`, or `setVideoHLS(...)` calls (randomly chosen), including deliberate duplicates
    - Assertion: `SiteExtractors.extractXnxx` (with a mock `fetchPage` returning the generated HTML) returns exactly the set of distinct URLs, each with the correct quality label; no duplicates in result
    - **Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.8**
  - [ ]* 12.3 Property 7 — XVideos regex extraction completeness
    - **Property 7: XVideos regex extraction completeness**
    - Generator: same as Property 6 but also randomly appends a `flv_url=<encoded-url>` fragment to the HTML
    - Assertion: `SiteExtractors.extractXvideos` returns exactly the set of distinct URLs with correct quality labels; `flv_url` decoded correctly; no duplicates
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 4.6, 4.9**
  - [ ]* 12.4 Property 8 — `prefetchedOptions` round-trip
    - **Property 8: prefetchedOptions round-trip**
    - Generator: `Arb.list(Arb.bind(...) { MediaQualityOption(...) }, 1..5)` × `Arb.string()` for page URL
    - Assertion: after storing options in `prefetchedOptions[pageUrl]`, `fetchQualityOptions(DetectedMedia(url = pageUrl, sourcePageUrl = pageUrl, ...))` returns those exact options without any network call (verify by asserting `okHttpClient` is never invoked)
    - **Validates: Requirements 7.2, 7.3**
  - _Requirements: 3.2–3.5, 3.8, 4.2–4.6, 4.9, 6.5, 7.2, 7.3_

- [ ] 13. Write unit/example-based tests
  - Create `app/src/test/java/com/cognitivechaos/xdownload/SiteExtractorsUnitTest.kt`
  - [ ]* 13.1 `XnxxExtractor` — HTML with all three quality variants returns correct options
    - Mock `fetchPage` to return HTML containing `setVideoUrlHigh("https://cdn.xnxx.com/high.mp4")`, `setVideoUrlLow("https://cdn.xnxx.com/low.mp4")`, `setVideoHLS("https://cdn.xnxx.com/hls.m3u8")`
    - Assert result has 3 entries with qualities `"High"`, `"Low"`, `"HLS"` and correct MIME types
    - _Requirements: 3.2, 3.3, 3.4, 3.5_
  - [ ]* 13.2 `XnxxExtractor` — non-2xx response returns empty list without crash
    - Mock HTTP response with status 403
    - Assert result is empty; no exception thrown
    - _Requirements: 3.6_
  - [ ]* 13.3 `XnxxExtractor` — HTML with no `setVideo*` returns empty list
    - Mock `fetchPage` to return plain HTML with no matching patterns
    - Assert result is empty
    - _Requirements: 3.7_
  - [ ]* 13.4 `XnxxExtractor` — duplicate URLs deduplicated
    - Mock HTML with the same URL appearing in two `setVideoUrlHigh` calls
    - Assert result contains that URL exactly once
    - _Requirements: 3.8_
  - [ ]* 13.5 `XvideosExtractor` — HTML with `setVideo*` and `flv_url` returns all options
    - Mock HTML with `setVideoUrlHigh`, `setVideoUrlLow`, `setVideoHLS`, and `flv_url=<encoded-url>`
    - Assert 4 entries with correct quality labels and MIME types; FLV URL is URL-decoded
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6_
  - [ ]* 13.6 `XvideosExtractor` — non-2xx response returns empty list without crash
    - _Requirements: 4.7_
  - [ ]* 13.7 `XvideosExtractor` — HTML with no patterns returns empty list
    - _Requirements: 4.8_
  - [ ]* 13.8 `fetchQualityOptions` returns cached options when `prefetchedOptions` is populated
    - Populate `prefetchedOptions[pageUrl]` with a known list; call `fetchQualityOptions` for a `DetectedMedia` with `sourcePageUrl = pageUrl`
    - Assert returned list equals the cached list; assert no HTTP calls made
    - _Requirements: 7.2, 7.3_
  - [ ]* 13.9 `clearDetectedMedia` clears `prefetchedOptions` and cancels `activeExtractionJob`
    - Start a slow extraction job; call `clearDetectedMedia()`; assert `prefetchedOptions` is empty and job is cancelled
    - _Requirements: 2.5, 7.6_
  - [ ]* 13.10 Page-fetch request includes all required headers
    - Capture the `Request` built by `fetchPage`; assert presence of `Cookie`, `User-Agent` (desktop Chrome), `Referer` (= page URL), `Accept-Language: en-US,en;q=0.9`
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [ ] 14. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- The PH extraction path in `VideoDetector.kt` is never modified — only the suppression guard (task 8) and the `fetchQualityOptions` cache check (task 9) are added
- `activeExtractionJob` is a single `Job?` field; only one site-specific extraction runs at a time per page navigation
- Property tests use kotest-property; add `testImplementation("io.kotest:kotest-property:5.x.x")` to `app/build.gradle.kts` if not already present
- The `registerSiteExtractorResult` guard `if (currentPageUrl != triggeredForUrl) return@launch` is the sole mechanism preventing stale results — it must be checked before any state mutation
