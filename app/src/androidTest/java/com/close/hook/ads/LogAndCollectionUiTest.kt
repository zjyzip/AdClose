package com.close.hook.ads

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.CompoundButton
import androidx.appcompat.view.menu.ListMenuItemView
import android.view.inspector.WindowInspector
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.data.repository.LogRepository
import com.close.hook.ads.hook.util.LogPayloadPolicy
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.activity.CustomHookActivity
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.fragment.request.RequestFragment
import com.close.hook.ads.ui.fragment.settings.SettingsPreferenceFragment
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogAndCollectionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: Activity? = null
    private val previousCollect = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
    private val previousLogging = HookPrefs.getEnableLogging(null)

    @After fun cleanup() {
        onMain {
            activity?.finish()
            HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, previousCollect)
            HookPrefs.setEnableLogging(null, previousLogging)
        }
        LogRepository.clearLogs()
        instrumentation.waitForIdleSync()
    }

    @Test fun retainedRequestMenuAndSettingsSwitchStayInSyncInBothDirections() {
        onMain { HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) }
        val main = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = main
        settle()
        navigate(main, R.id.bottom_item_2)
        lateinit var request: RequestFragment
        lateinit var settings: SettingsPreferenceFragment
        onMain {
            request = findFragment(main.supportFragmentManager, RequestFragment::class.java)!!
            settings = findFragment(main.supportFragmentManager, SettingsPreferenceFragment::class.java)!!
        }
        assertMenu(request, false)
        navigate(main, R.id.bottom_item_5)
        onMain { settings.findPreference<SwitchPreferenceCompat>(HookPrefs.KEY_COLLECT_RESPONSE_BODY)!!.performClick() }
        settle()
        // The Request view is retained offscreen; it must update without recreation.
        assertMenu(request, true)
        navigate(main, R.id.bottom_item_2)
        onMain {
            assertSame(request, findFragment(main.supportFragmentManager, RequestFragment::class.java))
            toolbar(request).menu.performIdentifierAction(R.id.action_collect_response, 0)
        }
        settle()
        onMain {
            assertFalse(HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, true))
            assertFalse(settings.findPreference<SwitchPreferenceCompat>(HookPrefs.KEY_COLLECT_RESPONSE_BODY)!!.isChecked)
        }
        navigate(main, R.id.bottom_item_5)
        onMain { settings.findPreference<SwitchPreferenceCompat>(HookPrefs.KEY_COLLECT_RESPONSE_BODY)!!.performClick() }
        settle()
        navigate(main, R.id.bottom_item_2)
        assertMenu(request, true)
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun closingCollectionFromTheSettingsRowUpdatesTheVisibleOverflowCheckbox() {
        checkSettingsToggle(false)
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun closingCollectionFromTheSettingsThumbUpdatesTheVisibleOverflowCheckbox() {
        checkSettingsToggle(true)
    }

    private fun checkSettingsToggle(tapThumb: Boolean) {
        onMain { HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, true) }
        val main = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = main
        settle()
        navigate(main, R.id.bottom_item_2)
        lateinit var request: RequestFragment
        lateinit var settings: SettingsPreferenceFragment
        onMain {
            request = findFragment(main.supportFragmentManager, RequestFragment::class.java)!!
            settings = findFragment(main.supportFragmentManager, SettingsPreferenceFragment::class.java)!!
        }
        assertOverflowCheckbox(request, true)
        navigate(main, R.id.bottom_item_5)
        repeat(4) { index ->
            val expected = index % 2 != 0
            val title = settings.findPreference<SwitchPreferenceCompat>(HookPrefs.KEY_COLLECT_RESPONSE_BODY)!!.title!!
            lateinit var target: View
            onMain {
                val label = findText(settings.requireView(), title)!!
                var row: View = label
                while (findCheckbox(row) == null) row = row.parent as View
                val widget = findCheckbox(row)!!
                assertEquals("Settings widget before tap", !expected, widget.isChecked)
                target = if (tapThumb) widget else label
            }
            tap(target)
            settle()
            onMain {
                assertEquals("Setting value after a real row tap", expected,
                    HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, !expected))
                assertEquals(expected, settings.findPreference<SwitchPreferenceCompat>(HookPrefs.KEY_COLLECT_RESPONSE_BODY)!!.isChecked)
            }
            navigate(main, R.id.bottom_item_2)
            assertMenu(request, expected)
            assertOverflowCheckbox(request, expected)
            navigate(main, R.id.bottom_item_5)
        }
    }

    private fun assertOverflowCheckbox(request: RequestFragment, expected: Boolean) {
        onMain { assertTrue(toolbar(request).showOverflowMenu()) }
        settle()
        onMain {
            val title = activity!!.getString(R.string.body_collect_response)
            val label = WindowInspector.getGlobalWindowViews().mapNotNull { findText(it, title) }.last()
            var row: View? = label
            while (row != null && row !is ListMenuItemView) row = row.parent as? View
            assertNotNull("Visible overflow row", row)
            val checkbox = findCheckbox(row!!)!!
            assertEquals("Visible overflow checkbox", expected, checkbox.isChecked)
            toolbar(request).hideOverflowMenu()
        }
        settle()
    }

    private fun findCheckbox(view: View): CompoundButton? {
        if (view is CompoundButton) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findCheckbox(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findText(view: View, text: CharSequence): TextView? {
        if (view is TextView && view.text.toString() == text.toString() && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findText(view.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun tap(view: View) {
        val point = IntArray(2)
        onMain {
            view.getLocationOnScreen(point)
            point[0] += view.width / 2
            point[1] += view.height / 2
        }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply {
                x = point[0].toFloat(); y = point[1].toFloat(); pressure = 1f; size = 1f
            }
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pointer), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun logDialogKeepsEachFrameOnOneLineAndCopiesTheCompleteStack() {
        val stack = "Stack Trace:\n" + (1..60).joinToString("\n") {
            "  android.app.servertransaction.TransactionExecutor.executeLifecycleItem(line: $it)"
        }
        assertTrue(stack.length > 2000)
        onMain { HookPrefs.setEnableLogging(null, true) }
        runBlocking {
            LogRepository.addLogs(listOf(LogEntry(timestamp = System.currentTimeMillis(), tag = "CustomHook",
                message = "fixture", packageName = "test.app", stackTrace = stack, hookScope = "global")))
        }
        val logs = instrumentation.startActivitySync(Intent(instrumentation.targetContext, CustomHookActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CustomHookActivity
        activity = logs
        settle()
        onMain { logs.bottomNavigationView.selectedItemId = R.id.nav_hook_log }
        settle()
        onMain {
            val list = logs.findViewById<RecyclerView>(R.id.recycler_view_logs)
            assertEquals(1, list.adapter!!.itemCount)
            list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        }
        settle()
        onMain {
            val root = WindowInspector.getGlobalWindowViews().first { it.findViewById<TextView>(R.id.textViewDetails) != null }
            val text = root.findViewById<TextView>(R.id.textViewDetails)
            assertEquals(stack, text.text.toString())
            assertEquals(stack.lines().size, text.layout.lineCount)
            assertTrue(text.parent is HorizontalScrollView)
            val horizontal = text.parent as HorizontalScrollView
            assertTrue(text.width > horizontal.width)
            root.findViewById<View>(android.R.id.button3).performClick()
        }
        settle()
        onMain {
            val clipboard = logs.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(stack, clipboard.primaryClip!!.getItemAt(0).coerceToText(logs).toString())
        }
    }

    @Test fun largeLogBatchEstimatesStayAboveActualParcelSizes() {
        val entries = (1..50).map {
            LogEntry(timestamp = it.toLong(), tag = "CustomHook", message = LogPayloadPolicy.message("m".repeat(50_000)),
                packageName = "test.app", stackTrace = LogPayloadPolicy.stack("frame\n".repeat(10_000)), hookScope = "global")
        }
        val batches = LogPayloadPolicy.batches(entries)
        assertTrue(batches.size > 1)
        for (batch in batches) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeTypedList(batch)
                assertTrue(parcel.dataSize() < LogPayloadPolicy.MAX_BATCH_BYTES)
                assertTrue(parcel.dataSize() <= batch.sumOf(LogPayloadPolicy::estimatedBytes))
            } finally { parcel.recycle() }
        }
    }

    private fun toolbar(fragment: RequestFragment): MaterialToolbar = fragment.requireView().findViewById(R.id.toolBar)

    private fun assertMenu(fragment: RequestFragment, expected: Boolean) = onMain {
        assertEquals(expected, toolbar(fragment).menu.findItem(R.id.action_collect_response).isChecked)
    }

    private fun navigate(main: MainActivity, item: Int) {
        onMain { main.getBottomNavigationView().selectedItemId = item }
        settle()
    }

    private fun <T : Fragment> findFragment(manager: FragmentManager, type: Class<T>): T? {
        for (fragment in manager.fragments) {
            if (type.isInstance(fragment)) return type.cast(fragment)
            findFragment(fragment.childFragmentManager, type)?.let { return it }
        }
        return null
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        instrumentation.waitForIdleSync()
    }

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw AssertionError(it) }
    }
}
