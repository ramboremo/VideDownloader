# Requirements Document

## Introduction

This feature adds "gold standard" site-specific video extraction for XNXX, XVideos, and XHamster — matching the quality and reliability of the existing Pornhub (PH) implementation in `VideoDetector.kt`. A hybrid approach is used:

- **XNXX & XVideos**: Proactive page-fetch via OkHttp on `Dispatchers.IO` the moment the user navigates to a video page. The HTML is regex-scanned for `setVideoUrlHigh/Low/HLS` JS calls. Results are cached before the user taps download — proactive, not reactive.
- **XHamster**: WebView interception. XHamster's own JS deciphers obfuscated URLs and makes CDN requests that pass through `shouldInterceptRequest`. The existing interception path is improved to ensure XHamster's CDN domain (`xhcdn.com`) is not filtered by `IGNORE_PATTERNS` and that CDN video URLs are correctly detected and quality-labelled.

All XNXX and XVideos extraction logic lives in a new `SiteExtractors.kt` file. XHamster is handled via targeted improvements to `VideoDetector.onResourceRequest`. The PH implementation is unchanged.

## Glossary

- **VideoDetector**: The existing singleton service (`VideoDetector.kt`) that detects downloadable media as the user browses. It is the single source of truth for detected media state.
- **SiteExtractors**: The new Kotlin file (`SiteExtractors.kt`) that houses site-specific extraction logic for XNXX and XVideos.
- **XnxxExtractor**: The component within `SiteExtractors.kt` responsible for extracting video URLs from XNXX video pages.
- **XvideosExtractor**: The component within `SiteExtractors.kt` responsible for extracting video URLs from XVideos video pages.
- **XhamsterExtractor**: The logic within `VideoDetector.onResourceRequest` responsible for detecting XHamster CDN video URLs via WebView interception.
- **Page_Fetch**: An OkHttp GET request for a video page's HTML, sent with the WebView's current cookies and a matching `User-Agent` and `Referer` header.
- **setVideo_Pattern**: The JavaScript function-call pattern `setVideo*(...)` embedded in XNXX and XVideos page HTML that contains direct video URLs. Specifically: `setVideoUrlHigh(...)`, `setVideoUrlLow(...)`, and `setVideoHLS(...)`.
- **XHamster_CDN_URL**: A video CDN URL served from `xhcdn.com` that XHamster's own JavaScript deciphers and requests. These URLs pass through `shouldInterceptRequest` and are the target of XHamster interception.
- **MediaQualityOption**: The existing data model representing a single downloadable video URL with quality label, resolution, file size, and MIME type.
- **DetectedMedia**: The existing data model representing a detected video on the current page, stored in `VideoDetector`'s state flow.
- **setCurrentPage**: The existing method on `VideoDetector` called by `BrowserViewModel` whenever the WebView navigates to a new URL.
- **prefetchedOptions**: The existing in-memory cache in `VideoDetector` that maps a source URL to a pre-fetched list of `MediaQualityOption` objects.
- **OkHttpClient**: The shared HTTP client injected into `VideoDetector` (and to be injected into `SiteExtractors`) for making network requests.

---

## Requirements

### Requirement 1: Preserve Existing Pornhub Implementation

**User Story:** As a developer, I want the existing PH extraction to remain completely unchanged, so that users who rely on PH downloads are not affected by this feature.

#### Acceptance Criteria

1. THE `VideoDetector` SHALL continue to intercept PH `/video/get_media` API requests via `shouldInterceptRequest` exactly as it does today, with no changes to the interception logic, header construction, JSON parsing, or pre-fetch flow.
2. THE `VideoDetector` SHALL continue to expose `parsePornhubGetMedia`, `addPornhubHeaders`, and all PH-related companion object constants without modification.
3. WHEN the `SiteExtractors` component is introduced, THE `VideoDetector` SHALL NOT have any of its existing PH-related code paths removed or altered.
4. WHEN `isPornhubPage()` returns true, THE `VideoDetector.onResourceRequest` SHALL return false immediately after the `get_media` check — no generic WebView interception detection SHALL run on PH pages. This fixes the existing bug where PH CDN `.mp4` URLs leak through generic detection and is considered part of the PH gold-standard implementation.
5. WHEN `isPornhubPage()` returns true, THE suppression described in criterion 4 SHALL be active from the very first resource request on that page — not only after a `get_media` URL has been observed — so that CDN `.mp4` URLs that load before `get_media` fires cannot slip through.

---

### Requirement 2: Site Detection on Navigation

**User Story:** As a user, I want the app to automatically detect when I'm on a video page for XNXX or XVideos, so that extraction starts without me having to do anything.

#### Acceptance Criteria

