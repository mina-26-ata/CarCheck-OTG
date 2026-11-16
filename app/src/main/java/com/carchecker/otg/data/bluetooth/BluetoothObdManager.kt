package com.carchecker.otg.data.bluetooth

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
import androidx.core.content.ContextCompat
import com.carchecker.otg.data.obd.ElmProtocolHelpers
import com.carchecker.otg.data.obd.ObdTransport
import com.carchecker.otg.model.BluetoothAdapterInfo
import com.carchecker.otg.model.BluetoothTransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates Bluetooth permissions, paired device listing, simultaneous Classic SPP + BLE
 * discovery, and builds dual-mode ELM327 transports for wireless OBD-II adapters.
 */
class BluetoothObdManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val foundMap = linkedMapOf<String, BluetoothAdapterInfo>()

    private val _devices = MutableStateFlow<List<BluetoothAdapterInfo>>(emptyList())
    val devices: StateFlow<List<BluetoothAdapterInfo>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private var classicReceiverRegistered = false
    private var scanStopJob: Job? = null
    private var activeBleScanner: BluetoothLeScanner? = null

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    fun hasAllPermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshStateAndPairedDevices() {
        val permGranted = hasAllPermissions()
        _hasPermissions.value = permGranted
        val adapter = bluetoothAdapter
        _isBluetoothEnabled.value = adapter?.isEnabled == true

        if (!permGranted || adapter == null || !adapter.isEnabled) {
            return
        }

        try {
            adapter.bondedDevices.orEmpty().forEach { dev ->
                upsertDevice(dev, rssi = null, discoveredViaBle = false)
            }
            publishSortedDevices()
        } catch (_: SecurityException) {
            _hasPermissions.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = 10_000L) {
        refreshStateAndPairedDevices()
        val adapter = bluetoothAdapter ?: return
        if (!hasAllPermissions() || !adapter.isEnabled) return

        stopScan()
        _isScanning.value = true

        // 1. Register Classic Discovery receiver
        if (!classicReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(context, discoveryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            classicReceiverRegistered = true
        }

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (_: Exception) {
        }

        // 2. Start BLE Scan simultaneously so BLE 4.0 dongles (like Temu ELM327 BLE) show immediately
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                activeBleScanner = scanner
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, bleScanCallback)
            }
        } catch (_: Exception) {
        }

        scanStopJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanStopJob?.cancel()
        scanStopJob = null

        try {
            activeBleScanner?.stopScan(bleScanCallback)
        } catch (_: Exception) {
        }
        activeBleScanner = null

        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
        } catch (_: Exception) {
        }

        if (classicReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {
            }
            classicReceiverRegistered = false
        }
        _isScanning.value = false
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            upsertDevice(dev, rssi = result.rssi, discoveredViaBle = true)
            publishSortedDevices()
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { res ->
                res.device?.let { upsertDevice(it, rssi = res.rssi, discoveredViaBle = true) }
            }
            publishSortedDevices()
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).let {
                        if (it == Short.MIN_VALUE) null else it.toInt()
                    }
                    if (device != null) {
                        upsertDevice(device, rssi = rssi, discoveredViaBle = false)
                        publishSortedDevices()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // BLE scan may still be running until scanStopJob finishes
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun upsertDevice(device: BluetoothDevice, rssi: Int?, discoveredViaBle: Boolean) {
        val address = device.address ?: return
        val rawName = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        val existing = foundMap[address]
        val resolvedName = rawName?.takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: "Bluetooth Device ($address)"

        val isBonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            existing?.isPaired ?: false
        }

        val devType = try {
            device.type
        } catch (_: SecurityException) {
            BluetoothDevice.DEVICE_TYPE_UNKNOWN
        }

        val inferredType = when {
            devType == BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothTransportType.DUAL
            devType == BluetoothDevice.DEVICE_TYPE_LE -> BluetoothTransportType.BLE_GATT
            devType == BluetoothDevice.DEVICE_TYPE_CLASSIC && discoveredViaBle -> BluetoothTransportType.DUAL
            devType == BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothTransportType.CLASSIC_SPP
            existing?.transportType == BluetoothTransportType.CLASSIC_SPP && discoveredViaBle -> BluetoothTransportType.DUAL
            existing?.transportType == BluetoothTransportType.BLE_GATT && !discoveredViaBle -> BluetoothTransportType.DUAL
            discoveredViaBle -> BluetoothTransportType.BLE_GATT
            else -> BluetoothTransportType.DUAL
        }

        val finalType = when {
            existing == null -> inferredType
            existing.transportType != inferredType && existing.transportType != BluetoothTransportType.DUAL -> BluetoothTransportType.DUAL
            else -> inferredType
        }

        val obdLike = ElmProtocolHelpers.looksLikeObdDeviceName(resolvedName) || (existing?.looksLikeObd == true)

        foundMap[address] = BluetoothAdapterInfo(
            address = address,
            name = resolvedName,
            transportType = finalType,
            isPaired = isBonded,
            rssi = rssi ?: existing?.rssi,
            looksLikeObd = obdLike
        )
    }

    private fun publishSortedDevices() {
        _devices.value = foundMap.values.sortedWith(
            compareByDescending<BluetoothAdapterInfo> { it.looksLikeObd }
                .thenByDescending { it.isPaired }
                .thenByDescending { it.rssi ?: -999 }
                .thenBy { it.name.lowercase() }
        )
    }

    @SuppressLint("MissingPermission")
    fun createTransport(info: BluetoothAdapterInfo): ObdTransport? {
        val adapter = bluetoothAdapter ?: return null
        val device = try {
            adapter.getRemoteDevice(info.address)
        } catch (_: Exception) {
            return null
        }
        return when (info.transportType) {
            BluetoothTransportType.CLASSIC_SPP -> BluetoothClassicElmTransport(adapter, device, info.name)
            BluetoothTransportType.BLE_GATT -> BluetoothBleElmTransport(context, device, info.name)
            BluetoothTransportType.DUAL -> DualModeBluetoothElmTransport(
                context = context,
                adapter = adapter,
                device = device,
                deviceLabel = info.name,
                preferClassicFirst = info.isPaired
            )
        }
    }
}

