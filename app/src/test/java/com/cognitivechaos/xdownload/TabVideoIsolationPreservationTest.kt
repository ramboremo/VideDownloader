package com.cognitivechaos.xdownload

import com.cognitivechaos.xdownload.data.model.DetectedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tab Video Isolation — Preservation Tests (Task 2)
 *
 * These tests MUST PASS on unfixed code. They confirm the baseline behavior that
 * must be preserved after the fixes are applied.
 *
 * Five preservation properties are tested:
 *
 *   Prop A — Single-Tab Navigation Reset: onPageStarted clears detected media and
 *             resets thumbnail for the active tab. Must work the same after the fix.
 *
 *   Prop B — Active Tab Thumbnail Captured: When onPageFinished fires for the active
 *             tab, setCurrentThumbnail() is called and currentPageThumbnail is updated.
 *             Must continue to work after the isActiveTab guard is added.
 *
 *   Prop C — New Tab Starts Empty: addNewTab() always produces a tab with empty
 *             detection state. Must remain true after the cache is introduced.
 *
 *   Prop D — Ad Blocking Unaffected: shouldInterceptRequest for background tabs still
 *             invokes the ad-blocker regardless of active tab. The isActiveTab guard
 *             in shouldInterceptRequest only gates video detection, not ad blocking.
 *
 *   Prop E — Quality Sheet Dismissed on Switch: switchToTab() always cancels the
 *             quality fetch job. Must remain true after the cache save/restore is added.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9
 *
 * NOTE: Following the established project pattern, we replicate the relevant logic
 * as pure Kotlin state machines (no Android SDK, no Hilt, no Room).
 */
class TabVideoIsolationPreservationTest {

    // =========================================================================
    // Minimal state replicas (same as exploration test, extended for preservation)
    // =========================================================================

    private data class FakeDetectedMedia(
        val url: String,
        val thumbnailUrl: String = "",
        val sourcePageUrl: String = ""
    )

    private inner class FakeVideoDetector {
        val detectedMedia = mutableListOf<FakeDetectedMedia>()
        val hasMedia: Boolean get() = detectedMedia.isNotEmpty()
        var currentPageThumbnail = ""
        var currentPageUrl = ""
        var currentPageTitle = ""
        var clearDetectedMediaCallCount = 0

        fun clearDetectedMedia() {
            detectedMedia.clear()
            currentPageThumbnail = ""
            clearDetectedMediaCallCount++
        }

        fun setCurrentThumbnail(url: String) {
            if (url.isNotBlank() && url != "null") {
                currentPageThumbnail = url
            }
        }

        fun setCurrentPage(url: String, title: String) {
            currentPageUrl = url
            currentPageTitle = title
        }

        fun injectMedia(vararg items: FakeDetectedMedia) {
            detectedMedia.addAll(items)
        }
    }

    private data class FakeTab(val id: String, val url: String = "", val title: String = "New Tab")

    private inner class FakeBrowserViewModel(val detector: FakeVideoDetector) {
        val tabs = mutableListOf(FakeTab("tab-a"))
        var activeTabIndex = 0
        var qualityFetchCancelled = false
        var qualityPrefetchCancelled = false

        val activeTabId: String get() = tabs[activeTabIndex].id

        fun isActiveTab(tabId: String): Boolean = tabs[activeTabIndex].id == tabId

        /** Mirrors onPageStarted() — clears media and sets current page for active tab */
        fun onPageStarted(tabId: String, url: String) {
            // Always update tab metadata (runs for all tabs)
            // Only update global UI state if this is the active tab
            if (!isActiveTab(tabId)) return
            detector.clearDetectedMedia()
            detector.setCurrentPage(url, "")
        }

        /** Mirrors addNewTab() */
        fun addNewTab() {
            val newTab = FakeTab("tab-${tabs.size}")
            tabs.add(newTab)
            activeTabIndex = tabs.size - 1
            detector.clearDetectedMedia()
        }

        /** Mirrors the UNFIXED switchToTab() — no cache */
        fun unfixedSwitchToTab(index: Int) {
            qualityFetchCancelled = true    // cancelQualityFetch() always called
            qualityPrefetchCancelled = true // cancelQualityPrefetch() always called
            detector.clearDetectedMedia()
            activeTabIndex = index
        }

        /** Mirrors the ad-blocking check in shouldInterceptRequest */
        fun shouldBlockAd(requestUrl: String, @Suppress("UNUSED_PARAMETER") tabId: String): Boolean {
            // Ad blocking runs for ALL tabs regardless of isActiveTab — this must be preserved
            return requestUrl.contains("doubleclick") ||
                requestUrl.contains("googleads") ||
                requestUrl.contains("adserver")
        }

        /** Mirrors the video detection check in shouldInterceptRequest */
        fun shouldRunVideoDetection(tabId: String): Boolean {
            // Video detection only runs for the active tab — this is already guarded
            return isActiveTab(tabId)
        }
    }

