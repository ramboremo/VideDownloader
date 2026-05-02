# Tab Switcher More Menu Bugfix Design

## Overview

The MoreVert (three-dots) button in the `TabManagerOverlay` bottom toolbar currently dismisses the tab switcher and opens the browser-level top bar dropdown menu — the wrong menu in the wrong context. The fix introduces a tab-switcher-scoped `DropdownMenu` anchored to that button, keeping the tab switcher open while offering four contextual actions: History, Dark Mode toggle, New Private Tab, and Settings.

The approach is minimal and surgical:
- `showTabSwitcherMenu` boolean state lives **locally inside `TabManagerOverlay`** — no state hoisting needed, since no parent needs to observe it.
- The `onShowMore` callback is **removed** from `TabManagerOverlay`'s signature; the menu is fully self-contained.
- Three new callbacks are added to `TabManagerOverlay` to handle actions that require parent coordination: `onShowHistory`, `onNavigateToSettings`, and `onToggleDarkMode`.
- `onAddIncognitoTab` already exists and is reused for "New Private Tab".
- Dark mode is toggled via `AppPreferences.setThemeMode()`, which is already wired in `MainActivity`. A new `toggleDarkMode()` function is added to `BrowserViewModel` to expose this.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — the user taps the MoreVert button inside `TabManagerOverlay`'s bottom toolbar.
- **Property (P)**: The desired behavior — a tab-switcher-scoped `DropdownMenu` appears anchored to the button; the tab switcher remains visible; the browser-level menu does NOT open.
- **Preservation**: All other `TabManagerOverlay` interactions (Done, tab card tap, add tab, close all, history shortcut) must remain unchanged. The browser top bar MoreVert menu must continue to work as before.
- **`TabManagerOverlay`**: The full-screen composable in `BrowserScreen.kt` (around line 2442) that renders the tab grid/list and its toolbar.
- **`onShowMore`**: The existing callback on `TabManagerOverlay` that currently (incorrectly) calls `viewModel.dismissTabManager()` + `viewModel.toggleMenu()`.
- **`themeMode`**: A `Flow<String>` in `AppPreferences` with values `"Light"`, `"Dark"`, or `"System"`. Toggling dark mode cycles between `"Dark"` and `"Light"` (or `"System"` → `"Dark"`).
- **`BrowserViewModel.toggleDarkMode()`**: New function to be added; reads current `themeMode` and writes the toggled value via `preferences.setThemeMode()`.

## Bug Details

### Bug Condition

The bug manifests when the user taps the MoreVert button in the `TabManagerOverlay` bottom toolbar. The `onShowMore` lambda passed from `BrowserScreen` calls `viewModel.dismissTabManager()` followed by `viewModel.toggleMenu()`, which dismisses the tab switcher and opens the unrelated browser top bar dropdown.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type UserAction
  OUTPUT: boolean

  RETURN input.location = TAB_SWITCHER_TOOLBAR
    AND input.action = TAP_MORE_VERT_BUTTON
