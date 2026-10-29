package com.example.scanner

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val results = ScanResults()
    private val devices = DeviceAdapter()
    private lateinit var scanner: BluetoothScanner
    private lateinit var root: LinearLayout
    private lateinit var name: TextInputEditText
    private lateinit var nameLayout: TextInputLayout
    private lateinit var slider: Slider
    private lateinit var thresholdText: TextView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var scan: MaterialButton
    private var filter = NameFilter("")
    private var threshold = -75
    private var showUnnamed = false
    private var foreground = false
    private var pendingStart = false
    private var awaitingSystem = false
    private val prefs by lazy { getSharedPreferences("scanner", MODE_PRIVATE) }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        awaitingSystem = false
        if (grants.values.all { it } && scanner.hasPermissions()) { if (foreground && pendingStart) attemptStart() }
        else {
            pendingStart = false; status.text = "Scan permission denied"
            Snackbar.make(root, "Bluetooth scan permission is required", Snackbar.LENGTH_LONG).setAction("Settings") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }.show()
        }
    }
    private val bluetoothRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingSystem = false
        if (scanner.hasPermissions() && runCatching { scanner.adapter?.isEnabled == true }.getOrDefault(false)) {
            if (foreground && pendingStart) attemptStart()
        } else { pendingStart = false; status.text = "Bluetooth is off" }
    }
    private val refresh = object : Runnable {
        override fun run() { renderResults(); devices.refreshAge(); if (foreground) main.postDelayed(this, 1000) }
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply { text = value; textSize = size }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threshold = prefs.getInt("rssi", -75).coerceIn(-110, -20)
        showUnnamed = prefs.getBoolean("showUnnamed", false)
        prefs.edit().remove("mode").apply()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(8), 0)
            addView(text("Scanner", 22f).apply { setTypeface(null, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_preferences); contentDescription = "Bluetooth settings"; tooltipText = contentDescription
                background = null
                setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        nameLayout = TextInputLayout(this).apply { id = R.id.nameFilterLayout; hint = "Name regex (optional)"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; isHintEnabled = true }
        name = TextInputEditText(nameLayout.context).apply {
            id = R.id.nameFilter; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.getString("name", "")); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        nameLayout.addView(name, LinearLayout.LayoutParams(-1, -2)); controls.addView(nameLayout)
        val rssiHeading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
            addView(text("RSSI threshold"), LinearLayout.LayoutParams(0, -2, 1f))
            thresholdText = text("$threshold dBm", 16f).apply { id = R.id.rssiValue; typeface = android.graphics.Typeface.MONOSPACE }
            addView(thresholdText)
        }
        controls.addView(rssiHeading)
        slider = Slider(this).apply {
            id = R.id.rssiSlider; valueFrom = RssiRange.MIN.toFloat(); valueTo = RssiRange.MAX.toFloat()
            stepSize = 1f; value = RssiRange.sliderValue(threshold).toFloat()
            contentDescription = "Signal range: lower values require stronger RSSI"
            setLabelFormatter { it.roundToInt().toString() }
        }
        controls.addView(slider, LinearLayout.LayoutParams(-1, dp(40)))
        controls.addView(LinearLayout(this).apply {
            addView(text("Closer", 12f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Farther", 12f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val unnamedSwitch = MaterialSwitch(this).apply {
            id = R.id.showUnnamed; text = "Show unnamed devices"; isChecked = showUnnamed
            minHeight = dp(48)
        }
        controls.addView(unnamedSwitch, LinearLayout.LayoutParams(-1, -2))
        val filterScroll = ScrollView(this).apply { isFillViewport = false; addView(controls) }
        body.addView(filterScroll, if (landscape) LinearLayout.LayoutParams(dp(280), -1) else LinearLayout.LayoutParams(-1, -2))
        val listColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listColumn, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        count = text("0 devices").apply { id = R.id.resultCount; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        listColumn.addView(count)
        val listFrame = FrameLayout(this)
        val list = RecyclerView(this).apply {
            id = R.id.deviceList; layoutManager = LinearLayoutManager(this@MainActivity); adapter = devices
            itemAnimator = null; setHasFixedSize(true)
        }
        empty = text("No devices found", 16f).apply { id = R.id.emptyState; gravity = Gravity.CENTER; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        listFrame.addView(list, FrameLayout.LayoutParams(-1, -1)); listFrame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        listColumn.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("Ready").apply { id = R.id.scanStatus; setPadding(dp(16), dp(4), dp(16), dp(6)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        scan = MaterialButton(this).apply {
            id = R.id.scanButton; text = "Scan"; setIconResource(android.R.drawable.ic_menu_search)
            setOnClickListener {
                hideKeyboard()
                if (scanner.scanning) { pendingStart = false; scanner.stop(); status.text = "Stopped"; updateScanning(false); renderResults() }
                else { pendingStart = true; attemptStart() }
            }
        }
        footer.addView(scan, LinearLayout.LayoutParams(0, dp(56), 1f))
        footer.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            id = R.id.clearButton; setIconResource(android.R.drawable.ic_menu_delete); contentDescription = "Clear results"; tooltipText = contentDescription
            setOnClickListener { results.clear(); devices.clearExpansion(); renderResults() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(8) })
        root.addView(footer)
        setContentView(root)
        scanner = BluetoothScanner(applicationContext, ::acceptDevice) { message, running ->
            status.text = message; updateScanning(running); renderResults()
        }
        filter = NameFilter(name.text.toString()); nameLayout.error = filter.error
        unnamedSwitch.setOnCheckedChangeListener { _, checked ->
            showUnnamed = checked
            prefs.edit().putBoolean("showUnnamed", checked).apply(); renderResults()
        }
        name.doAfterTextChanged {
            filter = NameFilter(it.toString()); nameLayout.error = filter.error
            prefs.edit().putString("name", it.toString()).apply(); renderResults()
        }
        slider.addOnChangeListener { _, value, _ ->
            threshold = RssiRange.threshold(value.roundToInt()); thresholdText.text = "$threshold dBm"
            prefs.edit().putInt("rssi", threshold).apply(); renderResults()
        }
    }
    internal fun acceptDevice(device: ScanDevice) { results.observe(device) }
    private fun attemptStart() {
        if (!foreground || awaitingSystem) return
        if (!scanner.hasPermissions()) { awaitingSystem = true; permissionRequest.launch(scanner.permissions()); return }
        if (scanner.adapter == null) { pendingStart = false; status.text = "Bluetooth is unavailable on this device"; return }
        if (runCatching { scanner.adapter?.isEnabled != true }.getOrDefault(true)) {
            awaitingSystem = true
            runCatching { bluetoothRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure {
                awaitingSystem = false; pendingStart = false; status.text = "Enable Bluetooth in system settings"
            }
            return
        }
        if (Build.VERSION.SDK_INT < 31 && !androidx.core.location.LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))) {
            pendingStart = false; status.text = "Location services must be enabled for scanning on this Android version"
            Snackbar.make(root, "Location services are off", Snackbar.LENGTH_LONG).setAction("Settings") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }.show()
            return
        }
        pendingStart = false; results.clear(); devices.clearExpansion(); scanner.start(); renderResults()
    }
    private fun hideKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(name.windowToken, 0); name.clearFocus() }
    private fun updateScanning(running: Boolean) {
        scan.text = if (running) "Stop" else "Scan"
        scan.setIconResource(if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_menu_search)
    }
    internal fun renderResults() {
        val visible = results.visible(threshold, filter, SystemClock.elapsedRealtime(), scanner.scanning, showUnnamed)
        devices.submitList(visible)
        count.text = "${visible.size} shown / ${results.size} seen"
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            filter.error != null -> "Invalid name regex"
            results.size > 0 -> "No matching devices"
            scanner.scanning -> "Searching for devices"
            else -> "No devices found"
        }
    }
    override fun onStart() {
        super.onStart(); foreground = true; main.post(refresh)
        if (pendingStart && !awaitingSystem) attemptStart()
    }
    override fun onStop() {
        foreground = false; main.removeCallbacks(refresh)
        if (scanner.scanning) { scanner.stop(); updateScanning(false); status.text = "Stopped" }
        super.onStop()
    }
    override fun onDestroy() { scanner.close(); super.onDestroy() }
}
