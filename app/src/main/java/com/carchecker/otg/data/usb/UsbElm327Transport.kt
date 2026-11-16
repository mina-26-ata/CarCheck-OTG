package com.carchecker.otg.data.usb

import android.hardware.usb.UsbDeviceConnection
import com.carchecker.otg.data.obd.ElmProtocolHelpers
import com.carchecker.otg.data.obd.ObdTransport
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class UsbElm327Transport(
    private val otgManager: UsbOtgManager,
    private val driver: UsbSerialDriver,
    private val preferredBaudRate: Int = 0
) : ObdTransport {

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var activeBaudRate: Int = 38400
    private var firmwareLabel: String = "ELM327 USB"
    private val ioMutex = Mutex()

    override val isConnected: Boolean
        get() = port != null && connection != null

    override val description: String
        get() = "$firmwareLabel (${activeBaudRate} bps)"

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val device = driver.device
                if (!otgManager.hasPermission(device)) {
                    return@withLock Result.failure(IllegalStateException("USB permission not granted"))
                }
                val conn = otgManager.openConnection(device)
                    ?: return@withLock Result.failure(IOException("Failed to open USB device connection"))

                val serialPort = driver.ports.firstOrNull()
                    ?: run {
                        conn.close()
                        return@withLock Result.failure(IOException("No serial ports exposed by USB adapter"))
                    }

                serialPort.open(conn)
                connection = conn
                port = serialPort

                val candidates = if (preferredBaudRate > 0) {
                    listOf(preferredBaudRate, 38400, 115200, 9600, 57600, 230400).distinct()
                } else {
                    listOf(38400, 115200, 9600, 57600, 230400)
                }

                var detected = false
                for (baud in candidates) {
                    if (probeBaudRate(serialPort, baud)) {
                        activeBaudRate = baud
                        detected = true
                        break
                    }
                }

                if (!detected) {
                    // Fallback to 38400 even if adapter didn't echo cleanly during probe
                    configurePort(serialPort, if (preferredBaudRate > 0) preferredBaudRate else 38400)
                    activeBaudRate = if (preferredBaudRate > 0) preferredBaudRate else 38400
                }

                Result.success(Unit)
            } catch (e: Exception) {
                disconnectInternal()
                Result.failure(e)
            }
        }
    }

    private fun configurePort(serialPort: UsbSerialPort, baud: Int) {
        serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try {
            serialPort.dtr = true
            serialPort.rts = true
        } catch (_: Exception) {
        }
        try {
            serialPort.purgeHwBuffers(true, true)
        } catch (_: Exception) {
        }
    }

    private fun probeBaudRate(serialPort: UsbSerialPort, baud: Int): Boolean {
        return try {
            configurePort(serialPort, baud)
            drainInput(serialPort)
            // Wake / check with ATI or ATZ
            serialPort.write("\rATI\r".toByteArray(Charsets.US_ASCII), 600)
            val resp = readUntilPrompt(serialPort, 1200L)
            if (ElmProtocolHelpers.looksLikeValidAtiResponse(resp)) {
                firmwareLabel = ElmProtocolHelpers.extractElmFirmwareLabel(resp, "ELM327 USB")
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val activePort = port ?: return@withLock Result.failure(IllegalStateException("USB serial port is closed"))
            try {
                drainInput(activePort)
                val payload = "${command.trim()}\r".toByteArray(Charsets.US_ASCII)
                activePort.write(payload, 1000)
                val response = readUntilPrompt(activePort, timeoutMs)
                Result.success(response)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        try {
            port?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        port = null
        connection = null
    }

    private fun drainInput(serialPort: UsbSerialPort) {
        val junk = ByteArray(256)
        try {
            while (serialPort.read(junk, 20) > 0) {
                // discard stale bytes
            }
        } catch (_: Exception) {
        }
    }

    private fun readUntilPrompt(serialPort: UsbSerialPort, timeoutMs: Long): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val len = try {
                serialPort.read(buf, 150)
            } catch (_: Exception) {
                0
            }
            if (len > 0) {
                val chunk = String(buf, 0, len, Charsets.US_ASCII)
                sb.append(chunk)
                if (sb.indexOf(">") >= 0) {
                    break
                }
            }
        }
        return ElmProtocolHelpers.cleanElmResponse(sb.toString())
    }
}
