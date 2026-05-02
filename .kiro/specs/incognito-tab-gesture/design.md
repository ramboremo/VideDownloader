# Design Document: Incognito Tab Gesture

## Overview

This feature adds a long-press gesture to the orange plus button in the `TabManagerOverlay` bottom bar. Holding the button for 500 ms causes a violet incognito button to spring upward from it. The user slides their finger onto the incognito button and releases to open a new incognito tab; releasing elsewhere cancels the gesture. The incognito button then animates back and disappears, leaving the UI in its original state ready for the next interaction.

The change is intentionally minimal and additive:

- **`BrowserViewModel`** gains one new method, `addIncognitoTab()`, that mirrors `addNewTab()` exactly except it sets `isIncognito = true`, `_isIncognito.value = true`, and `_currentTitle.value = "Incognito"`. No existing methods are touched.
- **`TabManagerOverlay`** in `BrowserScreen.kt` gains a new `onAddIncognitoTab` parameter and replaces the plain `clickable` modifier on the plus button with a `pointerInput` block that handles both the normal tap path and the long-press gesture path. All animation state is local to the composable.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  BrowserScreen (call site)                                  │
│  ─────────────────────────────────────────────────────────  │
│  onAddTab        = { viewModel.addNewTab(); … }             │
│  onAddIncognitoTab = { viewModel.addIncognitoTab(); … }     │  ← NEW
│  onDone          = { viewModel.dismissTabManager() }        │
└────────────────────────┬────────────────────────────────────┘
                         │ passes lambdas
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  TabManagerOverlay (Composable)                             │
│  ─────────────────────────────────────────────────────────  │
│  Local animation state (Animatable):                        │
│    incognitoScale, incognitoAlpha, incognitoOffsetY         │
│    plusScale                                                │
│  Local gesture state (remember):                            │
│    isOverTarget: Boolean                                    │
│                                                             │
│  Plus_Button ──► pointerInput(GestureController)            │
│                    ├─ short press  → onAddTab()             │
│                    ├─ long press   → pop-out animation      │
│                    │                 + drag tracking        │
│                    └─ release      → success / cancel       │
│                                                             │
│  Incognito_Button (overlay, z-order above plus button)      │
│    ├─ visible only when incognitoAlpha > 0                  │
│    └─ semantics onClick → onAddIncognitoTab() (a11y)        │
└────────────────────────┬────────────────────────────────────┘
                         │ calls
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  BrowserViewModel                                           │
│  ─────────────────────────────────────────────────────────  │
│  addNewTab()         (unchanged)                            │
│  addIncognitoTab()   (NEW — additive only)                  │
│  dismissTabManager() (unchanged)                            │
└─────────────────────────────────────────────────────────────┘
```

### Key design decisions

| Decision | Rationale |
|---|---|
| `pointerInput` with manual coroutine long-press detection | `detectTapGestures` does not support drag tracking after a long-press fires. A manual coroutine with `awaitPointerEventScope` gives full control over the press → hold → drag → release lifecycle. |
| All animation state local to `TabManagerOverlay` | No new ViewModel state is needed. The gesture is purely presentational. |
| `addIncognitoTab()` mirrors `addNewTab()` exactly | Keeps the two code paths symmetric and avoids any risk of accidentally touching URL/navigation state. `_currentUrl.value = ""` (empty string, not null) matches `addNewTab()` exactly. |
| `onDone` callback for overlay dismiss | Reuses the existing dismiss path (`viewModel.dismissTabManager()`) — no new state or callback needed. |
| `Icons.Default.VisibilityOff` for incognito icon | Available in the existing `material-icons-extended` dependency; communicates "hidden/private" clearly. |

---

## Components and Interfaces

### 1. `BrowserViewModel.addIncognitoTab()` (new method)

```kotlin
fun addIncognitoTab() {
    clearPageError()
    _isIncognito.value = true
    videoDetector.clearDetectedMedia()
    val newTab = BrowserTab(isActive = true, isIncognito = true)
    val updatedTabs = _tabs.value.map { it.copy(isActive = false) } + newTab
    _tabs.value = updatedTabs
    _activeTabIndex.value = updatedTabs.size - 1
    _currentUrl.value = ""
    _currentTitle.value = "Incognito"
}
```

Constraints:
- Does **not** touch `toggleIncognito()` or `addNewTab()`.
- `_currentUrl.value` is set to `""` (empty string), identical to `addNewTab()`.
- No null assignments anywhere.

### 2. `TabManagerOverlay` — updated signature

```kotlin
fun TabManagerOverlay(
    tabs: List<BrowserTab>,
    activeTabIndex: Int,
    isListMode: Boolean,
    previewBitmaps: Map<String, Bitmap>,
    newestTabId: String?,
    onAddTab: () -> Unit,
    onAddIncognitoTab: () -> Unit,   // NEW
    onDone: () -> Unit,
    onToggleViewMode: () -> Unit,
    onShowHistory: () -> Unit,
    onShowMore: () -> Unit,
    onTabClick: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onCloseAll: () -> Unit
)
```

### 3. Call site update in `BrowserScreen`

```kotlin
TabManagerOverlay(
    // … existing params …
    onAddTab = {
        viewModel.addNewTab()
        refreshTabPreviewsForSwitcher()
        newestTabId = viewModel.tabs.value.lastOrNull()?.id
    },
    onAddIncognitoTab = {          // NEW
        viewModel.addIncognitoTab()
        refreshTabPreviewsForSwitcher()
        newestTabId = viewModel.tabs.value.lastOrNull()?.id
    },
    onDone = { viewModel.dismissTabManager() },
    // … rest unchanged …
)
```

### 4. Gesture Controller (`pointerInput` block)

The existing `clickable(onClick = onAddTab)` modifier on the plus button is replaced with a `pointerInput` block. The block runs a coroutine that:

1. **Awaits the first pointer down** via `awaitPointerEventScope { awaitFirstDown() }`.
2. **Starts a 500 ms timer** (`launch { delay(500); longPressTriggered = true }`).
3. **Polls for movement** — if the finger moves > 10 dp from the initial point before the timer fires, the timer job is cancelled and the gesture is treated as a scroll/swipe (no action).
4. **On timer fire** — triggers haptic feedback, starts pop-out animation, enters drag-tracking loop.
5. **Drag-tracking loop** — on each `PointerEvent`, computes the finger's offset relative to the incognito button center and sets `isOverTarget` based on whether the distance is ≤ 34 dp. Updates incognito button hover scale accordingly.
6. **On pointer up** — if `isOverTarget`, calls `onAddIncognitoTab()` then `onDone()`; otherwise cancels. Either way, triggers dismiss animation and resets all state.
7. **Short press path** — if the pointer is released before the 500 ms timer fires and movement was within 10 dp, calls `onAddTab()`.

### 5. Local animation state

```kotlin
// Inside TabManagerOverlay
val incognitoScale   = remember { Animatable(0f) }
val incognitoAlpha   = remember { Animatable(0f) }
val incognitoOffsetY = remember { Animatable(0f) }   // in dp, negative = upward
val plusScale        = remember { Animatable(1f) }
var isOverTarget     by remember { mutableStateOf(false) }
```

All animations run in `LaunchedEffect` or directly inside the `pointerInput` coroutine scope.

### 6. Incognito Button layout

The incognito button is placed in a `Box` that wraps the entire plus-button area, using `offset` driven by `incognitoOffsetY` and `graphicsLayer` for scale/alpha. It is declared **after** the plus button in the `Box` so it paints on top (higher z-order).

```
Box {
    // Plus button (existing, modifier updated)
    Surface(modifier = Modifier.size(58.dp).graphicsLayer { scaleX = plusScale.value; scaleY = plusScale.value }) { … }

    // Incognito button (new, overlaid above)
    Surface(
        modifier = Modifier
            .size(58.dp)
            .align(Alignment.Center)
            .offset(y = incognitoOffsetY.value.dp)
            .graphicsLayer {
                scaleX = incognitoScale.value
                scaleY = incognitoScale.value
                alpha  = incognitoAlpha.value
            },
        shape = CircleShape,
        color = Color(0xFF6C5CE7),
        shadowElevation = 14.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { onClick(label = "Open incognito tab") { onAddIncognitoTab(); true } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.VisibilityOff, contentDescription = "Open incognito tab",
                 tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
```

---

## Data Models

No new data models are introduced. The existing `BrowserTab` data class already has `isIncognito: Boolean = false`, which is all that is needed.

```kotlin
data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "New Tab",
    val isActive: Boolean = false,
    val isIncognito: Boolean = false   // existing field, used as-is
)
```

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property Reflection

Before listing properties, redundancies are eliminated:

- Requirements 4.2 and 4.3 (isOverTarget = true/false based on position) are both captured by the single hit-test geometry property (4.4). They are consolidated into **Property 1**.
- Requirements 3.5 and 3.6 (hover scale 1.12f / 1f) are direct consequences of the isOverTarget flag and are consolidated into **Property 2**.
- Requirements 7.3, 7.4, 8.1 all describe the same post-dismiss state reset. They are consolidated into **Property 4**.
- Requirements 8.2 and 8.3 both describe repeatability. They are consolidated into **Property 5**.
- Requirements 1.1 and 1.2 describe the two sides of the 500 ms threshold. They are consolidated into **Property 6**.

---

### Property 1: Hit-target detection is a pure geometric predicate

*For any* finger position expressed as an (dx, dy) offset in dp from the incognito button center, `isOverTarget` SHALL be `true` if and only if `sqrt(dx² + dy²) ≤ 34`.

**Validates: Requirements 4.2, 4.3, 4.4**

---

### Property 2: Hover scale tracks isOverTarget

*For any* finger position during an active gesture, the incognito button's scale animation target SHALL be `1.12f` when `isOverTarget` is `true` and `1.0f` when `isOverTarget` is `false`.

**Validates: Requirements 3.5, 3.6**

---

### Property 3: addIncognitoTab() produces correct tab state

*For any* initial tabs list (of any length, with any active tab), calling `addIncognitoTab()` SHALL result in:
- A new `BrowserTab` appended to the list with `isIncognito = true` and `isActive = true`.
- All previously active tabs having `isActive = false`.
- `_isIncognito.value == true`.
- `_currentUrl.value == ""` (empty string, not null).
- `_currentTitle.value == "Incognito"`.

**Validates: Requirements 5.2**

---

### Property 4: Post-dismiss state is identical to initial state

*For any* gesture sequence (whether it ends in success or cancellation), after the dismiss animation completes, the gesture controller state SHALL satisfy: `isOverTarget == false`, `incognitoScale == 0f`, `incognitoAlpha == 0f`, `incognitoOffsetY == 0f`, `plusScale == 1f`.

**Validates: Requirements 7.3, 7.4, 8.1**

---

### Property 5: Gesture is idempotent across repetitions

*For any* number N ≥ 1 of sequential gesture completions (success or cancel), the gesture controller state after the Nth dismiss SHALL be identical to the state after the 1st dismiss — no animation state corruption or accumulated offset.

**Validates: Requirements 8.2, 8.3**

---

### Property 6: Long-press threshold gates the gesture

*For any* press duration D:
- If D ≥ 500 ms and finger movement ≤ 10 dp, the incognito gesture sequence SHALL begin (pop-out animation fires, `onAddTab` is NOT called).
- If D < 500 ms and finger movement ≤ 10 dp, `onAddTab` SHALL be called and no incognito sequence begins.

**Validates: Requirements 1.1, 1.2**

---

### Property 7: Movement cancels long-press detection

*For any* finger displacement > 10 dp from the initial touch point that occurs before the 500 ms threshold is reached, the long-press timer SHALL be cancelled and neither `onAddTab` nor the incognito gesture sequence SHALL be triggered.

**Validates: Requirements 1.4**

---

### Property 8: Gesture outcome determines tab creation

*For any* completed gesture (after long-press threshold is crossed):
- If the finger is released while `isOverTarget == true`, `addIncognitoTab()` SHALL be called exactly once and the tab count SHALL increase by exactly 1.
- If the finger is released while `isOverTarget == false`, `addIncognitoTab()` SHALL NOT be called and the tab count SHALL remain unchanged.

**Validates: Requirements 5.1, 6.1**

---

## Error Handling

| Scenario | Handling |
|---|---|
| `pointerInput` coroutine cancelled mid-gesture (e.g., composable leaves composition) | Kotlin structured concurrency cancels the coroutine cleanly; `Animatable` state is abandoned with the composable. No cleanup needed. |
| `addIncognitoTab()` called while tab list is empty | The `map { it.copy(isActive = false) }` on an empty list produces an empty list; the new tab is appended correctly. No crash. |
| Rapid repeated long-presses before dismiss animation completes | The `pointerInput` block awaits `awaitFirstDown()` at the top of its loop, so a new gesture cannot start until the previous pointer is fully up. The dismiss animation runs to completion before the next gesture can begin. |
| Accessibility `onClick` fires while gesture is in progress | The semantic `onClick` on the incognito button calls `onAddIncognitoTab()` directly. If a gesture is simultaneously in progress, the dismiss animation will fire twice (once from the gesture path, once from the semantic path). This is benign — the second dismiss is a no-op because the state is already reset. To be safe, a guard flag (`gestureActive`) can be checked before the semantic action fires. |

---

## Testing Strategy

### Unit tests (example-based)

These cover specific configurations and edge cases that are not suited to property-based testing:

- **Animation parameters**: Verify pop-out spring uses `dampingRatio = 0.55f, stiffness = 380f`; dismiss spring uses `dampingRatio = 0.7f, stiffness = 500f`.
- **Haptic feedback**: Verify `HapticFeedbackType.LongPress` is triggered exactly once at the 500 ms boundary.
- **Accessibility attributes**: Verify `contentDescription = "New tab — long press for incognito"` on the plus button and `"Open incognito tab"` on the incognito button.
- **Overlay dismiss callback**: Verify `onDone` is called after a successful gesture completion.
- **Cancel path**: Verify `onDone` is NOT called and tab count is unchanged after a cancelled gesture.
- **z-order**: Verify the incognito button is declared after the plus button in the `Box` so it paints on top.

### Property-based tests

Property-based testing is appropriate here because the gesture controller contains pure logic (hit-test geometry, threshold comparisons, state transitions) that varies meaningfully with input and where 100+ iterations will surface edge cases.

**Library**: [Kotest](https://kotest.io/) with its `forAll` property testing API (already a common choice in Kotlin/Android projects; no new dependency category needed).

**Configuration**: Minimum 100 iterations per property test.

**Tag format**: `// Feature: incognito-tab-gesture, Property N: <property text>`

#### Property 1 — Hit-target detection

```kotlin
// Feature: incognito-tab-gesture, Property 1: Hit-target detection is a pure geometric predicate
forAll(Arb.float(-200f, 200f), Arb.float(-200f, 200f)) { dx, dy ->
    val expected = sqrt(dx * dx + dy * dy) <= 34f
    isInHitTarget(dx, dy, radiusDp = 34f) == expected
}
```

#### Property 2 — Hover scale tracks isOverTarget

```kotlin
// Feature: incognito-tab-gesture, Property 2: Hover scale tracks isOverTarget
forAll(Arb.boolean()) { overTarget ->
    val targetScale = computeHoverScaleTarget(isOverTarget = overTarget)
    if (overTarget) targetScale == 1.12f else targetScale == 1.0f
}
```

#### Property 3 — addIncognitoTab() produces correct tab state

```kotlin
// Feature: incognito-tab-gesture, Property 3: addIncognitoTab() produces correct tab state
forAll(Arb.list(browserTabArb, 0..10)) { initialTabs ->
    val vm = buildTestViewModel(initialTabs)
    vm.addIncognitoTab()
    val tabs = vm.tabs.value
    tabs.last().isIncognito == true &&
    tabs.last().isActive == true &&
    tabs.dropLast(1).none { it.isActive } &&
    vm.isIncognito.value == true &&
    vm.currentUrl.value == "" &&
    vm.currentTitle.value == "Incognito"
}
```

#### Property 4 — Post-dismiss state reset

```kotlin
// Feature: incognito-tab-gesture, Property 4: Post-dismiss state is identical to initial state
forAll(Arb.boolean()) { gestureSucceeded ->
    val state = runGestureAndDismiss(gestureSucceeded)
    state.isOverTarget == false &&
    state.incognitoScale == 0f &&
    state.incognitoAlpha == 0f &&
    state.incognitoOffsetY == 0f &&
    state.plusScale == 1f
}
```

#### Property 5 — Gesture idempotence across repetitions

```kotlin
// Feature: incognito-tab-gesture, Property 5: Gesture is idempotent across repetitions
forAll(Arb.int(1, 20), Arb.boolean()) { n, succeed ->
    val state = repeatGesture(n, succeed)
    state.incognitoScale == 0f &&
    state.incognitoAlpha == 0f &&
    state.plusScale == 1f
}
```

#### Property 6 — Long-press threshold gates the gesture

```kotlin
// Feature: incognito-tab-gesture, Property 6: Long-press threshold gates the gesture
forAll(Arb.long(0, 1500)) { durationMs ->
    val result = simulatePress(durationMs, movementDp = 0f)
    if (durationMs >= 500L) result.gestureStarted && !result.addTabCalled
    else !result.gestureStarted && result.addTabCalled
}
```

#### Property 7 — Movement cancels long-press detection

```kotlin
// Feature: incognito-tab-gesture, Property 7: Movement cancels long-press detection
forAll(Arb.float(10.01f, 200f)) { movementDp ->
    val result = simulatePress(durationMs = 200, movementDp = movementDp)
    !result.gestureStarted && !result.addTabCalled
}
```

#### Property 8 — Gesture outcome determines tab creation

```kotlin
// Feature: incognito-tab-gesture, Property 8: Gesture outcome determines tab creation
forAll(Arb.boolean()) { releaseOverTarget ->
    val initialCount = 3
    val result = simulateFullGesture(initialTabCount = initialCount, releaseOverTarget = releaseOverTarget)
    if (releaseOverTarget) result.tabCount == initialCount + 1 && result.addIncognitoTabCalled
    else result.tabCount == initialCount && !result.addIncognitoTabCalled
}
```

### Integration / UI tests

- Compose UI test verifying the full gesture flow end-to-end using `performTouchInput { down(); advanceEventTime(600); moveTo(incognitoButtonCenter); up() }`.
- Verify the tab manager overlay is dismissed after a successful gesture.
- Verify no new tab is created after a cancelled gesture.