1. WHEN `setCurrentPage(url, title)` is called on `VideoDetector` with a URL whose host matches the regex `(?:video|www)\.xnxx3?\.com` and whose path indicates a video page (e.g., `/video-`), THE `VideoDetector` SHALL trigger background extraction via `XnxxExtractor`. This pattern MUST match both `xnxx.com` and the mirror `xnxx3.com`.
2. WHEN `setCurrentPage(url, title)` is called on `VideoDetector` with a URL whose host matches the regex `(?:[^.]+\.)?xvideos2?\.com` or `(?:www\.)?xvideos\.es` and whose path indicates a video page (e.g., `/video`), THE `VideoDetector` SHALL trigger background extraction via `XvideosExtractor`. This pattern MUST match subdomain variants such as `fr.xvideos.com` and `de.xvideos.com`, the mirror `xvideos2.com`, and the `.es` TLD variant.
3. WHEN `setCurrentPage` is called with a URL that does not match any supported site-specific pattern, THE `VideoDetector` SHALL NOT trigger any site-specific extractor.
4. WHEN `setCurrentPage` is called with a URL matching a supported site but the URL is not a video page (e.g., a category or search page), THE `VideoDetector` SHALL NOT trigger extraction for that URL.
5. WHEN `clearDetectedMedia()` is called, THE `VideoDetector` SHALL cancel any in-progress site-specific extraction coroutines for the previous page.
6. WHEN `setCurrentPage` is called with the same URL as `currentPageUrl` and extraction for that URL has already completed or is already in progress, THE `VideoDetector` SHALL NOT trigger a duplicate extraction.
7. THE site detection regex patterns for XNXX, XVideos, and XHamster SHALL be derived from yt-dlp's canonical extractor patterns so that all known domain variants are covered, including numbered mirror variants (e.g., `xhamster11.desi`, `xhamster19.com`).

---

### Requirement 3: XNXX Page-Fetch Extraction

**User Story:** As a user browsing XNXX, I want the app to extract direct MP4 download URLs from the video page, so that I can download the video at my preferred quality.

#### Acceptance Criteria

1. WHEN extraction is triggered for an XNXX video page, THE `XnxxExtractor` SHALL perform a `Page_Fetch` of the video page URL using the WebView's current cookies, a desktop `User-Agent`, and the page URL as `Referer`.
2. WHEN the page HTML is fetched successfully, THE `XnxxExtractor` SHALL scan the HTML for all occurrences of the `setVideo_Pattern` using the regex `setVideo(?:Url(?:Low|High)|HLS)\s*\(\s*(?:["'])(?<url>(?:https?:)?//.+?)(?:["'])`.
3. WHEN a `setVideoUrlHigh` match is found, THE `XnxxExtractor` SHALL produce a `MediaQualityOption` with quality label `"High"` and the extracted MP4 URL.
4. WHEN a `setVideoUrlLow` match is found, THE `XnxxExtractor` SHALL produce a `MediaQualityOption` with quality label `"Low"` and the extracted MP4 URL.
5. WHEN a `setVideoHLS` match is found, THE `XnxxExtractor` SHALL produce a `MediaQualityOption` with quality label `"HLS"`, MIME type `"application/x-mpegurl"`, and the extracted m3u8 URL.
6. IF the page fetch returns a non-2xx HTTP status code, THEN THE `XnxxExtractor` SHALL log the error and return an empty list without crashing.
7. IF no `setVideo_Pattern` matches are found in the page HTML, THEN THE `XnxxExtractor` SHALL return an empty list.
8. THE `XnxxExtractor` SHALL deduplicate extracted URLs so that the same URL does not appear more than once in the result list.

---

### Requirement 4: XVideos Page-Fetch Extraction

**User Story:** As a user browsing XVideos, I want the app to extract direct MP4 download URLs from the video page, so that I can download the video at my preferred quality.

#### Acceptance Criteria

1. WHEN extraction is triggered for an XVideos video page, THE `XvideosExtractor` SHALL perform a `Page_Fetch` of the video page URL using the WebView's current cookies, a desktop `User-Agent`, and the page URL as `Referer`.
2. WHEN the page HTML is fetched successfully, THE `XvideosExtractor` SHALL scan the HTML for all occurrences of the `setVideo_Pattern` using the regex `setVideo([^(]+)\((["\'])(http.+?)\2\)`.
3. WHEN a `setVideoUrlHigh` match is found, THE `XvideosExtractor` SHALL produce a `MediaQualityOption` with quality label `"High"` and the extracted MP4 URL.
4. WHEN a `setVideoUrlLow` match is found, THE `XvideosExtractor` SHALL produce a `MediaQualityOption` with quality label `"Low"` and the extracted MP4 URL.
5. WHEN a `setVideoHLS` match is found, THE `XvideosExtractor` SHALL produce a `MediaQualityOption` with quality label `"HLS"`, MIME type `"application/x-mpegurl"`, and the extracted m3u8 URL.
6. WHEN a `flv_url=` query parameter is present in the page HTML, THE `XvideosExtractor` SHALL extract and URL-decode the value and produce a `MediaQualityOption` with quality label `"FLV"`.
7. IF the page fetch returns a non-2xx HTTP status code, THEN THE `XvideosExtractor` SHALL log the error and return an empty list without crashing.
8. IF no `setVideo_Pattern` matches and no `flv_url` are found in the page HTML, THEN THE `XvideosExtractor` SHALL return an empty list.
9. THE `XvideosExtractor` SHALL deduplicate extracted URLs so that the same URL does not appear more than once in the result list.

