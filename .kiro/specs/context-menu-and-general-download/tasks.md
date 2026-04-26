# Implementation Plan: Context Menu and General Download

## Overview

Implement the long-press context menu (bottom sheet) and the general file download banner for the xdownload Android browser. All new state lives in `BrowserViewModel`; all new UI lives in `BrowserScreen`. `VideoDetector`, the FAB, and the quality sheet are not touched.

## Tasks

- [x] 1. Add data models to `Models.kt`
  - Add `sealed class ContextMenuTarget` with variants `Link`, `Image`, `LinkAndImage`, and `Video`
  - Add `data class PendingGeneralDownload` with fields `url`, `fileName`, `mimeType`, `fileSize: Long?`, `sourceUrl`
  - _Requirements: 1.1, 2.1, 3.3, 4.7, 5.1_

- [x] 2. Extend `BrowserViewModel` with context menu state and logic
  - [x] 2.1 Add `contextMenuTarget` `MutableStateFlow<ContextMenuTarget?>` and its public `StateFlow`
    - _Requirements: 3.3_
  - [x] 2.2 Implement `onLongPress(result: WebView.HitTestResult)`
    - Map `SRC_ANCHOR_TYPE` (non-video extra) → `ContextMenuTarget.Link`
    - Map `IMAGE_TYPE` → `ContextMenuTarget.Image`
    - Map `SRC_IMAGE_ANCHOR_TYPE` → `ContextMenuTarget.LinkAndImage`
    - Map `VIDEO_TYPE` or `SRC_ANCHOR_TYPE` with video extension extra → `ContextMenuTarget.Video`
    - Map `UNKNOWN_TYPE`, `EDIT_TEXT_TYPE`, or blank/null extra → leave `contextMenuTarget = null`
    - _Requirements: 3.4, 3.5, 4.1, 4.8_
  - [x] 2.3 Implement `dismissContextMenu()` — sets `contextMenuTarget = null`
    - Also call `dismissContextMenu()` inside `onPageStarted` so the menu auto-closes on navigation
    - _Requirements: 3.2_
  - [x] 2.4 Implement `downloadContextMenuTarget()`
    - Derive `url`, `fileName`, `mimeType` from the current `contextMenuTarget` variant
    - Call `DownloadService.startDownload` with the derived values and `_currentUrl.value` as `sourceUrl`
    - Call `dismissContextMenu()` after starting the download
    - _Requirements: 1.5, 2.5, 4.3_
  - [x] 2.5 Implement `openContextMenuTargetInNewTab()`
    - Call `addNewTab()` then `navigateTo(url)` using the URL from the current `contextMenuTarget`
    - Call `dismissContextMenu()` after
    - _Requirements: 1.3, 2.3_
  - [ ]* 2.6 Write unit tests for `onLongPress` hit-type mapping
    - Test `SRC_ANCHOR_TYPE` with non-video URL → `ContextMenuTarget.Link`
    - Test `IMAGE_TYPE` → `ContextMenuTarget.Image`
    - Test `SRC_IMAGE_ANCHOR_TYPE` → `ContextMenuTarget.LinkAndImage`
    - Test `VIDEO_TYPE` with non-blank extra → `ContextMenuTarget.Video`
    - Test `VIDEO_TYPE` with blank extra → `contextMenuTarget == null`
    - Test `UNKNOWN_TYPE` → `contextMenuTarget == null`
    - Test `onPageStarted` while menu open → `contextMenuTarget == null`
    - _Requirements: 3.1, 3.4, 3.5, 4.1, 4.8_
  - [ ]* 2.7 Write property test for non-media long-press never sets contextMenuTarget (Property 4)
    - **Property 4: Non-link/non-image long-press never sets contextMenuTarget**
    - **Validates: Requirements 3.1, 3.5**

