# Tab Video Isolation Bugfix Design

## Overview

Two bugs cause `VideoDetector`'s singleton global state to bleed across tabs in the multi-tab
browser. **Bug 1** (state bleed) occurs because `BrowserViewModel.switchToTab()` calls
`clearDetectedMedia()` *after* updating tab state, allowing Compose to recompose with the new
tab's identity but the old tab's detected media still in the shared `StateFlow`. It also
permanently destroys Tab A's detected media on every switch. **Bug 2** (stale thumbnail) occurs
because `runVideoDetection` and the `videoSignalsJs` injection in `onPageFinished` lack an
`isActiveTab(tabId)` guard, so background tabs' `postDelayed` callbacks can call
`setCurrentThumbnail()` and overwrite the active tab's thumbnail.

The fix is minimal and surgical:
- **Fix 1**: Add a single `if (!viewModel.isActiveTab(tabId)) return` guard in `onPageFinished`
  (after `onTitleChanged`) plus inner `isActiveTab` guards inside the two `postDelayed` lambdas.
- **Fix 2**: Introduce a `tabDetectedMediaCache: MutableMap<String, List<DetectedMedia>>` in
  `BrowserViewModel`. On `switchToTab()`, save the outgoing tab's media to the cache, clear the
  singleton, then restore the incoming tab's cached media. Evict cache entries on tab close,
  `closeAllTabs()`, and `onPageStarted()` (navigation invalidates the cache for that tab).
  Add `VideoDetector.restoreDetectedMedia()` to support the restore path.

---

## Glossary

- **Bug_Condition (C)**: The condition that triggers a bug — either (C1) a tab switch where the
  outgoing tab has detected media, or (C2) a `setCurrentThumbnail()` call originating from a
  background tab's `onPageFinished` or `postDelayed` callback.
- **Property (P)**: The desired correct behavior — (P1) Tab B starts with empty detection state
  and Tab A's media is preserved in cache; (P2) `currentPageThumbnail` is only updated by the
  active tab.
- **Preservation**: All single-tab browsing behavior, per-navigation resets, ad blocking, and
  existing guarded callbacks that must remain unchanged by this fix.
- **`VideoDetector`**: The `@Singleton` in `VideoDetector.kt` that owns `_detectedMedia`,
  `_hasMedia`, `detectedUrls`, `currentPageThumbnail`, `currentPageUrl`, and `currentPageTitle`.
- **`switchToTab(index)`**: The function in `BrowserViewModel.kt` that changes the active tab
  and currently calls `clearDetectedMedia()` before updating tab state.
- **`runVideoDetection(v, pageUrl)`**: A local function inside `onPageFinished` in
  `BrowserScreen.kt` that evaluates JS to extract video URLs and calls `setCurrentThumbnail()`.
- **`tabDetectedMediaCache`**: The new `MutableMap<String, List<DetectedMedia>>` added to
  `BrowserViewModel` to hold per-tab detected media across tab switches.
- **`isActiveTab(tabId)`**: The existing guard function in `BrowserViewModel` that returns true
  only when `tabId` matches the currently active tab's ID.

---

## Bug Details

### Bug Condition

**Bug 1 — State Bleed:** The bug manifests when the user switches from a tab that has detected
media to any other tab. `switchToTab()` calls `clearDetectedMedia()` first, then updates
`_tabs`, `_activeTabIndex`, `_currentUrl`, and `_currentTitle`. Compose can recompose between
these two state updates, briefly showing the new tab's identity with the old tab's media still
in the `StateFlow`. Additionally, `clearDetectedMedia()` permanently destroys the outgoing
tab's media — returning to Tab A shows no videos.

**Bug 2 — Stale Thumbnail:** The bug manifests when a background tab's `onPageFinished` fires
(or its `postDelayed` re-detection callback fires after a tab switch). The `runVideoDetection`
local function calls `viewModel.videoDetector.setCurrentThumbnail(cleanThumb)` with no
`isActiveTab(tabId)` guard, overwriting `currentPageThumbnail` in the singleton. Any
`DetectedMedia` created after that point bakes in the wrong thumbnail.