---

### Requirement 5: XHamster CDN Interception

**User Story:** As a user browsing XHamster, I want the app to detect video CDN URLs that XHamster's own JavaScript resolves and requests, so that I can download the video without the app needing to decipher obfuscated URLs itself.

#### Acceptance Criteria

1. WHEN `onResourceRequest` is called with a URL whose host contains `xhcdn.com`, THE `VideoDetector` SHALL NOT skip it due to `IGNORE_PATTERNS` matching.
2. WHEN a `xhcdn.com` URL is intercepted and its path or query string indicates it is a video file (e.g., contains a video file extension or matches known XHamster CDN video path patterns), THE `VideoDetector` SHALL treat it as a detected media URL and add a `DetectedMedia` entry.
3. WHEN a `xhcdn.com` video URL is intercepted, THE `VideoDetector` SHALL attempt to parse a quality label from the URL path (e.g., a segment containing `1080p`, `720p`, `480p`, `360p`, or similar resolution indicators).
4. IF no quality label can be parsed from the `xhcdn.com` URL, THEN THE `VideoDetector` SHALL assign the quality label `"Default"`.
5. WHEN multiple `xhcdn.com` video URLs are intercepted for the same XHamster page, THE `VideoDetector` SHALL deduplicate them so the same URL is not added to `_detectedMedia` more than once.
6. WHEN a `xhcdn.com` video URL is detected, THE `VideoDetector` SHALL pre-fetch its file size in the background on `Dispatchers.IO` without blocking the main thread.

---

### Requirement 6: Generic Detection Suppression for Gold-Standard Sites

**User Story:** As a user, I want the download list to contain only real, high-quality video URLs on sites that have a gold-standard extractor, so that ad pre-rolls, preview clips, thumbnail CDN requests, and HLS segments never appear alongside the actual download.

#### Acceptance Criteria

1. WHEN `onResourceRequest` is called and `isPornhubPage()` is true, THE `VideoDetector` SHALL return false immediately after the `get_media` check — no generic media detection logic SHALL execute for any other URL on that page.
2. WHEN `onResourceRequest` is called and the current page URL's host matches `(?:video|www)\.xnxx3?\.com`, THE `VideoDetector` SHALL return false immediately — no generic media detection logic SHALL execute on XNXX pages.
3. WHEN `onResourceRequest` is called and the current page URL's host matches `(?:[^.]+\.)?xvideos2?\.com` or `(?:www\.)?xvideos\.es`, THE `VideoDetector` SHALL return false immediately — no generic media detection logic SHALL execute on XVideos pages.
4. WHEN `onResourceRequest` is called and the current page URL's host matches `(?:[^.]+\.)?(?:xhamster\.(?:com|one|desi)|xhms\.pro|xhamster\d+\.(?:com|desi)|xhday\.com|xhvid\.com)`, THE `VideoDetector` SHALL process only URLs whose host contains `xhcdn.com` AND whose path or MIME type indicates an actual video file (extension `.mp4` or `.m3u8`, or a MIME type starting with `video/` or `application/x-mpegurl`). All other URLs on XHamster pages SHALL be ignored.
5. WHEN `onResourceRequest` is called and the current page URL does not match any gold-standard site pattern, THE `VideoDetector` SHALL run generic detection exactly as before — no change to existing behaviour.
6. WHEN a user navigates to a non-video page of a gold-standard site (e.g., homepage, search, category), THE suppression rules in criteria 1–4 SHALL still apply — no generic detection runs, no `DetectedMedia` entries are added, and the FAB remains hidden. This is the correct and expected outcome.
7. IF a gold-standard extractor fails silently (network error, HTTP 403, bot detection), THE `VideoDetector` SHALL still suppress generic detection — the user sees zero detections rather than garbage URLs. The failure SHALL be logged internally and SHALL NOT surface an error to the user.
8. WHEN `setCurrentPage` is called with a gold-standard site URL, THE suppression SHALL be active from the very first subsequent `onResourceRequest` call — not only after the gold extractor has fired.
9. WHEN a `xhcdn.com` URL is intercepted on an XHamster page and its path or MIME type indicates an image or thumbnail (e.g., extension `.jpg`, `.png`, `.gif`, `.webp`, or MIME type starting with `image/`), THE `VideoDetector` SHALL NOT treat it as a media URL.
10. IF a page-fetch coroutine for a gold-standard site completes after `clearDetectedMedia()` has been called (i.e., the user has navigated away), THE `VideoDetector` SHALL discard the results and SHALL NOT add any `DetectedMedia` entries. The page URL at coroutine completion time MUST match `currentPageUrl` at the time extraction was triggered.