- [-] 3. Extend `BrowserViewModel` with general download banner state and logic
  - [ ] 3.1 Add `pendingGeneralDownload` `MutableStateFlow<PendingGeneralDownload?>` and its public `StateFlow`
    - Add `private var bannerAutoDismissJob: Job?`
    - _Requirements: 5.1_
  - [ ] 3.2 Implement pure helper functions (internal, testable without Android context)
    - `internal fun inferFileName(url: String, contentDisposition: String?): String`
      - Parse `Content-Disposition` `filename=` / `filename*=` parameter first
      - Fall back to last path segment of URL (before `?`)
      - Fall back to `"download_<timestamp>"`
    - `internal fun ensureExtension(fileName: String, mimeType: String): String`
      - Return `fileName` unchanged if it already contains a known extension
      - Otherwise append extension from MIME-to-extension map
    - `internal fun mimeTypeToLabel(mimeType: String): String`
      - Map `application/pdf` → "PDF Document", `application/zip` / `application/x-zip-compressed` → "ZIP Archive", `application/vnd.android.package-archive` → "APK File", `image/*` → "Image", `text/*` → "Text File", everything else → "File"
    - _Requirements: 7.1, 7.2, 7.3_
  - [ ] 3.3 Implement `onGeneralDownloadIntercepted(url, contentDisposition, mimeType, contentLength)`
    - If `mimeType` starts with `"video/"` or `"audio/"` → return immediately without setting state
    - Otherwise build `PendingGeneralDownload` using `inferFileName` + `ensureExtension`, set `_pendingGeneralDownload.value`
    - Cancel any existing `bannerAutoDismissJob`, then launch a new one that calls `dismissGeneralDownload()` after 30 seconds
    - _Requirements: 5.1, 6.1, 6.2, 7.7_
  - [ ] 3.4 Implement `confirmGeneralDownload()`
    - Cancel `bannerAutoDismissJob`
    - Call `DownloadService.startDownload` with the pending download's fields
    - Set `_pendingGeneralDownload.value = null`
    - _Requirements: 5.5, 8.1_
  - [ ] 3.5 Implement `dismissGeneralDownload()`
    - Cancel `bannerAutoDismissJob`
    - Set `_pendingGeneralDownload.value = null`
    - _Requirements: 5.6_
  - Also call `dismissGeneralDownload()` inside `onPageStarted` so the banner auto-closes on navigation
    - _Requirements: error-handling row "User navigates away while banner is visible"_
  - [ ]* 3.6 Write unit tests for general download banner logic
    - `onGeneralDownloadIntercepted` with `video/mp4` → `pendingGeneralDownload == null`
    - `onGeneralDownloadIntercepted` with `application/pdf` → `pendingGeneralDownload != null`
    - `confirmGeneralDownload()` → `pendingGeneralDownload == null`
    - `dismissGeneralDownload()` → `pendingGeneralDownload == null`
    - Second intercept replaces first without confirming
    - Auto-dismiss: after 30 s `pendingGeneralDownload == null` (use `TestCoroutineScheduler`)
    - `inferFileName` with `Content-Disposition: attachment; filename="report.pdf"` → `"report.pdf"`
    - `inferFileName` with no header, URL `https://example.com/files/doc.pdf?token=abc` → `"doc.pdf"`
    - `ensureExtension("report", "application/pdf")` → `"report.pdf"`
    - `mimeTypeToLabel("application/pdf")` → `"PDF Document"`
    - `mimeTypeToLabel("image/png")` → `"Image"`
    - `mimeTypeToLabel("application/octet-stream")` → `"File"`
    - _Requirements: 5.1, 5.5, 5.6, 6.1, 6.2, 7.1, 7.2, 7.3_
  - [ ]* 3.7 Write property test: non-video MIME types always produce a pendingGeneralDownload (Property 5)
    - **Property 5: Non-video MIME types always produce a pendingGeneralDownload**
    - **Validates: Requirements 5.1, 6.1**
  - [ ]* 3.8 Write property test: video/audio MIME types never set pendingGeneralDownload (Property 6)
    - **Property 6: Video/audio MIME types never set pendingGeneralDownload**
    - **Validates: Requirements 5.1, 6.2**
  - [ ]* 3.9 Write property test: inferFileName always returns a non-blank name (Property 7)
    - **Property 7: File name inference always produces a non-blank name**
    - **Validates: Requirements 7.1**
  - [ ]* 3.10 Write property test: ensureExtension always produces a name with an extension (Property 8)
    - **Property 8: Extension inference always produces a name with an extension**
    - **Validates: Requirements 7.2**
  - [ ]* 3.11 Write property test: mimeTypeToLabel always returns a non-empty string (Property 9)
    - **Property 9: MIME-to-label mapping always returns a non-empty string**
    - **Validates: Requirements 7.3, 7.4**
  - [ ]* 3.12 Write property test: second download replaces first in banner state (Property 10)
    - **Property 10: Second download replaces first in banner state**
    - **Validates: Requirements 5.7**

- [ ] 4. Checkpoint — Ensure all ViewModel tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. Wire long-press listener and DownloadListener in `WebViewContent` (`BrowserScreen.kt`)
  - [ ] 5.1 Add `webView.setOnLongClickListener` inside `WebViewContent`
    - Call `webView.hitTestResult`, pass it to `viewModel.onLongPress(result)`, return `true`
    - _Requirements: 3.4_
  - [ ] 5.2 Add `webView.setDownloadListener` inside `WebViewContent`
    - Call `viewModel.onGeneralDownloadIntercepted(url, contentDisposition, mimeType, contentLength)`
    - _Requirements: 5.1, 6.1, 6.2_

