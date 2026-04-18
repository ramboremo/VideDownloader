# WebView Navigation & Download Fixes — Bugfix Design

## Overview

Three related bugs are fixed in this spec:

- **Bug 1a — about:blank back-stack stall**: In `WebViewContent` (BrowserScreen.kt), all three error callbacks (`onReceivedError` × 2, `onReceivedHttpError`) call `view?.loadUrl("about:blank")` after stopping the load. This pushes `about:blank` onto the WebView back stack. When the user presses Back, the WebView navigates to `about:blank` first and stalls there for ~10 seconds before reaching the previous real page. The `NetworkErrorScreen` overlay is already shown via `isNetworkError` state, so the `loadUrl("about:blank")` call is both unnecessary and harmful.

- **Bug 1b — White screen / reload on returning from Downloads**: In `AppNavigation` (MainActivity.kt), `sharedWebView` is created with `remember { WebView(context) }` and passed to `BrowserScreen`. When the user navigates to `FilesScreen`, Compose removes `BrowserScreen` from the composition. On return, the `AndroidView` factory in `WebViewContent` runs `if (parent != null) { (parent as ViewGroup).removeView(this) }`, which detaches and re-attaches the WebView, triggering a full layout/redraw cycle — producing a white flash and a full page reload.

- **Bug 2 — Non-media files rejected**: In `DownloadService.kt`, `isValidMediaFile()` is called unconditionally in Case 4 of `performDownload` for every completed download. Non-media files (PDFs, ZIPs, text files, etc.) have no video/audio magic bytes, so `isValidMediaFile()` returns `false`, the file is deleted, and the download is marked `FAILED` with "file is not a valid video".

The fix strategy is minimal and targeted: remove the harmful `loadUrl("about:blank")` calls, keep `BrowserScreen` always in the composition tree using visibility toggling, and gate the `isValidMediaFile()` check behind a MIME type / file extension guard.

---

## Glossary

- **Bug_Condition (C)**: The specific input condition that triggers each bug.
- **Property (P)**: The desired correct behavior when the bug condition holds.
- **Preservation**: Existing behaviors that must remain unchanged after the fix.
- **`WebViewContent`**: The `@Composable` function in `BrowserScreen.kt` that wraps the `WebView` in an `AndroidView`.
- **`onReceivedError` (new)**: `WebViewClient.onReceivedError(WebView?, WebResourceRequest?, WebResourceError?)` — fires on API 23+ for main-frame and sub-frame errors.
- **`onReceivedError` (deprecated)**: `WebViewClient.onReceivedError(WebView?, Int, String?, String?)` — fires on API < 23 and as a fallback.
- **`onReceivedHttpError`**: `WebViewClient.onReceivedHttpError(...)` — fires for HTTP 4xx/5xx responses on the main frame.
- **`sharedWebView`**: The single `WebView` instance created in `AppNavigation` and passed to `BrowserScreen`.
- **`AppNavigation`**: The `@Composable` function in `MainActivity.kt` that owns the `NavHost` and `sharedWebView`.
- **`isValidMediaFile(file)`**: Private function in `DownloadService.kt` that reads the first 12 bytes of a file and checks for known video/audio magic byte signatures.
- **`performDownload`**: The suspend function in `DownloadService.kt` that executes the HTTP download and runs all post-download validation (Cases 1–4).
- **`isBugCondition`**: Pseudocode function used in this document to formally identify inputs that trigger each bug.
- **`isMediaMimeType`**: Proposed helper that returns `true` when a MIME type starts with `"video/"` or `"audio/"`.
- **`isMediaExtension`**: Proposed helper that returns `true` when a file extension is a known media format.

---

## Bug Details

### Bug 1a — about:blank Back-Stack Stall

The bug manifests when a main-frame network or HTTP error is received. All three error callbacks in `WebViewContent` call `view?.loadUrl("about:blank")`, which pushes `about:blank` onto the WebView's internal back stack. The next Back press navigates to `about:blank` instead of the previous real page, and the WebView stalls there for ~10 seconds.

**Formal Specification:**
```
FUNCTION isBugCondition_BackStack(X)
  INPUT: X of type WebViewErrorEvent
  OUTPUT: boolean

  RETURN X.isMainFrame = true
         AND "about:blank" IN webView.backStack
         AND webView.backStack was modified by loadUrl("about:blank") during error handling
END FUNCTION
```

