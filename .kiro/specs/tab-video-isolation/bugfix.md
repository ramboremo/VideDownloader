# Bugfix Requirements Document

## Introduction

Two related bugs cause `VideoDetector`'s singleton global state to bleed across tabs in the
multi-tab browser, producing incorrect UI on every tab switch:

**Bug 1 — Video state bleeds across tabs when switching.**
`VideoDetector` is a `@Singleton` with a single shared `detectedMedia` list, `currentPageUrl`,
`currentPageTitle`, and `currentPageThumbnail`. When the user switches from Tab A (which has
detected videos) to Tab B, `BrowserViewModel.switchToTab()` calls `clearDetectedMedia()` — but
this happens *after* the UI has already rendered Tab B with Tab A's detected media still in the
flow. The result is that Tab B momentarily (or persistently) shows the download FAB and video
overlay from Tab A. Conversely, when the user returns to Tab A, its previously detected videos
are gone because `clearDetectedMedia()` was called on switch, forcing the user to re-detect.

**Bug 2 — Wrong thumbnail shown on tab switch.**
`currentPageThumbnail` in `VideoDetector` is a single global field. `clearDetectedMedia()` does
reset it to `""`, but `setCurrentThumbnail()` is called from `onPageFinished` callbacks in
`BrowserScreen` without an active-tab guard. A background tab's `onPageFinished` (or its
delayed `postDelayed` re-detection) can call `setCurrentThumbnail()` after the user has already
switched to a different tab, overwriting the active tab's thumbnail. Any `DetectedMedia` object
created after that point bakes in the stale thumbnail from the wrong tab.

---

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the user switches from Tab A (which has detected videos) to Tab B THEN the system
    displays Tab A's detected video list and download FAB on Tab B before `clearDetectedMedia()`
    takes effect, causing a visible flash or persistent stale state.

1.2 WHEN the user switches from Tab A to Tab B and then returns to Tab A THEN the system shows
    no detected videos on Tab A because `clearDetectedMedia()` was called on the switch, erasing
    Tab A's previously detected state.

1.3 WHEN a background tab's `onPageFinished` or delayed `postDelayed` re-detection callback fires
    after the user has switched to a different tab THEN the system calls
    `videoDetector.setCurrentThumbnail()` without an active-tab guard, overwriting the active
    tab's `currentPageThumbnail` with the background tab's thumbnail URL.

1.4 WHEN a `DetectedMedia` object is created (in `onResourceRequest` or
    `registerSiteExtractorResult`) while `currentPageThumbnail` holds a stale value from a
    previous or background tab THEN the system bakes the wrong thumbnail URL into the
    `DetectedMedia.thumbnailUrl` field, and the download overlay shows the incorrect thumbnail
    for the lifetime of that detection result.

1.5 WHEN `shouldInterceptRequest` fires for a background tab's in-flight network request THEN
    the system may call `videoDetector.onResourceRequest()` even though the `isActiveTab` guard
    is present, because `setCurrentThumbnail` and the delayed `runVideoDetection` calls in
    `onPageFinished` are not guarded by `isActiveTab`.

1.6 WHEN `onVideoElementDetected` is called from the JS detection path for a background tab
    (via a `postDelayed` callback that fires after a tab switch) THEN the system calls
    `setCurrentPage()` and `onResourceRequest()` on the singleton detector, potentially
    corrupting `currentPageUrl` and `currentPageTitle` for the active tab.

### Expected Behavior (Correct)

2.1 WHEN the user switches from Tab A to Tab B THEN the system SHALL immediately show an empty
    video detection state (no FAB, no detected media) on Tab B without any flash of Tab A's
    detected media.

2.2 WHEN the user switches from Tab A to Tab B and then returns to Tab A THEN the system SHALL
    re-run video detection on Tab A's page so that previously detected videos can be recovered
    (since per-tab state is not persisted across switches).

2.3 WHEN a background tab's `onPageFinished` or delayed re-detection callback fires after a tab
    switch THEN the system SHALL NOT call `videoDetector.setCurrentThumbnail()` unless the
    callback belongs to the currently active tab (i.e., the call SHALL be guarded by
    `isActiveTab(tabId)`).

2.4 WHEN a `DetectedMedia` object is created for the active tab THEN the system SHALL use only
    the thumbnail URL that was fetched for that tab's current page, never a thumbnail from a
    different tab or a previous navigation on the same tab.

