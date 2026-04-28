# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Tab Video State Bleed & Stale Thumbnail
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate both bugs exist
  - **Scoped PBT Approach**: Scope to concrete failing cases — (a) switch from a tab with N>0 detected media items, (b) background tab calling `setCurrentThumbnail()`
  - Write unit tests in `BrowserViewModelTest.kt` and `VideoDetectorTest.kt` against the real classes
  - **Test Case A — State Bleed**: Create two `BrowserTab` entries; inject detected media into `videoDetector._detectedMedia`; call `switchToTab(1)`; assert `detectedMedia.value.isEmpty()` immediately after — on unfixed code this assertion fails because `clearDetectedMedia()` fires after the tab index update
  - **Test Case B — Media Loss on Return**: Inject media on Tab A; call `switchToTab(1)`; call `switchToTab(0)`; assert `detectedMedia.value.isNotEmpty()` — on unfixed code this fails because `clearDetectedMedia()` permanently wiped Tab A's media
  - **Test Case C — Stale Thumbnail**: Set active tab to Tab B; call `videoDetector.setCurrentThumbnail("tab-a-thumb")` directly (no guard exists yet); assert `videoDetector.currentPageThumbnail != "tab-a-thumb"` — on unfixed code this fails because there is no `isActiveTab` guard
  - **Test Case D — Background `onPageFinished`**: With Tab B active, simulate `onPageFinished` for Tab A's `tabId`; assert `videoDetector.detectedMedia` is not updated — on unfixed code the JS injection and detection run for Tab A
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples found (e.g., "detectedMedia non-empty immediately after switchToTab", "currentPageThumbnail overwritten by background tab")
  - Mark task complete when tests are written, run, and failures are documented
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Single-Tab Browsing & Cache Eviction Behavior
  - **IMPORTANT**: Follow observation-first methodology — run UNFIXED code with non-buggy inputs first
  - Observe: single-tab `onPageStarted` → `clearDetectedMedia()` fires → `detectedMedia.value` is empty
  - Observe: single-tab `onPageFinished` for active tab → `setCurrentThumbnail()` is called → `currentPageThumbnail` is updated
  - Observe: `closeTab()` removes the tab from `_tabs` and resets active state
  - Observe: `closeAllTabs()` resets `_tabs` to a single fresh tab
  - Write property-based tests in `BrowserViewModelPreservationTest.kt`:
    - **Prop A — Single-Tab Navigation Reset**: For any single-tab session, calling `onPageStarted` clears detected media and resets thumbnail (from Preservation Requirements 3.1, 3.2)
    - **Prop B — Active Tab Thumbnail Captured**: For any `onPageFinished` call where `isActiveTab(tabId)` is true, `setCurrentThumbnail()` is called and `currentPageThumbnail` is updated (Preservation Requirement 3.6)
    - **Prop C — New Tab Starts Empty**: `addNewTab()` always produces a tab with empty detection state (Preservation Requirement 3.4)
    - **Prop D — Ad Blocking Unaffected**: `shouldInterceptRequest` for background tabs still invokes `adBlocker.isAd()` regardless of active tab (Preservation Requirement 3.3)
    - **Prop E — Quality Sheet Dismissed on Switch**: `switchToTab()` always calls `cancelQualityFetch()` (Preservation Requirement 3.8)
  - Verify all preservation tests PASS on UNFIXED code (these are non-buggy paths)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9_

