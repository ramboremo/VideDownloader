# Bugfix Requirements Document

## Introduction

Two bugs were discovered during testing of the site-specific extractors feature. SpankBang crashes immediately on page open due to a Python-style named capture group syntax in a Kotlin regex. RedTube detects the ad pre-roll video instead of the actual video due to two compounding issues: (1) a race condition where `shouldInterceptRequest` fires before `currentPageUrl` is updated, and (2) the `extractRedtube` implementation is incomplete — it doesn't follow `mediaDefinition` API URLs the way yt-dlp does, so it returns empty and never registers the real video.

Both bugs affect only SpankBang and RedTube; Eporner and YouPorn are confirmed working.

---

## Bug Analysis

### Current Behavior (Defect)

**Bug 1 — SpankBang: crash on page open**

1.1 WHEN the user navigates to any SpankBang URL THEN the system crashes with a `PatternSyntaxException` because `extractSpankbang` compiles a regex containing Python-style named capture groups `(?P<id>...)` and `(?P<url>...)`, which are invalid in Kotlin/Java regex.

1.2 WHEN `isSpankbangPage()` returns true and `setCurrentPage` launches the extraction coroutine THEN the system throws `PatternSyntaxException` at the point `Regex(...)` is evaluated, before any HTML is fetched. Because `PatternSyntaxException` extends `Error` (not `Exception`), it is not caught by any `try/catch (e: Exception)` block and propagates as an uncaught exception.

**Bug 2 — RedTube: ad pre-roll detected instead of actual video (two compounding causes)**

**Cause A — Incomplete extractor (primary cause):**

1.3 The current `extractRedtube` implementation does not correctly follow yt-dlp's technique. According to yt-dlp's `redtube.py`, when a `mediaDefinition` entry has `format='mp4'` and no `quality` field, the `videoUrl` is NOT a direct video URL — it is an **API endpoint** that must be fetched to get the actual list of quality URLs. Our implementation treats all `mediaDefinition` entries as direct URLs and skips those without a quality field, so it returns an empty list for most RedTube pages.

1.4 WHEN `extractRedtube` returns an empty list THEN `registerSiteExtractorResult` is a no-op, no `DetectedMedia` entry is added for the page, and the gold-standard suppression in `onResourceRequest` still fires (suppressing generic detection). The result is zero detections — but the user reported the ad was detected, which means the race condition (Cause B) is also present.

**Cause B — Race condition (secondary cause):**

1.5 `shouldInterceptRequest` is called by the WebView on a **background thread**. `onPageStarted` is called on the **main thread**. These two callbacks are not synchronised, so resource requests for a new page can arrive on the background thread before `onPageStarted` has had a chance to call `setCurrentPage` on the main thread.

1.6 WHEN the WebView begins navigating to a RedTube page THEN sub-resource requests (including the ad pre-roll CDN request) may arrive in `onResourceRequest` while `currentPageUrl` still holds the previous page's URL.

1.7 WHEN `onResourceRequest` is called and `currentPageUrl` does not yet contain a RedTube host THEN `isRedtubePage()` evaluates to false, the gold-standard suppression block is bypassed, and the ad pre-roll video URL is registered as a detected media item.

1.8 The ad pre-roll URL is served from a third-party CDN (e.g., TrafficJunky, ExoClick) — NOT from `redtube.com`. Checking the intercepted resource URL's own host against gold-standard patterns would therefore NOT suppress the ad. The suppression must be based on the destination page URL, not the resource URL.

1.9 The race condition fix must close the window by ensuring `currentPageUrl` is set to the RedTube URL **before** any resource requests for that page can arrive. The earliest reliable point is `BrowserViewModel.onUrlChanged()`, which is called when the WebView begins navigating (before sub-resources load), rather than waiting for `onPageStarted`.

**SpankBang — additional fallback missing:**

1.10 yt-dlp's `spankbang.py` shows that `stream_url_*` variables are the primary extraction method, but when they are absent (e.g., newer page layouts), yt-dlp falls back to extracting a `data-streamkey` attribute and hitting the `/api/videos/stream` POST endpoint. Our current implementation has no fallback, so it silently returns empty on pages that don't embed `stream_url_*` variables.

---

### Expected Behavior (Correct)

**Bug 1 — SpankBang: crash on page open**

2.1 WHEN the user navigates to any SpankBang URL THEN the system SHALL compile the `extractSpankbang` regex without error, using Kotlin/Java named capture group syntax `(?<id>...)` and `(?<url>...)`.

2.2 WHEN `isSpankbangPage()` returns true and `setCurrentPage` launches the extraction coroutine THEN the system SHALL fetch the page HTML and attempt to match stream URL variables without throwing any exception.

**Bug 2 — RedTube: ad pre-roll detected instead of actual video**

**Fix A — Correct the extractor (mirrors yt-dlp `redtube.py`):**

2.3 WHEN `extractRedtube` processes `mediaDefinition` entries THEN it SHALL follow yt-dlp's logic: if an entry has `format='mp4'` and an empty `quality` field, the `videoUrl` is an API endpoint that SHALL be fetched to retrieve the actual list of quality URLs (same pattern as `extractYouporn` already does).

2.4 WHEN the API endpoint is fetched and returns a JSON array THEN `extractRedtube` SHALL parse each entry, skip HLS entries, and produce a `MediaQualityOption` for each direct MP4 URL with the quality label from the entry's `quality` field.

