# SpankBang + RedTube Fixes — Bugfix Design

## Overview

Three targeted fixes across two files:

1. **Fix 1a** — `extractSpankbang` in `SiteExtractors.kt`: replace Python-style named capture group syntax `(?P<name>...)` with Kotlin/Java syntax `(?<name>...)` so the regex compiles without `PatternSyntaxException`.
2. **Fix 1b** — `extractSpankbang` in `SiteExtractors.kt`: add an API fallback that extracts `data-streamkey` from the page HTML and POSTs to `https://spankbang.com/api/videos/stream` when no `stream_url_*` variables are found, mirroring yt-dlp's `spankbang.py` fallback exactly.
3. **Fix 2a** — `extractRedtube` in `SiteExtractors.kt`: when a `mediaDefinition` entry has `format='mp4'` and an empty `quality` field, treat `videoUrl` as an API endpoint, fetch it, and parse the returned JSON array for direct MP4 URLs — mirroring yt-dlp's `redtube.py` exactly.
4. **Fix 2b** — `onUrlChanged` in `BrowserViewModel.kt`: call `videoDetector.setCurrentPage(url, _currentTitle.value)` immediately after `videoDetector.clearDetectedMedia()` so `currentPageUrl` is set before any `shouldInterceptRequest` background-thread calls arrive for the new page.

All other extractors (Eporner, YouPorn, XNXX, XVideos, XHamster, Pornhub) are untouched.

---

## Glossary

- **Bug_Condition (C)**: The condition that triggers a bug — either a SpankBang page navigation (Bug 1) or a resource request arriving before `currentPageUrl` is updated (Bug 2).
- **Property (P)**: The desired correct behavior — no crash and correct video URLs returned (Bug 1); ad pre-roll suppressed and real video detected (Bug 2).
- **Preservation**: Existing behavior for all non-SpankBang, non-RedTube pages that must remain unchanged.
- **`extractSpankbang`**: The function in `SiteExtractors.kt` that fetches a SpankBang page and extracts direct MP4 URLs from embedded JS variables or the stream API.
- **`extractRedtube`**: The function in `SiteExtractors.kt` that fetches a RedTube page and extracts direct MP4 URLs from `sources` JSON or `mediaDefinition` entries.
- **`onUrlChanged`**: The function in `BrowserViewModel.kt` called when the WebView begins navigating to a new URL — before sub-resources load.
- **`setCurrentPage`**: The `VideoDetector` method that sets `currentPageUrl` and launches site-specific extraction. Contains a `if (!urlChanged) return` guard to prevent duplicate extraction.
- **`clearDetectedMedia`**: The `VideoDetector` method that clears the detected media list but does NOT reset `currentPageUrl`.
- **`stream_url_*`**: JS variables embedded in SpankBang page HTML (e.g. `stream_url_720p = "https://..."`). Primary extraction path.
- **`data-streamkey`**: HTML attribute on SpankBang pages used as the fallback when `stream_url_*` variables are absent.
- **`mediaDefinition`**: JSON array embedded in RedTube page HTML. Each entry has `format`, `quality`, and `videoUrl` fields. When `format='mp4'` and `quality=''`, `videoUrl` is an API endpoint, not a direct video URL.

---

## Bug Details

### Bug 1a — SpankBang Regex Crash

The `extractSpankbang` function compiles a `Regex(...)` containing `(?P<id>...)` and `(?P<url>...)` — Python named capture group syntax. Kotlin/Java regex requires `(?<id>...)` and `(?<url>...)`. The JVM throws `PatternSyntaxException` at the point the `Regex(...)` constructor is evaluated, before any network call is made. Because `PatternSyntaxException` extends `Error` (not `Exception`), it is not caught by any `try/catch (e: Exception)` block.

**Formal Specification:**
```
FUNCTION isBugCondition_1a(X)
  INPUT: X of type PageNavigation
  OUTPUT: boolean

  RETURN isSpankbangPage(X.url)
END FUNCTION
```