**Formal Specification:**

```
FUNCTION isBugCondition_StateBleed(event)
  INPUT: event — a tab-switch action from tabA to tabB
  OUTPUT: boolean

  RETURN tabA.detectedMedia.size > 0
     AND tabB.id != tabA.id
     AND VideoDetector is a singleton (no per-tab cache exists)
END FUNCTION

FUNCTION isBugCondition_StaleThumbnail(event)
  INPUT: event — a setCurrentThumbnail() call
  OUTPUT: boolean

  RETURN event.originTabId != currentlyActiveTabId
END FUNCTION
```

### Examples

- **Bug 1, Example A**: User loads a video on Tab A → 3 videos detected → switches to Tab B →
  Tab B momentarily shows 3 videos and the download FAB (flash of stale state). After
  `clearDetectedMedia()` fires, Tab B shows 0 videos. Switching back to Tab A also shows 0
  videos (permanently lost).
- **Bug 1, Example B**: User opens Tab B immediately after Tab A finishes loading. Tab B starts
  with 0 videos (correct), but Tab A's media is gone when returning to it.
- **Bug 2, Example A**: User loads a video page on Tab A (triggers 2.5 s `postDelayed`), then
  switches to Tab B within 1 second. At t=2.5 s, Tab A's callback fires and calls
  `setCurrentThumbnail("https://tab-a-thumb.jpg")`. Tab B's next `DetectedMedia` object is
  created with `thumbnailUrl = "https://tab-a-thumb.jpg"`.
- **Bug 2, Edge Case**: User switches tabs rapidly (A→B→C). Multiple background `postDelayed`
  callbacks race to call `setCurrentThumbnail()`, each overwriting the previous value.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Single-tab browsing: video detection, FAB display, thumbnail capture, and quality sheet must
  work exactly as today.
- Per-navigation reset: `onPageStarted` already calls `clearDetectedMedia()` and
  `setCurrentPage()` — this must continue unchanged.
- Ad blocking in `shouldInterceptRequest` must continue for all tabs (already guarded correctly,
  do not touch).
- `onPageStarted`, `onReceivedError`, `onProgressChanged`, `onTitleChanged` callbacks are
  already correctly guarded — do not touch them.
- `clearDetectedMedia()` internals (cancels extraction job, clears `detectedUrls`,
  `detectionCounter`, prefetch caches, resets `currentPageThumbnail`) must remain unchanged.
- Quality sheet dismissal and `cancelQualityFetch()` on tab switch must continue to work.
- Closing a tab must continue to destroy its `WebView` and free resources.

**Scope:**
All inputs that do NOT involve a tab switch with detected media, or a background tab's
`onPageFinished`/`postDelayed` callback, should be completely unaffected by this fix. This
includes:
- All single-tab browsing flows
- Mouse/touch interactions with the browser UI
- Navigation within the same active tab
- Ad blocking for background tabs

---

## Hypothesized Root Cause

1. **Missing atomic state update in `switchToTab()`**: `clearDetectedMedia()` and the tab index
   update are separate `StateFlow` mutations. Compose's snapshot system can observe an
   intermediate state where the tab has changed but the media has not yet been cleared.

2. **No per-tab media persistence**: The singleton `_detectedMedia` is wiped on every tab
   switch. There is no mechanism to save and restore per-tab detection state, so returning to a
   previously visited tab always shows an empty state.

3. **Missing `isActiveTab` guard in `onPageFinished`**: The `videoSignalsJs` injection and
   `runVideoDetection` calls (including `postDelayed` lambdas) execute for every tab's
   `onPageFinished`, not just the active tab. `wv.onPause()` stops JS timers but does not
   cancel already-queued `postDelayed` messages, so background tab callbacks fire after a switch.

