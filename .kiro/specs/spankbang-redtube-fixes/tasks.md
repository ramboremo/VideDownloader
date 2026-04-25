# Implementation Plan

- [x] 1. Write bug condition exploration tests (BEFORE implementing any fix)
  - **Property 1: Bug Condition** - SpankBang Regex Crash + Missing Fallback + RedTube Empty Result + Race Window
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **GOAL**: Surface counterexamples that demonstrate each bug exists
  - **Scoped PBT Approach**: Scope each property to the concrete failing case(s) for reproducibility
  - Bug 1a: Instantiate `SiteExtractors` and call `extractSpankbang("https://spankbang.com/56b3d/video/test")` — assert `PatternSyntaxException` is thrown (confirms Python-style `(?P<id>...)` syntax crashes the JVM regex engine)
  - Bug 1b: Mock `fetchPage` to return HTML with only `data-streamkey="abc123"` (no `stream_url_*` variables) — assert `extractSpankbang` returns an empty list (confirms API fallback is missing)
  - Bug 2a: Mock `fetchPage` to return HTML with empty `sources: {}` and a `mediaDefinition` entry with `format='mp4'`, `quality=''`, `videoUrl='https://www.redtube.com/media?...'` — assert `extractRedtube` returns an empty list (confirms API follow-through is missing)
  - Bug 2b: Call `onUrlChanged("https://www.redtube.com/123")` on unfixed code, then immediately check `videoDetector.currentPageUrl` — assert it does NOT equal the RedTube URL (confirms race window exists because `setCurrentPage` is not called in `onUrlChanged`)
  - Run all tests on UNFIXED code
  - **EXPECTED OUTCOME**: All tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples found (e.g., "PatternSyntaxException at Regex(...) construction", "extractSpankbang returns [] for streamkey-only page", "extractRedtube returns [] for API-URL mediaDefinition", "currentPageUrl is stale after onUrlChanged")
  - Mark task complete when tests are written, run, and failures are documented
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 1.6, 1.10_