2.5 WHEN the `sources: {...}` primary path succeeds THEN the `mediaDefinition` fallback SHALL NOT be attempted (consistent with current behaviour).

2.6 WHEN the actual RedTube video extraction completes via the corrected `extractRedtube` THEN the system SHALL register only the real video quality options as detected media.

**Fix B — Close the race condition:**

2.7 WHEN `BrowserViewModel.onUrlChanged(url)` is called (i.e., the WebView begins navigating to a new URL) THEN `VideoDetector.setCurrentPage(url, currentTitle)` SHALL be called immediately, so that `currentPageUrl` reflects the destination URL before any `shouldInterceptRequest` calls arrive for that page's sub-resources.

2.8 WHEN the WebView navigates to a RedTube page and ad pre-roll CDN requests arrive before `onPageStarted` fires THEN `isRedtubePage()` SHALL already return true (because `currentPageUrl` was set in `onUrlChanged`), and the gold-standard suppression SHALL block the ad pre-roll from being registered as detected media.

2.9 WHEN `onUrlChanged` calls `clearDetectedMedia()` followed by `setCurrentPage(url, title)` THEN the `setCurrentPage` call SHALL NOT be a no-op — `clearDetectedMedia` does not reset `currentPageUrl`, so the URL-change guard in `setCurrentPage` (`if (!urlChanged) return`) will correctly detect the new URL and launch extraction.

**Fix C — Add SpankBang API fallback (mirrors yt-dlp `spankbang.py`):**

2.10 WHEN `extractSpankbang` finds no `stream_url_*` variables in the page HTML THEN it SHALL fall back to extracting the `data-streamkey` attribute and POSTing to `https://spankbang.com/api/videos/stream` with `id=<streamkey>&data=0` to retrieve format URLs.

2.11 WHEN the stream API returns a JSON object THEN `extractSpankbang` SHALL iterate its keys as format IDs and values as URLs, applying the same quality-label logic as the primary path.

---

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user navigates to an Eporner video page THEN the system SHALL CONTINUE TO extract video URLs via `extractEporner` and present them as download options.

3.2 WHEN the user navigates to a YouPorn video page THEN the system SHALL CONTINUE TO extract video URLs via `extractYouporn` and present them as download options.

3.3 WHEN the user navigates to an XNXX video page THEN the system SHALL CONTINUE TO extract video URLs via `extractXnxx` and present them as download options.

3.4 WHEN the user navigates to an XVideos video page THEN the system SHALL CONTINUE TO extract video URLs via `extractXvideos` and present them as download options.

3.5 WHEN the user navigates to a Pornhub video page THEN the system SHALL CONTINUE TO intercept the `get_media` API call and present the resulting quality options.

3.6 WHEN `onResourceRequest` is called for a URL on a non-gold-standard page THEN the system SHALL CONTINUE TO apply standard media detection logic unchanged.

3.7 WHEN the user navigates to a non-gold-standard site THEN the system SHALL CONTINUE TO detect media via generic WebView resource interception.

3.8 WHEN `onUrlChanged` calls `setCurrentPage` early AND `onPageStarted` subsequently calls `setCurrentPage` again with the same URL THEN the second call SHALL be a no-op (the `if (!urlChanged) return` guard prevents duplicate extraction jobs).

3.9 WHEN `extractSpankbang` successfully finds `stream_url_*` variables THEN it SHALL NOT hit the `/api/videos/stream` fallback endpoint.

---

## Bug Condition Pseudocode

### Bug 1 — SpankBang Regex Crash

```pascal
FUNCTION isBugCondition_Bug1(X)
  INPUT: X of type PageNavigation
  OUTPUT: boolean

  RETURN isSpankbangPage(X.url)
END FUNCTION

// Property: Fix Checking
FOR ALL X WHERE isBugCondition_Bug1(X) DO
  result ← extractSpankbang'(X.url)
  ASSERT no_PatternSyntaxException(result)
END FOR

// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition_Bug1(X) DO
  ASSERT extractSpankbang(X.url) = extractSpankbang'(X.url)
END FOR
```

### Bug 2 — RedTube Race Condition (Early Resource Request)

```pascal
FUNCTION isBugCondition_Bug2(X)
  INPUT: X of type ResourceRequest
  OUTPUT: boolean

  // Bug fires when a resource request arrives for a gold-standard page
  // but currentPageUrl has not yet been updated to that page's URL
  // (i.e., onUrlChanged has not yet called setCurrentPage)
  RETURN isGoldStandardPage(X.destinationPageUrl)
         AND NOT isGoldStandardPage(currentPageUrl)
         AND X arrives before onPageStarted fires
END FUNCTION

// Property: Fix Checking — after fix, onUrlChanged sets currentPageUrl early
FOR ALL X WHERE isBugCondition_Bug2(X) DO
  // After fix: onUrlChanged has already called setCurrentPage(destinationPageUrl)
  result ← onResourceRequest'(X.resourceUrl)
  ASSERT result = false  // suppressed — not registered as detected media
END FOR

// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition_Bug2(X) DO
  ASSERT onResourceRequest(X.resourceUrl) = onResourceRequest'(X.resourceUrl)
END FOR
```