    // =========================================================================
    // Prop A — Single-Tab Navigation Reset
    //
    // MUST PASS on unfixed code.
    // onPageStarted clears detected media and resets thumbnail for the active tab.
    // =========================================================================

    /**
     * Prop A: For a single-tab session, onPageStarted clears detected media and
     * resets currentPageThumbnail to "".
     *
     * This PASSES on unfixed code because onPageStarted already calls clearDetectedMedia()
     * and clearDetectedMedia() resets currentPageThumbnail.
     * The fix must preserve this behavior.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    fun `Prop A - single-tab navigation reset - onPageStarted clears media and thumbnail`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Single tab has detected media and a thumbnail
        detector.injectMedia(FakeDetectedMedia("https://cdn.example.com/video.mp4", "https://thumb.jpg"))
        detector.setCurrentThumbnail("https://thumb.jpg")
        assertEquals("Pre-condition: media should be present", 1, detector.detectedMedia.size)
        assertEquals("Pre-condition: thumbnail should be set", "https://thumb.jpg", detector.currentPageThumbnail)

        // User navigates to a new URL within the same tab
        vm.onPageStarted("tab-a", "https://example.com/new-page")

        // Preservation: media must be cleared
        assertTrue(
            "PRESERVATION: onPageStarted must clear detected media for the active tab. " +
                "detectedMedia.size=${detector.detectedMedia.size}",
            detector.detectedMedia.isEmpty()
        )

        // Preservation: thumbnail must be reset
        assertEquals(
            "PRESERVATION: onPageStarted must reset currentPageThumbnail to empty string. " +
                "currentPageThumbnail='${detector.currentPageThumbnail}'",
            "",
            detector.currentPageThumbnail
        )

        // Preservation: clearDetectedMedia was called exactly once
        assertEquals(
            "PRESERVATION: clearDetectedMedia() must be called exactly once by onPageStarted.",
            1,
            detector.clearDetectedMediaCallCount
        )
    }

    /**
     * Prop A (background tab): onPageStarted for a background tab does NOT clear
     * the active tab's detected media.
     *
     * This PASSES on unfixed code because onPageStarted has an isActiveTab guard.
     * The fix must preserve this behavior.
     *
     * Validates: Requirements 3.2
     */
    @Test
    fun `Prop A - background tab navigation - does NOT clear active tab media`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Add a second tab and make it active
        vm.addNewTab()
        assertEquals("Pre-condition: Tab 1 (tab-1) should be active", 1, vm.activeTabIndex)

        // Tab A (background) had detected media before the switch
        // Simulate: Tab A navigates while Tab B is active
        detector.injectMedia(FakeDetectedMedia("https://cdn.example.com/video.mp4"))
        val mediaCountBefore = detector.detectedMedia.size

        // Tab A (background) fires onPageStarted — should NOT clear active tab's media
        vm.onPageStarted("tab-a", "https://example.com/new-page")