- [ ] 6. Implement `ContextMenuBottomSheet` composable in `BrowserScreen.kt`
  - [ ] 6.1 Create `ContextMenuBottomSheet` as a `ModalBottomSheet` driven by `contextMenuTarget`
    - Show only when `contextMenuTarget != null`; call `viewModel.dismissContextMenu()` on dismiss
    - _Requirements: 1.1, 3.7_
  - [ ] 6.2 Render link actions for `ContextMenuTarget.Link`
    - Show URL subtitle, then four action rows: "Open in new tab", "Copy link address", "Download link", "Share link"
    - "Open in new tab" → `viewModel.openContextMenuTargetInNewTab()`
    - "Copy link address" → copy to clipboard + show Toast, then `viewModel.dismissContextMenu()`
    - "Download link" → `viewModel.downloadContextMenuTarget()`
    - "Share link" → Android system share sheet, then `viewModel.dismissContextMenu()`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_
  - [ ] 6.3 Render image actions for `ContextMenuTarget.Image`
    - Show Coil `AsyncImage` thumbnail preview above actions
    - Four action rows: "Open image in new tab", "Copy image URL", "Download image", "Share image"
    - Wire each action identically to the link variant (using image URL)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
  - [ ] 6.4 Render combined link+image actions for `ContextMenuTarget.LinkAndImage`
    - Show link action group, then `HorizontalDivider`, then image action group
    - _Requirements: 2.7_
  - [ ] 6.5 Render video actions for `ContextMenuTarget.Video`
    - Show video URL subtitle, then three action rows: "Download video", "Copy video URL", "Share video"
    - Wire each action to the corresponding ViewModel call / clipboard / share sheet
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_
  - Place `ContextMenuBottomSheet` call inside the content `Box` in `BrowserScreen`, alongside the existing `FloatingDownloadButton`
    - _Requirements: 3.3_

- [ ] 7. Implement `DownloadBanner` composable in `BrowserScreen.kt`
  - [ ] 7.1 Create `DownloadBanner` composable
    - Wrap in `AnimatedVisibility` with slide-down enter / slide-up exit animations
    - Position at the top of the content `Box`, below the URL bar `Surface`
    - Display file name, `mimeTypeToLabel` result, and file size (or "Unknown size" when null)
    - Include a "Download" button and a "✕" dismiss button
    - _Requirements: 5.2, 5.3, 5.4_
  - [ ] 7.2 Wire `DownloadBanner` actions
    - "Download" button → `viewModel.confirmGeneralDownload()`
    - "✕" button → `viewModel.dismissGeneralDownload()`
    - _Requirements: 5.5, 5.6_
  - [ ] 7.3 Add horizontal swipe-to-dismiss via `SwipeToDismissBox`
    - On swipe left or right → `viewModel.dismissGeneralDownload()`
    - _Requirements: 5.6_
  - Place `DownloadBanner` call inside the content `Box` in `BrowserScreen`, above the `FloatingDownloadButton` so the FAB remains visible
    - _Requirements: 6.4, 6.5_

- [ ] 8. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Property tests for context menu action lists
  - [ ]* 9.1 Write property test: link context menu contains all required actions (Property 1)
    - **Property 1: Link context menu contains all required actions**
    - **Validates: Requirements 1.1**
  - [ ]* 9.2 Write property test: image context menu contains all required actions (Property 2)
    - **Property 2: Image context menu contains all required actions**
    - **Validates: Requirements 2.1**
  - [ ]* 9.3 Write property test: combined link+image context menu contains all eight actions (Property 3)
    - **Property 3: Combined link+image context menu contains all eight actions**
    - **Validates: Requirements 2.7**
  - [ ]* 9.4 Write property test: video context menu contains all required actions (Property 11)
    - **Property 11: Video context menu contains all required actions**
    - **Validates: Requirements 4.1**

- [ ] 10. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- `VideoDetector`, the FAB, and the quality sheet are never modified — all new code is additive
- `DownloadService` is reused as-is; no changes needed there
- Kotest property tests use `Arb.string()` generators with a minimum of 100 iterations per property
- Each property test file should include the comment `// Feature: context-menu-and-general-download, Property N: <property text>`
- The `bannerAutoDismissJob` must be cancelled in both `confirmGeneralDownload()` and `dismissGeneralDownload()` to prevent a race where the timer fires after the user has already acted
