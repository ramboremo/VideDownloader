# Bugfix Requirements Document

## Introduction

When a user taps the fullscreen button on a video — either in the in-app video player (`VideoPlayerScreen`) or in the browser's WebView player (`BrowserScreen`) — the app unconditionally forces landscape orientation. This is wrong for portrait videos, which should enter fullscreen in portrait mode. Additionally, once in fullscreen the user cannot freely rotate between orientations; the lock prevents it.

The fix must make fullscreen orientation respect the video's actual aspect ratio (portrait videos → portrait fullscreen, landscape videos → landscape fullscreen) and allow the user to manually rotate while in fullscreen. Exiting fullscreen must restore the previous orientation state without side effects.

---

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the user taps the fullscreen button on a landscape video in `VideoPlayerScreen` THEN the system forces `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, which is correct but achieved via a hard-coded constant rather than video-aware logic.

1.2 WHEN the user taps the fullscreen button on a portrait video in `VideoPlayerScreen` THEN the system forces `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, causing the portrait video to be displayed sideways in fullscreen.

1.3 WHEN the user taps the fullscreen button on a video in the browser (`BrowserScreen` WebView fullscreen via `onShowCustomView`) THEN the system applies no orientation change at all, leaving the screen in whatever orientation it was in rather than matching the video's orientation.

1.4 WHEN the user is in fullscreen mode in `VideoPlayerScreen` and physically rotates the device THEN the system does not allow free rotation because `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` locks the axis to landscape only.

### Expected Behavior (Correct)

2.1 WHEN the user taps the fullscreen button on a landscape video (width > height) in `VideoPlayerScreen` THEN the system SHALL request `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` so the video fills the screen in landscape.

2.2 WHEN the user taps the fullscreen button on a portrait video (height > width) in `VideoPlayerScreen` THEN the system SHALL request `SCREEN_ORIENTATION_SENSOR_PORTRAIT` so the video fills the screen in portrait.

2.3 WHEN the user taps the fullscreen button on a video in the browser (`BrowserScreen`) THEN the system SHALL detect the video's orientation from the custom view's dimensions and request the matching sensor-based orientation (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` or `SCREEN_ORIENTATION_SENSOR_PORTRAIT`).

2.4 WHEN the user is in fullscreen mode and physically rotates the device THEN the system SHALL allow free rotation within the locked axis (sensor-based), so the user can flip between landscape-left and landscape-right (or portrait and reverse-portrait) without being stuck.

2.5 WHEN the user exits fullscreen (via back press or the fullscreen toggle button) THEN the system SHALL restore `SCREEN_ORIENTATION_UNSPECIFIED` so the rest of the app follows the device's natural orientation again.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user is not in fullscreen mode THEN the system SHALL CONTINUE TO allow free device rotation as determined by `SCREEN_ORIENTATION_UNSPECIFIED`.

3.2 WHEN the user exits the `VideoPlayerScreen` entirely (navigates back) THEN the system SHALL CONTINUE TO restore `SCREEN_ORIENTATION_UNSPECIFIED` so other screens are not affected.

3.3 WHEN a landscape video is played in fullscreen THEN the system SHALL CONTINUE TO hide system bars (status bar and navigation bar) for a true immersive experience.

3.4 WHEN a portrait video is played in fullscreen THEN the system SHALL CONTINUE TO hide system bars for a true immersive experience.

3.5 WHEN the user presses back while in fullscreen THEN the system SHALL CONTINUE TO exit fullscreen first before navigating away from the screen.

3.6 WHEN the video player is paused and resumed via the Android lifecycle (e.g., app goes to background) THEN the system SHALL CONTINUE TO pause and resume playback correctly regardless of orientation.

3.7 WHEN the browser's WebView fullscreen is exited (via back press or `onHideCustomView`) THEN the system SHALL CONTINUE TO restore system bars and orientation correctly.

---

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type FullscreenRequest { videoWidth: Int, videoHeight: Int, surface: "player" | "browser" }
  OUTPUT: boolean

  // Bug fires when entering fullscreen for a portrait video in the player,
  // OR when entering fullscreen in the browser (no orientation logic at all)
  RETURN (X.surface = "player" AND X.videoHeight > X.videoWidth)
      OR (X.surface = "browser")
END FUNCTION
```

**Property: Fix Checking**
```pascal
FOR ALL X WHERE isBugCondition(X) DO
  result ← enterFullscreen'(X)
  IF X.videoHeight > X.videoWidth THEN
    ASSERT requestedOrientation = SCREEN_ORIENTATION_SENSOR_PORTRAIT
  ELSE
    ASSERT requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  END IF
END FOR
```

**Property: Preservation Checking**
```pascal
FOR ALL X WHERE NOT isBugCondition(X) DO
  // Landscape videos in the player — behavior must be unchanged
  ASSERT enterFullscreen'(X) = enterFullscreen(X)
END FOR
```
