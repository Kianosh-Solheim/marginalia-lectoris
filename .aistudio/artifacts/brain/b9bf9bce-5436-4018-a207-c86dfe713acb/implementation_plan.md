# Implementation Plan: Fix 1:1 Print Edition Page Breaks & Stabilize Text Selection in Readium

## Overview
This plan resolves two user-reported issues with the Readium reader engine:
1. **1:1 Print Edition Page Breaks**: When "Paginate 1:1 with print edition" (`paginateByEpubPageEquivalents`) is enabled, print page markers (`<span epub:type="pagebreak">`, `role="doc-pagebreak"`, `.page-normal`, and page-list marker IDs) must trigger clean, explicit page breaks in Readium's layout so each print page begins on a fresh screen/column.
2. **Text Selection Stability**: Eliminating selection jitter, erratic jumps across paragraphs, and touch conflicts caused by rapid 180ms JavaScript bridge polling while dragging selection handles.

---

## Proposed Changes

### 1. 1:1 Print Edition Page Breaks in Readium

- **Parameter Wiring (`ReaderScreen.kt` & `ReadiumEpubReaderContent.kt`)**:
  - Add `paginateByEpubPageEquivalents: Boolean = false` to `ReadiumEpubReaderContent` parameter list.
  - Forward `paginateByEpubPageEquivalents = paginateByEpubPageEquivalentsSetting` in `ReaderScreen.kt`.

- **CSS Page-Break Injection for Print Markers**:
  - When `paginateByEpubPageEquivalents` is `true`:
    - Inject or apply CSS rules targeting EPUB 3 and EPUB 2 print page markers:
      ```css
      [epub\:type~="pagebreak"], [role~="doc-pagebreak"], .page-normal, .pagebreak {
          display: block !important;
          break-before: page !important;
          break-before: column !important;
          page-break-before: always !important;
          height: 0 !important;
          margin: 0 !important;
          padding: 0 !important;
      }
      ```
    - Also explicitly target all indexed element IDs from `pub.pageList` for the active spine resource (e.g. `#page-20, #page-21`) to guarantee page breaks even on unclassed or legacy NCX pagebreak anchors.
  - When `paginateByEpubPageEquivalents` is `false`:
    - Remove the injected pagebreak stylesheet so natural reflow is restored without artificial page breaks.
  - Apply this injection dynamically whenever a resource loads and whenever the user toggles the 1:1 print pagination switch in Reader Settings.

---

### 2. Stabilize Text Selection & Prevent Erratic Jumps

- **Eliminate Aggressive Selection Polling**:
  - Replace the rapid 180ms continuous `currentSelection()` polling loop with a settled, debounced interaction model:
    - Avoid polling while selection drag handles are actively moving or during touch down.
    - Settle selection queries after handle drag finishes or with a gentle debounce (350–400ms).
    - Ensure calling `currentSelection()` does not cause WebView DOM reflow or cancel active Android text selection gestures.

- **ActionMode Stability & Menu Handling**:
  - In `selectionActionModeCallback`:
    - Suppress native menu items without triggering destructive redraws or menu layout churn that forces Android's selection handles to recompute coordinates across paragraph boundaries.
    - Keep `activeReadiumSelection` stable so `SelectionActionToolbarCard` animates cleanly at the bottom without stealing touch events from the WebView.

---

## Verification & Testing Plan

1. **Unit & Robolectric Tests**:
   - Verify `cleanPrintPageLabel` and CSS selector generator for page-list markers.
   - Test that toggling `paginateByEpubPageEquivalents` triggers the proper CSS injection script.
   - Run `gradle :app:testDebugUnitTest`.

2. **Reader Behavior Verification**:
   - Open an EPUB with a page list and enable "Paginate 1:1 with print edition":
     - Verify that each print page begins at the top of a fresh column/page.
   - Disable "Paginate 1:1 with print edition":
     - Verify text flows continuously without forced breaks at marker positions.
   - Select text across single lines and multiple paragraphs:
     - Verify selection handles drag smoothly without jumping erratically across paragraphs or collapsing unexpectedly.
     - Verify the custom **More**, **Add as Author**, and **Set as Abstract/Summary** menu appears reliably on settled selection.