END FUNCTION
```

### Examples

- **Bug example**: User opens tab switcher → taps MoreVert → tab switcher disappears → browser top bar dropdown opens. Expected: tab switcher stays open, a scoped dropdown appears anchored to the button.
- **Bug example**: User taps MoreVert in tab switcher → taps "History" from the (wrong) browser menu → history opens but tab switcher was already dismissed. Expected: tab switcher stays open until the user explicitly selects an action that requires dismissal.
- **Edge case (correct)**: User taps MoreVert in the browser top bar (outside tab switcher) → browser-level dropdown opens as before. This must NOT be affected.
- **Edge case (correct)**: User opens tab-switcher menu → presses back → only the dropdown dismisses; tab switcher remains open.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Tapping MoreVert in the browser top bar (outside the tab switcher) MUST continue to open the browser-level dropdown menu via `viewModel.toggleMenu()`.
- Tapping "Done" in the tab switcher toolbar MUST continue to dismiss the tab switcher without showing any menu.
- Tapping a tab card MUST continue to switch to that tab and dismiss the tab switcher.
- Tapping the add-tab (+) button MUST continue to open a new tab without affecting the more menu.
- Tapping the close-all button MUST continue to show the close-all confirmation dialog without affecting the more menu.
- Pressing back while the tab-switcher dropdown is open MUST dismiss only the dropdown, leaving the tab switcher open.

**Scope:**
All inputs that do NOT involve tapping the MoreVert button inside `TabManagerOverlay`'s toolbar should be completely unaffected by this fix. This includes:
- All browser top bar interactions
- All tab card interactions
- All other toolbar buttons in `TabManagerOverlay` (Done, add tab, close all, history)

## Hypothesized Root Cause

The root cause is a single incorrect lambda passed to `TabManagerOverlay` at the `onShowMore` call site in `BrowserScreen.kt` (around line 1135–1139):

```kotlin
onShowMore = {
    viewModel.dismissTabManager()   // ← wrong: dismisses the tab switcher
    viewModel.toggleMenu()          // ← wrong: opens the browser top bar menu
}
```

There is no tab-switcher-scoped menu implemented anywhere. The fix requires:
1. Removing `onShowMore` from `TabManagerOverlay`'s parameter list.
2. Adding local `showTabSwitcherMenu` state inside `TabManagerOverlay`.
3. Wrapping the MoreVert `IconButton` in a `Box` to anchor the `DropdownMenu`.
4. Adding three new callbacks to `TabManagerOverlay` for actions requiring parent coordination.
5. Adding `toggleDarkMode()` to `BrowserViewModel`.
6. Updating the `TabManagerOverlay` call site in `BrowserScreen` to pass the new callbacks and remove `onShowMore`.

## Correctness Properties

Property 1: Bug Condition — Tab Switcher MoreVert Shows Scoped Menu

_For any_ user action where `isBugCondition` returns true (the user taps the MoreVert button inside `TabManagerOverlay`'s toolbar), the fixed code SHALL display a tab-switcher-scoped `DropdownMenu` anchored to that button, keep `showTabManager` as `true`, and NOT call `viewModel.toggleMenu()` or `viewModel.dismissTabManager()`.

**Validates: Requirements 2.1, 2.2, 2.7**

Property 2: Preservation — Non-MoreVert Interactions Unchanged

_For any_ user action where `isBugCondition` returns false (any interaction other than tapping the MoreVert button inside `TabManagerOverlay`'s toolbar), the fixed code SHALL produce exactly the same behavior as the original code, preserving all existing tab switcher and browser top bar functionality.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

## Fix Implementation

### Changes Required

**File**: `app/src/main/java/com/cognitivechaos/xdownload/ui/browser/BrowserViewModel.kt`

**Function**: New `toggleDarkMode()` function

**Specific Changes**:
1. **Add `themeMode` StateFlow**: Expose `preferences.themeMode` as a `StateFlow<String>` in `BrowserViewModel` (similar to how `blockAds` and `searchEngine` are exposed), so `TabManagerOverlay` can read the current value for the toggle label/icon.
2. **Add `toggleDarkMode()` function**: Read current `themeMode.value`, then call `preferences.setThemeMode()` in a coroutine — toggling `"Dark"` → `"Light"`, `"Light"` → `"Dark"`, and `"System"` → `"Dark"`.

```kotlin
val themeMode = preferences.themeMode
    .stateIn(viewModelScope, SharingStarted.Lazily, "System")