- [x] 3. Fix for tab video isolation (state bleed + stale thumbnail)

  - [x] 3.1 Add `isActiveTab` guard in `onPageFinished` (BrowserScreen.kt — Change 1)
    - In `BrowserScreen.kt`, inside the `WebViewClient.onPageFinished` override, after the `view?.title?.let { viewModel.onTitleChanged(tabId, it) }` call and before the `videoSignalsJs` injection block, insert:
      ```kotlin
      if (!viewModel.isActiveTab(tabId)) return
      ```
    - This single line prevents both the `videoSignalsJs` injection and all `runVideoDetection` calls from executing for background tabs
    - _Bug_Condition: isBugCondition_StaleThumbnail(event) where event.originTabId != currentlyActiveTabId_
    - _Expected_Behavior: setCurrentThumbnail() is never called for a background tab's onPageFinished_
    - _Preservation: Active tab's onPageFinished continues to inject JS and run detection unchanged_
    - _Requirements: 2.3, 2.4, 2.5, 3.6_

  - [x] 3.2 Add inner `isActiveTab` guards in `postDelayed` lambdas (BrowserScreen.kt — Changes 2 & 3)
    - Wrap each `postDelayed` body with an `isActiveTab` check so that a tab switch occurring after the outer guard passes but before the delayed callback fires does not execute detection for a now-background tab:
      ```kotlin
      view.postDelayed({
          if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
      }, 2500)
      view.postDelayed({
          if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
      }, 5000)
      ```
    - _Bug_Condition: isBugCondition_StaleThumbnail(event) — postDelayed fires after tab switch_
    - _Expected_Behavior: runVideoDetection only executes if the tab is still active at callback time_
    - _Preservation: Active tab's delayed re-detection (3.7) continues to run when tab remains active_
    - _Requirements: 2.3, 2.5, 3.7_

  - [x] 3.3 Add `restoreDetectedMedia()` to `VideoDetector` (VideoDetector.kt — Change 4)
    - Add the following method to `VideoDetector.kt`:
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
    - This supports the restore path in `switchToTab()` without exposing mutable internals
    - _Bug_Condition: isBugCondition_StateBleed — returning to Tab A finds empty detection state_
    - _Expected_Behavior: Tab A's media is restored from cache with correct deduplication state_
    - _Preservation: clearDetectedMedia() internals remain unchanged (3.9)_
    - _Requirements: 2.2, 3.9_

  - [x] 3.4 Add `tabDetectedMediaCache` field to `BrowserViewModel` (BrowserViewModel.kt — Change 5)
    - Declare a new private map at the top of the class body (after the existing `_tabs` declaration):
      ```kotlin
      private val tabDetectedMediaCache = mutableMapOf<String, List<DetectedMedia>>()
      ```
    - _Requirements: 2.1, 2.2_

  - [x] 3.5 Modify `switchToTab()` to save/restore per-tab media (BrowserViewModel.kt — Change 6)
    - Before calling `videoDetector.clearDetectedMedia()`, save the outgoing tab's media to the cache
    - After updating tab state, restore the incoming tab's cached media (if any):
      ```kotlin
      // Save outgoing tab's media before clearing
      val currentTabId = _tabs.value.getOrNull(_activeTabIndex.value)?.id
      if (currentTabId != null) {
          tabDetectedMediaCache[currentTabId] = videoDetector.detectedMedia.value
      }
      videoDetector.clearDetectedMedia()
      // ... existing tab state updates (unchanged) ...
      // Restore incoming tab's cached media
      val newTabId = updatedTabs[index].id
      val cached = tabDetectedMediaCache[newTabId]
      if (!cached.isNullOrEmpty()) {
          videoDetector.restoreDetectedMedia(cached)
      }
      ```
    - _Bug_Condition: isBugCondition_StateBleed(event) where outgoing tab has detectedMedia.size > 0_
    - _Expected_Behavior: Tab B starts with empty state; Tab A's media is preserved in cache and restored on return_
    - _Preservation: cancelQualityFetch(), cancelQualityPrefetch(), loadingFinishJob cancel, and _tabSwitchVersion increment remain unchanged (3.8)_
    - _Requirements: 2.1, 2.2, 3.8_

  - [x] 3.6 Evict cache in `closeTab()` (BrowserViewModel.kt — Change 7)
    - Before removing the tab from `_tabs`, capture its ID and remove it from the cache:
      ```kotlin
      val closedTabId = _tabs.value.getOrNull(index)?.id
      if (closedTabId != null) tabDetectedMediaCache.remove(closedTabId)
      ```
    - _Preservation: Tab WebView destruction and resource freeing remain unchanged (3.5)_
    - _Requirements: 3.5_

  - [x] 3.7 Evict cache in `closeAllTabs()` (BrowserViewModel.kt — Change 8)
    - Add `tabDetectedMediaCache.clear()` at the start of `closeAllTabs()`:
      ```kotlin
      tabDetectedMediaCache.clear()
      ```
    - _Requirements: 3.5_

  - [x] 3.8 Evict cache in `onPageStarted()` (BrowserViewModel.kt — Change 9)
    - Add `tabDetectedMediaCache.remove(tabId)` alongside the existing `updateTab(tabId, url = url)` call, BEFORE the `isActiveTab` guard, so it runs for both active and background tabs navigating:
      ```kotlin
      updateTab(tabId, url = url)
      tabDetectedMediaCache.remove(tabId)  // evict stale cache on navigation
      if (!isActiveTab(tabId)) return
      // ... rest of existing onPageStarted body unchanged ...
      ```
    - _Preservation: Per-navigation reset (clearDetectedMedia, setCurrentPage) for active tab remains unchanged (3.2)_
    - _Requirements: 3.2, 3.5_

  - [x] 3.9 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Tab Video State Bleed & Stale Thumbnail
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - The tests from task 1 encode the expected behavior
    - When these tests pass, it confirms the expected behavior is satisfied
    - Run all four test cases (A, B, C, D) from task 1
    - **EXPECTED OUTCOME**: All tests PASS (confirms both bugs are fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 3.10 Verify preservation tests still pass
    - **Property 2: Preservation** - Single-Tab Browsing & Cache Eviction Behavior
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run all preservation property tests (Props A–E) from task 2
    - **EXPECTED OUTCOME**: All tests PASS (confirms no regressions)
    - Confirm single-tab browsing, thumbnail capture, ad blocking, and quality sheet dismissal are all unaffected

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full unit test suite: `./gradlew :app:testDebugUnitTest`
  - Ensure all tests pass; ask the user if any questions arise
  - Verify the 8 surgical changes across 3 files are the only modifications made
  - Confirm no unrelated behavior was altered