**Examples:**
- Navigate to `https://spankbang.com/56b3d/video/...` → `PatternSyntaxException` thrown, app crashes.
- Navigate to `https://m.spankbang.com/3vvn/play` → same crash.
- Navigate to `https://spankbang.com/2y3td/embed/` → same crash.

---

### Bug 1b — SpankBang Missing API Fallback

When a SpankBang page does not embed `stream_url_*` JS variables (newer page layouts), `extractSpankbang` returns an empty list silently. yt-dlp's `spankbang.py` handles this by extracting `data-streamkey` and POSTing to `/api/videos/stream`.

**Formal Specification:**
```
FUNCTION isBugCondition_1b(X)
  INPUT: X of type PageNavigation
  OUTPUT: boolean

  RETURN isSpankbangPage(X.url)
         AND pageHTML(X.url) does NOT contain stream_url_* variables
         AND pageHTML(X.url) contains data-streamkey attribute
END FUNCTION
```

**Examples:**
- SpankBang page with `data-streamkey="abc123"` but no `stream_url_*` → returns empty list (bug).
- SpankBang page with `stream_url_720p = "https://..."` → primary path succeeds, fallback not needed.

---

### Bug 2a — RedTube Incomplete mediaDefinition Handling

The current `extractRedtube` fallback iterates `mediaDefinition` entries and treats every `videoUrl` as a direct video URL. Per yt-dlp's `redtube.py`, when `format='mp4'` and `quality=''`, the `videoUrl` is an API endpoint that must be fetched to get the actual list of quality URLs. The current code skips entries with empty `quality`, so it returns an empty list for most RedTube pages.

**Formal Specification:**
```
FUNCTION isBugCondition_2a(X)
  INPUT: X of type PageNavigation
  OUTPUT: boolean

  RETURN isRedtubePage(X.url)
         AND pageHTML(X.url) does NOT contain sources: {...} with direct URLs
         AND pageHTML(X.url) contains mediaDefinition with format='mp4' AND quality=''
END FUNCTION
```

**Examples:**
- RedTube page where `sources: {}` is empty and `mediaDefinition` has one entry with `format='mp4'`, `quality=''`, `videoUrl='https://www.redtube.com/media?...'` → current code returns empty list (bug); fixed code fetches the API URL and returns quality options.
- RedTube page where `sources: {"720": "https://..."}` is populated → primary path succeeds, `mediaDefinition` fallback not attempted (unchanged behavior).

---

### Bug 2b — Race Condition: Resource Requests Arrive Before `currentPageUrl` Is Set

`shouldInterceptRequest` is called on a background thread. `onPageStarted` (which calls `setCurrentPage`) is called on the main thread. Sub-resource requests for a new page can arrive before `onPageStarted` fires, so `currentPageUrl` still holds the previous page's URL. When `isRedtubePage()` evaluates to false, the gold-standard suppression block is bypassed and ad pre-roll CDN URLs are registered as detected media.

**Formal Specification:**
```
FUNCTION isBugCondition_2b(X)
  INPUT: X of type ResourceRequest
  OUTPUT: boolean

  RETURN isGoldStandardPage(X.destinationPageUrl)
         AND NOT isGoldStandardPage(currentPageUrl)
         AND X arrives before onPageStarted fires for X.destinationPageUrl
END FUNCTION
```

**Examples:**
- User navigates to RedTube; ad pre-roll CDN request arrives before `onPageStarted` → `currentPageUrl` is still the previous page, `isRedtubePage()` = false, ad URL registered (bug).
- After fix: `onUrlChanged` sets `currentPageUrl` to the RedTube URL immediately → `isRedtubePage()` = true, ad URL suppressed.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- `extractEporner`, `extractYouporn`, `extractXnxx`, `extractXvideos`, `extractXhamster` must continue to work exactly as before.
- Pornhub `get_media` API interception must continue to work exactly as before.
- Generic WebView resource interception for non-gold-standard sites must continue to work exactly as before.
- `onUrlChanged` calling `clearDetectedMedia()` followed by `setCurrentPage(url, title)` must not cause duplicate extraction — the `if (!urlChanged) return` guard in `setCurrentPage` ensures the subsequent `onPageStarted` call with the same URL is a no-op.
- When `extractSpankbang` finds `stream_url_*` variables, it must NOT hit the `/api/videos/stream` fallback endpoint.
- When `extractRedtube` finds a populated `sources: {...}` block, it must NOT attempt the `mediaDefinition` fallback.

