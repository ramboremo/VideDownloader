# Requirements Document

## Introduction

This feature adds two complementary capabilities to the xdownload Android browser app:

1. **Long-press context menu** — When the user long-presses a link or image inside the WebView, a bottom sheet appears with context-sensitive actions (open in new tab, copy, download, share). This mirrors the Chrome long-press experience without Chrome-specific items.

2. **General file download banner** — When the WebView triggers a non-video download (PDF, ZIP, APK, image, etc.), an in-app dismissible banner appears at the top of the browser screen, letting the user confirm or dismiss the download. This is completely separate from the existing video detection / floating download button flow, which remains unchanged.

## Glossary

- **ContextMenu**: The bottom sheet that appears when the user long-presses a link, image, or video in the WebView.
- **ContextMenuTarget**: The element the user long-pressed — a hyperlink (`HitTestResult.SRC_ANCHOR_TYPE`), an image (`HitTestResult.IMAGE_TYPE` / `SRC_IMAGE_ANCHOR_TYPE`), or a video (`HitTestResult.SRC_ANCHOR_TYPE` / `VIDEO_TYPE` where the extra data contains a direct video URL).
- **DownloadBanner**: The dismissible in-app notification strip shown at the top of the browser screen when a non-video downloadable file is intercepted.
- **GeneralDownload**: A download of a non-video file type (PDF, ZIP, APK, image, document, etc.) triggered by the WebView's `DownloadListener` or `shouldOverrideUrlLoading`.
- **VideoDetector**: The existing singleton service that detects video/audio media URLs. Must not be modified by this feature.
- **DownloadService**: The existing background service that performs file downloads. Reused as-is for general downloads.
- **BrowserViewModel**: The existing ViewModel for the browser screen. Extended with new state for the context menu and download banner.
- **BrowserScreen**: The existing Compose screen for the browser. Extended with new UI composables for the context menu and download banner.
- **WebView**: The Android `WebView` component embedded in `BrowserScreen`.
- **HitTestResult**: The Android `WebView.getHitTestResult()` result used to determine what element was long-pressed.

---

## Requirements

### Requirement 1: Long-Press Context Menu — Link Target

**User Story:** As a user browsing in the app, I want to long-press a hyperlink and see a context menu with relevant actions, so that I can open, copy, download, or share the link without leaving the current page.

#### Acceptance Criteria

1. WHEN the user long-presses a hyperlink in the WebView, THE ContextMenu SHALL appear as a bottom sheet containing the actions: "Open in new tab", "Copy link address", "Download link", and "Share link".
2. WHEN the ContextMenu is displayed for a link target, THE ContextMenu SHALL show the link URL as a subtitle beneath the action list so the user can verify the target.
3. WHEN the user taps "Open in new tab" in the ContextMenu, THE BrowserViewModel SHALL open a new tab and load the link URL in it.
4. WHEN the user taps "Copy link address" in the ContextMenu, THE BrowserScreen SHALL copy the link URL to the system clipboard and show a brief confirmation toast.
5. WHEN the user taps "Download link" in the ContextMenu, THE BrowserViewModel SHALL immediately start the download via `DownloadService.startDownload` without showing the DownloadBanner.
6. WHEN the user taps "Share link" in the ContextMenu, THE BrowserScreen SHALL invoke the Android system share sheet with the link URL.
7. WHEN the ContextMenu is open and the user taps outside it or swipes it down, THE ContextMenu SHALL dismiss without performing any action.

---

### Requirement 2: Long-Press Context Menu — Image Target

**User Story:** As a user browsing in the app, I want to long-press an image and see a context menu with relevant actions, so that I can open, copy, download, or share the image.

#### Acceptance Criteria

