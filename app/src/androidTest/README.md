# Request detail text selection regression

Run the UI regression on an Android device/emulator:

```shell
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.close.hook.ads.RequestTextSelectionTest
```

The fixtures open the real `RequestInfoActivity` with local request/response bodies. They do not require Xposed or intercepted network traffic. Tests show the keyboard, search, click the real tab strip, long press selectable text, click the floating Copy menu and compare the clipboard with the selected text. They cover closing search, keeping search active, leaving search through the back handler and repeated page recycling, across DNS, request/response headers, both bodies and stack traces.

## Log dialog and response collection toggle

Run `com.close.hook.ads.LogAndCollectionUiTest` with the same runner argument to check the retained Request menu and settings switch in both directions, stack frame layout and complete copying, and actual Parcel sizes for large log batches. The stack dialog test uses the public `WindowInspector` API and runs on Android 10+; the other cases run on the app's supported versions.

The regression control without the fixes reproduced a 61-line stack rendered as 181 wrapped lines, and a Request checkbox still showing false after the settings switch became true. With the fixes, all three cases passed on the Android 16 emulator. `LogPayloadPolicyTest` separately covers the stack limit, explicit truncation markers, Unicode boundaries and batch splitting.

## Cause

A `RecyclerView` bind can run after an existing page has been temporarily detached. The page root then has no parent, although `isAttachedToWindow` may still be true. Search highlight refreshes and keyboard layout changes exercise this path.

`TextView.setText` calls `Editor.prepareCursorControllers`. That code checks the root view's `WindowManager.LayoutParams` to decide whether the window supports selection handles. During the detached bind, the root is the page's `NestedScrollView` with `RecyclerView.LayoutParams`, so the framework sets `mSelectionControllerEnabled=false`. Reattaching the page does not itself recalculate that flag. When the existing text layout can be reused, no new text layout is built to recalculate it either.

A trace on the unmodified Android 16 UI captured:

- `RequestInfoPagerAdapter.updateStaticTextViews → TextView.setText` during `RecyclerView.Recycler.tryBindViewHolderByDeadline`, with the page detached and a non-window root.
- After reattachment: `isTextSelectable=true`, `isFocusable=true`, `isLongClickable=true`, a non-null text layout and valid window root, but `mSelectionControllerEnabled=false`.
- A real long press then logged `TextView does not support text selection. Selection cancelled.` Request URL, response headers, response body and stack text all failed along the same search/close/tab path.

The private field was read only in temporary diagnostic instrumentation. The committed UI tests and production fix do not use reflection or private APIs.

## Fix

`RequestInfoViewHolder` posts a single, coalesced restoration after window attachment or a detached bind. Once the page is attached and laid out, it reapplies the current text of visible selectable fields with `BufferType.SPANNABLE`. This makes the framework prepare selection controllers against the real window root while retaining the text and highlight spans. Detach/recycle cancels pending callbacks. Normal attached updates do not schedule restoration.

This is separate from clearing the search query: both plain text and highlighted text can be selected. Unconditionally replacing body text on every display delivery does not address the detached-bind lifecycle and can interrupt an existing selection.

## Verification

On an Android 16 emulator, with normal window/tab animations enabled:

- Disabling only `scheduleSelectionRestore` made the formal `closedBodySearchThenSwitchTabsCanSelectAndCopy` test fail on a real long press of `urlStringText` after searching and closing search. The same test first successfully copied the DNS host, confirming that touch injection and clipboard checks were working.
- Restoring the fix passed all four UI tests (25 long-press/menu/clipboard checks across six tabs). No private API policy changes were used for these formal runs.

The temporary diagnostic traces and before/after runner output are saved under `build/selection-regression/` in the development workspace. The AndroidX test runner and dependencies are restricted to `androidTest`.
