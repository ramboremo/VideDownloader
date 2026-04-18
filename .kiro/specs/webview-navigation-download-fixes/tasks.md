# Implementation Plan

- [x] 1. Write bug condition exploration tests (BEFORE implementing any fix)
  - **Property 1: Bug Condition** - Error Callbacks Pollute Back Stack & Non-Media Downloads Rejected
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: These tests encode the expected behavior — they will validate the fixes when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate each bug exists

  **Bug 1a — Back-Stack Stall:**
  - Scope: main-frame `onReceivedError` (new overload) on a `WebView` that has a real URL in its history
  - Simulate `onReceivedError(view, request, error)` where `request.isForMainFrame = true`
  - Assert `webView.copyBackForwardList()` does NOT contain `"about:blank"` (will FAIL on unfixed code — confirms bug)
  - Repeat for `onReceivedError(view, errorCode, description, failingUrl)` (deprecated overload)
  - Repeat for `onReceivedHttpError(view, request, errorResponse)` with HTTP 451/403 on main frame
  - Document counterexample: `"about:blank"` appears in back stack after each callback fires
  - Mark task complete when tests are written, run, and failures are documented

  **Bug 2 — Non-Media Download Rejected:**
  - Scope: `performDownload` called with a real PDF file and `mimeType = "application/pdf"`
  - Assert download status is `COMPLETED` and file still exists on disk (will FAIL on unfixed code — confirms bug)
  - Repeat for `mimeType = "application/zip"`
  - Document counterexample: valid PDF/ZIP download is marked `FAILED` and file is deleted
  - Mark task complete when tests are written, run, and failures are documented

  - _Requirements: 1.1, 1.4, 1.5_

- [x] 2. Write preservation property tests (BEFORE implementing any fix)
  - **Property 2: Preservation** - Existing Behaviors Remain Unchanged
  - **IMPORTANT**: Follow observation-first methodology — run UNFIXED code with non-buggy inputs and record actual outputs
  - **EXPECTED OUTCOME**: All preservation tests PASS on unfixed code (confirms baseline behavior to preserve)

  **Bug 1a — Sub-frame errors and successful loads:**
  - Observe: `onReceivedError` with `request.isForMainFrame = false` → `isNetworkError` state unchanged, no `about:blank` pushed
  - Write property-based test: for all `WebResourceRequest` objects where `isForMainFrame = false`, error callbacks do not modify `isNetworkError` state and do not push `about:blank`
  - Observe: `onReceivedError` still calls `stopLoading()` and `viewModel.onPageError()` on main-frame errors
  - Write test: assert `stopLoading()` is called in all three error callbacks for main-frame errors
  - Verify tests PASS on unfixed code

  **Bug 2 — Media validation still runs for video/audio:**
  - Observe: `performDownload` with `mimeType = "video/mp4"` and valid MP4 header → status `COMPLETED`
  - Observe: `performDownload` with `mimeType = "video/mp4"` and garbage bytes → status `FAILED`
  - Observe: `performDownload` with `mimeType = "audio/mpeg"` and valid header → status `COMPLETED`
  - Write property-based test: for all MIME types starting with `"video/"` or `"audio/"`, `isValidMediaFile()` is always called
  - Write property-based test: for all file extensions in `["mp4","webm","mkv","avi","mov","mp3","m4a","aac","flac","ogg","wav"]` with generic MIME type, `isValidMediaFile()` is still called
  - Verify tests PASS on unfixed code

  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 3. Fix Bug 1a — Remove `loadUrl("about:blank")` from error callbacks

  - [x] 3.1 Remove `view?.loadUrl("about:blank")` from all three error callbacks in `WebViewContent` (BrowserScreen.kt)
    - In `onReceivedError(view, request, error)` (new overload, API 23+): delete the `view?.loadUrl("about:blank")` line; keep `view?.stopLoading()`
    - In `onReceivedError(view, errorCode, description, failingUrl)` (deprecated overload): delete the `view?.loadUrl("about:blank")` line; keep `view?.stopLoading()`
    - In `onReceivedHttpError(view, request, errorResponse)`: delete the `view?.loadUrl("about:blank")` line; keep `view?.stopLoading()`
    - No other changes to BrowserScreen.kt for this bug
    - _Bug_Condition: isBugCondition_BackStack(X) where X.isMainFrame = true AND "about:blank" IN webView.backStack_
    - _Expected_Behavior: "about:blank" NOT IN webView.backStack AND isNetworkError = true AND stopLoading() was called_
    - _Preservation: stopLoading() remains in all three callbacks; NetworkErrorScreen overlay logic unchanged; Retry button still calls webView.reload()_
    - _Requirements: 2.1, 2.2, 3.3, 3.4_

  - [x] 3.2 Verify bug condition exploration test (Bug 1a) now passes
    - **Property 1: Expected Behavior** - Error Callbacks Do Not Pollute Back Stack
    - **IMPORTANT**: Re-run the SAME tests from task 1 for Bug 1a — do NOT write new tests
    - Run all three back-stack exploration tests (new overload, deprecated overload, HTTP error)
    - **EXPECTED OUTCOME**: All three tests PASS (confirms `about:blank` is no longer pushed onto the back stack)
    - _Requirements: 2.1, 2.2_

  - [x] 3.3 Verify preservation tests still pass after Bug 1a fix
    - **Property 2: Preservation** - Sub-Frame and Successful Load Behavior Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 for Bug 1a — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions in error callback behavior)