1. WHEN the user long-presses an image in the WebView, THE ContextMenu SHALL appear as a bottom sheet containing the actions: "Open image in new tab", "Copy image URL", "Download image", and "Share image".
2. WHEN the ContextMenu is displayed for an image target, THE ContextMenu SHALL show a thumbnail preview of the image (loaded from the image URL) above the action list.
3. WHEN the user taps "Open image in new tab" in the ContextMenu, THE BrowserViewModel SHALL open a new tab and load the image URL in it.
4. WHEN the user taps "Copy image URL" in the ContextMenu, THE BrowserScreen SHALL copy the image URL to the system clipboard and show a brief confirmation toast.
5. WHEN the user taps "Download image" in the ContextMenu, THE BrowserViewModel SHALL immediately start the download via `DownloadService.startDownload` without showing the DownloadBanner.
6. WHEN the user taps "Share image" in the ContextMenu, THE BrowserScreen SHALL invoke the Android system share sheet with the image URL.
7. IF the long-pressed element is both a link and an image (anchor wrapping an image), THEN THE ContextMenu SHALL display both the link actions and the image actions, with a visual separator between the two groups.

---

### Requirement 3: Long-Press Context Menu — Trigger and Dismissal

**User Story:** As a user, I want the context menu to appear reliably on long-press and disappear cleanly, so that normal browsing is not disrupted.

#### Acceptance Criteria

1. WHEN the user long-presses any element in the WebView that is NOT a link or image, THE ContextMenu SHALL NOT appear.
2. WHEN the ContextMenu is open and the user navigates to a new page, THE ContextMenu SHALL automatically dismiss.
3. THE BrowserViewModel SHALL expose a single `contextMenuTarget` state of type `ContextMenuTarget?` that is `null` when no menu is shown and non-null when a menu should be displayed.
4. WHEN the WebView fires `setOnLongClickListener`, THE BrowserScreen SHALL call `webView.getHitTestResult()` to determine the target type and populate `contextMenuTarget` via the ViewModel.
5. IF the `HitTestResult` type is `UNKNOWN_TYPE` or `EDIT_TEXT_TYPE`, THEN THE BrowserScreen SHALL NOT set a `contextMenuTarget`.

---

### Requirement 4: Long-Press Context Menu — Video Target

**User Story:** As a user browsing in the app, I want to long-press a `<video>` element that has a direct source URL and see a context menu with relevant actions, so that I can download, copy, or share the video URL without using the floating download button.

#### Acceptance Criteria

1. WHEN the user long-presses a `<video>` element in the WebView whose source URL is directly accessible (detected via `HitTestResult` returning `SRC_ANCHOR_TYPE` or `VIDEO_TYPE` with a non-blank extra data URL), THE ContextMenu SHALL appear as a bottom sheet containing the actions: "Download video", "Copy video URL", and "Share video".
2. WHEN the ContextMenu is displayed for a video target, THE ContextMenu SHALL show the video URL as a subtitle beneath the action list so the user can verify the source.
3. WHEN the user taps "Download video" in the ContextMenu, THE BrowserViewModel SHALL immediately start the download via `DownloadService.startDownload` without showing the DownloadBanner.
4. WHEN the user taps "Copy video URL" in the ContextMenu, THE BrowserScreen SHALL copy the video URL to the system clipboard and show a brief confirmation toast.
5. WHEN the user taps "Share video" in the ContextMenu, THE BrowserScreen SHALL invoke the Android system share sheet with the video URL.
6. WHEN the ContextMenu is open and the user taps outside it or swipes it down, THE ContextMenu SHALL dismiss without performing any action.
7. THE ContextMenuTarget sealed class SHALL include a `Video(videoUrl: String)` variant used exclusively for this case.
8. IF the `HitTestResult` extra data URL is blank or null for a video hit type, THEN THE BrowserScreen SHALL NOT set a `contextMenuTarget`.
9. THE video long-press context menu SHALL be independent of the existing VideoDetector and floating download button flow — it applies only to plain HTML5 `<video>` elements with a directly accessible source URL.

---

### Requirement 5: General File Download Banner — Appearance

**User Story:** As a user, I want to see a non-intrusive banner when a downloadable file is detected, so that I can confirm or dismiss the download without interrupting my browsing.

#### Acceptance Criteria

