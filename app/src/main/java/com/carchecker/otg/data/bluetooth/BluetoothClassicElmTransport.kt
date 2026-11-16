package com.carchecker.otg.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.carchecker.otg.data.obd.ElmProtocolHelpers
import com.carchecker.otg.data.obd.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * ELM327 Bluetooth Classic RFCOMM / SPP transport (standard SPP UUID 00001101-...).
 */
class BluetoothClassicElmTransport(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val device: BluetoothDevice,
    private val deviceLabel: String
) : ObdTransport {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioMutex = Mutex()
    private val rxLock = Any()
    private val rxBuffer = StringBuilder()
    private val rxSignal = Channel<Unit>(Channel.CONFLATED)

    private var firmwareLabel: String = "$deviceLabel (BT)"

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override val description: String
        get() = firmwareLabel

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (_: SecurityException) {
                }

                val btSocket = openRfcommSocket()
                socket = btSocket
                startReaderLoop(btSocket)

                // Wake and identify adapter via ATI
                delay(150)
                drainRx()
                val atiResp = sendRawAndAwaitPrompt("ATI", 2200L)
                if (ElmProtocolHelpers.looksLikeValidAtiResponse(atiResp)) {
                    firmwareLabel = ElmProtocolHelpers.extractElmFirmwareLabel(
                        rawAtiResponse = atiResp,
                        fallback = "$deviceLabel (BT)"
                    )
                }
                Result.success(Unit)
            } catch (e: Exception) {
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRfcommSocket(): BluetoothSocket {
        // First try standard secure RFCOMM, then fallback to insecure RFCOMM (for PIN-less OBD clones)
        var lastError: IOException? = null
        val creators = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
        )
        for (create in creators) {
            var candidate: BluetoothSocket? = null
            try {
                candidate = create()
                candidate.connect()
                return candidate
            } catch (io: IOException) {
                lastError = io
                try {
                    candidate?.close()
                } catch (_: Exception) {
                }
            }
        }
        throw lastError ?: IOException("Unable to open Bluetooth SPP socket")
    }

    private fun startReaderLoop(btSocket: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buf = ByteArray(512)
            val stream = btSocket.inputStream
            try {
                while (btSocket.isConnected) {
                    val readCount = stream.read(buf)
                    if (readCount <= 0) break
                    val text = String(buf, 0, readCount, Charsets.US_ASCII)
                    synchronized(rxLock) {
                        rxBuffer.append(text)
                    }
                    rxSignal.trySend(Unit)
                }
            } catch (_: Exception) {
                // Socket closed or disconnected
            }
        }
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isConnected) {
                return@withLock Result.failure(IllegalStateException("Bluetooth SPP socket is closed"))
            }
            try {
                drainRx()
                val response = sendRawAndAwaitPrompt(command, timeoutMs)
                Result.success(response)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun sendRawAndAwaitPrompt(command: String, timeoutMs: Long): String {
        val activeSocket = socket ?: throw IOException("Socket not connected")
        val payload = "${command.trim()}\r".toByteArray(Charsets.US_ASCII)
        activeSocket.outputStream.write(payload)
        activeSocket.outputStream.flush()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val done = synchronized(rxLock) {
                ElmProtocolHelpers.isElmPromptReady(rxBuffer)
            }
            if (done) break

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            withTimeoutOrNull(remaining) {
                rxSignal.receive()
            }
        }

        val raw = synchronized(rxLock) {
            val snapshot = rxBuffer.toString()
            rxBuffer.clear()
            snapshot
        }
        return ElmProtocolHelpers.cleanElmResponse(raw, sentCommand = command)
    }

    private fun drainRx() {
        synchronized(rxLock) {
            rxBuffer.clear()
        }
        while (rxSignal.tryReceive().isSuccess) {
            // discard pending wakeup tokens
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        readerJob?.cancel()
        readerJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        drainRx()
    }
}