2.5 WHEN `onVideoElementDetected` is called from a delayed JS callback THEN the system SHALL
    verify that the tab that triggered the callback is still the active tab before forwarding
    the call to `VideoDetector`, so that `currentPageUrl` and `currentPageTitle` are never
    overwritten by a background tab.

2.6 WHEN `clearDetectedMedia()` is called as part of a tab switch THEN the system SHALL also
    reset `currentPageThumbnail` to an empty string (this already happens in the current
    implementation and SHALL be preserved).

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user browses on a single tab with no other tabs open THEN the system SHALL
    CONTINUE TO detect videos, display the download FAB, and show the correct thumbnail exactly
    as it does today.

3.2 WHEN the user navigates to a new URL within the same active tab THEN the system SHALL
    CONTINUE TO clear detected media and reset the thumbnail on `onPageStarted`, preserving the
    existing per-navigation reset behavior.

3.3 WHEN a background tab's `shouldInterceptRequest` fires for an in-flight network request
    THEN the system SHALL CONTINUE TO block that request via the ad-blocker if it matches an
    ad pattern, regardless of which tab is active.

3.4 WHEN the user opens a new tab THEN the system SHALL CONTINUE TO start with an empty video
    detection state (no FAB, no detected media) on the new tab.

3.5 WHEN the user closes a tab THEN the system SHALL CONTINUE TO destroy that tab's `WebView`
    and free its resources, with no lingering detection state from the closed tab.

3.6 WHEN the active tab's `onPageFinished` fires and the page contains an `og:image` or
    `apple-touch-icon` meta tag THEN the system SHALL CONTINUE TO capture that URL as the
    thumbnail for detected media on that tab.

3.7 WHEN the active tab's delayed re-detection (`postDelayed` at 2.5 s and 5 s) fires THEN the
    system SHALL CONTINUE TO run `runVideoDetection` and update detected media for that tab,
    provided the tab is still active at the time the callback executes.

3.8 WHEN the user switches tabs while the quality sheet is open THEN the system SHALL CONTINUE
    TO dismiss the quality sheet and cancel any in-flight quality-fetch job, as it does today
    via `cancelQualityFetch()` in `switchToTab()`.

3.9 WHEN `VideoDetector.clearDetectedMedia()` is called THEN the system SHALL CONTINUE TO
    cancel any in-flight site-specific extraction job, clear `detectedUrls`, reset
    `detectionCounter`, clear all prefetch caches, and reset `currentPageThumbnail` to `""`.

---

## Bug Condition Pseudocode

### Bug 1 — Video State Bleed

```pascal
FUNCTION isBugCondition_StateBleed(event)
  INPUT: event — a tab-switch action from tab A to tab B
  OUTPUT: boolean

  RETURN tab A has detectedMedia.size > 0
     AND tab B is a different tab from tab A
     AND VideoDetector is a singleton (shared state)
END FUNCTION

// Property: Fix Checking — Tab B must start clean
FOR ALL switch_event WHERE isBugCondition_StateBleed(switch_event) DO
  result ← detectedMedia state observed on Tab B immediately after switch
  ASSERT result.isEmpty()
  ASSERT downloadFabVisible = false
END FOR

// Property: Preservation Checking — Tab A state is not permanently lost
FOR ALL switch_event WHERE isBugCondition_StateBleed(switch_event) DO
  ASSERT F(Tab A) = F'(Tab A)   // single-tab browsing unaffected
END FOR
```

### Bug 2 — Stale Thumbnail

```pascal
FUNCTION isBugCondition_StaleThumbnail(event)
  INPUT: event — a setCurrentThumbnail() call
  OUTPUT: boolean

  RETURN the tabId that triggered the call is NOT the currently active tab
END FUNCTION

// Property: Fix Checking — thumbnail must belong to the active tab
FOR ALL event WHERE isBugCondition_StaleThumbnail(event) DO
  ASSERT currentPageThumbnail is NOT updated by this event
  ASSERT DetectedMedia.thumbnailUrl for active tab is NOT overwritten
END FOR

// Property: Preservation Checking — active tab thumbnail still captured
FOR ALL event WHERE NOT isBugCondition_StaleThumbnail(event) DO
  ASSERT F(thumbnail capture) = F'(thumbnail capture)
END FOR
```
