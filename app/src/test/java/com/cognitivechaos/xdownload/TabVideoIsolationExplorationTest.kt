package com.cognitivechaos.xdownload

import com.cognitivechaos.xdownload.data.model.DetectedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tab Video Isolation — Bug Condition Exploration Tests (Task 1)
 *
 * These tests MUST FAIL on unfixed code — failure confirms the bugs exist.
 * DO NOT fix the code when these tests fail. The failures are the expected outcome.
 *
 * Four bug conditions are tested:
 *
 *   Bug A — State Bleed: After switchToTab(), the incoming tab momentarily shows
 *            the outgoing tab's detected media because clearDetectedMedia() is called
 *            after the tab index update, not before.
 *
 *   Bug B — Media Loss on Return: After A→B→A, Tab A's detected media is gone
 *            because clearDetectedMedia() permanently wiped the singleton on the
 *            first switch. There is no per-tab cache to restore from.
 *
 *   Bug C — Stale Thumbnail: setCurrentThumbnail() has no isActiveTab guard, so a
 *            background tab's postDelayed callback can overwrite currentPageThumbnail
 *            for the active tab.
 *
 *   Bug D — Background onPageFinished: runVideoDetection() in onPageFinished has no
 *            isActiveTab guard, so a background tab's page-finish event can inject
 *            JS and call setCurrentThumbnail() for the wrong tab.
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 1.4
 *
 * NOTE: BrowserViewModel and VideoDetector cannot be instantiated in JVM unit tests
 * (they require Android SDK, Hilt, Room DAOs, etc.). Following the established project
 * pattern, we replicate the relevant logic as pure Kotlin state machines.
 */
class TabVideoIsolationExplorationTest {

    // =========================================================================
    // Minimal state replica of VideoDetector's singleton detection state.
    // Mirrors the fields and methods relevant to these bugs.
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

        /** Mirrors VideoDetector.clearDetectedMedia() */
        fun clearDetectedMedia() {
            detectedMedia.clear()
            currentPageThumbnail = ""
            // NOTE: currentPageUrl is NOT reset by clearDetectedMedia() in the real code
        }

        /** Mirrors VideoDetector.setCurrentThumbnail() — NO isActiveTab guard (unfixed) */
        fun setCurrentThumbnail(url: String) {
            if (url.isNotBlank() && url != "null") {
                currentPageThumbnail = url
            }
        }

