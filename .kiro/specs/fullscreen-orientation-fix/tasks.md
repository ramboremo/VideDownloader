# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Fullscreen Orientation Mismatch
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists in both `VideoPlayerScreen` and `BrowserScreen`
  - **Scoped PBT Approach**: Scope the property to the concrete failing cases to ensure reproducibility:
    - Portrait video in player: `VideoSize(width=1080, height=1920)` with `isFullscreen=true`
    - Any video in browser: `View` with known dimensions passed to `onShowCustomView`
  - Test cases to implement (in `VideoPlayerScreenOrientationTest` and `BrowserScreenOrientationTest`):
    1. Mock `exoPlayer.videoSize` as `VideoSize(1080, 1920)`, trigger `isFullscreen = true`, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT` — **will FAIL** (gets `SENSOR_LANDSCAPE`)
    2. Call `onShowCustomView` with a `View` sized 1280×720, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE` — **will FAIL** (no orientation set)
    3. Call `onShowCustomView` with a `View` sized 720×1280, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT` — **will FAIL** (no orientation set)
    4. Square video edge case: `VideoSize(720, 720)`, trigger fullscreen, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE` — may FAIL
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct — it proves the bug exists)
  - Document counterexamples found:
    - `VideoPlayerScreen`: `requestedOrientation` is `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` for portrait `VideoSize(1080, 1920)` (wrong constant)
    - `BrowserScreen`: `requestedOrientation` is never set — no orientation change at all
  - Mark task complete when tests are written, run, and failures are documented
  - _Requirements: 1.2, 1.3_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Landscape Fullscreen Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Observe behavior on UNFIXED code for non-buggy inputs (landscape videos in `VideoPlayerScreen` where `isBugCondition` returns false):
    - Observe: `VideoSize(1920, 1080)` with `isFullscreen=true` → `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE` ✓
    - Observe: `VideoSize(1280, 720)` with `isFullscreen=true` → `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE` ✓
    - Observe: `isFullscreen=false` → `requestedOrientation == SCREEN_ORIENTATION_UNSPECIFIED` ✓
  - Write property-based tests capturing observed behavior patterns from Preservation Requirements:
    - For all `VideoSize(w, h)` where `w >= h > 0`: fixed `VideoPlayerScreen` SHALL request `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`
    - For all `isFullscreen=false` transitions: fixed code SHALL request `SCREEN_ORIENTATION_UNSPECIFIED`
    - For all fullscreen entries (portrait or landscape): system bars SHALL be hidden
  - Property-based testing generates many test cases for stronger guarantees across the landscape input domain
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.6_

- [x] 3. Fix fullscreen orientation in VideoPlayerScreen and BrowserScreen

  - [x] 3.1 Add `videoSize` state tracking via `Player.Listener` in `VideoPlayerScreen`
    - Add `var videoSize by remember { mutableStateOf(exoPlayer.videoSize) }` after the `exoPlayer` `remember` block
    - Add a `DisposableEffect(exoPlayer)` that registers a `Player.Listener` overriding `onVideoSizeChanged(size: VideoSize)` to update `videoSize = size`
    - Remove the listener in `onDispose` via `exoPlayer.removeListener(listener)`
    - Import `androidx.media3.common.Player` and `androidx.media3.common.VideoSize`
    - _Bug_Condition: `isBugCondition(X)` where `X.surface = "player" AND X.videoHeight > X.videoWidth`_
    - _Requirements: 2.2_

  - [x] 3.2 Replace hard-coded landscape constant with aspect-ratio check in `VideoPlayerScreen`
    - In `LaunchedEffect(isFullscreen)`, replace the unconditional `activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE` with:
      ```kotlin
      val orientation = if (videoSize.height > videoSize.width)
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      else
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      activity.requestedOrientation = orientation
      ```
    - The `else` branch (landscape) preserves existing behavior for all non-buggy inputs
    - The `SCREEN_ORIENTATION_UNSPECIFIED` restore in the `else` branch of `LaunchedEffect` remains unchanged
    - _Bug_Condition: `isBugCondition(X)` where `X.surface = "player" AND X.videoHeight > X.videoWidth`_
    - _Expected_Behavior: `requestedOrientation = SCREEN_ORIENTATION_SENSOR_PORTRAIT` when `videoSize.height > videoSize.width`, else `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`_
    - _Preservation: Landscape videos (`width >= height`) continue to get `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` — same as before_
    - _Requirements: 2.1, 2.2, 3.1, 3.2, 3.3_

  - [x] 3.3 Add orientation request in `BrowserScreen` fullscreen `LaunchedEffect`
    - In `LaunchedEffect(fullScreenCustomView)`, inside the `if (isFullscreen)` branch, after the existing system-bar hiding and `systemUiVisibility` fallback, add:
      ```kotlin
      val view = fullScreenCustomView
      if (view != null && activity != null) {
          view.post {
              val w = view.width.takeIf { it > 0 } ?: view.layoutParams?.width ?: 0
              val h = view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: 0
              val orientation = if (h > w && h > 0 && w > 0)
                  ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
              else
                  ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
              activity.requestedOrientation = orientation
          }
      }
      ```
    - In the `else` branch (fullscreen exit), add `activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`
    - Import `android.content.pm.ActivityInfo` if not already present
    - Use `view.post {}` to defer dimension reading until after layout, since the custom view may not be laid out when `onShowCustomView` fires
    - Zero-dimension fallback defaults to `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (safe default)
    - The existing `BackHandler` inside `fullScreenCustomView != null` already nulls the view, which triggers the `LaunchedEffect` `else` branch — no additional `BackHandler` change needed
    - _Bug_Condition: `isBugCondition(X)` where `X.surface = "browser"`_
    - _Expected_Behavior: `requestedOrientation = SCREEN_ORIENTATION_SENSOR_PORTRAIT` when `h > w && h > 0 && w > 0`, else `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`_
    - _Preservation: `BrowserScreen` fullscreen exit restores `SCREEN_ORIENTATION_UNSPECIFIED`; system-bar logic is unchanged_
    - _Requirements: 2.3, 2.5, 3.7_

  - [x] 3.4 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Fullscreen Orientation Mismatch
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - The tests from task 1 encode the expected behavior
    - When these tests pass, it confirms the expected behavior is satisfied for all bug-condition inputs
    - Run all four test cases from task 1 on the FIXED code
    - **EXPECTED OUTCOME**: All tests PASS (confirms bug is fixed)
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 3.5 Verify preservation tests still pass
    - **Property 2: Preservation** - Landscape Fullscreen Behavior Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run all preservation property tests from task 2 on the FIXED code
    - **EXPECTED OUTCOME**: All tests PASS (confirms no regressions)
    - Confirm landscape videos in `VideoPlayerScreen` still get `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`
    - Confirm fullscreen exit still restores `SCREEN_ORIENTATION_UNSPECIFIED`
    - Confirm system bars are still hidden on fullscreen entry for both portrait and landscape videos

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full test suite and confirm all tests pass
  - Verify no regressions in `VideoPlayerScreen` lifecycle behavior (pause/resume unaffected)
  - Verify `BrowserScreen` fullscreen exit via back press restores both system bars and `SCREEN_ORIENTATION_UNSPECIFIED`
  - Verify `VideoPlayerScreen` `onDispose` still restores `SCREEN_ORIENTATION_UNSPECIFIED` when navigating away while in fullscreen
  - Ask the user if any questions arise