**Scope:**
All inputs that do NOT involve SpankBang or RedTube pages should be completely unaffected by these fixes. This includes:
- All other gold-standard site extractors
- Generic media detection via WebView interception
- All `BrowserViewModel` state management (tabs, bookmarks, history, downloads)

---

## Hypothesized Root Cause

### Bug 1a
The regex string was written using Python named group syntax (`(?P<name>...)`) and was not adapted to Kotlin/Java syntax (`(?<name>...)`). The JVM regex engine does not recognise `?P<` and throws `PatternSyntaxException` immediately on `Regex(...)` construction.

### Bug 1b
The fallback path was simply not implemented. The primary `stream_url_*` regex was ported from yt-dlp but the `data-streamkey` + `/api/videos/stream` POST fallback was omitted.

### Bug 2a
The `mediaDefinition` fallback was partially ported. The code correctly finds the array and iterates entries, but it skips entries with empty `quality` instead of recognising them as API index URLs that must be fetched. The yt-dlp logic (`if format_id == 'mp4' and not quality: fetch(format_url)`) was not carried over.

### Bug 2b
`onUrlChanged` clears detected media but does not call `setCurrentPage`. The earliest call to `setCurrentPage` is in `onPageStarted`, which fires on the main thread after the WebView has already begun loading sub-resources on background threads. Moving `setCurrentPage` to `onUrlChanged` closes the race window.

---

## Correctness Properties

Property 1: Bug Condition — SpankBang Regex Compiles and Extracts

_For any_ navigation to a SpankBang URL, the fixed `extractSpankbang` function SHALL compile its regex without throwing `PatternSyntaxException`, fetch the page HTML, and return a non-empty list of `MediaQualityOption` items (either from `stream_url_*` variables or from the `/api/videos/stream` fallback).

**Validates: Requirements 2.1, 2.2, 2.10, 2.11**

Property 2: Preservation — Non-SpankBang Extractors Unchanged

_For any_ navigation to a non-SpankBang URL, the fixed code SHALL produce exactly the same extraction result as the original code, preserving all existing extractor behavior.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**

Property 3: Bug Condition — RedTube mediaDefinition API Follow-Through

_For any_ RedTube page where `sources: {}` is empty and `mediaDefinition` contains an entry with `format='mp4'` and empty `quality`, the fixed `extractRedtube` function SHALL fetch the `videoUrl` as an API endpoint and return a non-empty list of `MediaQualityOption` items for the direct MP4 URLs.

**Validates: Requirements 2.3, 2.4, 2.5, 2.6**

Property 4: Preservation — RedTube sources Path Unchanged

_For any_ RedTube page where `sources: {...}` contains direct URLs, the fixed `extractRedtube` function SHALL return the same result as the original, without attempting the `mediaDefinition` fallback.

**Validates: Requirements 2.5, 3.1**

Property 5: Bug Condition — Race Window Closed

_For any_ resource request that arrives for a RedTube page before `onPageStarted` fires, the fixed `onUrlChanged` SHALL have already called `setCurrentPage(url, title)` so that `isRedtubePage()` returns true and the gold-standard suppression block correctly suppresses ad pre-roll URLs.

**Validates: Requirements 2.7, 2.8**

Property 6: Preservation — No Duplicate Extraction from Early setCurrentPage

_For any_ navigation where `onUrlChanged` calls `setCurrentPage(url, title)` and `onPageStarted` subsequently calls `setCurrentPage(url, title)` again with the same URL, the second call SHALL be a no-op (the `if (!urlChanged) return` guard fires), producing no duplicate extraction job.

**Validates: Requirements 2.9, 3.8**

---

## Fix Implementation

### Fix 1a — Correct Named Capture Group Syntax

**File:** `app/src/main/java/com/cognitivechaos/xdownload/service/SiteExtractors.kt`

