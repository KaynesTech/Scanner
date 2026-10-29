package com.example.scanner

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScannerUiTest {
    @Before fun resetPreferences() {
        ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("scanner", 0).edit().clear().commit()
    }
    @Test fun filtersApplyImmediatelyAndInvalidRegexShowsError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun add(name: String?, rssi: Int, address: String) = activity.acceptDevice(
                    ScanDevice(name, address, rssi, ScanMode.BLE, "Dual-mode device", "Not paired", true,
                        listOf("0000180f-0000-1000-8000-00805f9b34fb"), listOf("0xFFFF: 01 02"), -4, SystemClock.elapsedRealtime()))
                add("Sensor One", -60, "02:00:00:00:00:01")
                add("Beacon_1234", -80, "02:00:00:00:00:02")
                add(null, -45, "02:00:00:00:00:03")
                activity.renderResults()
                assertEquals("1 shown / 3 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                activity.findViewById<Slider>(R.id.rssiSlider).value = 90f
                assertEquals("2 shown / 3 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                val unnamed = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.showUnnamed)
                assertFalse(unnamed.isChecked); unnamed.isChecked = true
                assertEquals("3 shown / 3 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                activity.findViewById<TextInputEditText>(R.id.nameFilter).setText("Sensor|Beacon")
                assertEquals("2 shown / 3 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                activity.findViewById<TextInputEditText>(R.id.nameFilter).setText("[")
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.nameFilterLayout).error)
                assertEquals("0 shown / 3 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                activity.findViewById<TextInputEditText>(R.id.nameFilter).setText("")
                assertNull(activity.findViewById<TextInputLayout>(R.id.nameFilterLayout).error)
            }
            idle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.deviceList)
                assertEquals(3, list.adapter!!.itemCount)
                activity.findViewById<View>(R.id.clearButton).performClick()
                assertEquals("0 shown / 0 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
            }
        }
    }
    @Test fun screenRendersDeviceDetailsAndUnifiedSources() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("", activity.findViewById<TextInputEditText>(R.id.nameFilter).text.toString())
                assertEquals(75f, activity.findViewById<Slider>(R.id.rssiSlider).value)
                assertEquals("-75 dBm", activity.findViewById<TextView>(R.id.rssiValue).text.toString())
                assertTrue(activity.findViewById<View>(R.id.scanButton).isEnabled)
                activity.findViewById<TextInputEditText>(R.id.nameFilter).setText("Sensor|Beacon")
                activity.findViewById<Slider>(R.id.rssiSlider).value = 90f
                for ((i, label) in listOf("Sensor One", "Beacon_1234", "Sensor With An Unusually Long Bluetooth Device Name").withIndex()) {
                    activity.acceptDevice(ScanDevice(label, "02:00:00:00:00:0${i + 1}", -50 - i * 12, ScanMode.BLE,
                        "Dual-mode device", "Not paired", true, listOf("0000180f-0000-1000-8000-00805f9b34fb"),
                        listOf("0xFFFF: 01 02"), -4, SystemClock.elapsedRealtime()))
                }
                activity.acceptDevice(ScanDevice(null, "02:00:00:00:00:01", -49, ScanMode.CLASSIC,
                    "Dual-mode device", "Not paired", services = listOf("0000111e-0000-1000-8000-00805f9b34fb"), seenAt = SystemClock.elapsedRealtime()))
                activity.renderResults()
            }
            idle()
            screenshot(scenario, "scanner-list.png")
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.deviceList)
                assertEquals(3, list.adapter!!.itemCount)
                assertEquals(setOf(ScanMode.BLE, ScanMode.CLASSIC), (list.adapter as DeviceAdapter).currentList.first().sources)
                val holder = list.findViewHolderForAdapterPosition(0)!!
                fun findDetails(view: View): View? = if (view.contentDescription == "Device details") view
                    else if (view is android.view.ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { findDetails(view.getChildAt(it)) } else null
                assertTrue(findDetails(holder.itemView)!!.performClick())
            }
            idle(); screenshot(scenario, "scanner-details.png")
        }
    }
    @Test fun filterPreferencesSurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<TextInputEditText>(R.id.nameFilter).setText("^Beacon")
                activity.findViewById<Slider>(R.id.rssiSlider).value = 88f
                activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.showUnnamed).isChecked = true
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("^Beacon", activity.findViewById<TextInputEditText>(R.id.nameFilter).text.toString())
                assertEquals(88f, activity.findViewById<Slider>(R.id.rssiSlider).value)
                assertEquals("-88 dBm", activity.findViewById<TextView>(R.id.rssiValue).text.toString())
                assertTrue(activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.showUnnamed).isChecked)
            }
        }
    }
    @Test fun lowerSliderPositionNarrowsToStrongerSignals() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptDevice(ScanDevice("Sensor Close", "02:00:00:00:00:01", -40, ScanMode.BLE,
                    "LE device", "Not paired", seenAt = SystemClock.elapsedRealtime()))
                activity.acceptDevice(ScanDevice("Sensor Farther", "02:00:00:00:00:02", -90, ScanMode.CLASSIC,
                    "Classic device", "Not paired", seenAt = SystemClock.elapsedRealtime()))
                val slider = activity.findViewById<Slider>(R.id.rssiSlider)
                slider.value = 50f
                assertEquals("1 shown / 2 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                slider.value = 110f
                assertEquals("2 shown / 2 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                slider.value = 20f
                assertEquals("0 shown / 2 seen", activity.findViewById<TextView>(R.id.resultCount).text.toString())
                assertEquals("-20 dBm", activity.findViewById<TextView>(R.id.rssiValue).text.toString())
            }
        }
    }
    private fun idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(400) }
    private fun screenshot(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
