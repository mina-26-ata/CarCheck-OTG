package com.carchecker.otg.data.obd

import com.carchecker.otg.model.DemoProfile
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

class SimulatedCarTransport(
    private val profile: DemoProfile
) : ObdTransport {

    private var connected = false
    private var headersEnabled = false
    private var currentHeader = "7DF"
    private var dtcsCleared = false
    private var tick = 0

    override val isConnected: Boolean
        get() = connected

    override val description: String
        get() = when (profile) {
            DemoProfile.HEALTHY -> "Simulator: 2022 Toyota Camry (Healthy)"
            DemoProfile.ENGINE_MISFIRE_FUEL -> "Simulator: 2019 Hyundai Elantra (Misfire & Fuel Trim)"
            DemoProfile.TRANS_ABS_COOLING -> "Simulator: 2017 Ford Explorer (Cooling / Trans / ABS)"
        }

    override suspend fun connect(): Result<Unit> {
        delay(220)
        connected = true
        dtcsCleared = false
        headersEnabled = false
        currentHeader = "7DF"
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
        if (!connected) return Result.failure(IllegalStateException("Simulator not connected"))
        val cmd = command.trim().uppercase().replace(" ", "")
        delay(28)
        tick++

        return Result.success(
            when {
                cmd == "ATZ" -> "ELM327 v1.5"
                cmd == "ATI" -> "ELM327 v1.5"
                cmd == "ATE0" || cmd == "ATE1" || cmd == "ATL0" || cmd == "ATL1" || cmd == "ATS0" || cmd == "ATS1" -> "OK"
                cmd == "ATH1" -> {
                    headersEnabled = true
                    "OK"
                }
                cmd == "ATH0" -> {
                    headersEnabled = false
                    "OK"
                }
                cmd.startsWith("ATSP") -> "OK"
                cmd == "ATDP" -> "ISO 15765-4 (CAN 11/500)"
                cmd == "ATDPN" -> "A6"
                cmd.startsWith("ATSH") -> {
                    currentHeader = cmd.removePrefix("ATSH").ifEmpty { "7DF" }
                    "OK"
                }
                cmd == "ATRV" -> when (profile) {
                    DemoProfile.HEALTHY -> "14.1V"
                    DemoProfile.ENGINE_MISFIRE_FUEL -> "13.9V"
                    DemoProfile.TRANS_ABS_COOLING -> "11.8V"
                }
                cmd == "0100" -> handle0100()
                cmd == "0120" -> "41 20 90 15 B0 11"
                cmd == "0140" -> "41 40 7A C0 00 00"
                cmd == "0160" -> "NO DATA"
                cmd == "0180" -> "NO DATA"
                cmd == "01A0" -> "41 A0 04 00 00 00"
                cmd == "0101" -> handleReadiness()
                cmd == "0121" -> if (!dtcsCleared && profile != DemoProfile.HEALTHY) "41 21 00 92" else "41 21 00 00"
                cmd == "0130" -> if (dtcsCleared) "41 30 01" else if (profile == DemoProfile.HEALTHY) "41 30 03" else "41 30 28"
                cmd == "0131" -> if (dtcsCleared) "41 31 00 08" else if (profile == DemoProfile.HEALTHY) "41 31 00 22" else "41 31 12 A0"
                cmd == "014D" -> if (!dtcsCleared && profile != DemoProfile.HEALTHY) "41 4D 00 B4" else "41 4D 00 00"
                cmd == "014E" -> if (dtcsCleared) "41 4E 00 05" else "41 4E 0A F0"
                cmd.startsWith("01") -> handleLiveSensor(cmd.removePrefix("01"))
                cmd == "03" -> handleMode03()
                cmd == "07" -> handleMode07()
                cmd == "0A" -> handleMode0A()
                cmd == "04" -> {
                    dtcsCleared = true
                    "44"
                }
                cmd == "14FFFFFF" -> {
                    dtcsCleared = true
                    "54"
                }
                cmd.startsWith("02") -> handleFreezeFrame(cmd)
                cmd == "0900" -> "49 00 55 40 00 00"
                cmd == "0902" -> handleVin()
                cmd == "0904" -> encodeAsciiMode09(0x04, when (profile) {
                    DemoProfile.HEALTHY -> "8966306L2100"
                    DemoProfile.ENGINE_MISFIRE_FUEL -> "391102E510"
                    DemoProfile.TRANS_ABS_COOLING -> "HL3A14C204CA"
                })
                cmd == "0906" -> "49 06 01 4A 8F 2C 91"
                cmd == "090A" -> encodeAsciiMode09(0x0A, "ECM-EngineControl")
                cmd == "1902AF" -> handleUds1902()
                else -> "NO DATA"
            }
        )
    }

    private fun handle0100(): String {
        return if (headersEnabled) {
            buildString {
                appendLine("7E8 06 41 00 BE 3F A8 13")
                if (profile == DemoProfile.TRANS_ABS_COOLING) {
                    appendLine("7EA 06 41 00 80 00 00 01")
                }
            }.trim()
        } else {
            "41 00 BE 3F A8 13"
        }
    }

    private fun handleReadiness(): String {
        // A: MIL + count, B: spark continuous, C: supported non-cont, D: incomplete (1 = incomplete)
        return when {
            dtcsCleared -> "41 01 00 07 65 65"
            profile == DemoProfile.HEALTHY -> "41 01 00 07 65 04" // EVAP still incomplete after 34km
            profile == DemoProfile.ENGINE_MISFIRE_FUEL -> "41 01 83 57 65 21" // MIL ON, 3 DTCs, misfire+fuel+cat+O2 incomplete
            else -> "41 01 84 07 65 01" // MIL ON, 4 DTCs, catalyst incomplete
        }
    }

    private fun wrapCanIfHeaders(payload: String): String {
        if (!headersEnabled) return payload
        val bytes = payload.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (bytes.size <= 7) {
            "7E8 ${hexByte(bytes.size)} ${bytes.joinToString(" ")}"
        } else {
            val totalLen = bytes.size
            val firstFrame = listOf("7E8", "10", hexByte(totalLen)) + bytes.take(6)
            val remaining = bytes.drop(6)
            val lines = mutableListOf(firstFrame.joinToString(" "))
            var seq = 1
            var idx = 0
            while (idx < remaining.size) {
                val chunk = remaining.drop(idx).take(7).toMutableList()
                while (chunk.size < 7) chunk.add("00")
                lines.add((listOf("7E8", hexByte(0x20 or (seq and 0x0F))) + chunk).joinToString(" "))
                idx += 7
                seq++
            }
            lines.joinToString("\n")
        }
    }

    private fun handleMode03(): String {
        val raw = if (dtcsCleared) {
            "43 00"
        } else when (profile) {
            DemoProfile.HEALTHY -> "43 00"
            DemoProfile.ENGINE_MISFIRE_FUEL -> "43 03 03 00 01 71 04 20" // P0300, P0171, P0420 (8 bytes -> multi-frame on CAN!)
            DemoProfile.TRANS_ABS_COOLING -> "43 03 01 28 07 00 07 15" // P0128, P0700, P0715
        }
        return wrapCanIfHeaders(raw)
    }

    private fun handleMode07(): String {
        val raw = if (dtcsCleared) {
            "47 00"
        } else when (profile) {
            DemoProfile.HEALTHY -> "47 00"
            DemoProfile.ENGINE_MISFIRE_FUEL -> "47 02 03 02 01 33" // P0302, P0133
            DemoProfile.TRANS_ABS_COOLING -> "47 01 05 62" // P0562 System Voltage Low
        }
        return wrapCanIfHeaders(raw)
    }

    private fun handleMode0A(): String {
        val raw = when (profile) {
            DemoProfile.HEALTHY -> "4A 00"
            DemoProfile.ENGINE_MISFIRE_FUEL -> "4A 01 03 00" // P0300 permanent
            DemoProfile.TRANS_ABS_COOLING -> "4A 01 01 28" // P0128 permanent
        }
        return wrapCanIfHeaders(raw)
    }

    private fun handleUds1902(): String {
        if (dtcsCleared) return "59 02 FF"
        return when {
            profile == DemoProfile.TRANS_ABS_COOLING && currentHeader == "7B0" ->
                // C0035 -> byte1=(1<<6)|(0<<4)|0=0x40, byte2=0x35 -> C0035, FTB=0x00, Status=0x09
                "59 02 FF 40 35 00 09"
            profile == DemoProfile.TRANS_ABS_COOLING && currentHeader == "7E1" ->
                // U0100 -> (3<<6)|(0<<4)|1=0xC1, 0x00 -> U0100
                "59 02 FF C1 00 00 09"
            else -> "NO DATA"
        }
    }

    private fun handleFreezeFrame(cmd: String): String {
        if (profile == DemoProfile.HEALTHY || dtcsCleared) return "NO DATA"
        val pid = cmd.removePrefix("02").take(2)
        return when (pid) {
            "02" -> "42 02 00 03 00"
            "04" -> "42 04 00 74" // ~45% load
            "05" -> if (profile == DemoProfile.TRANS_ABS_COOLING) "42 05 00 6A" else "42 05 00 84" // 66C vs 92C
            "06" -> if (profile == DemoProfile.ENGINE_MISFIRE_FUEL) "42 06 00 9C" else "42 06 00 82"
            "07" -> if (profile == DemoProfile.ENGINE_MISFIRE_FUEL) "42 07 00 9A" else "42 07 00 81"
            "0C" -> "42 0C 00 1A 90" // 1700 RPM
            "0D" -> "42 0D 00 3C" // 60 km/h
            "0F" -> "42 0F 00 4B" // 35C
            "11" -> "42 11 00 33" // 20%
            "42" -> if (profile == DemoProfile.TRANS_ABS_COOLING) "42 42 00 2E 18" else "42 42 00 35 E8"
            else -> "NO DATA"
        }
    }

    private fun handleLiveSensor(pid: String): String {
        val jitter = Random.nextDouble(-1.0, 1.0)
        return when (pid) {
            "04" -> { // Load
                val load = when (profile) {
                    DemoProfile.HEALTHY -> 21.0 + jitter * 1.5
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 39.5 + jitter * 3.0
                    DemoProfile.TRANS_ABS_COOLING -> 28.0 + jitter * 2.0
                }
                "41 04 ${hexByte((load * 255.0 / 100.0).roundToInt())}"
            }
            "05" -> { // Coolant C
                val temp = when (profile) {
                    DemoProfile.HEALTHY -> 90
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 94
                    DemoProfile.TRANS_ABS_COOLING -> 66 // Thermostat stuck open / cold!
                }
                "41 05 ${hexByte(temp + 40)}"
            }
            "06" -> { // STFT B1
                val stft = when (profile) {
                    DemoProfile.HEALTHY -> 1.5 + jitter * 1.2
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 14.8 + jitter * 2.5
                    DemoProfile.TRANS_ABS_COOLING -> 2.3 + jitter
                }
                "41 06 ${hexByte(((stft * 128.0 / 100.0) + 128.0).roundToInt())}"
            }
            "07" -> { // LTFT B1
                val ltft = when (profile) {
                    DemoProfile.HEALTHY -> 2.3
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 17.2
                    DemoProfile.TRANS_ABS_COOLING -> 3.1
                }
                "41 07 ${hexByte(((ltft * 128.0 / 100.0) + 128.0).roundToInt())}"
            }
            "0A" -> "41 0A ${hexByte(115)}" // 345 kPa
            "0B" -> { // MAP
                val map = if (profile == DemoProfile.ENGINE_MISFIRE_FUEL) 44 else 32
                "41 0B ${hexByte(map)}"
            }
            "0C" -> { // RPM
                val rpm = when (profile) {
                    DemoProfile.HEALTHY -> 740 + (jitter * 12).roundToInt()
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 690 + (jitter * 85).roundToInt() // rough idle
                    DemoProfile.TRANS_ABS_COOLING -> 820 + (jitter * 20).roundToInt()
                }
                val raw = (rpm * 4).coerceAtLeast(0)
                "41 0C ${hexByte(raw shr 8)} ${hexByte(raw and 0xFF)}"
            }
            "0D" -> "41 0D 00" // 0 km/h idle
            "0E" -> "41 0E ${hexByte(148)}" // +10 deg
            "0F" -> "41 0F ${hexByte(34 + 40)}" // IAT 34C
            "10" -> { // MAF
                val maf = if (profile == DemoProfile.ENGINE_MISFIRE_FUEL) 2.2 else 3.1
                val raw = (maf * 100).roundToInt()
                "41 10 ${hexByte(raw shr 8)} ${hexByte(raw and 0xFF)}"
            }
            "11" -> "41 11 ${hexByte(36)}" // 14.1% TPS
            "14" -> { // O2 B1S1 oscillating
                val v = if (tick % 2 == 0) 0.78 else 0.18
                "41 14 ${hexByte((v * 200).roundToInt())} 80"
            }
            "15" -> { // O2 B1S2 (steady ~0.68V on healthy, oscillating on P0420 bad cat!)
                val v = if (profile == DemoProfile.ENGINE_MISFIRE_FUEL) {
                    if (tick % 2 == 0) 0.72 else 0.22
                } else 0.68
                "41 15 ${hexByte((v * 200).roundToInt())} 80"
            }
            "1F" -> "41 1F 04 B0" // 1200 sec = 20 min runtime
            "24" -> "41 24 80 00 00 00"
            "2C" -> "41 2C 28"
            "2E" -> "41 2E 32"
            "2F" -> "41 2F A8" // ~66% fuel
            "33" -> "41 33 65" // 101 kPa
            "3C" -> { // Catalyst temp
                val catC = if (profile == DemoProfile.ENGINE_MISFIRE_FUEL) 710 else 435
                val raw = ((catC + 40) * 10)
                "41 3C ${hexByte(raw shr 8)} ${hexByte(raw and 0xFF)}"
            }
            "42" -> { // Module Voltage
                val mv = when (profile) {
                    DemoProfile.HEALTHY -> 14120
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 13920
                    DemoProfile.TRANS_ABS_COOLING -> 11840 // Alternator / battery weak!
                }
                "41 42 ${hexByte(mv shr 8)} ${hexByte(mv and 0xFF)}"
            }
            "43" -> "41 43 00 4B"
            "45" -> "41 45 14"
            "46" -> "41 46 46" // 30C ambient
            "49" -> "41 49 26"
            "5C" -> "41 5C ${hexByte(95 + 40)}"
            "A6" -> {
                val kmTenths = when (profile) {
                    DemoProfile.HEALTHY -> 482100L // 48,210 km
                    DemoProfile.ENGINE_MISFIRE_FUEL -> 1148500L // 114,850 km
                    DemoProfile.TRANS_ABS_COOLING -> 1684200L // 168,420 km
                }
                "41 A6 ${hexByte((kmTenths shr 24).toInt())} ${hexByte((kmTenths shr 16).toInt())} ${hexByte((kmTenths shr 8).toInt())} ${hexByte(kmTenths.toInt())}"
            }
            else -> "NO DATA"
        }
    }

    private fun handleVin(): String {
        val vin = when (profile) {
            DemoProfile.HEALTHY -> "4T1G11AK5NU612345" // 2022 Toyota
            DemoProfile.ENGINE_MISFIRE_FUEL -> "KMHD84LF9KU812345" // 2019 Hyundai
            DemoProfile.TRANS_ABS_COOLING -> "1FM5K8D84HGB12345" // 2017 Ford
        }
        val hexBytes = vin.map { it.code.toString(16).uppercase().padStart(2, '0') }
        // Return multi-frame ISO-TP response
        val all = listOf("49", "02", "01") + hexBytes
        return buildString {
            append("0: ").append(all.take(6).joinToString(" ")).append("\n")
            append("1: ").append(all.drop(6).take(7).joinToString(" ")).append("\n")
            append("2: ").append(all.drop(13).joinToString(" "))
        }
    }

    private fun encodeAsciiMode09(pid: Int, text: String): String {
        val bytes = listOf("49", hexByte(pid), "01") + text.map { hexByte(it.code) }
        return bytes.joinToString(" ")
    }

    private fun hexByte(v: Int): String = (v and 0xFF).toString(16).uppercase().padStart(2, '0')
}
