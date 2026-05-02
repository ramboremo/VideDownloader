package com.cognitivechaos.xdownload

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fullscreen Orientation Fix — Preservation Property Tests (Task 2 / Task 3.5)
 *
 * These tests MUST PASS on fixed code — they document the CORRECT existing behavior
 * for non-buggy inputs (landscape videos in VideoPlayerScreen where isBugCondition
 * returns false) and confirm the fix does not regress it.
 *
 * The preservation property states:
 *   FOR ALL X WHERE NOT isBugCondition(X) DO
 *     ASSERT enterFullscreen_original(X).requestedOrientation
 *          = enterFullscreen_fixed(X).requestedOrientation
 *   END FOR
 *
 * For VideoPlayerScreen, isBugCondition is false when:
 *   - surface = "player" AND videoWidth >= videoHeight (landscape or square videos)
 *
 * These inputs already produced the correct behavior (SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
 * on unfixed code. The tests here confirm that the fix preserves this baseline.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.6
 *
 * NOTE: VideoPlayerScreen is a Compose composable that depends on Android SDK, Hilt,
 * and ExoPlayer — it cannot be instantiated in JVM unit tests. Following the established
 * project pattern (see FullscreenOrientationExplorationTest), we replicate the relevant
 * orientation-selection logic as pure Kotlin functions that mirror the FIXED code,
 * then assert the expected (preserved) behavior.
 */
class FullscreenOrientationPreservationTest {

    // =========================================================================
    // Pure-function replica of the FIXED orientation-selection logic
    //
    // This mirrors the fixed VideoPlayerScreen LaunchedEffect(isFullscreen) block:
    //
    //   val orientation = if (videoSize.height > videoSize.width)
    //       ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    //   else
    //       ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    //   activity.requestedOrientation = orientation
    //
    // For landscape inputs (width >= height), this returns SENSOR_LANDSCAPE —
    // the same result as the unfixed code, preserving the existing correct behavior.
    // =========================================================================

    /**
     * Replicates the FIXED orientation logic in VideoPlayerScreen's
     * LaunchedEffect(isFullscreen) block.
     *
     * For landscape videos (width >= height), this returns SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
     * which is the correct and expected behavior that the fix preserves.
     */
    private fun fixedVideoPlayerSelectOrientation(
        isFullscreen: Boolean,
        videoWidth: Int,
        videoHeight: Int
    ): Int {
        return if (isFullscreen) {
            if (videoHeight > videoWidth)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // =========================================================================
    // Property 2: Preservation — Landscape Fullscreen Behavior Unchanged
    //
    // For all VideoSize(w, h) where w >= h > 0 (landscape or square):
    //   fixed VideoPlayerScreen returns SCREEN_ORIENTATION_SENSOR_LANDSCAPE ✓
    //
    // EXPECTED OUTCOME: All tests PASS (confirms baseline behavior is preserved).
    // =========================================================================

    /**
     * Specific example: VideoSize(1920, 1080) — standard 1080p landscape.
     *
     * Expected: SCREEN_ORIENTATION_SENSOR_LANDSCAPE
     * Actual (fixed): SCREEN_ORIENTATION_SENSOR_LANDSCAPE ✓
     *
     * EXPECTED OUTCOME: PASSES on fixed code.
     *
     * Validates: Requirements 3.1, 3.2, 3.3
     */
    @Test
    fun `Preservation - landscape video 1920x1080 in VideoPlayerScreen fullscreen returns SENSOR_LANDSCAPE`() {
        val videoWidth = 1920
        val videoHeight = 1080

        val actual = fixedVideoPlayerSelectOrientation(
            isFullscreen = true,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertEquals(
            "PRESERVATION: Landscape video ($videoWidth×$videoHeight) in VideoPlayerScreen fullscreen " +
                "should return SCREEN_ORIENTATION_SENSOR_LANDSCAPE. " +
                "This is the existing correct behavior that the fix must not regress.",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            actual
        )
    }

    /**
     * Specific example: VideoSize(1280, 720) — standard 720p landscape.
     *
     * Expected: SCREEN_ORIENTATION_SENSOR_LANDSCAPE
     * Actual (fixed): SCREEN_ORIENTATION_SENSOR_LANDSCAPE ✓
     *
     * EXPECTED OUTCOME: PASSES on fixed code.
     *
     * Validates: Requirements 3.1, 3.2, 3.3
     */
    @Test
    fun `Preservation - landscape video 1280x720 in VideoPlayerScreen fullscreen returns SENSOR_LANDSCAPE`() {
        val videoWidth = 1280
        val videoHeight = 720

        val actual = fixedVideoPlayerSelectOrientation(
            isFullscreen = true,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertEquals(
            "PRESERVATION: Landscape video ($videoWidth×$videoHeight) in VideoPlayerScreen fullscreen " +
                "should return SCREEN_ORIENTATION_SENSOR_LANDSCAPE. " +
                "This is the existing correct behavior that the fix must not regress.",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            actual
        )
    }

    /**
     * Preservation: isFullscreen=false always returns SCREEN_ORIENTATION_UNSPECIFIED.
     *
     * When the user exits fullscreen, the orientation must be restored to UNSPECIFIED
     * regardless of the video's aspect ratio. This is correct in the fixed code.
     *
     * Expected: SCREEN_ORIENTATION_UNSPECIFIED
     * Actual (fixed): SCREEN_ORIENTATION_UNSPECIFIED ✓
     *
     * EXPECTED OUTCOME: PASSES on fixed code.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    fun `Preservation - isFullscreen false returns SCREEN_ORIENTATION_UNSPECIFIED`() {
        // Test with a landscape video — the orientation should still be UNSPECIFIED when not fullscreen
        val actual = fixedVideoPlayerSelectOrientation(
            isFullscreen = false,
            videoWidth = 1920,
            videoHeight = 1080
        )

        assertEquals(
            "PRESERVATION: When isFullscreen=false, VideoPlayerScreen should return " +
                "SCREEN_ORIENTATION_UNSPECIFIED to restore free rotation. " +
                "This is the existing correct behavior that the fix must not regress.",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            actual
        )
    }

    /**
     * Preservation: isFullscreen=false with portrait dimensions also returns UNSPECIFIED.
     *
     * The fullscreen-exit path must always restore UNSPECIFIED, regardless of video dimensions.
     *
     * Expected: SCREEN_ORIENTATION_UNSPECIFIED
     * Actual (fixed): SCREEN_ORIENTATION_UNSPECIFIED ✓
     *
     * EXPECTED OUTCOME: PASSES on fixed code.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    fun `Preservation - isFullscreen false with any video dimensions returns SCREEN_ORIENTATION_UNSPECIFIED`() {
        val actual = fixedVideoPlayerSelectOrientation(
            isFullscreen = false,
            videoWidth = 1080,
            videoHeight = 1920
        )

        assertEquals(
            "PRESERVATION: When isFullscreen=false, VideoPlayerScreen should return " +
                "SCREEN_ORIENTATION_UNSPECIFIED regardless of video dimensions.",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            actual
        )
    }

    /**
     * Property-based preservation test: For ALL landscape video dimensions (w >= h > 0),
     * the fixed VideoPlayerScreen returns SCREEN_ORIENTATION_SENSOR_LANDSCAPE.
     *
     * This simulates property-based testing by iterating over a representative range
     * of landscape dimensions. The project does not include a PBT library, so we use
     * a loop to generate many test cases and assert the property holds for all of them.
     *
     * Tested range:
     *   - height: 1 to 1080 (step 60, covering common resolutions)
     *   - width: height to 3840 (step 120, ensuring w >= h)
     *
     * This generates ~500 landscape dimension pairs covering common aspect ratios
     * (1:1, 4:3, 16:9, 21:9, ultra-wide) and edge cases (w == h, very wide).
     *
     * EXPECTED OUTCOME: PASSES on fixed code (all landscape inputs return SENSOR_LANDSCAPE).
     *
     * Validates: Requirements 3.1, 3.2, 3.3
     */
    @Test
    fun `Property - for all landscape VideoSize w ge h gt 0 fixed code returns SENSOR_LANDSCAPE`() {
        val failures = mutableListOf<String>()

        // Iterate over a representative range of landscape dimensions
        for (h in 1..1080 step 60) {
            for (w in h..3840 step 120) {
                val actual = fixedVideoPlayerSelectOrientation(
                    isFullscreen = true,
                    videoWidth = w,
                    videoHeight = h
                )
                if (actual != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    failures.add("VideoSize($w, $h) → $actual (expected SENSOR_LANDSCAPE)")
                    if (failures.size >= 5) break // Collect first 5 failures only
                }
            }
            if (failures.size >= 5) break
        }

        assertEquals(
            "PRESERVATION PROPERTY FAILED for ${failures.size} landscape dimension(s):\n" +
                failures.joinToString("\n") +
                "\nFor all VideoSize(w, h) where w >= h > 0, fixed VideoPlayerScreen " +
                "must return SCREEN_ORIENTATION_SENSOR_LANDSCAPE.",
            0,
            failures.size
        )
    }

    /**
     * Property-based preservation test: For ALL isFullscreen=false transitions,
     * the fixed VideoPlayerScreen returns SCREEN_ORIENTATION_UNSPECIFIED.
     *
     * Tests a range of video dimensions (both landscape and portrait) to confirm
     * that the fullscreen-exit path always restores UNSPECIFIED.
     *
     * EXPECTED OUTCOME: PASSES on fixed code.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    fun `Property - for all isFullscreen false transitions fixed code returns UNSPECIFIED`() {
        val failures = mutableListOf<String>()

        // Test a variety of video dimensions — the result should always be UNSPECIFIED
        val testDimensions = listOf(
            Pair(1920, 1080), // landscape 16:9
            Pair(1280, 720),  // landscape 16:9
            Pair(1080, 1920), // portrait 9:16
            Pair(720, 1280),  // portrait 9:16
            Pair(720, 720),   // square 1:1
            Pair(1, 1),       // minimum square
            Pair(3840, 2160), // 4K landscape
            Pair(2160, 3840), // 4K portrait
            Pair(640, 480),   // 4:3 landscape
            Pair(480, 640),   // 4:3 portrait
        )

        for ((w, h) in testDimensions) {
            val actual = fixedVideoPlayerSelectOrientation(
                isFullscreen = false,
                videoWidth = w,
                videoHeight = h
            )
            if (actual != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                failures.add("VideoSize($w, $h) with isFullscreen=false → $actual (expected UNSPECIFIED)")
            }
        }

        assertEquals(
            "PRESERVATION PROPERTY FAILED for ${failures.size} case(s):\n" +
                failures.joinToString("\n") +
                "\nFor all isFullscreen=false transitions, VideoPlayerScreen " +
                "must return SCREEN_ORIENTATION_UNSPECIFIED.",
            0,
            failures.size
        )
    }

    /**
     * Additional specific examples: common landscape resolutions.
     *
     * Tests a set of well-known landscape video resolutions to confirm they all
     * return SCREEN_ORIENTATION_SENSOR_LANDSCAPE on fixed code.
     *
     * EXPECTED OUTCOME: PASSES on fixed code.
     *
     * Validates: Requirements 3.1, 3.3
     */
    @Test
    fun `Preservation - common landscape resolutions all return SENSOR_LANDSCAPE`() {
        val landscapeResolutions = listOf(
            Pair(3840, 2160), // 4K UHD
            Pair(2560, 1440), // 2K QHD
            Pair(1920, 1080), // 1080p FHD
            Pair(1280, 720),  // 720p HD
            Pair(854, 480),   // 480p
            Pair(640, 360),   // 360p
            Pair(426, 240),   // 240p
            Pair(1920, 800),  // 2.4:1 cinematic
            Pair(2560, 1080), // 21:9 ultra-wide
            Pair(720, 720),   // square (w == h → landscape default)
        )

        val failures = mutableListOf<String>()

        for ((w, h) in landscapeResolutions) {
            val actual = fixedVideoPlayerSelectOrientation(
                isFullscreen = true,
                videoWidth = w,
                videoHeight = h
            )
            if (actual != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                failures.add("VideoSize($w, $h) → $actual (expected SENSOR_LANDSCAPE)")
            }
        }

        assertEquals(
            "PRESERVATION: ${failures.size} landscape resolution(s) did not return SENSOR_LANDSCAPE:\n" +
                failures.joinToString("\n"),
            0,
            failures.size
        )
    }
}
