# Implementation Plan: Smart Video Ranking

## Overview

Introduce a pure `VideoRanker` component, extend `DetectedMedia` with detection signals, wire continuous background ranking into `BrowserViewModel`, inject a JS snippet for DOM signals, and update the Quality Sheet to show the top candidate prominently with an "Other Videos" accordion.

## Tasks

- [x] 1. Extend `DetectedMedia` with ranking signal fields
  - Add `detectionIndex: Int = 0`, `isPlaying: Boolean? = null`, `isVisible: Boolean? = null`, `hasAdUIPatterns: Boolean? = null` to the `DetectedMedia` data class in `data/model/Models.kt`
  - All new fields must have defaults so existing call sites compile without changes
  - _Requirements: 1.1, 4.1, 5.1, 6.1_

- [x] 2. Add detection counter and `updateCandidateSignals` to `VideoDetector`
  - [x] 2.1 Add `AtomicInteger` detection counter; stamp each new `DetectedMedia` with `detectionIndex` in `onResourceRequest`
    - Reset counter to 0 in `clearDetectedMedia()`
    - _Requirements: 1.1, 1.3_

  - [ ]* 2.2 Write unit tests for detection counter
    - Verify `detectionIndex` increments correctly across multiple `onResourceRequest` calls
    - Verify counter resets to 0 after `clearDetectedMedia()`
    - _Requirements: 1.1, 1.3_

  - [x] 2.3 Add `updateCandidateSignals(url, isPlaying, isVisible, hasAdUIPatterns)` to `VideoDetector`
    - Replaces the matching candidate in `_detectedMedia` with an updated copy
    - No-op if URL not found
    - _Requirements: 4.1, 5.1_

- [x] 3. Implement `VideoRanker`
  - [x] 3.1 Create `service/VideoRanker.kt` as a Kotlin `object` with `score()` and `rank()` pure functions
    - Implement all score weights from the design: URL type, temporal bonus (capped at +2 000), file size bonus (capped at +3 000), playing/visible/hidden bonuses, ad URL penalty, ad UI penalty, tiny-file penalty
    - Expose `AD_URL_PATTERNS` and all weight constants for testing
    - `ScoreBreakdown` data class with individual signal fields and `total`
    - _Requirements: 1.2, 1.4, 2.1, 2.2, 3.1, 3.2, 4.2, 5.2, 5.3, 5.4, 6.1, 6.2, 6.3, 6.4, 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ]* 3.2 Write property test for Property 1 — Temporal Ordering
    - **Property 1: Temporal Ordering** — two candidates identical except `detectionIndex`; higher index → higher score
    - **Validates: Requirements 1.2**

  - [ ]* 3.3 Write property test for Property 2 — Ad URL Penalty
    - **Property 2: Ad URL Penalty** — inserting a known ad pattern into a URL decreases the score
    - **Validates: Requirements 2.2**

  - [ ]* 3.4 Write property test for Property 3 — No-Filtering Invariant
    - **Property 3: No-Filtering Invariant** — `rank()` output list length equals input list length for any non-empty input
    - **Validates: Requirements 2.3, 3.3, 4.3, 8.1, 8.2**

  - [ ]* 3.5 Write property test for Property 4 — File Size Bonus
    - **Property 4: File Size Bonus** — candidate with larger known file size scores higher or equal
    - **Validates: Requirements 3.1, 3.4**

  - [ ]* 3.6 Write property test for Property 5 — Ad UI Penalty
    - **Property 5: Ad UI Penalty** — `hasAdUIPatterns = true` produces strictly lower score than `false`, all else equal
    - **Validates: Requirements 4.2**

  - [ ]* 3.7 Write property test for Property 6 — Playback State Ordering
    - **Property 6: Playback State Ordering** — score order: `isPlaying=true` > `isVisible=true` > `isVisible=false`, all else equal
    - **Validates: Requirements 5.2, 5.3, 5.4**

  - [ ]* 3.8 Write property test for Property 7 — Determinism
    - **Property 7: Determinism** — calling `score()` twice with identical inputs returns identical `ScoreBreakdown`
    - **Validates: Requirements 6.2, 6.4**

  - [ ]* 3.9 Write property test for Property 8 — Tie-Breaking by Temporal Index
    - **Property 8: Tie-Breaking** — equal-score candidates: higher `detectionIndex` appears first in `rank()` output
    - **Validates: Requirements 6.3**

  - [ ]* 3.10 Write property test for Property 9 — Tiny-File Penalty
    - **Property 9: Tiny-File Penalty** — `fileSize < 500_000` produces strictly lower score than `fileSize >= 500_000`, all else equal
    - **Validates: Requirements 8.4**

  - [ ]* 3.11 Write property test for Property 10 — Ranked List is Sorted
    - **Property 10: Ranked List Sorted** — `rank()` output is in descending score order (ties broken by descending `detectionIndex`)
    - **Validates: Requirements 7.4**