4. **`postDelayed` lambdas capture tab context but don't re-check active status**: Even if an
   outer `isActiveTab` guard is added, the 2.5 s and 5 s lambdas are posted before the switch
   and execute after it. Each lambda needs its own inner `isActiveTab` check at execution time.

---

## Correctness Properties

Property 1: Bug Condition — Tab B Starts with Empty Detection State

_For any_ tab-switch event where the outgoing tab has `detectedMedia.size > 0`, the fixed
`switchToTab()` SHALL ensure that `videoDetector.detectedMedia` is empty and
`videoDetector.hasMedia` is false immediately after the switch completes, with no intermediate
Compose recomposition observing the outgoing tab's media on the incoming tab.

**Validates: Requirements 2.1**

Property 2: Bug Condition — Tab A Media Restored from Cache on Return

_For any_ tab-switch sequence A→B→A where Tab A had detected media before the first switch,
the fixed `switchToTab()` SHALL restore Tab A's detected media from `tabDetectedMediaCache`
when switching back to Tab A, so the user sees the same videos without re-detection.

**Validates: Requirements 2.2**

Property 3: Bug Condition — Background Tab Cannot Overwrite Active Tab's Thumbnail

_For any_ `setCurrentThumbnail()` call originating from a background tab's `onPageFinished` or
`postDelayed` callback (i.e., `isActiveTab(tabId)` is false at call time), the fixed code SHALL
NOT update `currentPageThumbnail` in `VideoDetector`, preserving the active tab's thumbnail.

**Validates: Requirements 2.3, 2.4**

Property 4: Preservation — Single-Tab Browsing Unaffected

_For any_ browsing session with exactly one tab, the fixed code SHALL produce exactly the same
detection behavior, thumbnail capture, FAB visibility, and quality sheet behavior as the
original code.

**Validates: Requirements 3.1, 3.2, 3.6, 3.7**

Property 5: Preservation — Cache Evicted on Tab Close and Navigation

_For any_ tab that is closed (via `closeTab()` or `closeAllTabs()`) or navigates to a new URL
(via `onPageStarted()`), the fixed code SHALL remove that tab's entry from
`tabDetectedMediaCache`, so stale media is never restored for a recycled or navigated tab.

**Validates: Requirements 3.2, 3.5**

---

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

---

**File**: `app/src/main/java/com/cognitivechaos/xdownload/ui/browser/BrowserScreen.kt`

**Function**: `onPageFinished` (inside `WebViewClient` anonymous object)

**Specific Changes**:

1. **Add outer `isActiveTab` guard**: After the `view?.title?.let { viewModel.onTitleChanged(tabId, it) }` call and before the `videoSignalsJs` injection block, insert:
   ```kotlin
   if (!viewModel.isActiveTab(tabId)) return
   ```
   This single line prevents both the JS injection and all `runVideoDetection` calls from
   executing for background tabs.

2. **Add inner guards in `postDelayed` lambdas**: Wrap each `postDelayed` body with an
   `isActiveTab` check so that a tab switch occurring *after* the outer guard passes but
   *before* the delayed callback fires does not execute detection for a now-background tab:
   ```kotlin
   view.postDelayed({
       if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
   }, 2500)
   view.postDelayed({
       if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
   }, 5000)
   ```

---

**File**: `app/src/main/java/com/cognitivechaos/xdownload/service/VideoDetector.kt`

**Specific Changes**:

3. **Add `restoreDetectedMedia()` method**: This method restores a previously saved
   `List<DetectedMedia>` into the singleton's state, including repopulating `detectedUrls` for
   correct deduplication and restoring `currentPageUrl`/`currentPageTitle`/`currentPageThumbnail`
   from the first media item:
   ```kotlin
   fun restoreDetectedMedia(media: List<DetectedMedia>) {
       detectedUrls.clear()
       media.forEach { detectedUrls.add(it.url) }
       _detectedMedia.value = media
       _hasMedia.value = media.isNotEmpty()
       media.firstOrNull()?.let { first ->
           currentPageUrl = first.sourcePageUrl
           currentPageTitle = first.sourcePageTitle
           currentPageThumbnail = first.thumbnailUrl ?: ""
       }
   }
   ```