fun toggleDarkMode() {
    viewModelScope.launch {
        val next = when (themeMode.value) {
            "Dark" -> "Light"
            else   -> "Dark"   // "Light" and "System" both go to "Dark"
        }
        preferences.setThemeMode(next)
    }
}
```

---

**File**: `app/src/main/java/com/cognitivechaos/xdownload/ui/browser/BrowserScreen.kt`

**Function**: `TabManagerOverlay` composable (around line 2442)

**Specific Changes**:

1. **Remove `onShowMore` parameter**: Delete `onShowMore: () -> Unit` from the function signature.

2. **Add three new callback parameters**:
   ```kotlin
   onToggleDarkMode: () -> Unit,
   onNavigateToSettings: () -> Unit,
   isDarkMode: Boolean,
   ```
   (`onShowHistory` already exists in the signature — reuse it.)

3. **Add local menu state**: Inside the composable body, add:
   ```kotlin
   var showMoreMenu by remember { mutableStateOf(false) }
   ```

4. **Wrap MoreVert `IconButton` in a `Box`**: Replace the bare `IconButton(onClick = onShowMore)` with:
   ```kotlin
   Box {
       IconButton(onClick = { showMoreMenu = true }) {
           Icon(Icons.Default.MoreVert, contentDescription = "More", ...)
       }
       DropdownMenu(
           expanded = showMoreMenu,
           onDismissRequest = { showMoreMenu = false }
       ) {
           DropdownMenuItem(
               text = { Text("History") },
               leadingIcon = { Icon(Icons.Default.History, null) },
               onClick = {
                   showMoreMenu = false
                   onShowHistory()
               }
           )
           DropdownMenuItem(
               text = { Text(if (isDarkMode) "Light Mode" else "Dark Mode") },
               leadingIcon = {
                   Icon(
                       if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                       null
                   )
               },
               onClick = {
                   showMoreMenu = false
                   onToggleDarkMode()
               }
           )
           DropdownMenuItem(
               text = { Text("New Private Tab") },
               leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
               onClick = {
                   showMoreMenu = false
                   onAddIncognitoTab()
               }
           )
           DropdownMenuItem(
               text = { Text("Settings") },
               leadingIcon = { Icon(Icons.Default.Settings, null) },
               onClick = {
                   showMoreMenu = false
                   onNavigateToSettings()
               }
           )
       }
   }
   ```

5. **Update the `TabManagerOverlay` call site** in `BrowserScreen` (around line 1120):
   - Remove `onShowMore = { viewModel.dismissTabManager(); viewModel.toggleMenu() }`
   - Add:
     ```kotlin
     isDarkMode = themeMode == "Dark",
     onToggleDarkMode = { viewModel.toggleDarkMode() },
     onNavigateToSettings = {
         viewModel.dismissTabManager()
         onNavigateToSettings()
     },
     ```
   - `onShowHistory` already dismisses the tab manager and shows history — no change needed.
   - Collect `themeMode` in `BrowserScreen`:
     ```kotlin
     val themeMode by viewModel.themeMode.collectAsState()
     ```

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm the root cause: `onShowMore` calls `dismissTabManager()` + `toggleMenu()` instead of showing a scoped menu.

**Test Plan**: Write unit/instrumentation tests that simulate a tap on the MoreVert button inside `TabManagerOverlay` and assert that `showTabManager` remains `true` and `showMenu` remains `false`. Run these on the UNFIXED code to observe failures.

**Test Cases**:
1. **MoreVert tap dismisses tab switcher** (will fail on unfixed code): Simulate tap on MoreVert in `TabManagerOverlay` toolbar → assert `viewModel.showTabManager.value == true`. On unfixed code, this will be `false`.
2. **MoreVert tap opens browser menu** (will fail on unfixed code): Simulate tap on MoreVert in `TabManagerOverlay` toolbar → assert `viewModel.showMenu.value == false`. On unfixed code, this will be `true`.
3. **No scoped menu shown** (will fail on unfixed code): After tapping MoreVert, assert that a `DropdownMenu` with "History", "Dark Mode", "New Private Tab", "Settings" items is visible. On unfixed code, no such menu exists.

**Expected Counterexamples**:
- `showTabManager` becomes `false` after MoreVert tap (tab switcher dismissed).
- `showMenu` becomes `true` after MoreVert tap (wrong menu opened).
- Possible causes: `onShowMore` lambda directly calls `dismissTabManager()` and `toggleMenu()`.

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := onMoreVertTap_fixed(input)
  ASSERT showTabManager = true
    AND showTabSwitcherMenu = true
    AND showMenu = false
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed code produces the same behavior as the original.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT behavior_original(input) = behavior_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because it generates many combinations of tab states, toolbar button taps, and menu states automatically, catching regressions that manual tests might miss.

**Test Plan**: Observe behavior on UNFIXED code for all non-MoreVert interactions, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Done button preservation**: Verify tapping "Done" still calls `dismissTabManager()` and does not show any menu.
2. **Browser top bar MoreVert preservation**: Verify tapping MoreVert in the browser top bar (outside tab switcher) still calls `toggleMenu()` and does NOT affect `showTabManager`.
3. **Tab card tap preservation**: Verify tapping a tab card still calls `switchToTab()` and `dismissTabManager()`.
4. **Add tab preservation**: Verify tapping the add-tab button still calls `addNewTab()` without affecting menu state.
5. **Close all preservation**: Verify tapping close-all still shows the confirmation dialog.
6. **Back press with menu open**: Verify pressing back while `showTabSwitcherMenu = true` sets it to `false` and leaves `showTabManager = true`.

### Unit Tests

- Test `BrowserViewModel.toggleDarkMode()`: verify `"System"` → `"Dark"`, `"Dark"` → `"Light"`, `"Light"` → `"Dark"`.
- Test that `TabManagerOverlay` with `showMoreMenu = true` renders all four menu items.
- Test that each menu item's `onClick` fires the correct callback and sets `showMoreMenu = false`.
- Test edge case: tapping MoreVert when tab switcher has 0 tabs still shows the menu without crashing.

### Property-Based Tests

- Generate random `themeMode` values and verify `toggleDarkMode()` always produces a valid `"Dark"` or `"Light"` result.
- Generate random sequences of toolbar button taps and verify `showTabManager` is only set to `false` by the correct actions (Done, tab card tap, History, Settings, New Private Tab) and never by MoreVert tap alone.
- Generate random tab configurations and verify that opening/closing the tab-switcher scoped menu does not mutate tab state.

### Integration Tests

- Full flow: open tab switcher → tap MoreVert → verify scoped menu appears → tap "History" → verify tab switcher dismisses and history overlay opens.
- Full flow: open tab switcher → tap MoreVert → tap "Dark Mode" → verify theme toggles without dismissing tab switcher.
- Full flow: open tab switcher → tap MoreVert → tap "New Private Tab" → verify incognito tab is added and tab switcher dismisses.
- Full flow: open tab switcher → tap MoreVert → tap "Settings" → verify tab switcher dismisses and settings screen opens.
- Full flow: open tab switcher → tap MoreVert → press back → verify only the dropdown closes, tab switcher remains open.
- Regression: open browser top bar MoreVert (outside tab switcher) → verify browser-level menu opens as before.
