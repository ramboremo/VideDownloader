# Bugfix Requirements Document

## Introduction

This document covers two bugs in the Android video downloader app (Kotlin/Jetpack Compose):

1. **WebView navigation issues** — When a blocked or restricted site is visited, the WebView loads `about:blank` as a workaround to suppress the browser's native error page. This causes two regressions: (a) the hardware back button stalls on `about:blank` for ~10 seconds before navigating back, and (b) navigating away to the Downloads screen and returning causes the WebView to go white and fully reload the current page.

2. **Non-video file downloads rejected** — The `DownloadService` validates every downloaded file against a set of known video/audio magic byte signatures. This was introduced to prevent corrupted media files from being marked `COMPLETED`, but it now blocks all non-media file types (PDFs, ZIPs, text files, etc.) that users legitimately download from the browser.

---

## Bug Analysis

### Current Behavior (Defect)

**Bug 1 — WebView navigation**

1.1 WHEN a visited page returns a network error or HTTP 4xx/5xx response THEN the system calls `view.loadUrl("about:blank")` which pushes `about:blank` onto the WebView's back stack

1.2 WHEN the user presses the hardware back button after an error page THEN the system navigates to `about:blank` first and stalls there for approximately 10 seconds before going back to the previous real page

1.3 WHEN the user navigates to the Downloads screen and returns to the Browser screen THEN the system detaches and re-attaches the shared `WebView` instance, causing the visible area to go white and the page to fully reload

**Bug 2 — Non-video file downloads**

1.4 WHEN a download completes for a non-media file (e.g. PDF, ZIP, text file) THEN the system calls `isValidMediaFile()` which returns `false` because the file header does not match any video/audio magic byte signature

1.5 WHEN `isValidMediaFile()` returns `false` THEN the system deletes the file and marks the download as `FAILED` with the message "file is not a valid video"

---

### Expected Behavior (Correct)

**Bug 1 — WebView navigation**

2.1 WHEN a visited page returns a network error or HTTP 4xx/5xx response THEN the system SHALL display the custom `NetworkErrorScreen` overlay without loading `about:blank` into the WebView's back stack

2.2 WHEN the user presses the hardware back button after an error page THEN the system SHALL navigate directly back to the previous real page without stalling on `about:blank`

2.3 WHEN the user navigates to the Downloads screen and returns to the Browser screen THEN the system SHALL preserve the WebView's rendered content and SHALL NOT trigger a full page reload

**Bug 2 — Non-video file downloads**

2.4 WHEN a download completes for a non-media file (e.g. PDF, ZIP, text file) THEN the system SHALL mark the download as `COMPLETED` without performing magic byte validation

2.5 WHEN a download completes for a file whose MIME type or URL extension identifies it as video or audio THEN the system SHALL validate the file header and SHALL mark the download as `FAILED` if the header does not match a known media signature

---

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a video or audio file download completes with a valid media file header THEN the system SHALL CONTINUE TO mark the download as `COMPLETED`

3.2 WHEN a video or audio file download completes but the file contains garbage/encrypted bytes (no valid media header) THEN the system SHALL CONTINUE TO mark the download as `FAILED` and delete the corrupted file

3.3 WHEN a page loads successfully (HTTP 2xx) THEN the system SHALL CONTINUE TO display the page content normally without showing the error overlay

3.4 WHEN the user presses the hardware back button on a page with browser history THEN the system SHALL CONTINUE TO navigate back through the WebView's history

3.5 WHEN the user navigates between tabs or opens a new tab THEN the system SHALL CONTINUE TO load the correct URL in the WebView

3.6 WHEN a download completes for an HLS/DASH playlist file THEN the system SHALL CONTINUE TO mark the download as `FAILED` (this check is independent of the media validation fix)

---

## Bug Condition Pseudocode

### Bug 1 — about:blank back-stack pollution

```pascal
FUNCTION isBugCondition_BackStack(X)
  INPUT: X of type WebViewNavigation
  OUTPUT: boolean

  // Bug fires when an error causes about:blank to be pushed onto the back stack
  RETURN X.isMainFrameError = true AND webView.backStack.contains("about:blank")
END FUNCTION

// Property: Fix Checking
FOR ALL X WHERE isBugCondition_BackStack(X) DO
  result ← handlePageError'(X)
  ASSERT webView.backStack.doesNotContain("about:blank")
  ASSERT customErrorOverlay.isVisible = true
END FOR

// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition_BackStack(X) DO
  ASSERT handlePageError(X) = handlePageError'(X)
END FOR
```

### Bug 1 — WebView white flash on screen return

```pascal
FUNCTION isBugCondition_WhiteFlash(X)
  INPUT: X of type ScreenNavigation
  OUTPUT: boolean

  // Bug fires when returning to BrowserScreen after navigating away
  RETURN X.fromScreen = "FilesScreen" AND X.toScreen = "BrowserScreen"
         AND sharedWebView.isAttachedToWindow = false
END FUNCTION

// Property: Fix Checking
FOR ALL X WHERE isBugCondition_WhiteFlash(X) DO
  result ← reattachWebView'(X)
  ASSERT webView.renderedContent.isPreserved = true
  ASSERT webView.didReload = false
END FOR
```

### Bug 2 — Non-media file falsely rejected

```pascal
FUNCTION isBugCondition_NonMediaRejected(X)
  INPUT: X of type CompletedDownload
  OUTPUT: boolean

  // Bug fires when a non-video/audio file is validated against media magic bytes
  RETURN isValidMediaFile(X.file) = false
         AND X.mimeType doesNotStartWith "video/"
         AND X.mimeType doesNotStartWith "audio/"
END FUNCTION

// Property: Fix Checking
FOR ALL X WHERE isBugCondition_NonMediaRejected(X) DO
  result ← validateDownload'(X)
  ASSERT result.status = "COMPLETED"
  ASSERT X.file.exists = true
END FOR

// Property: Preservation Checking — media validation still runs for video/audio
FOR ALL X WHERE NOT isBugCondition_NonMediaRejected(X)
               AND (X.mimeType startsWith "video/" OR X.mimeType startsWith "audio/") DO
  ASSERT validateDownload(X) = validateDownload'(X)
END FOR
```
