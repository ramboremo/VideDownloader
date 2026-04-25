# Gold-Standard Site Extractors

Sites with dedicated extraction logic — generic WebView interception is fully suppressed on these pages.

## Active Gold-Standard Sites

| Site | Method | Technique | Status |
|------|--------|-----------|--------|
| **Pornhub** | API interception | Intercepts `/video/get_media` API call the player makes → JSON with direct MP4 URLs per quality | ✅ Working |
| **XNXX** | Page fetch + regex | Fetches page HTML, regex for `setVideoUrlHigh/Low/HLS(...)` JS calls | ✅ Working |
| **XVideos** | Page fetch + regex | Same `setVideo*(...)` pattern + `flv_url=` fallback | ✅ Working |
| **RedTube** | Page fetch + JSON | Fetches page HTML, extracts `sources: {quality: url}` JSON object | ✅ Implemented |
| **SpankBang** | Page fetch + regex | Fetches page HTML, regex for `stream_url_720p = "..."` variables | ✅ Implemented |
| **YouPorn** | Page fetch + API | Fetches page HTML → extracts `playervars.mediaDefinitions` → follows mp4 API URL | ✅ Implemented |
| **Eporner** | Page fetch + API | Fetches page HTML → extracts hash → computes derived hash → hits `/xhr/video/{id}` API | ✅ Implemented |
| **XHamster** | Page fetch + cipher | Fetches page HTML → extracts `window.initials` → parses `xplayerSettings.sources.standard` → XOR cipher decode | ⚠️ Implemented but unreliable (cipher may break) |

## Domain Patterns Covered

| Site | Domains Matched |
|------|----------------|
| Pornhub | `pornhub.com`, `pornhub.net`, `pornhub.org` |
| XNXX | `xnxx.com`, `xnxx3.com`, `video.xnxx.com`, `www.xnxx3.com` |
| XVideos | `xvideos.com`, `xvideos2.com`, `fr.xvideos.com`, `de.xvideos.com`, `xvideos.es`, etc. |
| RedTube | `redtube.com`, `www.redtube.com`, `it.redtube.com`, `redtube.com.br` |
| SpankBang | `spankbang.com`, `m.spankbang.com` |
| YouPorn | `youporn.com`, `www.youporn.com` |
| Eporner | `eporner.com`, `www.eporner.com` |
| XHamster | `xhamster.com`, `xhamster.one`, `xhamster.desi`, `xhms.pro`, `xhamster{N}.com`, `xhamster{N}.desi`, `xhday.com`, `xhvid.com` |

## Sites Skipped

| Site | Reason |
|------|--------|
| **Tube8** | Site is dead — `_WORKING = False` in yt-dlp |

## How to Add a New Site

1. Check yt-dlp's extractor for the site: `yt-dlp-ref/yt_dlp/extractor/{site}.py`
2. Identify the technique (page HTML regex, embedded JSON, API call)
3. Add `extract{Site}()` to `SiteExtractors.kt`
4. Add `is{Site}Page()` and `is{Site}VideoPage()` helpers to `VideoDetector.kt`
5. Add the extraction branch to `setCurrentPage()` in `VideoDetector.kt`
6. Add suppression to `onResourceRequest()` in `VideoDetector.kt`
7. Update this file