/**
 * Dual-mode wrapper for adapters detected as both Classic & BLE: tries the primary mode first
 * and transparently falls back to the other mode if connection/service discovery fails.
 */
private class DualModeBluetoothElmTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val deviceLabel: String,
    private val preferClassicFirst: Boolean
) : ObdTransport {

    private var activeDelegate: ObdTransport? = null

    override val isConnected: Boolean
        get() = activeDelegate?.isConnected == true

    override val description: String
        get() = activeDelegate?.description ?: "$deviceLabel (Bluetooth)"

    override suspend fun connect(): Result<Unit> {
        val first: ObdTransport = if (preferClassicFirst) {
            BluetoothClassicElmTransport(adapter, device, deviceLabel)
        } else {
            BluetoothBleElmTransport(context, device, deviceLabel)
        }
        val firstRes = first.connect()
        if (firstRes.isSuccess) {
            activeDelegate = first
            return Result.success(Unit)
        }
        first.disconnect()

        val second: ObdTransport = if (preferClassicFirst) {
            BluetoothBleElmTransport(context, device, deviceLabel)
        } else {
            BluetoothClassicElmTransport(adapter, device, deviceLabel)
        }
        val secondRes = second.connect()
        if (secondRes.isSuccess) {
            activeDelegate = second
            return Result.success(Unit)
        }
        second.disconnect()
        return secondRes
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
        val delegate = activeDelegate
            ?: return Result.failure(IllegalStateException("Bluetooth transport is not connected"))
        return delegate.sendCommand(command, timeoutMs)
    }

    override suspend fun disconnect() {
        activeDelegate?.disconnect()
        activeDelegate = null
    }
}