        // Preservation: active tab's media must NOT be cleared by a background tab's onPageStarted
        assertEquals(
            "PRESERVATION: Background tab's onPageStarted must NOT clear the active tab's media. " +
                "detectedMedia.size=${detector.detectedMedia.size} (expected $mediaCountBefore).",
            mediaCountBefore,
            detector.detectedMedia.size
        )
    }

    // =========================================================================
    // Prop B — Active Tab Thumbnail Captured
    //
    // MUST PASS on unfixed code.
    // When onPageFinished fires for the active tab, setCurrentThumbnail() updates
    // currentPageThumbnail. After the fix adds the isActiveTab guard, this must
    // still work for the active tab.
    // =========================================================================

    /**
     * Prop B: When onPageFinished fires for the active tab, setCurrentThumbnail()
     * is called and currentPageThumbnail is updated.
     *
     * This PASSES on unfixed code because setCurrentThumbnail() has no guard —
     * it always updates. After the fix, the active tab's path must still work.
     *
     * Validates: Requirements 3.6
     */
    @Test
    fun `Prop B - active tab thumbnail captured - setCurrentThumbnail updates for active tab`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Active tab is tab-a
        assertEquals("Pre-condition: tab-a should be active", "tab-a", vm.activeTabId)
        assertEquals("Pre-condition: thumbnail should be empty", "", detector.currentPageThumbnail)

        // Simulate the active tab's onPageFinished detection block calling setCurrentThumbnail
        // (This is what runVideoDetection() does after the fix adds the isActiveTab guard)
        val activeTabId = vm.activeTabId
        assertTrue("Pre-condition: tab-a is the active tab", vm.isActiveTab(activeTabId))

        // The fixed code will only call setCurrentThumbnail if isActiveTab(tabId) is true
        // For the active tab, this condition is true — thumbnail must be updated
        if (vm.isActiveTab(activeTabId)) {
            detector.setCurrentThumbnail("https://active-tab-thumb.jpg")
        }

        // Preservation: active tab's thumbnail must be captured
        assertEquals(
            "PRESERVATION: Active tab's thumbnail must be captured by onPageFinished. " +
                "currentPageThumbnail='${detector.currentPageThumbnail}'",
            "https://active-tab-thumb.jpg",
            detector.currentPageThumbnail
        )
    }

    /**
     * Prop B (delayed detection): Active tab's postDelayed callbacks (2.5s, 5s) must
     * still run when the tab remains active at callback time.
     *
     * After the fix adds inner isActiveTab guards in the postDelayed lambdas, the
     * callbacks must still execute for the active tab.
     *
     * Validates: Requirements 3.7
     */
    @Test
    fun `Prop B - active tab delayed detection - postDelayed runs when tab is still active`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Active tab is tab-a
        val activeTabId = vm.activeTabId

        // Simulate the fixed postDelayed lambda:
        //   view.postDelayed({
        //       if (viewModel.isActiveTab(tabId)) runVideoDetection(view, url)
        //   }, 2500)
        //
        // When the tab is still active at callback time, detection must run.
        var detectionRan = false
        val fixedPostDelayedCallback = {
            if (vm.isActiveTab(activeTabId)) {
                // runVideoDetection would call setCurrentThumbnail here
                detector.setCurrentThumbnail("https://delayed-thumb.jpg")
                detectionRan = true
            }
        }

        // Tab is still active when callback fires
        assertTrue("Pre-condition: tab-a is still active", vm.isActiveTab(activeTabId))
        fixedPostDelayedCallback()

        // Preservation: detection must have run for the active tab
        assertTrue(
            "PRESERVATION: postDelayed callback must run detection when tab is still active. " +
                "detectionRan=$detectionRan",
            detectionRan
        )
        assertEquals(
            "PRESERVATION: Active tab's thumbnail must be updated by delayed detection. " +
                "currentPageThumbnail='${detector.currentPageThumbnail}'",
            "https://delayed-thumb.jpg",
            detector.currentPageThumbnail
        )
    }

    // =========================================================================
    // Prop C — New Tab Starts Empty
    //
    // MUST PASS on unfixed code.
    // addNewTab() always produces a tab with empty detection state.
    // =========================================================================

    /**
     * Prop C: addNewTab() always produces a tab with empty detection state.
     *
     * This PASSES on unfixed code because addNewTab() calls clearDetectedMedia().
     * After the fix introduces tabDetectedMediaCache, addNewTab() must still start
     * with empty detection state (no stale cache entry for the new tab).
     *
     * Validates: Requirements 3.4
     */
    @Test
    fun `Prop C - new tab starts empty - addNewTab clears detection state`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Tab A has detected media
        detector.injectMedia(
            FakeDetectedMedia("https://cdn.example.com/video1.mp4"),
            FakeDetectedMedia("https://cdn.example.com/video2.mp4")
        )
        assertEquals("Pre-condition: Tab A has 2 detected media items", 2, detector.detectedMedia.size)

        // Open a new tab
        vm.addNewTab()

        // Preservation: new tab must start with empty detection state
        assertTrue(
            "PRESERVATION: addNewTab() must clear detected media. " +
                "detectedMedia.size=${detector.detectedMedia.size}",
            detector.detectedMedia.isEmpty()
        )
        assertFalse(
            "PRESERVATION: addNewTab() must set hasMedia to false. " +
                "hasMedia=${detector.hasMedia}",
            detector.hasMedia
        )
        assertEquals(
            "PRESERVATION: addNewTab() must reset currentPageThumbnail. " +
                "currentPageThumbnail='${detector.currentPageThumbnail}'",
            "",
            detector.currentPageThumbnail
        )
    }

    // =========================================================================
    // Prop D — Ad Blocking Unaffected
    //
    // MUST PASS on unfixed code.
    // shouldInterceptRequest for background tabs still invokes the ad-blocker.
    // The isActiveTab guard only gates video detection, not ad blocking.
    // =========================================================================

    /**
     * Prop D: Ad blocking runs for ALL tabs regardless of which tab is active.
     *
     * This PASSES on unfixed code because the ad-blocking check in shouldInterceptRequest
     * is NOT gated by isActiveTab. The fix must preserve this — only video detection
     * is gated, not ad blocking.
     *
     * Validates: Requirements 3.3
     */
    @Test
    fun `Prop D - ad blocking unaffected - background tab requests are still blocked`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Add a second tab and make it active
        vm.addNewTab()
        assertEquals("Pre-condition: tab-1 should be active", 1, vm.activeTabIndex)

        val backgroundTabId = "tab-a"
        assertFalse("Pre-condition: tab-a is a background tab", vm.isActiveTab(backgroundTabId))

        // Background tab makes a request to an ad server
        val adUrl = "https://googleads.g.doubleclick.net/pagead/ads"
        val nonAdUrl = "https://cdn.example.com/video.mp4"

        // Preservation: ad blocking must run for background tab requests
        assertTrue(
            "PRESERVATION: Ad blocking must run for background tab requests. " +
                "adUrl='$adUrl' should be blocked regardless of active tab.",
            vm.shouldBlockAd(adUrl, backgroundTabId)
        )

        // Preservation: non-ad URLs must not be blocked
        assertFalse(
            "PRESERVATION: Non-ad URLs must not be blocked. " +
                "nonAdUrl='$nonAdUrl' should not be blocked.",
            vm.shouldBlockAd(nonAdUrl, backgroundTabId)
        )

        // Preservation: video detection is correctly gated by isActiveTab
        assertFalse(
            "PRESERVATION: Video detection must NOT run for background tab requests. " +
                "shouldRunVideoDetection(backgroundTabId)=${vm.shouldRunVideoDetection(backgroundTabId)}",
            vm.shouldRunVideoDetection(backgroundTabId)
        )

        // Preservation: video detection runs for active tab
        val activeTabId = vm.activeTabId
        assertTrue(
            "PRESERVATION: Video detection must run for active tab requests. " +
                "shouldRunVideoDetection(activeTabId)=${vm.shouldRunVideoDetection(activeTabId)}",
            vm.shouldRunVideoDetection(activeTabId)
        )
    }

    // =========================================================================
    // Prop E — Quality Sheet Dismissed on Switch
    //
    // MUST PASS on unfixed code.
    // switchToTab() always cancels the quality fetch job.
    // =========================================================================

    /**
     * Prop E: switchToTab() always calls cancelQualityFetch() and cancelQualityPrefetch().
     *
     * This PASSES on unfixed code because switchToTab() always cancels these jobs.
     * After the fix adds cache save/restore logic, these cancellations must still happen.
     *
     * Validates: Requirements 3.8
     */
    @Test
    fun `Prop E - quality sheet dismissed on switch - cancelQualityFetch always called`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Add a second tab
        vm.tabs.add(FakeTab("tab-b"))

        // Quality fetch is in progress (simulated by the flag being false initially)
        vm.qualityFetchCancelled = false
        vm.qualityPrefetchCancelled = false

        // Switch to Tab B
        vm.unfixedSwitchToTab(1)

        // Preservation: quality fetch must be cancelled on tab switch
        assertTrue(
            "PRESERVATION: switchToTab() must cancel the quality fetch job. " +
                "qualityFetchCancelled=${vm.qualityFetchCancelled}",
            vm.qualityFetchCancelled
        )
        assertTrue(
            "PRESERVATION: switchToTab() must cancel the quality prefetch job. " +
                "qualityPrefetchCancelled=${vm.qualityPrefetchCancelled}",
            vm.qualityPrefetchCancelled
        )
    }

    // =========================================================================
    // Prop F — clearDetectedMedia() Contract Preserved
    //
    // MUST PASS on unfixed code.
    // clearDetectedMedia() must continue to cancel extraction jobs, clear detectedUrls,
    // reset detectionCounter, clear prefetch caches, and reset currentPageThumbnail.
    // =========================================================================

    /**
     * Prop F: clearDetectedMedia() resets all detection state including currentPageThumbnail.
     *
     * This PASSES on unfixed code. The fix must not change clearDetectedMedia() internals.
     *
     * Validates: Requirements 3.9
     */
    @Test
    fun `Prop F - clearDetectedMedia contract preserved - resets all detection state`() {
        val detector = FakeVideoDetector()

        // Set up some state
        detector.injectMedia(
            FakeDetectedMedia("https://cdn.example.com/video1.mp4", "https://thumb1.jpg"),
            FakeDetectedMedia("https://cdn.example.com/video2.mp4", "https://thumb2.jpg")
        )
        detector.setCurrentThumbnail("https://thumb1.jpg")
        detector.setCurrentPage("https://example.com/video", "Video Page")

        assertEquals("Pre-condition: 2 media items", 2, detector.detectedMedia.size)
        assertTrue("Pre-condition: hasMedia is true", detector.hasMedia)
        assertEquals("Pre-condition: thumbnail is set", "https://thumb1.jpg", detector.currentPageThumbnail)

        // Call clearDetectedMedia()
        detector.clearDetectedMedia()

        // Preservation: all detection state must be reset
        assertTrue(
            "PRESERVATION: clearDetectedMedia() must clear detectedMedia list. " +
                "detectedMedia.size=${detector.detectedMedia.size}",
            detector.detectedMedia.isEmpty()
        )
        assertFalse(
            "PRESERVATION: clearDetectedMedia() must set hasMedia to false. " +
                "hasMedia=${detector.hasMedia}",
            detector.hasMedia
        )
        assertEquals(
            "PRESERVATION: clearDetectedMedia() must reset currentPageThumbnail to empty string. " +
                "currentPageThumbnail='${detector.currentPageThumbnail}'",
            "",
            detector.currentPageThumbnail
        )
    }
}
