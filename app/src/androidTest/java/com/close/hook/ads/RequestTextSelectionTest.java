package com.close.hook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.ui.activity.RequestInfoActivity;
import com.google.android.material.tabs.TabLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Exercises real touch events, text selection, the floating Copy menu and the clipboard. */
@RunWith(AndroidJUnit4.class)
public class RequestTextSelectionTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private File body;

    @Before public void openDetail() throws Exception {
        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(service);
        body = new File(instrumentation.getTargetContext().getCacheDir(), "selection-fixture.txt");
        Files.write(body.toPath(), "alpha bravo charlie delta\nsecond line of response text".getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(instrumentation.getTargetContext(), RequestInfoActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("dnsHost", "alpha.example.test")
                .putExtra("fullAddress", "192.0.2.1")
                .putExtra("method", "POST")
                .putExtra("urlString", "https://example.test/alpha")
                .putExtra("requestHeaders", "Content-Type: text/plain\nX-Fixture: alpha bravo")
                .putExtra("responseCode", "200")
                .putExtra("responseMessage", "alpha bravo")
                .putExtra("responseHeaders", "Content-Type: text/plain\nX-Fixture: alpha bravo")
                .putExtra("requestBodyUriString", body.toURI().toString())
                .putExtra("responseBodyUriString", body.toURI().toString())
                .putExtra("stack", "alpha bravo charlie\nfixture.stack.Method");
        activity = instrumentation.startActivitySync(intent);
        settle();
    }

    @After public void closeDetail() {
        if (activity != null) onMain(() -> activity.finish());
        instrumentation.waitForIdleSync();
        if (body != null) body.delete();
    }

    @Test public void closedBodySearchThenSwitchTabsCanSelectAndCopy() {
        selectTab(R.string.body_response_title);
        search("alpha");
        closeSearch();
        assertQuery("");
        selectAndCopyEveryTab();
    }

    @Test public void activeBodySearchThenSwitchTabsCanSelectAndCopy() {
        selectTab(R.string.body_response_title);
        search("alpha");
        selectAndCopyEveryTab();
        assertQuery("alpha");
    }

    @Test public void backFromStaticSearchThenSwitchTabsCanSelectAndCopy() {
        selectTab(R.string.detail_request);
        search("alpha");
        onMain(() -> assertTrue(((RequestInfoActivity) activity).getBackController().onBackPressed()));
        settle();
        assertQuery("");
        selectAndCopyEveryTab();
    }

    @Test public void repeatedSearchAndTabRecyclingCanStillSelectAndCopy() {
        selectTab(R.string.detail_dns);
        longPressAndCopy(R.id.dnsHostText);
        for (int i = 0; i < 2; i++) {
            selectTab(R.string.body_request_title);
            search(i == 0 ? "alpha" : "bravo");
            closeSearch();
            selectTab(R.string.log_stack_trace);
            longPressAndCopy(R.id.stackText);
            selectTab(R.string.body_response_title);
            longPressAndCopy(R.id.responseBodyText);
            selectTab(R.string.detail_request);
            longPressAndCopy(R.id.urlStringText);
        }
    }

    private void selectAndCopyEveryTab() {
        selectTab(R.string.detail_dns);
        longPressAndCopy(R.id.dnsHostText);
        selectTab(R.string.detail_request);
        longPressAndCopy(R.id.urlStringText);
        selectTab(R.string.body_request_title);
        longPressAndCopy(R.id.requestBodyText);
        selectTab(R.string.detail_response);
        longPressAndCopy(R.id.responseHeadersText);
        selectTab(R.string.body_response_title);
        longPressAndCopy(R.id.responseBodyText);
        selectTab(R.string.log_stack_trace);
        longPressAndCopy(R.id.stackText);
    }

    private void selectTab(int title) {
        final View[] target = new View[1];
        final int[] index = {-1};
        onMain(() -> {
            TabLayout tabs = activity.findViewById(R.id.tabs);
            String label = activity.getString(title);
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (label.contentEquals(tabs.getTabAt(i).getText())) {
                    index[0] = i;
                    tabs.setScrollPosition(i, 0f, true);
                    target[0] = ((ViewGroup) tabs.getChildAt(0)).getChildAt(i);
                    break;
                }
            }
            assertNotNull("Missing tab " + label, target[0]);
        });
        settle();
        tap(target[0]);
        settle();
        onMain(() -> assertEquals(index[0], ((ViewPager2) activity.findViewById(R.id.view_pager)).getCurrentItem()));
    }

    private void search(String query) {
        // Show the real keyboard: its layout changes are part of the regression trigger.
        onMain(() -> {
            EditText edit = activity.findViewById(R.id.editText);
            edit.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            edit.setText(query);
        });
        settle();
    }

    private void closeSearch() {
        onMain(() -> {
            activity.findViewById(R.id.editText).requestFocus();
            activity.findViewById(R.id.search_icon).performClick();
        });
        settle();
    }

    private void assertQuery(String query) {
        onMain(() -> assertEquals(query, ((EditText) activity.findViewById(R.id.editText)).getText().toString()));
    }

    private void longPressAndCopy(int id) {
        final TextView[] target = new TextView[1];
        final float[] point = new float[2];
        onMain(() -> {
            target[0] = visibleText(activity.getWindow().getDecorView(), id);
            TextView text = target[0];
            assertNotNull("Missing visible text " + id, text);
            Rect rect = new Rect();
            assertTrue(text.getGlobalVisibleRect(rect));
            point[0] = rect.left + text.getTotalPaddingLeft() + text.getPaint().measureText("al");
            point[1] = rect.top + text.getTotalPaddingTop() + text.getLineHeight() / 2f;
        });
        touch(point[0], point[1], ViewConfiguration.getLongPressTimeout() + 400);
        settle();
        final String[] selected = new String[1];
        onMain(() -> {
            TextView text = target[0];
            assertTrue("Long press failed for " + activity.getResources().getResourceEntryName(id),
                    text.getSelectionStart() >= 0 && text.getSelectionEnd() > text.getSelectionStart());
            selected[0] = text.getText().subSequence(text.getSelectionStart(), text.getSelectionEnd()).toString();
        });
        clickCopyMenu();
        settle();
        onMain(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            assertNotNull("Copy menu did not populate clipboard", clip);
            assertEquals(selected[0], clip.getItemAt(0).coerceToText(activity).toString());
            // End selection without invoking the Activity's navigation back handler.
            target[0].clearFocus();
            activity.findViewById(R.id.search_container).requestFocus();
        });
        settle();
    }

    private void clickCopyMenu() {
        String copy = activity.getString(android.R.string.copy);
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (SystemClock.uptimeMillis() < deadline) {
            for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(copy);
                for (AccessibilityNodeInfo node : nodes) {
                    if (copy.equalsIgnoreCase(String.valueOf(node.getText()))
                            || copy.equalsIgnoreCase(String.valueOf(node.getContentDescription()))) {
                        Rect rect = new Rect();
                        node.getBoundsInScreen(rect);
                        touch(rect.centerX(), rect.centerY(), 0);
                        return;
                    }
                }
            }
            SystemClock.sleep(100);
        }
        fail("Floating Copy menu did not appear");
    }

    private TextView visibleText(View view, int id) {
        if (view.getId() == id && view.isShown()) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = visibleText(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void tap(View view) {
        Rect rect = new Rect();
        onMain(() -> assertTrue(view.getGlobalVisibleRect(rect)));
        touch(rect.centerX(), rect.centerY(), 0);
    }

    private void touch(float x, float y, long holdMillis) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        instrumentation.sendPointerSync(down);
        down.recycle();
        if (holdMillis > 0) SystemClock.sleep(holdMillis);
        MotionEvent up = fingerEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
        instrumentation.sendPointerSync(up);
        up.recycle();
    }

    private MotionEvent fingerEvent(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1;
        coords.size = 1;
        return MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private void settle() {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(700); // IME/tab transitions plus the 300 ms search debounce.
        instrumentation.waitForIdleSync();
    }

    private void onMain(Runnable action) {
        final Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
