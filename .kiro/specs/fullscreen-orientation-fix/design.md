# Fullscreen Orientation Fix — Bugfix Design

## Overview

The app unconditionally forces `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` whenever a video enters fullscreen in `VideoPlayerScreen`. Portrait videos are therefore displayed sideways. In `BrowserScreen`, the WebView fullscreen path (`onShowCustomView`) applies no orientation change at all, leaving the screen in whatever orientation it happened to be in.

The fix is minimal and targeted: detect the video's aspect ratio at the moment fullscreen is entered and request the matching sensor-based orientation constant (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` for landscape videos, `SCREEN_ORIENTATION_SENSOR_PORTRAIT` for portrait videos). Both screens must be updated. No other behavior changes.

---

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — entering fullscreen for a portrait video in `VideoPlayerScreen`, OR entering fullscreen in `BrowserScreen` (which has no orientation logic at all).
- **Property (P)**: The desired behavior when the bug condition holds — the requested orientation constant must match the video's aspect ratio.
- **Preservation**: All existing behavior for landscape videos in `VideoPlayerScreen`, lifecycle handling, system-bar hiding, back-press handling, and `BrowserScreen` exit behavior that must remain unchanged by the fix.
- **`isFullscreen`**: The `Boolean` state variable in `VideoPlayerScreen` that drives the `LaunchedEffect` responsible for orientation and system-bar changes.
- **`fullScreenCustomView`**: The `View?` state variable in `BrowserScreen` that holds the WebView's custom fullscreen view; its presence drives the `LaunchedEffect` for system-bar changes.
- **`videoSize`**: The `VideoSize` object exposed by ExoPlayer's `Player.Listener.onVideoSizeChanged` callback, containing `width` and `height` of the currently playing video.
- **`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`**: Android constant (`ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) — locks to landscape axis but allows sensor-driven flip between landscape-left and landscape-right.
- **`SCREEN_ORIENTATION_SENSOR_PORTRAIT`**: Android constant (`ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT`) — locks to portrait axis but allows sensor-driven flip between portrait and reverse-portrait.
- **`SCREEN_ORIENTATION_UNSPECIFIED`**: Android constant (`ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`) — restores free rotation; used when exiting fullscreen.

---

## Bug Details

### Bug Condition

The bug manifests when a user taps the fullscreen button on a portrait video in `VideoPlayerScreen`, or taps fullscreen on any video in `BrowserScreen`. In `VideoPlayerScreen`, the `LaunchedEffect(isFullscreen)` block hard-codes `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` without consulting the video's actual dimensions. In `BrowserScreen`, the `LaunchedEffect(fullScreenCustomView)` block never calls `requestedOrientation` at all.

**Formal Specification:**
```
FUNCTION isBugCondition(X)
  INPUT: X of type FullscreenRequest {
    videoWidth:  Int,
    videoHeight: Int,
    surface:     "player" | "browser"
  }
  OUTPUT: boolean

  RETURN (X.surface = "player"  AND X.videoHeight > X.videoWidth)
      OR (X.surface = "browser")
END FUNCTION
```

### Examples

- **Portrait video in player** — User plays a 1080×1920 TikTok-style video and taps fullscreen. Expected: portrait fullscreen. Actual: landscape fullscreen, video appears rotated 90°.
- **Portrait video in browser** — User browses a site with a 9:16 video in the WebView and taps the browser's native fullscreen button. Expected: portrait fullscreen. Actual: screen stays in whatever orientation it was in (no orientation request made).
- **Landscape video in browser** — User browses a 16:9 YouTube-style video in the WebView and taps fullscreen. Expected: landscape fullscreen. Actual: screen stays in whatever orientation it was in (no orientation request made).
- **Square video (edge case)** — A 1:1 video. `videoHeight == videoWidth`, so `isBugCondition` returns false for the player surface. The fix should treat this as landscape (default) since `width >= height`.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Landscape videos in `VideoPlayerScreen` must continue to enter fullscreen in landscape orientation (behavior is currently correct; the fix must not regress it).
- System bars (status bar and navigation bar) must continue to be hidden when entering fullscreen, for both landscape and portrait videos, in both screens.
- Back press while in fullscreen must continue to exit fullscreen first before navigating away.
- Exiting fullscreen (via back press or the fullscreen toggle button) must continue to restore `SCREEN_ORIENTATION_UNSPECIFIED`.
- Navigating away from `VideoPlayerScreen` entirely must continue to restore `SCREEN_ORIENTATION_UNSPECIFIED` via the `DisposableEffect` `onDispose` block.
- ExoPlayer lifecycle pause/resume behavior must be completely unaffected by the orientation change.
- `BrowserScreen` fullscreen exit (`onHideCustomView` / back press) must continue to restore system bars and orientation correctly.

**Scope:**
All inputs where `isBugCondition` returns false — landscape videos in the player, non-fullscreen interactions, mouse/touch interactions, lifecycle events — must be completely unaffected by this fix.

---

## Hypothesized Root Cause

Based on code inspection of `VideoPlayerScreen.kt` and `BrowserScreen.kt`:

1. **Hard-coded orientation constant in `VideoPlayerScreen`**: The `LaunchedEffect(isFullscreen)` block at line ~80 unconditionally calls `activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. There is no reference to `exoPlayer.videoSize` or any aspect-ratio check. The fix requires reading `exoPlayer.videoSize` (or a tracked `videoSize` state) at the moment fullscreen is entered.

2. **No orientation logic in `BrowserScreen`**: The `LaunchedEffect(fullScreenCustomView)` block handles system-bar visibility but never calls `activity?.requestedOrientation`. The custom view passed to `onShowCustomView` has layout dimensions that reflect the video's aspect ratio, but those dimensions are never read. The fix requires measuring the view's width and height (or using a `VideoSize` hint if available) and requesting the appropriate constant.

3. **ExoPlayer `videoSize` availability**: `ExoPlayer` exposes the current video's dimensions via `player.videoSize` (a `VideoSize` object with `width` and `height`). This is available synchronously once the video has been prepared. The `LaunchedEffect(isFullscreen)` runs after the user taps fullscreen, by which time the video is already playing and `videoSize` is populated. No additional async work is needed.

4. **Custom view dimensions in `BrowserScreen`**: The `View` passed to `onShowCustomView` may not have been laid out yet when the callback fires. A safe approach is to use `view.post { }` to read dimensions after layout, or to use `ViewTreeObserver.OnGlobalLayoutListener`. Alternatively, the `VideoSize` hint parameter of `onShowCustomView(View, CustomViewCallback, VideoSize)` (API 23+) can be used if available, but the current code uses the two-argument override. The simplest reliable approach is to read `view.layoutParams` width/height if set, or fall back to a `post { }` measurement.

---

## Correctness Properties

Property 1: Bug Condition — Orientation Matches Video Aspect Ratio

_For any_ fullscreen request where the bug condition holds (`isBugCondition(X)` returns true) — i.e., a portrait video entering fullscreen in the player, or any video entering fullscreen in the browser — the fixed code SHALL request `SCREEN_ORIENTATION_SENSOR_PORTRAIT` when `videoHeight > videoWidth`, and `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` when `videoWidth >= videoHeight`.

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Preservation — Non-Buggy Fullscreen Behavior Unchanged

_For any_ fullscreen request where the bug condition does NOT hold (`isBugCondition(X)` returns false) — i.e., a landscape video entering fullscreen in the player — the fixed code SHALL produce exactly the same `requestedOrientation` value as the original code (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`), preserving all existing landscape fullscreen behavior.

**Validates: Requirements 3.1, 3.2, 3.3, 3.6**

---

## Fix Implementation

### Changes Required

Assuming the root cause analysis is correct:

**File 1**: `app/src/main/java/com/cognitivechaos/xdownload/ui/player/VideoPlayerScreen.kt`

**Change 1 — Track video size as state**:
Add a `videoSize` state variable that is updated via an `ExoPlayer` listener:
```kotlin
var videoSize by remember { mutableStateOf(exoPlayer.videoSize) }
DisposableEffect(exoPlayer) {
    val listener = object : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) {
            videoSize = size
        }
    }
    exoPlayer.addListener(listener)
    onDispose { exoPlayer.removeListener(listener) }
}
```

**Change 2 — Use aspect ratio in the fullscreen `LaunchedEffect`**:
Replace the hard-coded `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` with an aspect-ratio check:
```kotlin
LaunchedEffect(isFullscreen) {
    if (window != null && activity != null) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val orientation = if (videoSize.height > videoSize.width)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity.requestedOrientation = orientation
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
```

---

**File 2**: `app/src/main/java/com/cognitivechaos/xdownload/ui/browser/BrowserScreen.kt`

**Change 3 — Add orientation request in the fullscreen `LaunchedEffect`**:
Inside the existing `LaunchedEffect(fullScreenCustomView)` block, after hiding system bars, add orientation logic. Since the view may not be laid out yet, use `view.post { }` to read dimensions after layout:
```kotlin
LaunchedEffect(fullScreenCustomView) {
    if (window != null) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val isFullscreen = fullScreenCustomView != null

        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // ... existing systemUiVisibility fallback ...

            // NEW: detect orientation from custom view dimensions
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
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            // ... existing systemUiVisibility restore ...
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
```

**Change 4 — Restore orientation on BrowserScreen fullscreen exit (BackHandler)**:
The existing `BackHandler` inside the `if (fullScreenCustomView != null)` block already nulls `fullScreenCustomView`, which triggers the `LaunchedEffect` to restore system bars. The `LaunchedEffect` change above will also restore `SCREEN_ORIENTATION_UNSPECIFIED` in the `else` branch, so no additional change is needed in the `BackHandler`.

---

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write unit tests that simulate the fullscreen entry path for portrait and landscape videos. For `VideoPlayerScreen`, mock `ExoPlayer.videoSize` and assert the `requestedOrientation` value set on the activity. For `BrowserScreen`, create a mock `View` with known dimensions and assert the `requestedOrientation` value. Run these tests on the UNFIXED code to observe failures.

**Test Cases**:
1. **Portrait video fullscreen in player** — Set `exoPlayer.videoSize` to `VideoSize(1080, 1920)`, trigger `isFullscreen = true`, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT`. Will fail on unfixed code (gets `SENSOR_LANDSCAPE`).
2. **Landscape video fullscreen in browser** — Call `onShowCustomView` with a `View` sized 1280×720, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. Will fail on unfixed code (no orientation set).
3. **Portrait video fullscreen in browser** — Call `onShowCustomView` with a `View` sized 720×1280, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT`. Will fail on unfixed code (no orientation set).
4. **Square video edge case** — Set `videoSize` to `VideoSize(720, 720)`, trigger fullscreen, assert `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (default for non-portrait). May fail on unfixed code.

**Expected Counterexamples**:
- `requestedOrientation` is `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` for portrait videos in the player (wrong constant).
- `requestedOrientation` is never set in `BrowserScreen` (no orientation change at all).

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL X WHERE isBugCondition(X) DO
  result := enterFullscreen_fixed(X)
  IF X.videoHeight > X.videoWidth THEN
    ASSERT result.requestedOrientation = SCREEN_ORIENTATION_SENSOR_PORTRAIT
  ELSE
    ASSERT result.requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  END IF
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT enterFullscreen_original(X).requestedOrientation
       = enterFullscreen_fixed(X).requestedOrientation
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain (random video dimensions where `width >= height`).
- It catches edge cases that manual unit tests might miss (e.g., very wide videos, 1:1 aspect ratio).
- It provides strong guarantees that landscape fullscreen behavior is unchanged for all non-buggy inputs.

**Test Plan**: Observe that landscape video fullscreen in `VideoPlayerScreen` correctly requests `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` on unfixed code, then write property-based tests to verify this continues after the fix.

**Test Cases**:
1. **Landscape video preservation** — For any `VideoSize(w, h)` where `w >= h`, verify `requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE` both before and after the fix.
2. **Fullscreen exit preservation** — Verify that setting `isFullscreen = false` always restores `SCREEN_ORIENTATION_UNSPECIFIED`, regardless of the video's aspect ratio.
3. **System bar hiding preservation** — Verify that system bars are hidden on fullscreen entry for both portrait and landscape videos (unchanged behavior).
4. **Lifecycle preservation** — Verify that ExoPlayer pause/resume behavior is unaffected by the orientation change (the `DisposableEffect` observer is independent of the `LaunchedEffect` orientation logic).

### Unit Tests

- Test `VideoPlayerScreen` fullscreen entry with portrait video (`height > width`) → asserts `SCREEN_ORIENTATION_SENSOR_PORTRAIT`.
- Test `VideoPlayerScreen` fullscreen entry with landscape video (`width > height`) → asserts `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
- Test `VideoPlayerScreen` fullscreen exit → asserts `SCREEN_ORIENTATION_UNSPECIFIED`.
- Test `BrowserScreen` `onShowCustomView` with portrait-shaped view → asserts `SCREEN_ORIENTATION_SENSOR_PORTRAIT`.
- Test `BrowserScreen` `onShowCustomView` with landscape-shaped view → asserts `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
- Test `BrowserScreen` fullscreen exit → asserts `SCREEN_ORIENTATION_UNSPECIFIED`.
- Test edge case: square video (`width == height`) → asserts `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
- Test edge case: view with zero dimensions in `BrowserScreen` → asserts `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (safe default).

### Property-Based Tests

- Generate random `VideoSize(w, h)` where `w >= h > 0` and verify the fixed `VideoPlayerScreen` always requests `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (preservation of existing correct behavior).
- Generate random `VideoSize(w, h)` where `h > w > 0` and verify the fixed `VideoPlayerScreen` always requests `SCREEN_ORIENTATION_SENSOR_PORTRAIT` (fix checking).
- Generate random view dimensions `(w, h)` where `w >= h > 0` and verify `BrowserScreen` always requests `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
- Generate random view dimensions `(w, h)` where `h > w > 0` and verify `BrowserScreen` always requests `SCREEN_ORIENTATION_SENSOR_PORTRAIT`.

### Integration Tests

- Full flow: open a portrait video in `VideoPlayerScreen`, tap fullscreen, verify the activity is in portrait orientation, tap fullscreen again, verify orientation is restored to unspecified.
- Full flow: open a landscape video in `VideoPlayerScreen`, tap fullscreen, verify landscape orientation, exit, verify restored.
- Full flow: navigate to a page with a portrait video in `BrowserScreen`, trigger WebView fullscreen, verify portrait orientation, exit, verify restored.
- Regression: navigate away from `VideoPlayerScreen` while in fullscreen, verify `SCREEN_ORIENTATION_UNSPECIFIED` is restored by `onDispose`.
