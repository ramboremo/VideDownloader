# Requirements Document

## Introduction

This feature adds a long-press gesture on the center "new tab" plus button in the browser's tab manager bottom bar. When the user long-presses the plus button, a secondary incognito button animates upward from it (a "pop-out birth" animation). The user then slides their finger up onto the incognito button and releases to open a new incognito tab. If the user lifts their finger outside the incognito button, the gesture is cancelled. After the interaction completes (success or cancel), the incognito button animates back and disappears. The gesture is fully repeatable.

The feature targets the `TabManagerOverlay` composable in `BrowserScreen.kt` and the `BrowserViewModel` in `BrowserViewModel.kt` of the Android Jetpack Compose browser app.

## Glossary

- **Plus_Button**: The circular orange button with an `Icons.Default.Add` icon at the center of the `TabManagerOverlay` bottom bar, used to open a new regular tab.
- **Incognito_Button**: The secondary violet/purple circular button that pops out above the Plus_Button during a long-press gesture, used to trigger incognito tab creation.
- **Gesture_Controller**: The Compose `pointerInput` handler attached to the Plus_Button that detects long-press, tracks finger movement, and determines gesture outcome.
- **Incognito_Icon**: A custom visual mark on the Incognito_Button that communicates private browsing — a stylized eye with a diagonal slash overlaid on a hat silhouette (spy/detective motif).
- **Pop_Out_Animation**: The spring-based upward translation and scale-in animation that reveals the Incognito_Button above the Plus_Button.
- **Dismiss_Animation**: The spring-based downward translation and scale-out animation that hides the Incognito_Button after the gesture ends.
- **Hit_Target**: The circular touch area of the Incognito_Button used to determine whether the user's finger is over it at release time.
- **BrowserViewModel**: The `BrowserViewModel` class in `BrowserViewModel.kt` that manages tab state and exposes `addIncognitoTab()`.
- **Tab_Manager_Bar**: The bottom `Surface` row inside `TabManagerOverlay` that contains the Plus_Button and surrounding navigation icons.

---

## Requirements

### Requirement 1: Long-Press Detection on Plus Button

**User Story:** As a user, I want to long-press the new-tab plus button so that I can trigger the incognito tab gesture without accidentally activating it on a normal tap.

#### Acceptance Criteria

1. WHEN the user presses and holds the Plus_Button for 500 milliseconds or longer, THE Gesture_Controller SHALL begin the incognito gesture sequence.
2. WHEN the user releases the Plus_Button before 500 milliseconds have elapsed, THE Gesture_Controller SHALL treat the interaction as a normal tap and invoke the regular `onAddTab` callback.
3. WHILE the long-press threshold has not been reached, THE Plus_Button SHALL provide haptic feedback (a single `HapticFeedbackType.LongPress` vibration) at the moment the threshold is crossed.
4. IF the user moves their finger more than 10 dp from the initial touch point before the long-press threshold is reached, THEN THE Gesture_Controller SHALL cancel the long-press detection and treat the interaction as a scroll or swipe.

---

### Requirement 2: Incognito Button Pop-Out Animation

**User Story:** As a user, I want to see the incognito button smoothly appear above the plus button so that the gesture feels alive and polished.

#### Acceptance Criteria

1. WHEN the long-press threshold is crossed, THE Incognito_Button SHALL animate from a scale of 0f and translationY of 0f (co-located with the Plus_Button) to a scale of 1f and a translationY of −72 dp above the Plus_Button center using a spring animation with `dampingRatio = 0.55f` and `stiffness = 380f`.
2. WHEN the Pop_Out_Animation begins, THE Incognito_Button SHALL fade in from alpha 0f to alpha 1f over 220 milliseconds using `FastOutSlowInEasing`.
3. WHILE the Incognito_Button is visible, THE Plus_Button SHALL scale down to 0.88f using a `tween(180)` animation to visually indicate the gesture is active.
4. THE Incognito_Button SHALL be rendered above the Plus_Button in z-order so it is never occluded by surrounding UI elements.

---

### Requirement 3: Incognito Button Visual Design

**User Story:** As a user, I want the incognito button to look distinct and recognizable so that I immediately understand it will open a private browsing tab.

#### Acceptance Criteria

1. THE Incognito_Button SHALL have a circular shape with a diameter of 58 dp and a background color of `Color(0xFF6C5CE7)` (violet/purple).
2. THE Incognito_Button SHALL display the Incognito_Icon centered within it, rendered in `Color.White` at 28 dp size.
3. THE Incognito_Icon SHALL be composed of `Icons.Default.VisibilityOff` (an eye with a diagonal slash) as the primary symbol, communicating private/hidden browsing.
4. THE Incognito_Button SHALL display a drop shadow with `shadowElevation = 14.dp` and `CircleShape` to visually lift it above the Tab_Manager_Bar surface.
5. WHILE the user's finger is positioned over the Incognito_Button's Hit_Target, THE Incognito_Button SHALL scale to 1.12f using a `spring(dampingRatio = 0.4f, stiffness = 500f)` animation to provide hover feedback.
6. WHEN the user's finger moves off the Incognito_Button's Hit_Target, THE Incognito_Button SHALL scale back to 1f using a `spring(dampingRatio = 0.6f, stiffness = 400f)` animation.