- [x] 4. Fix Bug 1b — Keep BrowserScreen always in composition via visibility toggling

  - [x] 4.1 Refactor `AppNavigation` in MainActivity.kt to render `BrowserScreen` outside the `NavHost`
    - Observe `navController.currentBackStackEntryAsState()` to derive the active destination route
    - Render `BrowserScreen` unconditionally (outside `NavHost`), always in the composition tree
    - Wrap `BrowserScreen` in a `Box` with `Modifier.graphicsLayer { alpha = if (isBrowserActive) 1f else 0f }` to hide it visually when not active
    - Add `Modifier.pointerInput(isBrowserActive) { if (!isBrowserActive) awaitPointerEventScope { while (true) awaitPointerEvent() } }` (or equivalent) to block touch events when hidden
    - Add appropriate `Modifier.zIndex` so the active screen always renders on top
    - Remove the `browser` composable destination from inside `NavHost`; keep `files`, `player/{downloadId}`, and `settings` destinations in `NavHost`
    - The `sharedWebView` creation (`remember { WebView(context) }`) remains unchanged
    - _Bug_Condition: isBugCondition_WhiteFlash(X) where X.previousScreen = "FilesScreen" AND X.currentScreen = "BrowserScreen" AND sharedWebView.parent = null_
    - _Expected_Behavior: webView.didReload = false AND webView.renderedContent.isPreserved = true_
    - _Preservation: All BrowserScreen content, ViewModel, and WebView settings unchanged; back/forward nav unchanged; sharedWebView settings unchanged_
    - _Requirements: 2.3, 3.3, 3.4, 3.5_

  - [x] 4.2 Verify bug condition exploration test (Bug 1b) now passes
    - **Property 1: Expected Behavior** - BrowserScreen WebView Preserved Across Navigation
    - In a Compose test, navigate from `BrowserScreen` to `FilesScreen` and back
    - Assert `WebViewClient.onPageStarted` is called exactly once (no reload on return)
    - Assert the `AndroidView` factory in `WebViewContent` is not re-invoked after initial composition
    - **EXPECTED OUTCOME**: Test PASSES (confirms no white flash and no page reload on return)
    - _Requirements: 2.3_

  - [x] 4.3 Verify preservation tests still pass after Bug 1b fix
    - **Property 2: Preservation** - BrowserScreen Behavior Unchanged When Active
    - Verify user staying on `BrowserScreen` and loading a URL produces no reload and no white flash
    - Verify tab switching and new tab behavior still works correctly
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions in navigation behavior)

- [x] 5. Fix Bug 2 — Gate `isValidMediaFile()` behind MIME type + extension check

  - [x] 5.1 Add `isMediaMimeType` and `isMediaExtension` helpers and update `performDownload` in DownloadService.kt
    - Add private helper: `fun isMediaMimeType(mimeType: String): Boolean = mimeType.startsWith("video/") || mimeType.startsWith("audio/")`
    - Add private helper: `fun isMediaExtension(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in setOf("mp4","webm","mkv","avi","mov","mp3","m4a","aac","flac","ogg","wav")`
    - In `performDownload`, before the Case 4 block (`if (!isValidMediaFile(file))`), retrieve `mimeType` from the already-fetched `entity` variable
    - Wrap the entire Case 4 block in: `if (isMediaMimeType(mimeType) || isMediaExtension(file.name)) { ... }`
    - Do NOT modify `isValidMediaFile()` itself
    - Do NOT change Cases 0-byte, HTML response (Case 3 playlist), or any other validation case
    - _Bug_Condition: isBugCondition_NonMediaRejected(X) where isValidMediaFile(X.file) = false AND X.mimeType does not start with "video/" or "audio/" AND X.fileExtension NOT IN media set_
    - _Expected_Behavior: result.status = "COMPLETED" AND X.file.exists = true_
    - _Preservation: isValidMediaFile() still called for all video/* and audio/* MIME types and known media extensions; corrupted video/audio still marked FAILED; HLS/DASH playlist check (Case 3) unchanged_
    - _Requirements: 2.4, 2.5, 3.1, 3.2, 3.6_

  - [x] 5.2 Verify bug condition exploration test (Bug 2) now passes
    - **Property 1: Expected Behavior** - Non-Media Downloads Marked COMPLETED
    - **IMPORTANT**: Re-run the SAME tests from task 1 for Bug 2 — do NOT write new tests
    - Run PDF (`application/pdf`) and ZIP (`application/zip`) download tests
    - **EXPECTED OUTCOME**: Tests PASS — status is `COMPLETED` and files exist on disk
    - _Requirements: 2.4_

  - [x] 5.3 Verify preservation tests still pass after Bug 2 fix
    - **Property 2: Preservation** - Media Validation Still Runs for Video/Audio
    - **IMPORTANT**: Re-run the SAME property-based tests from task 2 for Bug 2 — do NOT write new tests
    - Verify valid MP4 → `COMPLETED`, corrupted MP4 → `FAILED`, valid audio → `COMPLETED`
    - Verify `isValidMediaFile()` is called for all video/audio MIME types and known media extensions
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions in media validation)
    - _Requirements: 2.5, 3.1, 3.2_

- [x] 6. Checkpoint — Ensure all tests pass
  - Re-run the full test suite (unit + property-based + integration tests)
  - Confirm Property 1 (Bug Condition) tests all pass for Bug 1a, Bug 1b, and Bug 2
  - Confirm Property 2 (Preservation) tests all pass for all three bugs
  - Confirm no regressions in: back/forward navigation, tab switching, error overlay display, Retry button, HLS/DASH playlist detection, 0-byte and HTML response rejection
  - Ensure all tests pass; ask the user if questions arise