**Examples:**
- User visits `https://blocked-site.com` → HTTP 451 → `onReceivedHttpError` fires → `loadUrl("about:blank")` pushes `about:blank` → user presses Back → WebView navigates to `about:blank` and stalls for ~10 s. Expected: Back navigates directly to the previous real page.
- User visits a URL with no network → `onReceivedError` (new overload) fires → same stall. Expected: Back navigates directly to the previous real page.
- User visits a URL on API < 23 → `onReceivedError` (deprecated overload) fires → same stall. Expected: same.

---

### Bug 1b — White Screen / Reload on Returning from Downloads

The bug manifests when the user navigates from `BrowserScreen` to `FilesScreen` and then returns. Compose removes `BrowserScreen` from the composition when it is not the active destination. On re-entry, the `AndroidView` factory re-runs and calls `(parent as ViewGroup).removeView(this)` on the `sharedWebView`, detaching and re-attaching it to the window. This triggers a full layout pass and page reload.

**Formal Specification:**
```
FUNCTION isBugCondition_WhiteFlash(X)
  INPUT: X of type ScreenNavigationEvent
  OUTPUT: boolean

  RETURN X.previousScreen = "FilesScreen"
         AND X.currentScreen = "BrowserScreen"
         AND sharedWebView.parent = null  // detached during FilesScreen visit
         AND AndroidView.factory was re-invoked on BrowserScreen re-entry
END FUNCTION
```

**Examples:**
- User is on `https://youtube.com` → taps Downloads → taps Back → white flash, page reloads from scratch. Expected: page content is preserved, no reload.
- User is mid-scroll on a page → taps Downloads → taps Back → scroll position lost, page reloads. Expected: scroll position preserved.

---

### Bug 2 — Non-Media Files Falsely Rejected

The bug manifests when a download completes for a file whose MIME type is not `video/*` or `audio/*`. `isValidMediaFile()` is called unconditionally and returns `false` for any file without a recognized media magic byte header (PDFs, ZIPs, APKs, text files, etc.).

**Formal Specification:**
```
FUNCTION isBugCondition_NonMediaRejected(X)
  INPUT: X of type CompletedDownload
  OUTPUT: boolean

  RETURN isValidMediaFile(X.file) = false
         AND X.mimeType DOES NOT start with "video/"
         AND X.mimeType DOES NOT start with "audio/"
         AND X.fileExtension NOT IN ["mp4","webm","mkv","avi","mov","mp3","m4a","aac","flac","ogg","wav"]
END FUNCTION
```

**Examples:**
- User downloads a PDF (`application/pdf`) → `isValidMediaFile()` returns `false` → file deleted, status `FAILED`. Expected: status `COMPLETED`.
- User downloads a ZIP (`application/zip`) → same failure. Expected: status `COMPLETED`.
- User downloads an MP4 (`video/mp4`) with valid header → `isValidMediaFile()` returns `true` → status `COMPLETED`. Must remain unchanged.
- User downloads an MP4 with garbage bytes → `isValidMediaFile()` returns `false` → status `FAILED`. Must remain unchanged.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- `view?.stopLoading()` in all three error callbacks must remain — it prevents the WebView from rendering its own error page behind the overlay.
- The `NetworkErrorScreen` overlay display logic (`isNetworkError` state) must remain unchanged.
- The Retry button in `NetworkErrorScreen` must continue to call `webView?.reload()` correctly.
- WebView back/forward navigation via the bottom nav bar must continue to work.
- The `sharedWebView` settings (JavaScript, DOM storage, user agent, etc.) must remain unchanged.
- `isValidMediaFile()` must continue to be called for all downloads where the MIME type starts with `"video/"` or `"audio/"`, or where the file extension is a known media format.
- Downloads of corrupted/garbage video files must continue to be marked `FAILED`.
- HLS/DASH playlist detection (Case 3) must remain unchanged and independent of the Bug 2 fix.
- All other `performDownload` validation cases (0-byte, HTML response, playlist) must remain unchanged.

**Scope:**
- Bug 1a fix: only the three `loadUrl("about:blank")` calls are removed. No other error handling logic changes.
- Bug 1b fix: only the composition lifecycle of `BrowserScreen` changes. All screen content, ViewModel, and WebView behavior remain identical.
- Bug 2 fix: only the guard condition before `isValidMediaFile()` changes. The function itself is not modified.