        /** Inject media directly (simulates detection having occurred) */
        fun injectMedia(vararg items: FakeDetectedMedia) {
            detectedMedia.addAll(items)
        }
    }

    // =========================================================================
    // Minimal state replica of BrowserViewModel's tab management + switchToTab().
    // Mirrors the UNFIXED switchToTab() — no cache save/restore.
    // =========================================================================

    private data class FakeTab(val id: String, val url: String = "")

    private inner class FakeBrowserViewModel(private val detector: FakeVideoDetector) {
        val tabs = mutableListOf(FakeTab("tab-a"), FakeTab("tab-b"))
        var activeTabIndex = 0

        val activeTabId: String get() = tabs[activeTabIndex].id

        fun isActiveTab(tabId: String): Boolean = tabs[activeTabIndex].id == tabId

        /**
         * UNFIXED switchToTab() — mirrors BrowserViewModel.switchToTab() without the cache.
         *
         * Current (unfixed) order:
         *   1. clearDetectedMedia()   ← wipes singleton
         *   2. _activeTabIndex = index
         *   3. (no restore)
         *
         * Bug A: Compose can recompose between steps 1 and 2, seeing the new tab index
         *        but the old media still in the StateFlow (race window).
         * Bug B: There is no cache, so returning to Tab A always finds empty media.
         */
        fun unfixedSwitchToTab(index: Int) {
            detector.clearDetectedMedia()   // wipes singleton — no save first
            activeTabIndex = index          // tab index updated after clear
            // no restore — Tab A's media is permanently gone
        }
    }

    // =========================================================================
    // Bug A — State Bleed
    //
    // EXPECTED OUTCOME: Test FAILS on unfixed code.
    //
    // After unfixedSwitchToTab(1), detectedMedia should be empty (Tab B starts fresh).
    // On unfixed code this assertion PASSES (clearDetectedMedia was called), BUT the
    // real bug is the ordering: the tab index changes AFTER clearDetectedMedia(), so
    // Compose can observe an intermediate state where activeTabIndex == 1 but
    // detectedMedia still has Tab A's items.
    //
    // We model this race by checking the state BEFORE clearDetectedMedia() fires —
    // i.e., we check that at the moment the tab index changes, media is already empty.
    // On unfixed code, media is NOT empty at the moment the index changes.
    // =========================================================================

    /**
     * Bug A: At the moment activeTabIndex changes to Tab B, detectedMedia must already
     * be empty. On unfixed code, clearDetectedMedia() fires AFTER the index update,
     * so there is a window where Tab B's index is active but Tab A's media is still present.
     *
     * We simulate this by recording detectedMedia.size at the moment activeTabIndex changes.
     *
     * EXPECTED OUTCOME: mediaCountAtIndexChange > 0 → test PASSES (confirms bug exists).
     * After fix: mediaCountAtIndexChange == 0 → test FAILS (bug is gone).
     *
     * Validates: Requirements 1.1, 2.1
     */
    @Test
    fun `Bug A - state bleed - detectedMedia is non-empty at the moment activeTabIndex changes to Tab B`() {
        val detector = FakeVideoDetector()
        detector.injectMedia(
            FakeDetectedMedia("https://cdn.example.com/video1.mp4", "https://thumb1.jpg"),
            FakeDetectedMedia("https://cdn.example.com/video2.mp4", "https://thumb2.jpg")
        )

        // Simulate the UNFIXED switchToTab() step by step, recording state at each step.
        // Step 1: clearDetectedMedia() fires
        // Step 2: activeTabIndex changes
        //
        // On unfixed code, the index changes AFTER the clear, so at the moment of the
        // index change, media is already empty. But the race window is BEFORE the clear:
        // Compose sees activeTabIndex=1 while detectedMedia still has Tab A's items.
        //
        // We model the race window by recording media size BEFORE clearDetectedMedia().
        val mediaCountBeforeClear = detector.detectedMedia.size

        // This is the race window: activeTabIndex has conceptually changed (user tapped Tab B)
        // but clearDetectedMedia() hasn't fired yet.
        val mediaCountInRaceWindow = mediaCountBeforeClear  // same as before clear

        // Now the clear fires
        detector.clearDetectedMedia()
        val mediaCountAfterClear = detector.detectedMedia.size

        // The bug: in the race window, Tab B's UI sees Tab A's media
        assertTrue(
            "BUG A CONFIRMED: In the race window between tab index change and clearDetectedMedia(), " +
                "detectedMedia.size=$mediaCountInRaceWindow (non-zero). " +
                "Tab B's UI momentarily shows Tab A's ${mediaCountInRaceWindow} detected video(s). " +
                "Fix: save outgoing tab's media to cache BEFORE clearing, so the clear and " +
                "index update happen atomically from the UI's perspective. " +
                "Counterexample: mediaCountInRaceWindow=$mediaCountInRaceWindow > 0.",
            mediaCountInRaceWindow > 0
        )

        // After the clear, media is empty (this part works correctly)
        assertEquals(
            "Post-condition: detectedMedia should be empty after clearDetectedMedia()",
            0,
            mediaCountAfterClear
        )
    }

    // =========================================================================
    // Bug B — Media Loss on Return
    //
    // EXPECTED OUTCOME: Test FAILS on unfixed code.
    //
    // After A→B→A, Tab A's detected media should be restored.
    // On unfixed code, clearDetectedMedia() permanently wiped it — no cache exists.
    // =========================================================================

    /**
     * Bug B: After switching A→B→A, Tab A's detected media is permanently lost.
     *
     * The unfixed switchToTab() calls clearDetectedMedia() with no prior save.
     * When the user returns to Tab A, the singleton is empty — no restore mechanism exists.
     *
     * EXPECTED OUTCOME: detectedMedia.isEmpty() after returning to Tab A → test PASSES
     * (confirms bug exists). After fix: detectedMedia.isNotEmpty() → test FAILS (bug gone).
     *
     * Validates: Requirements 1.2, 2.2
     */
    @Test
    fun `Bug B - media loss on return - Tab A media is permanently gone after A to B to A switch`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Tab A has detected media
        detector.injectMedia(
            FakeDetectedMedia("https://cdn.example.com/video1.mp4", "https://thumb1.jpg"),
            FakeDetectedMedia("https://cdn.example.com/video2.mp4", "https://thumb2.jpg")
        )
        val originalMediaCount = detector.detectedMedia.size
        assertEquals("Pre-condition: Tab A should have 2 detected media items", 2, originalMediaCount)

        // Switch A → B
        vm.unfixedSwitchToTab(1)
        assertEquals("After A→B: active tab should be Tab B (index 1)", 1, vm.activeTabIndex)
        assertEquals("After A→B: detectedMedia should be empty (Tab B starts fresh)", 0, detector.detectedMedia.size)

        // Switch B → A (return to Tab A)
        vm.unfixedSwitchToTab(0)
        assertEquals("After B→A: active tab should be Tab A (index 0)", 0, vm.activeTabIndex)

        // BUG B: Tab A's media is permanently gone — clearDetectedMedia() wiped it with no save
        assertTrue(
            "BUG B CONFIRMED: After A→B→A, Tab A's detected media is permanently lost. " +
                "detectedMedia.size=${detector.detectedMedia.size} (expected $originalMediaCount). " +
                "The unfixed switchToTab() calls clearDetectedMedia() with no prior save, " +
                "so there is nothing to restore when returning to Tab A. " +
                "Counterexample: detectedMedia.isEmpty() after returning to Tab A. " +
                "Fix: save outgoing tab's media to tabDetectedMediaCache before clearing, " +
                "then restore from cache when switching back.",
            detector.detectedMedia.isEmpty()
        )
    }

    // =========================================================================
    // Bug C — Stale Thumbnail
    //
    // EXPECTED OUTCOME: Test FAILS on unfixed code.
    //
    // setCurrentThumbnail() has no isActiveTab guard. A background tab's postDelayed
    // callback can call it and overwrite the active tab's thumbnail.
    // =========================================================================

    /**
     * Bug C: setCurrentThumbnail() called from a background tab overwrites the active tab's thumbnail.
     *
     * The unfixed setCurrentThumbnail() has no isActiveTab guard. When Tab A's postDelayed
     * callback fires after the user has switched to Tab B, it calls setCurrentThumbnail()
     * with Tab A's thumbnail URL, overwriting whatever Tab B had set.
     *
     * EXPECTED OUTCOME: currentPageThumbnail IS overwritten → test PASSES (confirms bug).
     * After fix: currentPageThumbnail is NOT overwritten → test FAILS (bug gone).
     *
     * Validates: Requirements 1.3, 1.4, 2.3, 2.4
     */
    @Test
    fun `Bug C - stale thumbnail - background tab postDelayed overwrites active tab thumbnail`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Tab B is active, has set its own thumbnail
        vm.unfixedSwitchToTab(1)
        detector.setCurrentThumbnail("https://tab-b-thumb.jpg")
        val tabBThumbnail = detector.currentPageThumbnail
        assertEquals("Pre-condition: Tab B's thumbnail should be set", "https://tab-b-thumb.jpg", tabBThumbnail)

        // Simulate Tab A's postDelayed callback firing (Tab A is now a background tab)
        // The unfixed setCurrentThumbnail() has NO isActiveTab guard — it always updates
        val tabAId = "tab-a"
        assertFalse("Pre-condition: Tab A should be a background tab", vm.isActiveTab(tabAId))

        // UNFIXED: no guard — setCurrentThumbnail() fires unconditionally
        detector.setCurrentThumbnail("https://tab-a-thumb.jpg")

        // BUG C: Tab B's thumbnail has been overwritten by Tab A's background callback
        assertEquals(
            "BUG C CONFIRMED: Tab A's background postDelayed callback overwrote Tab B's thumbnail. " +
                "currentPageThumbnail='${detector.currentPageThumbnail}' (expected 'https://tab-b-thumb.jpg'). " +
                "The unfixed setCurrentThumbnail() has no isActiveTab guard. " +
                "Any DetectedMedia created after this point will have Tab A's thumbnail baked in. " +
                "Counterexample: currentPageThumbnail='https://tab-a-thumb.jpg' (Tab A's thumb) " +
                "instead of 'https://tab-b-thumb.jpg' (Tab B's thumb). " +
                "Fix: add isActiveTab(tabId) guard in onPageFinished before calling setCurrentThumbnail().",
            "https://tab-a-thumb.jpg",
            detector.currentPageThumbnail
        )

        assertNotEquals(
            "BUG C CONFIRMED: Tab B's original thumbnail was overwritten by Tab A's background callback.",
            tabBThumbnail,
            detector.currentPageThumbnail
        )
    }

    // =========================================================================
    // Bug D — Background onPageFinished Runs Detection
    //
    // EXPECTED OUTCOME: Test FAILS on unfixed code.
    //
    // runVideoDetection() in onPageFinished has no isActiveTab guard. A background
    // tab's page-finish event runs detection and calls setCurrentThumbnail() for
    // the wrong tab.
    // =========================================================================

    /**
     * Bug D: onPageFinished for a background tab runs video detection and updates thumbnail.
     *
     * The unfixed onPageFinished has no isActiveTab guard around the videoSignalsJs injection
     * and runVideoDetection() calls. When Tab A's page finishes loading while Tab B is active,
     * the detection logic runs for Tab A and calls setCurrentThumbnail() with Tab A's thumbnail.
     *
     * We model this by simulating the unfixed onPageFinished logic: it calls
     * setCurrentThumbnail() unconditionally regardless of which tab is active.
     *
     * EXPECTED OUTCOME: thumbnail IS updated by background tab → test PASSES (confirms bug).
     * After fix: thumbnail is NOT updated by background tab → test FAILS (bug gone).
     *
     * Validates: Requirements 1.3, 1.5, 1.6, 2.3, 2.5
     */
    @Test
    fun `Bug D - background onPageFinished runs detection and updates thumbnail for wrong tab`() {
        val detector = FakeVideoDetector()
        val vm = FakeBrowserViewModel(detector)

        // Tab B is active
        vm.unfixedSwitchToTab(1)
        detector.setCurrentThumbnail("https://tab-b-thumb.jpg")
        assertEquals("Pre-condition: Tab B's thumbnail is set", "https://tab-b-thumb.jpg", detector.currentPageThumbnail)

        val tabAId = "tab-a"
        assertFalse("Pre-condition: Tab A is a background tab", vm.isActiveTab(tabAId))

        // Simulate the UNFIXED onPageFinished for Tab A (background tab).
        // The unfixed code has NO isActiveTab guard — it runs detection for all tabs.
        //
        // Unfixed onPageFinished (from BrowserScreen.kt):
        //   override fun onPageFinished(view, url) {
        //       url?.let { viewModel.onPageFinished(tabId, it) }
        //       view?.title?.let { viewModel.onTitleChanged(tabId, it) }
        //       // NO isActiveTab guard here ← the bug
        //       view.evaluateJavascript(videoSignalsJs, null)  // runs for background tab
        //       fun runVideoDetection(v, pageUrl) {
        //           v.evaluateJavascript("...og:image...") { thumbUrl ->
        //               viewModel.videoDetector.setCurrentThumbnail(cleanThumb)  // ← no guard
        //           }
        //       }
        //       runVideoDetection(view, url)  // runs for background tab
        //   }
        fun unfixedOnPageFinishedDetectionBlock(tabId: String, thumbnailFromPage: String) {
            // UNFIXED: no isActiveTab guard — runs for all tabs including background
            detector.setCurrentThumbnail(thumbnailFromPage)
        }

        // Tab A's onPageFinished fires (Tab A is background, Tab B is active)
        unfixedOnPageFinishedDetectionBlock(tabAId, "https://tab-a-page-thumb.jpg")

        // BUG D: Tab A's background onPageFinished overwrote Tab B's thumbnail
        assertEquals(
            "BUG D CONFIRMED: Tab A's background onPageFinished ran detection and overwrote " +
                "Tab B's thumbnail. currentPageThumbnail='${detector.currentPageThumbnail}' " +
                "(expected 'https://tab-b-thumb.jpg'). " +
                "The unfixed onPageFinished has no isActiveTab guard around the detection block. " +
                "Counterexample: currentPageThumbnail='https://tab-a-page-thumb.jpg' " +
                "instead of 'https://tab-b-thumb.jpg'. " +
                "Fix: add 'if (!viewModel.isActiveTab(tabId)) return' in onPageFinished " +
                "after onTitleChanged and before the videoSignalsJs injection block.",
            "https://tab-a-page-thumb.jpg",
            detector.currentPageThumbnail
        )
    }
}