**Function:** `extractSpankbang`

**Current code (lines ~590–592):**
```kotlin
val regex = Regex("""stream_url_(?P<id>[^\s=]+)\s*=\s*["'](?P<url>https?://[^"']+)["']""")
for (match in regex.findAll(html)) {
    val formatId = match.groups["id"]?.value?.trim() ?: continue
    val url = match.groups["url"]?.value?.trim() ?: continue
```

**Fixed code:**
```kotlin
val regex = Regex("""stream_url_(?<id>[^\s=]+)\s*=\s*["'](?<url>https?://[^"']+)["']""")
for (match in regex.findAll(html)) {
    val formatId = match.groups["id"]?.value?.trim() ?: continue
    val url = match.groups["url"]?.value?.trim() ?: continue
```

**Change:** Remove `P` from `(?P<id>...)` → `(?<id>...)` and `(?P<url>...)` → `(?<url>...)`. The named group references `match.groups["id"]` and `match.groups["url"]` are already correct Kotlin syntax and require no change.

---

### Fix 1b — Add SpankBang API Fallback

**File:** `app/src/main/java/com/cognitivechaos/xdownload/service/SiteExtractors.kt`

**Function:** `extractSpankbang`

After the existing `stream_url_*` loop, add a fallback block that runs only when `options` is still empty:

```kotlin
// Fallback: data-streamkey + POST /api/videos/stream (mirrors yt-dlp spankbang.py)
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
```

**Key details:**
- Uses `okhttp3.FormBody` for the POST body (`id=<streamkey>&data=0`).
- Adds `X-Requested-With: XMLHttpRequest` header (required by SpankBang API).
- Handles the case where a format value is a JSON array (take index 0), matching yt-dlp's `if isinstance(format_url, list): format_url = format_url[0]`.
- Applies the same quality-label logic as the primary path.
- Skips HLS/MPD URLs.
- Only runs when `options.isEmpty()` after the primary loop — satisfies requirement 3.9.

---

### Fix 2a — Correct RedTube mediaDefinition API Follow-Through

**File:** `app/src/main/java/com/cognitivechaos/xdownload/service/SiteExtractors.kt`

**Function:** `extractRedtube`

**Current fallback block (inside `if (options.isEmpty())`):**
```kotlin
for (i in 0 until arr.length()) {
    val obj = arr.optJSONObject(i) ?: continue
    val url = obj.optString("videoUrl", "")
    if (url.isBlank() || !url.startsWith("http")) continue
    val format = obj.optString("format", "").lowercase()
    if (format == "hls") continue
    val quality = obj.optString("quality", "")
    if (!seen.add(url)) continue
    options.add(MediaQualityOption(url = url, quality = if (quality.isNotEmpty()) "${quality}p" else "Default", mimeType = "video/mp4"))
}
```

**Fixed fallback block:**
```kotlin
for (i in 0 until arr.length()) {
    val obj = arr.optJSONObject(i) ?: continue
    val videoUrl = obj.optString("videoUrl", "")
    if (videoUrl.isBlank() || !videoUrl.startsWith("http")) continue
    val format = obj.optString("format", "").lowercase()
    val quality = obj.optString("quality", "")

    // yt-dlp redtube.py: if format='mp4' and quality is empty, videoUrl is an API
    // endpoint that returns a JSON array of direct quality URLs — fetch it.
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

    // Direct entry: format='mp4' with a quality value, or format='hls' (skip HLS)
    if (format == "hls") continue
    if (!seen.add(videoUrl)) continue
    options.add(MediaQualityOption(
        url = videoUrl,
        quality = if (quality.isNotEmpty()) "${quality}p" else "Default",
        mimeType = "video/mp4"
    ))
}
```

**Key details:**
- When `format == "mp4" && quality.isEmpty()`: fetch `videoUrl` as an API endpoint, parse the returned JSON array, skip HLS entries, produce `MediaQualityOption` for each direct MP4 URL.
- After the API fetch block, `continue` skips the direct-URL path for that entry.
- Direct entries with a non-empty `quality` are handled by the existing path at the bottom.
- HLS entries are still skipped.
- The `sources: {...}` primary path is unchanged — the `mediaDefinition` block only runs when `options.isEmpty()`.