---

### Requirement 7: Background Pre-fetch and Instant Quality Picker

**User Story:** As a user, I want the quality picker to appear instantly when I tap the download button, so that I don't have to wait for network requests after tapping.

#### Acceptance Criteria

1. WHEN site-specific extraction is triggered by `setCurrentPage`, THE `VideoDetector` SHALL launch the extraction in a background coroutine on `Dispatchers.IO` without blocking the main thread.
2. WHEN a site-specific extractor returns one or more `MediaQualityOption` entries, THE `VideoDetector` SHALL store them in `prefetchedOptions` keyed by the video page URL.
3. WHEN `fetchQualityOptions` is called for a `DetectedMedia` whose source page URL has a cached entry in `prefetchedOptions`, THE `VideoDetector` SHALL return the cached options immediately without making additional network requests.
4. WHEN site-specific extraction produces multiple quality options, THE `VideoDetector` SHALL fetch file sizes for all options concurrently using `async`/`awaitAll`, with a per-URL timeout of 5000ms.
5. WHEN a file size fetch times out or fails for a specific quality option, THE `VideoDetector` SHALL still include that option in the result with a `null` file size rather than dropping it.
6. WHEN `clearDetectedMedia()` is called, THE `VideoDetector` SHALL clear all site-specific pre-fetched options from `prefetchedOptions`.

---

### Requirement 8: DetectedMedia Registration

**User Story:** As a user, I want the download FAB to become active when I'm on a supported video page, so that I know a download is available.

#### Acceptance Criteria

1. WHEN a site-specific extractor successfully returns at least one `MediaQualityOption`, THE `VideoDetector` SHALL add a `DetectedMedia` entry to `_detectedMedia` with the video page URL, the current page title, the current page thumbnail, and `mimeType = "video/mp4"`.
2. WHEN a `DetectedMedia` entry is added for a site-specific extraction result, THE `VideoDetector` SHALL set `_hasMedia` to `true`.
3. WHEN a site-specific extractor returns an empty list (extraction failed or no URLs found), THE `VideoDetector` SHALL NOT add a `DetectedMedia` entry for that page.
4. THE `VideoDetector` SHALL NOT add duplicate `DetectedMedia` entries if `setCurrentPage` is called multiple times for the same URL before `clearDetectedMedia` is called.

---

### Requirement 9: Code Organisation

**User Story:** As a developer, I want the site-specific extraction logic to be isolated from `VideoDetector.kt`, so that the file remains maintainable and each extractor can be developed and tested independently.

#### Acceptance Criteria

1. THE `SiteExtractors` SHALL be implemented in a new file `app/src/main/java/com/cognitivechaos/xdownload/service/SiteExtractors.kt`.
2. THE `VideoDetector` SHALL delegate XNXX and XVideos extraction to `SiteExtractors` via a clean interface, with no XNXX- or XVideos-specific regex logic inside `VideoDetector.kt`.
3. THE `SiteExtractors` SHALL accept an `OkHttpClient` instance and a cookie-provider lambda (or equivalent) as constructor parameters so it can be injected and tested independently.
4. XHamster detection SHALL be handled entirely within `VideoDetector.onResourceRequest` — no XHamster-specific logic SHALL be added to `SiteExtractors.kt`.
5. THE `VideoDetector.kt` file SHALL NOT be modified in any way that changes the behaviour of the existing PH extraction path.

---

### Requirement 10: HTTP Request Headers

**User Story:** As a developer, I want page-fetch requests to include the correct headers, so that XNXX and XVideos serve the full video page HTML rather than a bot-detection page or redirect.

#### Acceptance Criteria

1. WHEN performing a `Page_Fetch` for XNXX or XVideos, THE `SiteExtractors` SHALL include a `Cookie` header populated from `android.webkit.CookieManager` for the target URL, falling back to the current page URL if the direct lookup returns empty.
2. WHEN performing a `Page_Fetch`, THE `SiteExtractors` SHALL include a `User-Agent` header matching a recent desktop Chrome browser string (e.g., `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36`).
3. WHEN performing a `Page_Fetch`, THE `SiteExtractors` SHALL include a `Referer` header set to the video page URL being fetched.
4. WHEN performing a `Page_Fetch`, THE `SiteExtractors` SHALL include an `Accept-Language` header set to `en-US,en;q=0.9` to request English-language pages.
