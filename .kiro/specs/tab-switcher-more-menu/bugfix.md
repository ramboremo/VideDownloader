# Bugfix Requirements Document

## Introduction

The three-dots (MoreVert) button in the tab switcher toolbar (`TabManagerOverlay`) currently dismisses the tab switcher and opens the browser-level top bar dropdown menu instead of showing a tab-switcher-specific menu. This breaks the user's expectation that the tab switcher has its own contextual menu with options relevant to the tab management context (History, Dark Mode toggle, New Private Tab, Settings). The fix must show a scoped dropdown menu directly within the tab switcher UI without dismissing it.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the user taps the MoreVert (three-dots) button in the `TabManagerOverlay` bottom toolbar THEN the system dismisses the tab switcher by calling `viewModel.dismissTabManager()`

1.2 WHEN the user taps the MoreVert button in the `TabManagerOverlay` bottom toolbar THEN the system opens the browser-level top bar dropdown menu by calling `viewModel.toggleMenu()`, which is unrelated to the tab switcher context

1.3 WHEN the `onShowMore` callback fires from `TabManagerOverlay` THEN the system navigates the user away from the tab switcher instead of keeping it open

### Expected Behavior (Correct)

2.1 WHEN the user taps the MoreVert button in the `TabManagerOverlay` bottom toolbar THEN the system SHALL display a tab-switcher-scoped dropdown menu anchored to that button without dismissing the tab switcher

2.2 WHEN the tab-switcher-scoped dropdown menu is shown THEN the system SHALL present the following options: History, Dark Mode toggle, New Private Tab, and Settings

2.3 WHEN the user selects "History" from the tab-switcher menu THEN the system SHALL dismiss the tab switcher and open the history overlay

2.4 WHEN the user selects "Dark Mode" from the tab-switcher menu THEN the system SHALL toggle the app's dark mode setting without dismissing the tab switcher

2.5 WHEN the user selects "New Private Tab" from the tab-switcher menu THEN the system SHALL open a new incognito tab and dismiss the tab switcher

2.6 WHEN the user selects "Settings" from the tab-switcher menu THEN the system SHALL dismiss the tab switcher and navigate to the Settings screen

2.7 WHEN the tab-switcher-scoped dropdown menu is open THEN the system SHALL keep the tab switcher visible and not call `viewModel.dismissTabManager()`

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user taps the MoreVert button in the browser top bar (outside the tab switcher) THEN the system SHALL CONTINUE TO open the browser-level dropdown menu as before

3.2 WHEN the user taps "Done" in the tab switcher toolbar THEN the system SHALL CONTINUE TO dismiss the tab switcher without showing any menu

3.3 WHEN the user taps a tab card in the tab switcher THEN the system SHALL CONTINUE TO switch to that tab and dismiss the tab switcher

3.4 WHEN the user taps the add-tab button in the tab switcher THEN the system SHALL CONTINUE TO open a new tab without affecting the more menu behavior

3.5 WHEN the user taps the close-all button in the tab switcher THEN the system SHALL CONTINUE TO show the close-all confirmation dialog without affecting the more menu behavior

3.6 WHEN the user presses back while the tab-switcher-scoped dropdown menu is open THEN the system SHALL CONTINUE TO dismiss only the dropdown menu, leaving the tab switcher open

---

## Bug Condition

**Bug Condition Function:**
```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type UserAction
  OUTPUT: boolean

  RETURN X.location = TAB_SWITCHER_TOOLBAR
    AND X.action = TAP_MORE_VERT_BUTTON
END FUNCTION
```

**Property: Fix Checking**
```pascal
FOR ALL X WHERE isBugCondition(X) DO
  result ← onShowMore'(X)
  ASSERT tabSwitcherVisible = true
    AND tabSwitcherMenuVisible = true
    AND browserTopBarMenuVisible = false
END FOR
```

**Property: Preservation Checking**
```pascal
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT onShowMore(X) = onShowMore'(X)
END FOR
```