---

## Hypothesized Root Cause

### Bug 1a
The `loadUrl("about:blank")` calls were added as a workaround to prevent the WebView's built-in error page from flashing behind the custom `NetworkErrorScreen` overlay. The intent was correct, but the side effect — pushing `about:blank` onto the back stack — was not considered. Since `view?.stopLoading()` already prevents the WebView from rendering its own error page, the `loadUrl("about:blank")` is redundant and only causes the back-stack stall.

### Bug 1b
Jetpack Compose Navigation removes composables from the composition when they are not the active destination. The `sharedWebView` is a stateful Android `View` that holds its rendered content in native memory. When `BrowserScreen` leaves the composition, the `AndroidView` is disposed and the `WebView` is detached from its parent. On re-entry, the `AndroidView` factory re-runs, detects `parent != null` (from a previous attach), removes the view, and re-adds it — which the WebView interprets as a new attach and triggers a reload. The root cause is that `BrowserScreen` should never leave the composition while the WebView holds live content.

### Bug 2
`isValidMediaFile()` was introduced to catch corrupted or blocked media downloads that have a plausible file size but invalid content. The check is correct for video/audio files but was applied unconditionally to all downloads, including non-media files that legitimately have no video/audio magic bytes. The `mimeType` field is available on the `DownloadEntity` and was not consulted before calling the validation function.

---

## Correctness Properties

Property 1: Bug Condition — Error Callbacks Do Not Pollute Back Stack

_For any_ main-frame WebView error event (network error or HTTP 4xx/5xx) where `isBugCondition_BackStack` holds, the fixed error callbacks SHALL call `view?.stopLoading()` and update the `isNetworkError` state, and SHALL NOT call `view?.loadUrl("about:blank")`, ensuring `about:blank` is never pushed onto the WebView back stack.

**Validates: Requirements 2.1, 2.2**

Property 2: Preservation — Back Navigation After Error

_For any_ input where the bug condition does NOT hold (no error event, or error on a sub-frame), the fixed error callbacks SHALL produce exactly the same behavior as the original callbacks, preserving all existing navigation and overlay behavior.

**Validates: Requirements 3.3, 3.4**

Property 3: Bug Condition — BrowserScreen WebView Preserved Across Navigation

_For any_ screen navigation event where `isBugCondition_WhiteFlash` holds (user returns to BrowserScreen from FilesScreen), the fixed composition SHALL preserve the WebView's rendered content and SHALL NOT trigger a page reload.

**Validates: Requirements 2.3**

Property 4: Preservation — BrowserScreen Behavior Unchanged When Active

_For any_ input where the user remains on BrowserScreen (no navigation away), the fixed composition SHALL produce exactly the same behavior as the original, preserving all browsing, tab, and download detection functionality.

**Validates: Requirements 3.3, 3.4, 3.5**

Property 5: Bug Condition — Non-Media Downloads Marked COMPLETED

_For any_ completed download where `isBugCondition_NonMediaRejected` holds (non-media MIME type and non-media extension), the fixed `performDownload` SHALL mark the download as `COMPLETED` and SHALL NOT delete the file.

**Validates: Requirements 2.4**

Property 6: Preservation — Media Validation Still Runs for Video/Audio

_For any_ completed download where the MIME type starts with `"video/"` or `"audio/"`, or the file extension is a known media format, the fixed `performDownload` SHALL call `isValidMediaFile()` and produce the same result as the original code.

**Validates: Requirements 2.5, 3.1, 3.2**

---

## Fix Implementation

### Bug 1a — Remove `loadUrl("about:blank")` from Error Callbacks

**File:** `app/src/main/java/com/videdownloader/app/ui/browser/BrowserScreen.kt`

**Function:** `WebViewContent` (inside the `webViewClient` assignment in the `AndroidView` factory)

**Specific Changes:**

1. In `onReceivedError(view, request, error)` (new overload, API 23+): remove `view?.loadUrl("about:blank")`. Keep `view?.stopLoading()`.
2. In `onReceivedError(view, errorCode, description, failingUrl)` (deprecated overload): remove `view?.loadUrl("about:blank")`. Keep `view?.stopLoading()`.
3. In `onReceivedHttpError(view, request, errorResponse)`: remove `view?.loadUrl("about:blank")`. Keep `view?.stopLoading()`.

