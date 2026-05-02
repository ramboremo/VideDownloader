# Implementation Plan: Incognito Tab Gesture

## Overview

Implement a long-press gesture on the orange plus button in `TabManagerOverlay` that pops out a violet incognito button above it. The user slides their finger onto the incognito button and releases to open a new incognito tab. The change touches exactly two files: `BrowserViewModel.kt` (one new method) and `BrowserScreen.kt` (updated composable signature, gesture controller, animation state, and incognito button composable).

**Critical constraints:**
- Do NOT modify `addNewTab()`, `toggleIncognito()`, or any existing URL/navigation logic in `BrowserViewModel`.
- `addIncognitoTab()` sets `_currentUrl.value = ""` (empty string, not null).
- All animation state is local to `TabManagerOverlay` — no new ViewModel state.
- The gesture uses a manual `pointerInput` coroutine, NOT `detectTapGestures`.
- Overlay dismiss uses the existing `onDone` callback.

---

## Tasks

- [x] 1. Add `addIncognitoTab()` to `BrowserViewModel`
  - In `BrowserViewModel.kt`, add a new `addIncognitoTab()` method immediately after `addNewTab()`.
  - The method must call `clearPageError()`, set `_isIncognito.value = true`, call `videoDetector.clearDetectedMedia()`, create a `BrowserTab(isActive = true, isIncognito = true)`, map existing tabs to `isActive = false`, append the new tab, update `_activeTabIndex`, set `_currentUrl.value = ""` (empty string), and set `_currentTitle.value = "Incognito"`.
  - Do NOT touch `addNewTab()`, `toggleIncognito()`, or any other existing method.
  - _Requirements: 5.2_

  - [ ]* 1.1 Write property test for `addIncognitoTab()` — Property 3
    - **Property 3: `addIncognitoTab()` produces correct tab state**
    - For any initial tabs list (0–10 tabs), calling `addIncognitoTab()` must append a tab with `isIncognito = true` and `isActive = true`, deactivate all prior tabs, and leave `isIncognito.value == true`, `currentUrl.value == ""`, `currentTitle.value == "Incognito"`.
    - Use Kotest `forAll` with `Arb.list(browserTabArb, 0..10)`. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 3: addIncognitoTab() produces correct tab state`
    - **Validates: Requirements 5.2**

- [x] 2. Add `onAddIncognitoTab` parameter to `TabManagerOverlay` and update the call site
  - In `BrowserScreen.kt`, add `onAddIncognitoTab: () -> Unit` as a new parameter to `TabManagerOverlay` (place it immediately after `onAddTab`).
  - At the call site in `BrowserScreen`, pass a lambda that calls `viewModel.addIncognitoTab()`, `refreshTabPreviewsForSwitcher()`, and updates `newestTabId` — mirroring the existing `onAddTab` lambda exactly.
  - Do NOT change any other parameters or their order.
  - _Requirements: 5.1, 5.4_

- [x] 3. Replace `clickable` with `pointerInput` gesture controller on the plus button
  - In `TabManagerOverlay`, remove the `clickable(onClick = onAddTab)` modifier from the plus button `Box`.
  - Add a `pointerInput(Unit)` block that implements the full gesture lifecycle using `awaitPointerEventScope`:
    1. `awaitFirstDown()` to detect press.
    2. Launch a 500 ms timer coroutine; if it fires, set `longPressTriggered = true`.
    3. Poll for movement — if finger moves > 10 dp before the timer fires, cancel the timer and do nothing (scroll/swipe pass-through).
    4. On timer fire: trigger haptic feedback (`HapticFeedbackType.LongPress`), then start the pop-out animation sequence (Task 6).
    5. Enter drag-tracking loop: on each `PointerEvent`, compute offset from incognito button center and update `isOverTarget` (radius ≤ 34 dp).
    6. On pointer up: if `isOverTarget`, call `onAddIncognitoTab()` then `onDone()`; otherwise cancel. Either way, trigger dismiss animation (Task 6) and reset all state.
    7. Short-press path: if pointer up before 500 ms with movement ≤ 10 dp, call `onAddTab()`.
  - Add `contentDescription = "New tab — long press for incognito"` to the plus button for accessibility (Requirement 9.1).
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 4.1, 4.2, 4.3, 5.1, 5.3, 6.1, 6.2, 9.1_

  - [ ]* 3.1 Write property test for long-press threshold gating — Property 6
    - **Property 6: Long-press threshold gates the gesture**
    - For any press duration D (0–1500 ms) with movement ≤ 10 dp: if D ≥ 500 ms the gesture starts and `onAddTab` is NOT called; if D < 500 ms `onAddTab` IS called and no gesture starts.
    - Use Kotest `forAll` with `Arb.long(0, 1500)`. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 6: Long-press threshold gates the gesture`
    - **Validates: Requirements 1.1, 1.2**

  - [ ]* 3.2 Write property test for movement cancellation — Property 7
    - **Property 7: Movement cancels long-press detection**
    - For any finger displacement > 10 dp before the 500 ms threshold, neither `onAddTab` nor the incognito gesture sequence is triggered.
    - Use Kotest `forAll` with `Arb.float(10.01f, 200f)`. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 7: Movement cancels long-press detection`
    - **Validates: Requirements 1.4**

  - [ ]* 3.3 Write unit test for haptic feedback
    - Verify `HapticFeedbackType.LongPress` is triggered exactly once at the 500 ms boundary.
    - Verify it is NOT triggered on a short press or a cancelled gesture.
    - _Requirements: 1.3_

- [x] 4. Add local animation state (`Animatable` values) to `TabManagerOverlay`
  - Inside `TabManagerOverlay`, declare the following remembered state (before the bottom bar `Surface`):
    ```kotlin
    val incognitoScale   = remember { Animatable(0f) }
    val incognitoAlpha   = remember { Animatable(0f) }
    val incognitoOffsetY = remember { Animatable(0f) }   // dp, negative = upward
    val plusScale        = remember { Animatable(1f) }
    var isOverTarget     by remember { mutableStateOf(false) }
    ```
  - No new state is added to `BrowserViewModel` or any other class.
  - _Requirements: 2.1, 2.2, 2.3, 3.5, 3.6_

- [x] 5. Implement the Incognito Button composable inside `TabManagerOverlay`
  - Wrap the existing plus button `Surface` in a `Box` so the incognito button can be overlaid above it.
  - Declare the incognito button **after** the plus button inside the `Box` so it paints on top (higher z-order).
  - The incognito button is a `Surface` with:
    - `Modifier.size(58.dp).align(Alignment.Center).offset(y = incognitoOffsetY.value.dp).graphicsLayer { scaleX = incognitoScale.value; scaleY = incognitoScale.value; alpha = incognitoAlpha.value }`
    - `shape = CircleShape`, `color = Color(0xFF6C5CE7)`, `shadowElevation = 14.dp`
  - Inside the surface, a `Box(contentAlignment = Alignment.Center)` with:
    - `semantics { onClick(label = "Open incognito tab") { onAddIncognitoTab(); true } }` for accessibility.
    - `Icon(Icons.Default.VisibilityOff, contentDescription = "Open incognito tab", tint = Color.White, modifier = Modifier.size(28.dp))`
  - _Requirements: 2.4, 3.1, 3.2, 3.3, 3.4, 9.2, 9.3_

  - [ ]* 5.1 Write unit test for accessibility attributes
    - Verify `contentDescription = "New tab — long press for incognito"` on the plus button.
    - Verify `contentDescription = "Open incognito tab"` on the incognito button.
    - Verify the semantic `onClick` action is present on the incognito button.
    - _Requirements: 9.1, 9.2, 9.3_

  - [ ]* 5.2 Write unit test for z-order
    - Verify the incognito button `Surface` is declared after the plus button `Surface` in the wrapping `Box`, confirming it paints on top.
    - _Requirements: 2.4_

- [x] 6. Wire up pop-out animation, drag tracking, hover scale, and dismiss animation
  - **Pop-out animation** (triggered when long-press threshold fires):
    - Animate `incognitoOffsetY` to `−72f` using `spring(dampingRatio = 0.55f, stiffness = 380f)`.
    - Animate `incognitoScale` to `1f` using the same spring.
    - Animate `incognitoAlpha` to `1f` using `tween(220, easing = FastOutSlowInEasing)`.
    - Animate `plusScale` to `0.88f` using `tween(180)`.
    - Apply `plusScale` to the plus button via `graphicsLayer { scaleX = plusScale.value; scaleY = plusScale.value }`.
  - **Hover scale** (updated continuously in the drag-tracking loop):
    - When `isOverTarget` becomes `true`: animate `incognitoScale` to `1.12f` using `spring(dampingRatio = 0.4f, stiffness = 500f)`.
    - When `isOverTarget` becomes `false`: animate `incognitoScale` to `1f` using `spring(dampingRatio = 0.6f, stiffness = 400f)`.
  - **Dismiss animation** (triggered on pointer up, success or cancel):
    - Animate `incognitoOffsetY` to `0f` and `incognitoScale` to `0f` using `spring(dampingRatio = 0.7f, stiffness = 500f)`.
    - Animate `incognitoAlpha` to `0f` using `tween(180, easing = FastOutSlowInEasing)`.
    - Animate `plusScale` back to `1f` using `tween(200)`.
    - After dismiss completes, reset `isOverTarget = false`.
  - _Requirements: 2.1, 2.2, 2.3, 3.5, 3.6, 6.3, 7.1, 7.2, 7.3, 7.4_

  - [ ]* 6.1 Write property test for hit-target detection — Property 1
    - **Property 1: Hit-target detection is a pure geometric predicate**
    - For any (dx, dy) offset in dp from the incognito button center, `isInHitTarget(dx, dy, radiusDp = 34f)` must equal `sqrt(dx² + dy²) ≤ 34f`.
    - Extract `isInHitTarget` as a pure top-level function for testability.
    - Use Kotest `forAll` with `Arb.float(-200f, 200f)` for both axes. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 1: Hit-target detection is a pure geometric predicate`
    - **Validates: Requirements 4.2, 4.3, 4.4**

  - [ ]* 6.2 Write property test for hover scale tracking — Property 2
    - **Property 2: Hover scale tracks isOverTarget**
    - For any boolean `isOverTarget`, `computeHoverScaleTarget(isOverTarget)` must return `1.12f` when true and `1.0f` when false.
    - Extract `computeHoverScaleTarget` as a pure top-level function for testability.
    - Use Kotest `forAll` with `Arb.boolean()`. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 2: Hover scale tracks isOverTarget`
    - **Validates: Requirements 3.5, 3.6**

  - [ ]* 6.3 Write property test for gesture outcome determining tab creation — Property 8
    - **Property 8: Gesture outcome determines tab creation**
    - For any boolean `releaseOverTarget` with an initial tab count of 3: if true, `addIncognitoTab()` is called exactly once and tab count increases by 1; if false, `addIncognitoTab()` is NOT called and tab count is unchanged.
    - Use Kotest `forAll` with `Arb.boolean()`. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 8: Gesture outcome determines tab creation`
    - **Validates: Requirements 5.1, 6.1**

  - [ ]* 6.4 Write unit test for cancel path
    - Verify that when the gesture is cancelled (pointer up with `isOverTarget == false`), `onDone` is NOT called and the tab count is unchanged.
    - _Requirements: 6.1, 6.2_