---

**File**: `app/src/main/java/com/cognitivechaos/xdownload/ui/browser/BrowserViewModel.kt`

**Specific Changes**:

4. **Add `tabDetectedMediaCache`**: Declare a new private map at the top of the class body:
   ```kotlin
   private val tabDetectedMediaCache = mutableMapOf<String, List<DetectedMedia>>()
   ```

5. **Modify `switchToTab()`**: Before calling `clearDetectedMedia()`, save the outgoing tab's
   media to the cache. After updating tab state, restore the incoming tab's cached media (if
   any):
   ```kotlin
   // Save outgoing tab's media
   val currentTabId = _tabs.value.getOrNull(_activeTabIndex.value)?.id
   if (currentTabId != null) {
       tabDetectedMediaCache[currentTabId] = videoDetector.detectedMedia.value
   }
   videoDetector.clearDetectedMedia()
   // ... existing tab state updates ...
   // Restore incoming tab's cached media
   val newTabId = updatedTabs[index].id
   val cached = tabDetectedMediaCache[newTabId]
   if (!cached.isNullOrEmpty()) {
       videoDetector.restoreDetectedMedia(cached)
   }
   ```

6. **Evict cache in `closeTab()`**: Before removing the tab from `_tabs`, capture its ID and
   remove it from the cache:
   ```kotlin
   val closedTabId = _tabs.value.getOrNull(index)?.id
   if (closedTabId != null) tabDetectedMediaCache.remove(closedTabId)
   ```

7. **Evict cache in `closeAllTabs()`**: Clear the entire cache when all tabs are closed:
   ```kotlin
   tabDetectedMediaCache.clear()
   ```

8. **Evict cache in `onPageStarted()`**: Remove the navigating tab's cache entry so that
   returning to it after a switch does not restore stale media from the previous page:
   ```kotlin
   tabDetectedMediaCache.remove(tabId)
   ```
   This line should be added inside the existing `if (!isActiveTab(tabId)) return` block is
   NOT the right place — it must run for ALL tabs (including background tabs navigating), so
   add it before the `isActiveTab` guard, alongside the existing `updateTab(tabId, url = url)`.

---

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that
demonstrate each bug on unfixed code, then verify the fix works correctly and preserves
existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bugs BEFORE implementing the fix.
Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write unit tests against `BrowserViewModel` and `VideoDetector` directly,
simulating tab switches and background `postDelayed` callbacks. Run these tests on the UNFIXED
code to observe failures and understand the root cause.

**Test Cases**:

1. **State Bleed Test** (will fail on unfixed code): Add two tabs, inject detected media into
   `videoDetector._detectedMedia`, call `switchToTab(1)`. Assert that `detectedMedia.value` is
   empty immediately after the call. On unfixed code, there is a window where it is not empty.

2. **Media Loss on Return Test** (will fail on unfixed code): Add Tab A with detected media,
   switch to Tab B, switch back to Tab A. Assert `detectedMedia.value` is non-empty. On unfixed
   code, it is empty because `clearDetectedMedia()` wiped it.

3. **Stale Thumbnail Test** (will fail on unfixed code): Set active tab to Tab B. Simulate Tab
   A's `postDelayed` callback by calling `videoDetector.setCurrentThumbnail("tab-a-thumb")`
   directly (no `isActiveTab` guard exists yet). Assert `currentPageThumbnail` is NOT
   `"tab-a-thumb"`. On unfixed code, it is updated.

4. **Background `onPageFinished` Test** (will fail on unfixed code): With Tab B active, call
   `onPageFinished` for Tab A's `tabId`. Assert that `videoDetector.detectedMedia` is not
   updated. On unfixed code, the JS injection and detection run for Tab A.

