package com.example.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale

@SuppressLint("MissingPermission")
class BluetoothScanner(
    private val context: Context,
    private val found: (ScanDevice) -> Unit,
    private val state: (String, Boolean) -> Unit,
) {
    val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var bleScanner: BluetoothLeScanner? = null
    private var bleCallback: ScanCallback? = null
    private var ownsDiscovery = false
    private var classicEnabled = false
    private var classicRestart: Runnable? = null
    private var classicFailures = 0
    private var generation = 0
    private var closed = false
    var scanning = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                    stop(); state("Bluetooth is off", false)
                }
                return
            }
            if (!scanning || !hasPermissions()) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (device != null && rssi != Short.MIN_VALUE.toInt()) runCatching {
                        found(describe(device, ScanMode.CLASSIC,
                            resolveDeviceName(intent.getStringExtra(BluetoothDevice.EXTRA_NAME), device.name), rssi,
                            services = device.uuids?.map { it.uuid.toString() }.orEmpty()))
                    }.onFailure { fail("Bluetooth permission or device information unavailable") }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Ignore a late finish broadcast if a new discovery has already started.
                    if (classicEnabled && !runCatching { adapter?.isDiscovering == true }.getOrDefault(false)) {
                        ownsDiscovery = false; scheduleClassicRestart()
                    }
                }
            }
        }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }
    fun hasPermissions(): Boolean = permissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun start() {
        stop()
        if (closed) return
        if (!hasPermissions()) { state("Bluetooth scan permission required", false); return }
        if (adapter == null) { state("Bluetooth is unavailable on this device", false); return }
        try {
            if (!adapter.isEnabled) { state("Bluetooth is off", false); return }
            scanning = true
            val bleError = startBle()
            // Existing system discovery can supply results, but is never cancelled by this app.
            classicEnabled = if (adapter.isDiscovering) true else adapter.startDiscovery().also { ownsDiscovery = it }
            if (bleCallback == null && !classicEnabled) { fail(bleError ?: "Bluetooth discovery could not start"); return }
            state(scanLabel() + when {
                bleError != null -> " | $bleError"
                !classicEnabled -> " | Classic discovery unavailable"
                else -> ""
            }, true)
        } catch (_: SecurityException) { fail("Bluetooth scan permission required") }
        catch (_: IllegalStateException) { fail("Bluetooth became unavailable; retry") }
    }
    private fun scanLabel() = when {
        bleCallback != null && classicEnabled -> "Scanning BLE + Classic"
        bleCallback != null -> "Scanning BLE"
        else -> "Scanning Classic"
    }
    private fun startBle(): String? {
        val scanner = adapter?.bluetoothLeScanner ?: return "BLE scanning unavailable"
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = receive(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::receive) }
            private fun receive(result: ScanResult) {
                main.post {
                    if (closed || !scanning || bleCallback !== this || !hasPermissions()) return@post
                    runCatching {
                        val record = result.scanRecord
                        val ids = record?.manufacturerSpecificData
                        val manufacturers = if (ids == null) emptyList() else (0 until ids.size()).map { index ->
                            "0x%04X: %s".format(Locale.US, ids.keyAt(index), ids.valueAt(index).take(24).joinToString(" ") { "%02X".format(it) })
                        }
                        found(describe(result.device, ScanMode.BLE, resolveDeviceName(record?.deviceName, result.device.name), result.rssi,
                            result.isConnectable, record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                            manufacturers, record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }))
                    }.onFailure { fail("Bluetooth permission or advertisement unavailable") }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (bleCallback !== this) return@post
                    bleCallback = null; bleScanner = null
                    val message = when (errorCode) {
                        SCAN_FAILED_ALREADY_STARTED -> "BLE scan already started"
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE registration failed; toggle Bluetooth and retry"
                        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "BLE rate limit reached; wait 30 seconds and retry"
                        else -> "BLE scan failed ($errorCode)"
                    }
                    if (classicEnabled) state("Scanning Classic | $message", true) else fail(message)
                }
            }
        }
        bleScanner = scanner; bleCallback = callback
        return try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            null
        } catch (error: IllegalStateException) {
            bleScanner = null; bleCallback = null
            "BLE scanning unavailable"
        }
    }
    private fun scheduleClassicRestart() {
        classicRestart?.let(main::removeCallbacks)
        val token = generation
        classicRestart = Runnable {
            classicRestart = null
            if (!scanning || closed || !classicEnabled || token != generation) return@Runnable
            try {
                if (!hasPermissions()) { fail("Bluetooth scan permission required"); return@Runnable }
                if (adapter?.isDiscovering == true) return@Runnable
                ownsDiscovery = adapter?.startDiscovery() == true
                if (ownsDiscovery) { classicFailures = 0; state(scanLabel(), true) }
                else if (++classicFailures < 3) scheduleClassicRestart()
                else {
                    classicEnabled = false
                    if (bleCallback != null) state("Scanning BLE | Classic discovery unavailable", true)
                    else fail("Classic discovery could not restart")
                }
            } catch (_: SecurityException) { fail("Bluetooth scan permission required") }
            catch (_: IllegalStateException) { fail("Bluetooth became unavailable; retry") }
        }.also { main.postDelayed(it, 2000) }
    }
    private fun describe(device: BluetoothDevice, source: ScanMode, name: String?, rssi: Int, connectable: Boolean? = null,
                         services: List<String> = emptyList(), manufacturers: List<String> = emptyList(), tx: Int? = null) = ScanDevice(
        name, device.address, rssi, source,
        when (device.type) { BluetoothDevice.DEVICE_TYPE_LE -> "LE device"; BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic device"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual-mode device"; else -> "Device type unknown" },
        when (device.bondState) { BluetoothDevice.BOND_BONDED -> "Paired"; BluetoothDevice.BOND_BONDING -> "Pairing"; else -> "Not paired" },
        connectable, services, manufacturers, tx, SystemClock.elapsedRealtime(),
    )
    private fun fail(message: String) { stop(); state(message, false) }
    fun stop() {
        scanning = false; classicEnabled = false; generation++; classicFailures = 0
        classicRestart?.let(main::removeCallbacks); classicRestart = null
        val scanner = bleScanner; val callback = bleCallback
        bleScanner = null; bleCallback = null
        if (callback != null) runCatching { scanner?.stopScan(callback) }
        if (ownsDiscovery) runCatching { adapter?.cancelDiscovery() }
        ownsDiscovery = false
    }
    fun close() { stop(); closed = true; main.removeCallbacksAndMessages(null); context.unregisterReceiver(receiver) }
}