---

### Fix 2b — Set currentPageUrl Early in onUrlChanged

**File:** `app/src/main/java/com/cognitivechaos/xdownload/ui/browser/BrowserViewModel.kt`

**Function:** `onUrlChanged`

**Current code:**
```kotlin
fun onUrlChanged(url: String) {
    clearPageError()
    cancelQualityFetch()
    cancelQualityPrefetch()
    _currentUrl.value = url
    videoDetector.clearDetectedMedia()
    updateActiveTab(url = url)
}
```

**Fixed code:**
```kotlin
fun onUrlChanged(url: String) {
    clearPageError()
    cancelQualityFetch()
    cancelQualityPrefetch()
    _currentUrl.value = url
    videoDetector.clearDetectedMedia()
    // Fix 2b: set currentPageUrl before any shouldInterceptRequest calls arrive
    // for this page's sub-resources (which come in on a background thread).
    // setCurrentPage contains an urlChanged guard so the subsequent onPageStarted
    // call with the same URL will be a no-op.
    videoDetector.setCurrentPage(url, _currentTitle.value)
    updateActiveTab(url = url)
}
```

**Key details:**
- `setCurrentPage` is called after `clearDetectedMedia()` — `clearDetectedMedia` does not reset `currentPageUrl`, so the URL-change guard (`if (!urlChanged) return`) in `setCurrentPage` will correctly detect the new URL and launch extraction.
- When `onPageStarted` subsequently calls `setCurrentPage(url, title)` with the same URL, the guard fires and the call is a no-op — no duplicate extraction job (satisfies requirement 3.8).
- `_currentTitle.value` at this point is the title of the previous page, which is acceptable — `setCurrentPage` will be called again from `onTitleChanged` with the correct new title once it is known.

---

## Testing Strategy

### Validation Approach

Two-phase approach: first surface counterexamples on unfixed code to confirm root cause, then verify the fix and preservation.

### Exploratory Bug Condition Checking

**Goal:** Confirm root causes before implementing fixes. Run on UNFIXED code.

**Test Cases:**

1. **Bug 1a — Regex crash**: Instantiate `SiteExtractors` and call `extractSpankbang("https://spankbang.com/56b3d/video/test")` on unfixed code. Assert that `PatternSyntaxException` is thrown. This confirms the named group syntax is the crash cause.

2. **Bug 1b — Empty result on keyless page**: Mock `fetchPage` to return HTML containing only `data-streamkey="abc123"` (no `stream_url_*`). Call `extractSpankbang` on unfixed code. Assert result is empty. This confirms the fallback is missing.

3. **Bug 2a — Empty result from mediaDefinition API URL**: Mock `fetchPage` to return HTML with an empty `sources: {}` and a `mediaDefinition` entry with `format='mp4'`, `quality=''`, `videoUrl='https://www.redtube.com/media?...'`. Call `extractRedtube` on unfixed code. Assert result is empty. This confirms the API follow-through is missing.

4. **Bug 2b — Race condition**: Simulate `onUrlChanged("https://www.redtube.com/123")` on unfixed code, then immediately call `videoDetector.isRedtubePage()`. Assert it returns false (because `setCurrentPage` has not been called yet). This confirms the race window exists.

**Expected Counterexamples:**
- Bug 1a: `PatternSyntaxException` thrown at `Regex(...)` construction.
- Bug 1b: `extractSpankbang` returns empty list when only `data-streamkey` is present.
- Bug 2a: `extractRedtube` returns empty list when `mediaDefinition` has API-URL entries.
- Bug 2b: `isRedtubePage()` returns false immediately after `onUrlChanged`.

### Fix Checking

**Goal:** Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL X WHERE isBugCondition_1a(X) DO
  result := extractSpankbang_fixed(X.url)
  ASSERT no_PatternSyntaxException(result)
END FOR

FOR ALL X WHERE isBugCondition_1b(X) DO
  result := extractSpankbang_fixed(X.url)
  ASSERT result.isNotEmpty()