**Expected Counterexamples**:
- `detectedMedia` is non-empty immediately after `switchToTab()` (race window)
- `detectedMedia` is empty when returning to a previously visited tab
- `currentPageThumbnail` is overwritten by a background tab's callback

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed code produces the
expected behavior.

**Pseudocode:**
```
// Bug 1 fix check
FOR ALL switch_event WHERE isBugCondition_StateBleed(switch_event) DO
  result_tabB := detectedMedia.value immediately after switchToTab()
  ASSERT result_tabB.isEmpty()
  result_tabA_on_return := detectedMedia.value after switching back to tabA
  ASSERT result_tabA_on_return == original_tabA_media
END FOR

// Bug 2 fix check
FOR ALL thumbnail_event WHERE isBugCondition_StaleThumbnail(thumbnail_event) DO
  thumbnail_before := currentPageThumbnail
  simulate background tab postDelayed callback
  ASSERT currentPageThumbnail == thumbnail_before  // unchanged
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed code
produces the same result as the original code.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition_StateBleed(input)
                AND NOT isBugCondition_StaleThumbnail(input) DO
  ASSERT BrowserViewModel_original(input) = BrowserViewModel_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for single-tab flows and cache eviction
scenarios, then write property-based tests capturing that behavior.

**Test Cases**:

1. **Single-Tab Preservation**: With one tab, detect videos, navigate to new URL. Assert
   `clearDetectedMedia()` still fires on `onPageStarted` and detection state resets correctly.

2. **Cache Eviction on Close**: Close Tab A. Assert `tabDetectedMediaCache` no longer contains
   Tab A's entry.

3. **Cache Eviction on Navigation**: Tab A navigates to a new URL (`onPageStarted` fires).
   Assert `tabDetectedMediaCache[tabAId]` is removed.

4. **Active Tab Thumbnail Still Captured**: With Tab A active, simulate `onPageFinished` for
   Tab A. Assert `setCurrentThumbnail()` is called and `currentPageThumbnail` is updated.

5. **`closeAllTabs()` Clears Cache**: Open 3 tabs with cached media, call `closeAllTabs()`.
   Assert `tabDetectedMediaCache` is empty.

### Unit Tests

- Test `switchToTab()` saves outgoing tab's media to cache before clearing
- Test `switchToTab()` restores incoming tab's cached media after clearing
- Test `switchToTab()` with no cached media for incoming tab leaves detection empty
- Test `closeTab()` removes the closed tab's cache entry
- Test `closeAllTabs()` clears the entire cache
- Test `onPageStarted()` removes the navigating tab's cache entry (for both active and
  background tabs)
- Test `restoreDetectedMedia()` correctly repopulates `_detectedMedia`, `_hasMedia`,
  `detectedUrls`, and `currentPageThumbnail`
- Test that `onPageFinished` for a background tab does NOT call `setCurrentThumbnail()`

### Property-Based Tests

- Generate random sequences of tab switches with varying detected media counts; assert that
  after each switch the incoming tab's state matches either its cached media or empty
- Generate random `List<DetectedMedia>` inputs to `restoreDetectedMedia()`; assert that
  `detectedMedia.value == input` and `hasMedia.value == input.isNotEmpty()` and
  `detectedUrls` contains exactly the URLs from input
- Generate random tab IDs and assert that `isActiveTab(tabId)` correctly gates all
  `setCurrentThumbnail()` paths across many simulated background callbacks

### Integration Tests

- Full flow: open two tabs, load video page on Tab A, switch to Tab B, switch back to Tab A —
  assert Tab A's videos are restored from cache
- Full flow: load video page on Tab A, switch to Tab B within 1 s, wait 3 s for `postDelayed`
  callbacks — assert Tab B's `currentPageThumbnail` is not overwritten by Tab A's callback
- Full flow: Tab A navigates to a new URL while Tab B is active — assert Tab A's cache entry
  is evicted so returning to Tab A shows empty detection state
