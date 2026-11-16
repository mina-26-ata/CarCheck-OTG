package com.carchecker.otg.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.carchecker.otg.data.obd.ElmProtocolHelpers
import com.carchecker.otg.data.obd.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * ELM327 Bluetooth Low Energy (BLE 4.0+ GATT) transport compatible with dual-mode
 * Android/iOS OBDII dongles (FFF0/FFE0/18F0/Nordic UART/vendor serial profiles).
 */
class BluetoothBleElmTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val deviceLabel: String
) : ObdTransport {

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BLE_CHUNK_BYTES = 20
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var useWriteNoResponse: Boolean = false
    @Volatile
    private var connectedFlag = false
    private var firmwareLabel: String = "$deviceLabel (BLE)"

    private val ioMutex = Mutex()
    private val rxLock = Any()
    private val rxBuffer = StringBuilder()
    private val rxSignal = Channel<Unit>(Channel.CONFLATED)

    private var connectReadyDeferred: CompletableDeferred<Result<Unit>>? = null
    private var descriptorWriteDeferred: CompletableDeferred<Int>? = null
    private var charWriteDeferred: CompletableDeferred<Int>? = null

    override val isConnected: Boolean
        get() = connectedFlag && gatt != null && writeChar != null

    override val description: String
        get() = firmwareLabel

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            disconnectInternal()
            val ready = CompletableDeferred<Result<Unit>>()
            connectReadyDeferred = ready

            val gattClient = withContext(Dispatchers.Main) {
                device.connectGatt(context.applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            if (gattClient == null) {
                return@withLock Result.failure(IOException("Failed to open BLE GATT connection"))
            }
            gatt = gattClient

            val setupResult = withTimeoutOrNull(12_000L) { ready.await() }
                ?: Result.failure(IOException("BLE GATT connection timed out"))
            connectReadyDeferred = null

            if (setupResult.isFailure) {
                disconnectInternal()
                return@withLock setupResult
            }

            // Probe ELM firmware via ATI
            delay(200)
            drainRx()
            val atiResp = sendBleCommandInternal("ATI", 2200L)
            if (ElmProtocolHelpers.looksLikeValidAtiResponse(atiResp)) {
                firmwareLabel = ElmProtocolHelpers.extractElmFirmwareLabel(
                    rawAtiResponse = atiResp,
                    fallback = "$deviceLabel (BLE)"
                )
            }
            Result.success(Unit)
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                connectedFlag = false
                connectReadyDeferred?.complete(Result.failure(IOException("BLE connection failed (status=$status)")))
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedFlag = false
                    connectReadyDeferred?.complete(Result.failure(IOException("BLE device disconnected")))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectReadyDeferred?.complete(Result.failure(IOException("BLE service discovery failed ($status)")))
                return
            }

            val candidates = gatt.services.orEmpty().flatMap { svc ->
                svc.characteristics.orEmpty().map { ch ->
                    val props = ch.properties
                    ElmProtocolHelpers.GattCharCandidate(
                        serviceUuid = svc.uuid.toString(),
                        charUuid = ch.uuid.toString(),
                        canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                        canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                        canWrite = (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    )
                }
            }

            val selected = ElmProtocolHelpers.selectBestBleEndpoints(candidates)
            if (selected == null) {
                connectReadyDeferred?.complete(Result.failure(IOException("No compatible ELM327 BLE GATT UART service found")))
                return
            }

            val svc = gatt.getService(UUID.fromString(selected.serviceUuid))
            val wChar = svc?.getCharacteristic(UUID.fromString(selected.writeCharUuid))
            val nChar = svc?.getCharacteristic(UUID.fromString(selected.notifyCharUuid))
            if (wChar == null || nChar == null) {
                connectReadyDeferred?.complete(Result.failure(IOException("ELM327 GATT endpoints missing")))
                return
            }

            writeChar = wChar
            val props = wChar.properties
            useWriteNoResponse = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

            gatt.setCharacteristicNotification(nChar, true)
            val cccd = nChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                val enableVal = if (selected.preferIndicate) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                val def = CompletableDeferred<Int>()
                descriptorWriteDeferred = def
                val started = writeDescriptorCompat(gatt, cccd, enableVal)
                if (!started) {
                    descriptorWriteDeferred = null
                    connectedFlag = true
                    connectReadyDeferred?.complete(Result.success(Unit))
                }
            } else {
                connectedFlag = true
                connectReadyDeferred?.complete(Result.success(Unit))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWriteDeferred?.complete(status)
            descriptorWriteDeferred = null
            connectedFlag = true
            connectReadyDeferred?.complete(Result.success(Unit))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            charWriteDeferred?.complete(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        @Deprecated("Deprecated on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { handleIncomingBytes(it) }
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val text = String(bytes, Charsets.US_ASCII)
        synchronized(rxLock) {
            rxBuffer.append(text)
        }
        rxSignal.trySend(Unit)
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isConnected) {
                return@withLock Result.failure(IllegalStateException("BLE adapter is disconnected"))
            }
            try {
                drainRx()
                Result.success(sendBleCommandInternal(command, timeoutMs))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendBleCommandInternal(command: String, timeoutMs: Long): String {
        val activeGatt = gatt ?: throw IOException("BLE GATT not connected")
        val wChar = writeChar ?: throw IOException("BLE write characteristic missing")

        val fullBytes = "${command.trim()}\r".toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset < fullBytes.size) {
            val end = minOf(offset + BLE_CHUNK_BYTES, fullBytes.size)
            val chunk = fullBytes.copyOfRange(offset, end)
            val writeAck = CompletableDeferred<Int>()
            charWriteDeferred = writeAck

            val writeType = if (useWriteNoResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            val queued = writeCharacteristicCompat(activeGatt, wChar, chunk, writeType)
            if (!queued) {
                charWriteDeferred = null
                throw IOException("Failed to enqueue BLE write")
            }

            withTimeoutOrNull(1800L) { writeAck.await() }
            charWriteDeferred = null
            offset = end
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ready = synchronized(rxLock) {
                ElmProtocolHelpers.isElmPromptReady(rxBuffer)
            }
            if (ready) break
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            withTimeoutOrNull(remaining) {
                rxSignal.receive()
            }
        }

        val raw = synchronized(rxLock) {
            val text = rxBuffer.toString()
            rxBuffer.clear()
            text
        }
        return ElmProtocolHelpers.cleanElmResponse(raw, sentCommand = command)
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun drainRx() {
        synchronized(rxLock) {
            rxBuffer.clear()
        }
        while (rxSignal.tryReceive().isSuccess) {
            // clear signal tokens
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            disconnectInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal() {
        connectedFlag = false
        connectReadyDeferred?.cancel()
        descriptorWriteDeferred?.cancel()
        charWriteDeferred?.cancel()
        connectReadyDeferred = null
        descriptorWriteDeferred = null
        charWriteDeferred = null
        writeChar = null
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        drainRx()
    }
}
