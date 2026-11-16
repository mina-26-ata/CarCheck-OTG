package com.carchecker.otg.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.carchecker.otg.model.UsbAdapterInfo
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbOtgManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.carchecker.otg.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _adapters = MutableStateFlow<List<UsbAdapterInfo>>(emptyList())
    val adapters: StateFlow<List<UsbAdapterInfo>> = _adapters.asStateFlow()

    private val _lastPermissionResult = MutableStateFlow<Pair<Int, Boolean>?>(null)
    val lastPermissionResult: StateFlow<Pair<Int, Boolean>?> = _lastPermissionResult.asStateFlow()

    private val customProber: UsbSerialProber by lazy {
        val table = UsbSerialProber.getDefaultProbeTable()
        // Extra clone VID/PID mappings frequently seen in OTG ELM327 cables
        table.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
        table.addProduct(0x1A86, 0x5523, Ch34xSerialDriver::class.java)
        table.addProduct(0x1A86, 0x7522, Ch34xSerialDriver::class.java)
        table.addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
        table.addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java)
        table.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
        table.addProduct(0x067B, 0x2303, ProlificSerialDriver::class.java)
        table.addProduct(0x067B, 0x23A3, ProlificSerialDriver::class.java)
        table.addProduct(0x04D8, 0x000A, CdcAcmSerialDriver::class.java)
        table.addProduct(0x0483, 0x5740, CdcAcmSerialDriver::class.java)
        UsbSerialProber(table)
    }

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    refreshDevices()
                    if (device != null) {
                        _lastPermissionResult.value = device.deviceId to granted
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        refreshDevices()
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    fun refreshDevices(): List<UsbAdapterInfo> {
        val drivers = findAllDrivers()
        val list = drivers.map { driver ->
            val dev = driver.device
            UsbAdapterInfo(
                deviceId = dev.deviceId,
                deviceName = dev.productName?.takeIf { it.isNotBlank() }
                    ?: dev.deviceName,
                vendorId = dev.vendorId,
                productId = dev.productId,
                chipsetLabel = detectChipsetLabel(driver, dev),
                driverName = driver.javaClass.simpleName,
                hasPermission = usbManager.hasPermission(dev)
            )
        }
        _adapters.value = list
        return list
    }

    fun findDriverByDeviceId(deviceId: Int? = null): UsbSerialDriver? {
        val drivers = findAllDrivers()
        return if (deviceId != null) {
            drivers.firstOrNull { it.device.deviceId == deviceId } ?: drivers.firstOrNull()
        } else {
            drivers.firstOrNull()
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    fun openConnection(device: UsbDevice) = usbManager.openDevice(device)

    private fun findAllDrivers(): List<UsbSerialDriver> {
        val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val extraDrivers = customProber.findAllDrivers(usbManager)
        val merged = linkedMapOf<Int, UsbSerialDriver>()
        for (d in defaultDrivers) merged[d.device.deviceId] = d
        for (d in extraDrivers) merged.putIfAbsent(d.device.deviceId, d)
        return merged.values.toList()
    }

    private fun detectChipsetLabel(driver: UsbSerialDriver, dev: UsbDevice): String = when (driver) {
        is Ch34xSerialDriver -> "CH340 / CH341 USB-OBD"
        is FtdiSerialDriver -> "FTDI (FT232R / OBDLink)"
        is Cp21xxSerialDriver -> "Silicon Labs CP210x"
        is ProlificSerialDriver -> "Prolific PL2303"
        is CdcAcmSerialDriver -> "USB CDC-ACM (${dev.manufacturerName ?: "Generic"})"
        else -> driver.javaClass.simpleName.removeSuffix("SerialDriver")
    }
}