- [x] 2. Write preservation property tests (BEFORE implementing any fix)
  - **Property 2: Preservation** - Non-SpankBang/Non-RedTube Extractor Behavior + SpankBang Primary Path + RedTube Sources Path + No Duplicate Extraction
  - **IMPORTANT**: Follow observation-first methodology — run UNFIXED code with non-buggy inputs and record actual outputs before writing assertions
  - Observe: `extractSpankbang` with HTML containing `stream_url_720p = "https://cdn.spankbang.com/video.mp4"` returns a non-empty list with quality "720p" on unfixed code
  - Observe: `extractRedtube` with HTML containing `sources: {"720": "https://cdn.redtube.com/video.mp4"}` returns a non-empty list on unfixed code
  - Observe: `extractEporner`, `extractYouporn`, `extractXnxx`, `extractXvideos`, `extractXhamster` each return their expected results on unfixed code
  - Observe: calling `onUrlChanged(url)` then `onPageStarted(tabId, url)` results in `setCurrentPage` being called (at least once) on unfixed code
  - Write property-based test: for all SpankBang HTML inputs containing at least one `stream_url_*` variable, fixed code returns the same non-empty result as unfixed code (primary path preserved, no API call made)
  - Write property-based test: for all RedTube HTML inputs where `sources: {...}` is non-empty, fixed code returns the same result as unfixed code (sources path preserved, mediaDefinition fallback not attempted)
  - Write property-based test: for all non-SpankBang, non-RedTube page URLs, all extractor functions return identical results on fixed vs unfixed code
  - Write test: `onUrlChanged(url)` followed by `onPageStarted(tabId, url)` triggers extraction exactly once (second `setCurrentPage` call is a no-op due to URL-change guard)
  - Run all tests on UNFIXED code
  - **EXPECTED OUTCOME**: All tests PASS (confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9_

- [x] 3. Apply all four fixes

  - [x] 3.1 Fix 1a — correct named capture group syntax in `extractSpankbang`
    - In `SiteExtractors.kt` `extractSpankbang`, change `(?P<id>...)` → `(?<id>...)` and `(?P<url>...)` → `(?<url>...)` in the `stream_url_*` regex
    - The named group references `match.groups["id"]` and `match.groups["url"]` are already correct Kotlin syntax — no change needed there
    - _Bug_Condition: isBugCondition_Bug1(X) where isSpankbangPage(X.url) is true_
    - _Expected_Behavior: extractSpankbang compiles regex without PatternSyntaxException and returns quality options from stream_url_* variables_
    - _Preservation: SpankBang primary path result must be identical before and after fix; all other extractors untouched_
    - _Requirements: 2.1, 2.2_

  - [x] 3.2 Fix 1b — add SpankBang API fallback after the `stream_url_*` loop
    - In `SiteExtractors.kt` `extractSpankbang`, after the existing `stream_url_*` loop, add a block guarded by `if (options.isEmpty())`
    - Extract `data-streamkey` from HTML using `Regex("""data-streamkey\s*=\s*["'](?<value>[^"']+)["']""")`
    - POST to `https://spankbang.com/api/videos/stream` with `FormBody` `id=<streamkey>&data=0` and headers `User-Agent`, `Referer`, `X-Requested-With: XMLHttpRequest`
    - Parse JSON response: keys = formatId, values = URL string or JSONArray (take index 0 if array)
    - Apply same quality-label logic as primary path; skip HLS/MPD URLs; deduplicate via `seen` set
    - Wrap in `try/catch (e: Exception)` and log on failure
    - _Bug_Condition: isBugCondition_1b(X) where isSpankbangPage(X.url) AND no stream_url_* in HTML AND data-streamkey present_
    - _Expected_Behavior: extractSpankbang returns non-empty MediaQualityOption list via API fallback_
    - _Preservation: fallback block only runs when options.isEmpty() — primary path result is never discarded (Requirement 3.9)_
    - _Requirements: 2.10, 2.11, 3.9_

  - [x] 3.3 Fix 2a — correct RedTube `mediaDefinition` API follow-through
    - In `SiteExtractors.kt` `extractRedtube`, inside the `mediaDefinition` fallback loop, add a branch: when `format == "mp4" && quality.isEmpty()`, treat `videoUrl` as an API endpoint
    - Fetch `videoUrl` with `User-Agent` and `Referer` headers; parse returned JSON array
    - For each item in the array: skip HLS entries, skip blank/non-HTTP URLs, deduplicate via `seen`, produce `MediaQualityOption` with quality label from item's `quality` field
    - After the API fetch block, add `continue` to skip the direct-URL path for that entry
    - Direct entries with non-empty `quality` fall through to the existing path at the bottom of the loop
    - Wrap API fetch in `try/catch (e: Exception)` and log on failure
    - _Bug_Condition: isBugCondition_2a(X) where isRedtubePage(X.url) AND sources:{} empty AND mediaDefinition has format='mp4' quality='' entry_
    - _Expected_Behavior: extractRedtube fetches the API URL and returns non-empty MediaQualityOption list for direct MP4 URLs_
    - _Preservation: sources:{} primary path unchanged; mediaDefinition fallback only runs when options.isEmpty()_
    - _Requirements: 2.3, 2.4, 2.5, 2.6_

  - [x] 3.4 Fix 2b — call `setCurrentPage` early in `onUrlChanged`
    - In `BrowserViewModel.kt` `onUrlChanged`, add `videoDetector.setCurrentPage(url, _currentTitle.value)` immediately after `videoDetector.clearDetectedMedia()`
    - `clearDetectedMedia` does NOT reset `currentPageUrl`, so the URL-change guard in `setCurrentPage` will correctly detect the new URL and launch extraction
    - `_currentTitle.value` at this point is the previous page's title — this is acceptable; `setCurrentPage` will be called again from `onTitleChanged` with the correct title once known
    - _Bug_Condition: isBugCondition_Bug2(X) where isGoldStandardPage(X.destinationPageUrl) AND currentPageUrl not yet updated AND resource request arrives before onPageStarted_
    - _Expected_Behavior: currentPageUrl reflects destination URL before any shouldInterceptRequest calls arrive; isRedtubePage() returns true; ad pre-roll suppressed_
    - _Preservation: subsequent onPageStarted call with same URL is a no-op (urlChanged guard fires); no duplicate extraction job (Requirement 3.8)_
    - _Requirements: 2.7, 2.8, 2.9, 3.8_

  - [x] 3.5 Verify bug condition exploration tests now pass
    - **Property 1: Expected Behavior** - SpankBang Regex Compiles + API Fallback Works + RedTube API Follow-Through + Race Window Closed
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - The tests from task 1 encode the expected behavior; when they pass, the bugs are fixed
    - Run all four exploration tests from step 1 on FIXED code
    - **EXPECTED OUTCOME**: All tests PASS (confirms all four bugs are fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.7, 2.8, 2.10, 2.11_

  - [x] 3.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Non-SpankBang/Non-RedTube Extractor Behavior + SpankBang Primary Path + RedTube Sources Path + No Duplicate Extraction
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run all preservation property tests from step 2 on FIXED code
    - **EXPECTED OUTCOME**: All tests PASS (confirms no regressions)
    - Confirm SpankBang primary path still works (no API call when `stream_url_*` present)
    - Confirm RedTube sources path still works (no `mediaDefinition` fallback when `sources:{}` populated)
    - Confirm all other extractors (Eporner, YouPorn, XNXX, XVideos, XHamster) return identical results
    - Confirm `onUrlChanged` + `onPageStarted` with same URL triggers extraction exactly once

- [x] 4. Checkpoint — ensure all tests pass
  - Run the full test suite and confirm all tests pass
  - Ensure all four exploration tests pass (bugs confirmed fixed)
  - Ensure all preservation tests pass (no regressions)
  - Ask the user if any questions arise
