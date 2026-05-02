# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Tab Switcher MoreVert Dismisses Tab Switcher and Opens Wrong Menu
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to the concrete failing case — a single tap on the MoreVert button inside `TabManagerOverlay`'s bottom toolbar
  - Create a unit test in `app/src/test/.../BrowserViewModelTest.kt` (or equivalent)
  - Simulate the `onShowMore` lambda as currently wired in `BrowserScreen`: call `viewModel.dismissTabManager()` then `viewModel.toggleMenu()`
  - Assert `viewModel.showTabManager.value == true` (tab switcher must remain visible) — this will be `false` on unfixed code
  - Assert `viewModel.showMenu.value == false` (browser top bar menu must NOT open) — this will be `true` on unfixed code
  - Document counterexamples found: e.g., `showTabManager = false` and `showMenu = true` after MoreVert tap
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct — it proves the bug exists)
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Non-MoreVert Tab Switcher Interactions Unchanged
  - **IMPORTANT**: Follow observation-first methodology — run UNFIXED code with non-buggy inputs and record actual outputs
  - Observe: tapping "Done" calls `viewModel.dismissTabManager()` and `showTabManager` becomes `false`; `showMenu` stays `false`
  - Observe: tapping a tab card calls `viewModel.switchToTab(index)` then `viewModel.dismissTabManager()`; `showTabManager` becomes `false`
  - Observe: tapping the add-tab button calls `viewModel.addNewTab()`; `showTabManager` stays `true`; `showMenu` stays `false`
  - Observe: tapping close-all shows the confirmation dialog; `showTabManager` stays `true`; `showMenu` stays `false`
  - Observe: tapping MoreVert in the browser top bar (outside tab switcher) calls `viewModel.toggleMenu()`; `showMenu` becomes `true`; `showTabManager` stays `false`
  - Write property-based tests for each observed behavior pattern (for all valid tab indices, for all tab counts ≥ 1, etc.)
  - Verify all preservation tests PASS on UNFIXED code before proceeding
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 3. Fix: Tab Switcher MoreVert Shows Scoped Dropdown Menu

  - [x] 3.1 Add `themeMode` StateFlow and `toggleDarkMode()` to `BrowserViewModel`
    - In `BrowserViewModel.kt`, expose `preferences.themeMode` as a `StateFlow<String>`:
      ```kotlin
      val themeMode = preferences.themeMode
          .stateIn(viewModelScope, SharingStarted.Lazily, "System")
      ```
    - Add `toggleDarkMode()` function that reads `themeMode.value` and calls `preferences.setThemeMode()` in a coroutine:
      - `"Dark"` → `"Light"`
      - `"Light"` or `"System"` → `"Dark"`
    - _Bug_Condition: isBugCondition(input) where input.location = TAB_SWITCHER_TOOLBAR AND input.action = TAP_MORE_VERT_BUTTON_
    - _Expected_Behavior: showTabManager = true AND showTabSwitcherMenu = true AND showMenu = false_
    - _Preservation: All non-MoreVert interactions must remain unchanged_
    - _Requirements: 2.4_

  - [x] 3.2 Update `TabManagerOverlay` signature: remove `onShowMore`, add new callbacks
    - In `BrowserScreen.kt`, remove `onShowMore: () -> Unit` from `TabManagerOverlay`'s parameter list
    - Add three new callback parameters:
      ```kotlin
      isDarkMode: Boolean,
      onToggleDarkMode: () -> Unit,
      onNavigateToSettings: () -> Unit,
      ```
    - (`onShowHistory` and `onAddIncognitoTab` already exist — reuse them)
    - _Requirements: 2.1, 2.2, 2.3, 2.5, 2.6_

  - [x] 3.3 Add local `showMoreMenu` state and anchor `DropdownMenu` to the MoreVert button
    - Inside `TabManagerOverlay`'s composable body, add:
      ```kotlin
      var showMoreMenu by remember { mutableStateOf(false) }
      ```
    - Wrap the existing bare `IconButton(onClick = onShowMore)` in a `Box`
    - Replace `onClick = onShowMore` with `onClick = { showMoreMenu = true }`
    - Add a `DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false })` inside the `Box`, anchored to the button
    - Add four `DropdownMenuItem` entries inside the menu:
      1. **History** — `leadingIcon = Icon(Icons.Default.History)`, `onClick = { showMoreMenu = false; onShowHistory() }`
      2. **Dark Mode / Light Mode toggle** — label and icon depend on `isDarkMode`; `onClick = { showMoreMenu = false; onToggleDarkMode() }`
      3. **New Private Tab** — `leadingIcon = Icon(Icons.Default.VisibilityOff)`, `onClick = { showMoreMenu = false; onAddIncognitoTab() }`
      4. **Settings** — `leadingIcon = Icon(Icons.Default.Settings)`, `onClick = { showMoreMenu = false; onNavigateToSettings() }`
    - _Bug_Condition: isBugCondition(input) where input.location = TAB_SWITCHER_TOOLBAR AND input.action = TAP_MORE_VERT_BUTTON_
    - _Expected_Behavior: showTabManager = true AND showTabSwitcherMenu = true AND showMenu = false_
    - _Preservation: showMoreMenu is local state — no parent observes it; all other toolbar buttons are unaffected_
    - _Requirements: 2.1, 2.2, 2.7, 3.6_

  - [x] 3.4 Update the `TabManagerOverlay` call site in `BrowserScreen`
    - In `BrowserScreen.kt`, collect `themeMode` from the view model:
      ```kotlin
      val themeMode by viewModel.themeMode.collectAsState()
      ```
    - At the `TabManagerOverlay(...)` call site (around line 1107), remove:
      ```kotlin
      onShowMore = {
          viewModel.dismissTabManager()
          viewModel.toggleMenu()
      }
      ```
    - Add the three new arguments:
      ```kotlin
      isDarkMode = themeMode == "Dark",
      onToggleDarkMode = { viewModel.toggleDarkMode() },
      onNavigateToSettings = {
          viewModel.dismissTabManager()
          onNavigateToSettings()
      },
      ```
    - `onShowHistory` already dismisses the tab manager and shows history — no change needed
    - _Requirements: 2.3, 2.4, 2.5, 2.6, 2.7, 3.1_

  - [x] 3.5 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Tab Switcher MoreVert Shows Scoped Menu
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior: `showTabManager == true` and `showMenu == false` after MoreVert tap
    - When this test passes, it confirms the scoped menu is shown and the tab switcher is not dismissed
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.1, 2.2, 2.7_

  - [x] 3.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Non-MoreVert Interactions Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run all preservation property tests from step 2
    - **EXPECTED OUTCOME**: All tests PASS (confirms no regressions)
    - Confirm Done, tab card tap, add-tab, close-all, and browser top bar MoreVert all behave exactly as before

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full test suite and confirm all tests pass
  - Verify the bug condition exploration test (Property 1) passes — tab switcher stays open, scoped menu appears, browser top bar menu does NOT open
  - Verify all preservation tests (Property 2) pass — Done, tab card tap, add-tab, close-all, browser top bar MoreVert all unchanged
  - Verify `toggleDarkMode()` unit tests pass: `"System"` → `"Dark"`, `"Dark"` → `"Light"`, `"Light"` → `"Dark"`
  - Ask the user if any questions arise before marking complete