- [x] 4. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement `VideoSignalsJsInterface` and JS injection
  - [x] 5.1 Create `ui/browser/VideoSignalsJsInterface.kt`
    - `@JavascriptInterface` class with `reportVideoSignals(json: String)` method
    - Parse JSON array; each element: `{ url, isPlaying, isVisible, hasAdUI }`
    - On parse success, call `viewModel.onVideoSignalsReceived(signals)`
    - Catch `JSONException`, log at debug level, no crash
    - Define `VideoSignal` data class in the same file
    - _Requirements: 4.1, 4.4, 4.5, 5.1, 5.5_

  - [ ]* 5.2 Write unit tests for `VideoSignalsJsInterface`
    - Test well-formed JSON parses correctly into `VideoSignal` list
    - Test malformed JSON is caught without throwing
    - _Requirements: 4.4_

  - [x] 5.3 Inject `VideoSignalsJsInterface` into the WebView in `BrowserScreen.kt`
    - Add `webView.addJavascriptInterface(VideoSignalsJsInterface(viewModel), "Android")` during WebView setup
    - Inject the JS snippet via `webView.evaluateJavascript()` in `onPageFinished`
    - JS snippet scans `<video>` elements for `isPlaying`, `isVisible`, and ad UI text patterns; calls `Android.reportVideoSignals(JSON.stringify(results))`
    - _Requirements: 4.1, 5.1_

- [x] 6. Wire `rankedCandidates` into `BrowserViewModel`
  - [x] 6.1 Add `rankedCandidates`, `topCandidate`, and `otherCandidates` `StateFlow`s derived from `detectedMedia` + `prefetchedFileSizes`
    - Recompute in the background whenever `detectedMedia` changes (use `combine` + `map` in a `viewModelScope` coroutine, not at click time)
    - _Requirements: 6.1, 6.2, 7.1_

  - [x] 6.2 Add `onVideoSignalsReceived(signals: List<VideoSignal>)` to `BrowserViewModel`
    - Calls `videoDetector.updateCandidateSignals()` for each signal
    - _Requirements: 4.1, 5.1_

  - [x] 6.3 Replace `scoreMediaCandidate()` usage in `init {}` prefetch with `rankedCandidates.firstOrNull()`
    - Remove the `scoreMediaCandidate()` private function
    - _Requirements: 6.1_

  - [x] 6.4 Update `onDownloadFabClicked()` to use `topCandidate` directly instead of sorting inline
    - Iterate `rankedCandidates` in order when the top candidate returns empty quality options (Requirement 8.6)
    - Emit `Log.d` score breakdown per candidate using `VideoRanker.score()` (truncate URLs to 100 chars)
    - _Requirements: 7.1, 8.6, 9.1, 9.3_

  - [ ]* 6.5 Write property test for Property 11 — URL Truncation in Logs
    - **Property 11: URL Truncation** — any URL of arbitrary length logged by the ViewModel is at most 100 characters
    - **Validates: Requirements 9.3**

- [x] 7. Update Quality Sheet UI in `BrowserScreen.kt`
  - [x] 7.1 Update `QualitySheet` composable to read `topCandidate` and `otherCandidates` from the ViewModel
    - Top section: thumbnail, title, and quality options for `topCandidate` (always visible)
    - _Requirements: 7.1, 7.2_

  - [x] 7.2 Add "Other Videos (N)" accordion row at the bottom of the sheet
    - Hidden entirely when `otherCandidates` is empty (single-candidate case)
    - Collapsed by default; tapping expands/collapses the list
    - Expanded list shows title-only rows (no thumbnails) for each other candidate
    - Tapping a candidate row expands its quality options inline (accordion: only one open at a time)
    - _Requirements: 7.3, 7.4, 7.5_

- [x] 8. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Property tests use Kotest's property testing module (`io.kotest:kotest-property`)
- Each property test references the design property number it validates
- `VideoRanker` is a pure Kotlin `object` — no Android dependencies, safe to test on the JVM
- The `scoreMediaCandidate()` function in `BrowserViewModel` is fully replaced by `VideoRanker`; do not leave both in place