END FOR

FOR ALL X WHERE isBugCondition_2a(X) DO
  result := extractRedtube_fixed(X.url)
  ASSERT result.isNotEmpty()
END FOR

FOR ALL X WHERE isBugCondition_2b(X) DO
  onUrlChanged_fixed(X.destinationPageUrl)
  ASSERT isRedtubePage() == true
END FOR
```

### Preservation Checking

**Goal:** Verify that for all inputs where the bug condition does NOT hold, the fixed functions produce the same result as the original.

**Pseudocode:**
```
FOR ALL X WHERE NOT isBugCondition_1a(X) AND NOT isBugCondition_1b(X) DO
  ASSERT extractSpankbang_original(X.url) = extractSpankbang_fixed(X.url)
END FOR

FOR ALL X WHERE NOT isBugCondition_2a(X) DO
  ASSERT extractRedtube_original(X.url) = extractRedtube_fixed(X.url)
END FOR

FOR ALL X WHERE NOT isBugCondition_2b(X) DO
  ASSERT onResourceRequest_original(X) = onResourceRequest_fixed(X)
END FOR
```

**Testing Approach:** Property-based testing is recommended for preservation checking because it generates many test cases automatically and catches edge cases that manual unit tests might miss.

**Test Cases:**
1. **SpankBang primary path preservation**: Mock HTML with `stream_url_720p = "https://cdn.spankbang.com/video.mp4"`. Assert fixed code returns the same result as original (no API call made).
2. **RedTube sources path preservation**: Mock HTML with `sources: {"720": "https://..."}`. Assert fixed code returns the same result as original (no `mediaDefinition` fallback attempted).
3. **Other extractor preservation**: Call `extractEporner`, `extractYouporn`, `extractXnxx`, `extractXvideos`, `extractXhamster` on fixed code. Assert results are identical to original.
4. **onUrlChanged no-duplicate-extraction**: Call `onUrlChanged(url)` then `onPageStarted(tabId, url)`. Assert `setCurrentPage` extraction job runs exactly once (second call is no-op due to URL-change guard).

### Unit Tests

- Test `extractSpankbang` with mocked HTML containing `(?<id>...)` regex — assert no exception and correct quality options returned.
- Test `extractSpankbang` with mocked HTML containing only `data-streamkey` — assert API POST is made and quality options returned from mocked JSON response.
- Test `extractSpankbang` with mocked HTML containing both `stream_url_*` and `data-streamkey` — assert only primary path is used (no API call).
- Test `extractRedtube` with mocked HTML where `mediaDefinition` has `format='mp4'`, `quality=''` entry — assert API fetch is made and quality options returned.
- Test `extractRedtube` with mocked HTML where `sources: {...}` is populated — assert `mediaDefinition` fallback is not attempted.
- Test `onUrlChanged` calls `setCurrentPage` before `onPageStarted` fires.
- Test that `onPageStarted` after `onUrlChanged` with same URL does not launch a second extraction job.

### Property-Based Tests

- Generate random SpankBang-format HTML with varying numbers of `stream_url_*` entries — assert all valid MP4 URLs are extracted and no HLS/MPD URLs are included.
- Generate random `mediaDefinition` JSON arrays with mixed `format`/`quality` combinations — assert only direct MP4 entries (non-empty quality) and API-fetched entries produce `MediaQualityOption` items.
- Generate random non-SpankBang, non-RedTube page URLs — assert fixed extractors return the same result as original extractors (preservation property).
- Generate random sequences of `onUrlChanged` + `onPageStarted` calls with the same URL — assert `setCurrentPage` extraction runs exactly once per unique URL.

### Integration Tests

- Navigate to a SpankBang page in the full app — assert no crash and quality options appear in the download sheet.
- Navigate to a RedTube page — assert the real video (not ad pre-roll) appears as the detected media.
- Navigate from a non-RedTube page to a RedTube page rapidly — assert ad pre-roll is suppressed even when resource requests arrive before `onPageStarted`.
- Navigate to Eporner and YouPorn pages after applying fixes — assert extraction still works correctly (regression check).