- [x] 7. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Validate post-dismiss state reset and gesture repeatability
  - After the dismiss animation completes, verify all state is reset: `isOverTarget == false`, `incognitoScale == 0f`, `incognitoAlpha == 0f`, `incognitoOffsetY == 0f`, `plusScale == 1f`.
  - Confirm the `pointerInput` loop returns to `awaitFirstDown()` so the next gesture can begin immediately.
  - _Requirements: 7.3, 7.4, 8.1, 8.2, 8.3_

  - [ ]* 8.1 Write property test for post-dismiss state reset — Property 4
    - **Property 4: Post-dismiss state is identical to initial state**
    - For any gesture sequence (success or cancel), after dismiss animation completes all five state values must be at their initial values.
    - Use Kotest `forAll` with `Arb.boolean()` (gestureSucceeded). Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 4: Post-dismiss state is identical to initial state`
    - **Validates: Requirements 7.3, 7.4, 8.1**

  - [ ]* 8.2 Write property test for gesture idempotence — Property 5
    - **Property 5: Gesture is idempotent across repetitions**
    - For any N (1–20) sequential gesture completions (success or cancel), the state after the Nth dismiss must be identical to the state after the 1st dismiss.
    - Use Kotest `forAll` with `Arb.int(1, 20)` and `Arb.boolean()`. Minimum 100 iterations.
    - Tag: `// Feature: incognito-tab-gesture, Property 5: Gesture is idempotent across repetitions`
    - **Validates: Requirements 8.2, 8.3**

- [x] 9. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP.
- Only two files change: `BrowserViewModel.kt` and `BrowserScreen.kt`.
- Pure helper functions (`isInHitTarget`, `computeHoverScaleTarget`) should be extracted as top-level functions in `BrowserScreen.kt` to keep them unit-testable without a Compose runtime.
- Property tests use Kotest `forAll` with a minimum of 100 iterations per property.
- Each property test task references the property number from the design document for traceability.
- Checkpoints at Tasks 7 and 9 ensure incremental validation before and after repeatability work.