No other changes to `BrowserScreen.kt` for this bug.

---

### Bug 1b — Keep BrowserScreen Always in Composition

**File:** `app/src/main/java/com/videdownloader/app/MainActivity.kt`

**Function:** `AppNavigation`

**Specific Changes:**

Replace the `NavHost`-based approach for `BrowserScreen` with always-on composition using visibility toggling. `BrowserScreen` is rendered unconditionally; its visibility is controlled by comparing the current back-stack destination against `"browser"`. All other destinations (`files`, `player/{downloadId}`, `settings`) remain as `NavHost` composable destinations.

**Implementation approach:**
1. Observe `navController.currentBackStackEntryAsState()` to get the active destination route.
2. Render `BrowserScreen` outside the `NavHost`, always in the composition, wrapped in a `Box` with `Modifier.graphicsLayer { alpha = if (isBrowserActive) 1f else 0f }` and `Modifier.zIndex` to hide it visually when not active. Use `pointerInput` to block touch events when hidden.
3. Keep the `NavHost` for `files`, `player/{downloadId}`, and `settings` destinations only. Remove the `browser` composable from `NavHost`.
4. The `sharedWebView` remains created with `remember { WebView(context) }` — no change needed there.

This ensures the `AndroidView` factory in `WebViewContent` is never re-invoked after the initial composition, so the WebView is never detached and re-attached.

---

### Bug 2 — Gate `isValidMediaFile()` Behind MIME/Extension Check

**File:** `app/src/main/java/com/videdownloader/app/service/DownloadService.kt`

**Function:** `performDownload`

**Specific Changes:**

1. Before the Case 4 block (`if (!isValidMediaFile(file))`), retrieve the entity's `mimeType` from the already-fetched `entity` variable (available at the top of `performDownload`).
2. Add a helper check: `isMediaMimeType(mimeType)` returns `true` if `mimeType.startsWith("video/") || mimeType.startsWith("audio/")`.
3. Add a fallback check: `isMediaExtension(file.name)` returns `true` if the file extension (lowercased) is in `["mp4", "webm", "mkv", "avi", "mov", "mp3", "m4a", "aac", "flac", "ogg", "wav"]`.
4. Only call `isValidMediaFile(file)` if `isMediaMimeType(mimeType) || isMediaExtension(file.name)` is `true`. If neither is true, skip Case 4 entirely and proceed to the success block.

The `isValidMediaFile()` function itself is not modified.

---

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate each bug on the unfixed code, then verify the fix works correctly and preserves existing behavior.

---

### Exploratory Bug Condition Checking

**Goal:** Surface counterexamples that demonstrate each bug BEFORE implementing the fix. Confirm or refute the root cause analysis.

**Test Plan:** Write unit/instrumentation tests that simulate the exact conditions of each bug and assert the incorrect behavior on unfixed code.

**Test Cases:**

1. **Back-Stack Stall (Bug 1a):** Simulate a main-frame `onReceivedError` call on a `WebView` with a real URL in its history. Assert that `webView.copyBackForwardList()` contains `"about:blank"` after the callback fires. (Will pass on unfixed code — confirms the bug.)
2. **HTTP Error Back-Stack (Bug 1a):** Simulate `onReceivedHttpError` with a 403 status on the main frame. Assert `about:blank` is in the back stack. (Will pass on unfixed code.)
3. **WebView Reload on Return (Bug 1b):** In a Compose test, navigate from `BrowserScreen` to `FilesScreen` and back. Assert that `WebViewClient.onPageStarted` is called more than once (reload occurred). (Will pass on unfixed code — confirms the bug.)
4. **Non-Media Download Rejected (Bug 2):** Call `performDownload` with a real PDF file and `mimeType = "application/pdf"`. Assert the download status is `FAILED`. (Will pass on unfixed code — confirms the bug.)

**Expected Counterexamples:**
- `about:blank` appears in the WebView back stack after error callbacks fire.
- `onPageStarted` fires a second time after returning to `BrowserScreen`.
- A valid PDF download is marked `FAILED` and the file is deleted.

---

### Fix Checking

**Goal:** Verify that for all inputs where each bug condition holds, the fixed code produces the expected behavior.