1. WHEN the WebView's `DownloadListener.onDownloadStart` is triggered for a non-video MIME type, THE BrowserViewModel SHALL set a `pendingGeneralDownload` state containing the URL, inferred file name, MIME type, and estimated file size.
2. WHEN `pendingGeneralDownload` is non-null, THE BrowserScreen SHALL display the DownloadBanner at the top of the content area (below the URL bar) with an animated slide-down entrance.
3. THE DownloadBanner SHALL display the file name, a human-readable file type label (e.g., "PDF Document", "ZIP Archive", "APK File"), and a file size estimate when available.
4. THE DownloadBanner SHALL contain a "Download" button and a dismiss ("✕") button.
5. WHEN the user taps the "Download" button in the DownloadBanner, THE BrowserViewModel SHALL start the download via `DownloadService.startDownload` and clear `pendingGeneralDownload`.
6. WHEN the user taps the dismiss button or swipes the DownloadBanner left or right, THE BrowserViewModel SHALL clear `pendingGeneralDownload` and the banner SHALL animate out.
7. WHEN a new `pendingGeneralDownload` arrives while a banner is already visible, THE BrowserScreen SHALL replace the existing banner with the new one.
8. WHEN the DownloadBanner has been visible for 30 seconds without the user tapping "Download" or the dismiss button, THE BrowserViewModel SHALL automatically clear `pendingGeneralDownload` and the banner SHALL animate out.

---

### Requirement 6: General File Download Banner — Scope and Isolation

**User Story:** As a developer, I want the general download banner to be completely isolated from the existing video detection flow, so that video downloads continue to work exactly as before.

#### Acceptance Criteria

1. THE BrowserViewModel SHALL only set `pendingGeneralDownload` for MIME types that are NOT video or audio (i.e., MIME types that do NOT start with `video/` or `audio/`).
2. WHEN `DownloadListener.onDownloadStart` is triggered for a `video/` or `audio/` MIME type, THE BrowserViewModel SHALL NOT set `pendingGeneralDownload` and SHALL allow the existing VideoDetector flow to handle it.
3. THE VideoDetector SHALL NOT be modified by this feature.
4. THE existing floating download button (FAB) and quality sheet SHALL continue to function independently of the DownloadBanner.
5. WHEN the DownloadBanner is visible, THE floating download FAB SHALL remain visible and functional.

---

### Requirement 7: General File Download — File Name and Type Inference

**User Story:** As a user, I want the download banner to show a meaningful file name and type, so that I know what I am about to download.

#### Acceptance Criteria

1. WHEN `pendingGeneralDownload` is set, THE BrowserViewModel SHALL infer the file name from the `Content-Disposition` header if present, falling back to the last path segment of the URL.
2. WHEN the inferred file name has no extension, THE BrowserViewModel SHALL append an appropriate extension derived from the MIME type (e.g., `application/pdf` → `.pdf`, `application/zip` → `.zip`, `application/vnd.android.package-archive` → `.apk`).
3. THE BrowserViewModel SHALL map common MIME types to human-readable labels: `application/pdf` → "PDF Document", `application/zip` → "ZIP Archive", `application/vnd.android.package-archive` → "APK File", `image/*` → "Image", `text/*` → "Text File", and all other types → "File".
4. FOR ALL valid MIME type strings, the MIME-to-label mapping SHALL return a non-empty string (no null or blank labels).

---

### Requirement 8: General File Download — Integration with DownloadService

**User Story:** As a user, I want confirmed general downloads to be tracked and managed the same way as video downloads, so that I can see their progress in the Files screen.

#### Acceptance Criteria

1. WHEN the user confirms a GeneralDownload via the DownloadBanner, THE BrowserViewModel SHALL call `DownloadService.startDownload` with the correct URL, file name, MIME type, and source URL.
2. WHEN `DownloadService` receives a GeneralDownload, THE DownloadService SHALL save the download to the database and show a system notification with progress, identical to the existing video download flow.
3. THE DownloadService SHALL NOT require any modification to support GeneralDownload — it already handles arbitrary MIME types.
4. FOR ALL non-video MIME types passed to `DownloadService.startDownload`, the service SHALL NOT reject the download based on MIME type alone.