---

### Requirement 4: Slide-to-Confirm Gesture Tracking

**User Story:** As a user, I want to slide my finger from the plus button up onto the incognito button so that the gesture feels intentional and prevents accidental incognito tab creation.

#### Acceptance Criteria

1. WHILE the Incognito_Button is visible, THE Gesture_Controller SHALL continuously track the user's finger position relative to the Incognito_Button's Hit_Target.
2. WHILE the user's finger is within the Incognito_Button's Hit_Target, THE Gesture_Controller SHALL set an `isOverTarget` flag to `true`.
3. WHILE the user's finger is outside the Incognito_Button's Hit_Target, THE Gesture_Controller SHALL set the `isOverTarget` flag to `false`.
4. THE Hit_Target SHALL be a circular region with a radius of 34 dp centered on the Incognito_Button.

---

### Requirement 5: Incognito Tab Creation on Release

**User Story:** As a user, I want releasing my finger on the incognito button to open a new incognito tab so that the gesture completes the intended action.

#### Acceptance Criteria

1. WHEN the user releases their finger while `isOverTarget` is `true`, THE BrowserViewModel SHALL invoke `addIncognitoTab()` to create a new incognito tab.
2. WHEN `addIncognitoTab()` is called, THE BrowserViewModel SHALL add a new `BrowserTab` with `isIncognito = true` to the tabs list, set it as the active tab, set `_isIncognito` to `true`, and set `_currentUrl` to `""` and `_currentTitle` to `"Incognito"`.
3. WHEN the user releases their finger while `isOverTarget` is `true`, THE Gesture_Controller SHALL trigger the Dismiss_Animation immediately after invoking `addIncognitoTab()`.
4. WHEN `addIncognitoTab()` completes, THE Tab_Manager_Bar SHALL dismiss the tab manager overlay so the user lands directly in the new incognito tab.

---

### Requirement 6: Gesture Cancellation

**User Story:** As a user, I want to cancel the incognito gesture by releasing my finger away from the incognito button so that I can back out without opening an unwanted tab.

#### Acceptance Criteria

1. WHEN the user releases their finger while `isOverTarget` is `false`, THE Gesture_Controller SHALL cancel the gesture without creating a new tab.
2. WHEN the gesture is cancelled, THE Gesture_Controller SHALL trigger the Dismiss_Animation.
3. WHEN the gesture is cancelled, THE Plus_Button SHALL animate back to scale 1f using a `tween(200)` animation.

---

### Requirement 7: Dismiss Animation

**User Story:** As a user, I want the incognito button to smoothly disappear after the gesture ends so that the UI returns cleanly to its normal state.

#### Acceptance Criteria

1. WHEN the Dismiss_Animation is triggered, THE Incognito_Button SHALL animate from its current scale and translationY back to scale 0f and translationY 0f using a spring animation with `dampingRatio = 0.7f` and `stiffness = 500f`.
2. WHEN the Dismiss_Animation is triggered, THE Incognito_Button SHALL fade from alpha 1f to alpha 0f over 180 milliseconds using `FastOutSlowInEasing`.
3. WHEN the Dismiss_Animation completes, THE Gesture_Controller SHALL reset all gesture state so the Plus_Button is ready for the next long-press.
4. WHEN the Dismiss_Animation completes, THE Plus_Button SHALL have returned to scale 1f.

---

### Requirement 8: Gesture Repeatability

**User Story:** As a user, I want to be able to long-press the plus button again after a gesture completes so that I can open multiple incognito tabs in sequence.

#### Acceptance Criteria

1. WHEN the Dismiss_Animation completes, THE Gesture_Controller SHALL reset `isOverTarget` to `false`, the Incognito_Button scale to 0f, and the Incognito_Button alpha to 0f.
2. WHEN the Gesture_Controller state is fully reset, THE Plus_Button SHALL respond to a new long-press event and begin the gesture sequence again from Requirement 1.
3. THE Gesture_Controller SHALL support an unlimited number of sequential gesture repetitions without memory leaks or animation state corruption.

---

### Requirement 9: Accessibility

**User Story:** As a user relying on accessibility services, I want the incognito gesture to be discoverable and operable so that I am not excluded from the feature.

#### Acceptance Criteria

1. THE Plus_Button SHALL have a `contentDescription` of `"New tab — long press for incognito"` so screen readers announce the long-press affordance.
2. THE Incognito_Button SHALL have a `contentDescription` of `"Open incognito tab"` when visible.
3. WHERE Android accessibility services are active, THE Gesture_Controller SHALL also expose a standard `onClick` semantic action on the Incognito_Button that invokes `addIncognitoTab()` directly, bypassing the slide-to-confirm requirement.