**Pseudocode:**
```
// Bug 1a
FOR ALL X WHERE isBugCondition_BackStack(X) DO
  result := handlePageError_fixed(X)
  ASSERT "about:blank" NOT IN webView.backStack
  ASSERT isNetworkError = true
END FOR

// Bug 1b
FOR ALL X WHERE isBugCondition_WhiteFlash(X) DO
  result := reattachWebView_fixed(X)
  ASSERT webView.didReload = false
  ASSERT webView.renderedContent.isPreserved = true
END FOR

// Bug 2
FOR ALL X WHERE isBugCondition_NonMediaRejected(X) DO
  result := performDownload_fixed(X)
  ASSERT result.status = "COMPLETED"
  ASSERT X.file.exists = true
END FOR
```

---

### Preservation Checking

**Goal:** Verify that for all inputs where each bug condition does NOT hold, the fixed code produces the same result as the original.

**Pseudocode:**
```
// Bug 1a — sub-frame errors and successful loads are unaffected
FOR ALL X WHERE NOT isBugCondition_BackStack(X) DO
  ASSERT handlePageError_original(X) = handlePageError_fixed(X)
END FOR

// Bug 1b — BrowserScreen behavior when active is unchanged
FOR ALL X WHERE NOT isBugCondition_WhiteFlash(X) DO
  ASSERT browserScreen_original(X) = browserScreen_fixed(X)
END FOR

// Bug 2 — video/audio downloads still validated
FOR ALL X WHERE NOT isBugCondition_NonMediaRejected(X)
               AND (isMediaMimeType(X.mimeType) OR isMediaExtension(X.file.name)) DO
  ASSERT performDownload_original(X) = performDownload_fixed(X)
END FOR
```

**Testing Approach:** Property-based testing is recommended for Bug 2 preservation checking because it can generate many random MIME type / file extension combinations and verify that the guard condition correctly routes each case to or away from `isValidMediaFile()`.

**Test Cases:**
1. **Valid MP4 preserved:** Download completes with `mimeType = "video/mp4"` and valid MP4 header → still `COMPLETED`.
2. **Corrupted MP4 preserved:** Download completes with `mimeType = "video/mp4"` and garbage bytes → still `FAILED`.
3. **Sub-frame error preserved (Bug 1a):** `onReceivedError` fires for a sub-frame resource → `isNetworkError` state unchanged, no `about:blank` in back stack (same as before).
4. **Normal page load preserved (Bug 1b):** User stays on `BrowserScreen` and loads a URL → no reload, no white flash.

---

### Unit Tests

- Test that `onReceivedError` (both overloads) and `onReceivedHttpError` do NOT call `loadUrl("about:blank")` after the fix.
- Test that `onReceivedError` still calls `stopLoading()` and `viewModel.onPageError()`.
- Test that `performDownload` skips `isValidMediaFile()` for `mimeType = "application/pdf"`.
- Test that `performDownload` skips `isValidMediaFile()` for `mimeType = "application/zip"`.
- Test that `performDownload` still calls `isValidMediaFile()` for `mimeType = "video/mp4"`.
- Test that `performDownload` still calls `isValidMediaFile()` for `mimeType = "audio/mpeg"`.
- Test that `performDownload` calls `isValidMediaFile()` for a file with extension `.mp4` even if MIME type is generic.

### Property-Based Tests

- Generate random non-media MIME types (not starting with `"video/"` or `"audio/"`) and random non-media extensions, and verify that `isValidMediaFile()` is never called for those inputs after the fix.
- Generate random video/audio MIME types and verify that `isValidMediaFile()` is always called for those inputs after the fix.
- Generate random `WebResourceRequest` objects with `isForMainFrame = false` and verify that error callbacks never modify `isNetworkError` state.

### Integration Tests

- Full navigation flow: load a page → navigate to Downloads → navigate back → assert page content is preserved and `onPageStarted` was not called a second time.
- Error flow: simulate a blocked URL → assert `NetworkErrorScreen` is shown → press Back → assert navigation goes to the previous real page without stalling.
- Non-media download flow: trigger a PDF download → assert download status is `COMPLETED` and file exists on disk.
- Media download flow: trigger an MP4 download with valid header → assert status is `COMPLETED`. Trigger with garbage bytes → assert status is `FAILED`.
