package com.carchecker.otg.data.obd

import com.carchecker.otg.model.ReadinessMonitor
import com.carchecker.otg.model.ReadinessSummary

data class RawDtcEntry(
    val code: String,
    val ecuAddress: String
)

object ObdResponseParser {

    private val errorTokens = listOf(
        "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR",
        "DATA ERROR", "BUFFER FULL", "STOPPED", "ERROR", "?"
    )

    fun isErrorOrEmpty(raw: String): Boolean {
        val upper = raw.trim().uppercase()
        if (upper.isEmpty()) return true
        return errorTokens.any { token ->
            if (token == "?") upper == "?" else upper.contains(token)
        }
    }

    fun parseVoltage(raw: String): Double? {
        val match = Regex("""(\d{1,2}\.\d{1,2})\s*V?""", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    /**
     * Reassembles single-frame or ISO-TP multi-frame hex lines into byte arrays per ECU.
     * Supports both `ATH0` (no header) and `ATH1` (3-char CAN header like `7E8` or 3-byte J1850/ISO9141 header).
     */
    fun extractHexPayloads(raw: String, expectCanHeaders: Boolean = false): Map<String, IntArray> {
        if (isErrorOrEmpty(raw)) return emptyMap()

        val lines = raw.replace("\r", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("SEARCHING", ignoreCase = true) &&
                    !line.startsWith("BUS INIT", ignoreCase = true) &&
                    !line.equals(">")
            }

        if (lines.isEmpty()) return emptyMap()

        // Check for ISO-TP line prefixes like `0: 49 02 01 ...` or `7E8 10 14 49 02 ...`
        val isIndexedMultiFrame = lines.any { Regex("""^([0-9A-Fa-f]+):\s*.*""").matches(it) }
        if (isIndexedMultiFrame) {
            val bytes = mutableListOf<Int>()
            for (line in lines) {
                val afterColon = line.substringAfter(':', line).trim()
                bytes.addAll(parseHexTokens(afterColon))
            }
            return if (bytes.isNotEmpty()) mapOf("7E8" to bytes.toIntArray()) else emptyMap()
        }

        val hasExplicitCanHeaders = lines.any { line ->
            val c = line.replace(" ", "").uppercase()
            (c.length % 2 == 1 && Regex("""^[0-9A-F]{3}[0-9A-F]+$""").matches(c)) ||
                Regex("""^18DA[0-9A-F]{4}[0-9A-F]+$""").matches(c)
        }

        if (!expectCanHeaders || !hasExplicitCanHeaders) {
            // Standard headerless response (or adapter ignored ATH1)
            val combined = mutableListOf<Int>()
            for (line in lines) {
                combined.addAll(parseHexTokens(line))
            }
            return if (combined.isNotEmpty()) mapOf("7E8" to combined.toIntArray()) else emptyMap()
        }

        // ATH1 mode: group by CAN ECU ID (3 hex chars e.g. `7E8`, `7EA`, `7E9`)
        val ecuFrames = linkedMapOf<String, MutableList<String>>()
        for (line in lines) {
            val cleaned = line.replace(" ", "")
            // Match 11-bit CAN header `7xx` or `6xx` or `5xx` followed by payload
            val match = Regex("""^([0-9A-Fa-f]{3})([0-9A-Fa-f]+)$""").find(cleaned)
            if (match != null) {
                val ecuId = match.groupValues[1].uppercase()
                val frameHex = match.groupValues[2]
                ecuFrames.getOrPut(ecuId) { mutableListOf() }.add(frameHex)
            } else {
                // Fallback for 29-bit CAN `18DAF110...`
                val match29 = Regex("""^(18DA[0-9A-Fa-f]{4})([0-9A-Fa-f]+)$""").find(cleaned)
                if (match29 != null) {
                    val ecuId = match29.groupValues[1].uppercase()
                    val frameHex = match29.groupValues[2]
                    ecuFrames.getOrPut(ecuId) { mutableListOf() }.add(frameHex)
                }
            }
        }

        val result = linkedMapOf<String, IntArray>()
        for ((ecuId, frames) in ecuFrames) {
            val assembled = assembleIsoTpFrames(frames)
            if (assembled.isNotEmpty()) {
                result[ecuId] = assembled
            }
        }
        return result
    }

    private fun assembleIsoTpFrames(frames: List<String>): IntArray {
        val rawBytesList = frames.map { hexStringToBytes(it) }.filter { it.isNotEmpty() }
        if (rawBytesList.isEmpty()) return intArrayOf()

        // Inspect PCI byte of first frame to see if ISO-TP PCI is included (with ATH1 on ELM327, PCI byte IS included)
        val first = rawBytesList[0]
        val pciType = (first[0] and 0xF0) shr 4

        return when {
            rawBytesList.size == 1 && pciType == 0 -> {
                val len = (first[0] and 0x0F).coerceAtMost(first.size - 1)
                first.sliceArray(1..len)
            }
            pciType == 1 -> {
                // First Frame (1x LL ...) followed by Consecutive Frames (21 ..., 22 ...)
                val totalLen = ((first[0] and 0x0F) shl 8) or first[1]
                val out = mutableListOf<Int>()
                for (i in 2 until first.size) out.add(first[i])
                for (fIdx in 1 until rawBytesList.size) {
                    val cf = rawBytesList[fIdx]
                    if (((cf[0] and 0xF0) shr 4) == 2) {
                        for (i in 1 until cf.size) out.add(cf[i])
                    }
                }
                if (totalLen in 1..out.size) out.take(totalLen).toIntArray() else out.toIntArray()
            }
            else -> {
                // Non-PCI raw concatenated bytes
                rawBytesList.flatMap { it.toList() }.toIntArray()
            }
        }
    }

    fun parseHexTokens(line: String): List<Int> {
        val noSpaces = line.replace(" ", "").replace("\t", "")
        if (noSpaces.length < 2 || !noSpaces.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            // Try splitting whitespace tokens if there are mixed non-hex markers
            return line.split(Regex("\\s+"))
                .filter { it.length == 2 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
                .map { it.toInt(16) }
        }
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < noSpaces.length) {
            out.add(noSpaces.substring(i, i + 2).toInt(16))
            i += 2
        }
        return out
    }

    private fun hexStringToBytes(hex: String): IntArray {
        val len = hex.length / 2
        return IntArray(len) { idx -> hex.substring(idx * 2, idx * 2 + 2).toInt(16) }
    }

    /**
     * Parses `0100`, `0120`, `0140`, etc. bitmask into supported PID hex strings like `"0C"`, `"05"`.
     */
    fun parseSupportedPids(raw: String, basePid: Int): Set<String> {
        val payloads = extractHexPayloads(raw, expectCanHeaders = false)
        val bytes = payloads.values.firstOrNull() ?: return emptySet()
        // Expected `41 <basePid> B0 B1 B2 B3`
        val idx = findSequence(bytes, 0x41, basePid)
        if (idx < 0 || idx + 5 >= bytes.size) return emptySet()

        val supported = linkedSetOf<String>()
        for (byteOffset in 0 until 4) {
            val b = bytes[idx + 2 + byteOffset]
            for (bit in 0 until 8) {
                if ((b and (1 shl (7 - bit))) != 0) {
                    val pidNum = basePid + byteOffset * 8 + bit + 1
                    supported.add(pidNum.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
        return supported
    }

    fun parseMode01SensorBytes(raw: String, expectedPidHex: String): IntArray? {
        val pidInt = expectedPidHex.toIntOrNull(16) ?: return null
        val payloads = extractHexPayloads(raw, expectCanHeaders = false)
        val bytes = payloads.values.firstOrNull() ?: return null
        val idx = findSequence(bytes, 0x41, pidInt)
        if (idx < 0 || idx + 2 >= bytes.size) return null
        return bytes.sliceArray((idx + 2) until bytes.size)
    }

    /**
     * Decodes a 2-byte OBD-II DTC (SAE J2012 format).
     */
    fun decodeTwoByteDtc(b1: Int, b2: Int): String? {
        if (b1 == 0 && b2 == 0) return null
        val prefix = when ((b1 and 0xC0) shr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d2 = (b1 and 0x30) shr 4
        val d3 = b1 and 0x0F
        val d4 = (b2 and 0xF0) shr 4
        val d5 = b2 and 0x0F
        return "$prefix$d2${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}${d5.toString(16).uppercase()}"
    }

    fun parseDtcs(
        raw: String,
        expectedModeResponse: Int, // 0x43, 0x47, or 0x4A
        isCanProtocol: Boolean,
        hasCanHeaders: Boolean
    ): List<RawDtcEntry> {
        val payloads = extractHexPayloads(raw, expectCanHeaders = hasCanHeaders)
        if (payloads.isEmpty()) return emptyList()

        val result = mutableListOf<RawDtcEntry>()
        for ((ecuId, bytes) in payloads) {
            var pos = 0
            while (pos < bytes.size) {
                if (bytes[pos] != expectedModeResponse) {
                    pos++
                    continue
                }
                if (isCanProtocol || hasCanHeaders) {
                    if (pos + 1 >= bytes.size) break
                    val count = bytes[pos + 1]
                    var cursor = pos + 2
                    var read = 0
                    while (read < count && cursor + 1 < bytes.size) {
                        val code = decodeTwoByteDtc(bytes[cursor], bytes[cursor + 1])
                        if (code != null) {
                            result.add(RawDtcEntry(code = code, ecuAddress = ecuId))
                        }
                        cursor += 2
                        read++
                    }
                    pos = cursor
                } else {
                    // Legacy J1850 / ISO9141 / KWP2000: 3 DTC pairs (6 bytes) per response frame without count byte
                    var cursor = pos + 1
                    var pairs = 0
                    while (pairs < 3 && cursor + 1 < bytes.size) {
                        val code = decodeTwoByteDtc(bytes[cursor], bytes[cursor + 1])
                        if (code != null) {
                            result.add(RawDtcEntry(code = code, ecuAddress = ecuId))
                        }
                        cursor += 2
                        pairs++
                    }
                    pos = cursor
                }
            }
        }
        return result.distinctBy { "${it.ecuAddress}:${it.code}" }
    }

    /**
     * Parses UDS ReadDTCInformation (Service 0x19, subfunction 0x02) -> response `59 02 <statusMask> <hi> <mid> <ftb> <status>...`
     */
    fun parseUdsDtcs(raw: String, ecuAddress: String): List<RawDtcEntry> {
        val payloads = extractHexPayloads(raw, expectCanHeaders = false)
        val bytes = payloads.values.firstOrNull() ?: return emptyList()
        val idx = findSequence(bytes, 0x59, 0x02)
        if (idx < 0 || idx + 3 >= bytes.size) return emptyList()

        val entries = mutableListOf<RawDtcEntry>()
        var cursor = idx + 3 // skip 59 02 availabilityMask
        while (cursor + 3 < bytes.size) {
            val b1 = bytes[cursor]
            val b2 = bytes[cursor + 1]
            // bytes[cursor + 2] is FTB (failure type byte), bytes[cursor + 3] is DTC status byte
            val statusByte = bytes[cursor + 3]
            val code = decodeTwoByteDtc(b1, b2)
            // Include if testFailed(0x01), pending(0x04), confirmed(0x08), or warningIndicator(0x80)
            if (code != null && (statusByte and 0x8D) != 0) {
                entries.add(RawDtcEntry(code = code, ecuAddress = ecuAddress))
            }
            cursor += 4
        }
        return entries.distinctBy { it.code }
    }

    fun parseReadiness(raw: String): ReadinessSummary? {
        val bytes = parseMode01SensorBytes(raw, "01") ?: return null
        if (bytes.size < 4) return null

        val a = bytes[0]
        val b = bytes[1]
        val c = bytes[2]
        val d = bytes[3]

        val milOn = (a and 0x80) != 0
        val dtcCount = a and 0x7F
        val isDiesel = (b and 0x08) != 0

        val monitors = mutableListOf<ReadinessMonitor>()

        fun addMonitor(id: String, nameEn: String, nameAr: String, supBit: Int, incBit: Int, cont: Boolean, supReg: Int, incReg: Int) {
            val supported = (supReg and (1 shl supBit)) != 0
            val incomplete = (incReg and (1 shl incBit)) != 0
            monitors.add(
                ReadinessMonitor(
                    id = id,
                    nameEn = nameEn,
                    nameAr = nameAr,
                    supported = supported,
                    completed = supported && !incomplete,
                    isContinuous = cont
                )
            )
        }

        // Continuous monitors (bits 0..2 of B supported, bits 4..6 of B incomplete)
        addMonitor("MISFIRE", "Misfire Monitoring", "مراقبة التفتفة / الإشعال (Misfire)", 0, 4, true, b, b)
        addMonitor("FUEL", "Fuel System", "نظام الوقود", 1, 5, true, b, b)
        addMonitor("COMPONENTS", "Comprehensive Components", "المكونات الشاملة والحساسات", 2, 6, true, b, b)

        if (!isDiesel) {
            addMonitor("CATALYST", "Catalyst (Catalytic Converter)", "المحفز / دبة البيئة (Catalyst)", 0, 0, false, c, d)
            addMonitor("HEATED_CAT", "Heated Catalyst", "تسخين المحفز", 1, 1, false, c, d)
            addMonitor("EVAP", "Evaporative System (EVAP)", "نظام أبخرة الوقود (EVAP)", 2, 2, false, c, d)
            addMonitor("SEC_AIR", "Secondary Air System", "نظام الهواء الثانوي", 3, 3, false, c, d)
            addMonitor("O2_SENSOR", "Oxygen Sensor (O2)", "حساس الأكسجين (O2)", 5, 5, false, c, d)
            addMonitor("O2_HEATER", "Oxygen Sensor Heater", "سخان حساس الأكسجين", 6, 6, false, c, d)
            addMonitor("EGR_VVT", "EGR / VVT System", "نظام EGR / توقيت الصمامات VVT", 7, 7, false, c, d)
        } else {
            addMonitor("NMHC", "NMHC Catalyst", "محفز NMHC (ديزل)", 0, 0, false, c, d)
            addMonitor("NOX_SCR", "NOx / SCR Aftertreatment", "نظام NOx / SCR (ديزل)", 1, 1, false, c, d)
            addMonitor("BOOST", "Boost Pressure", "ضغط التيربو (Boost)", 3, 3, false, c, d)
            addMonitor("EXH_GAS", "Exhaust Gas Sensor", "حساس غاز العادم", 5, 5, false, c, d)
            addMonitor("PM_FILTER", "PM Filter (DPF)", "فلتر جزيئات الديزل (DPF)", 6, 6, false, c, d)
            addMonitor("EGR_VVT", "EGR / VVT System", "نظام EGR / VVT", 7, 7, false, c, d)
        }

        return ReadinessSummary(
            milOn = milOn,
            ecuReportedDtcCount = dtcCount,
            isDiesel = isDiesel,
            monitors = monitors
        )
    }

    fun parseMode09Ascii(raw: String, pidByte: Int): String? {
        val payloads = extractHexPayloads(raw, expectCanHeaders = false)
        val bytes = payloads.values.firstOrNull() ?: return null
        val idx = findSequence(bytes, 0x49, pidByte)
        if (idx < 0 || idx + 2 >= bytes.size) return null

        // Mode 09 PID 02/04/0A usually has a 1-byte item count (`01`) at idx + 2
        val startOffset = if (bytes[idx + 2] in 1..16) idx + 3 else idx + 2
        val chars = StringBuilder()
        for (i in startOffset until bytes.size) {
            val v = bytes[i]
            if (v in 32..126) {
                chars.append(v.toChar())
            }
        }
        val text = chars.toString().trim()
        return text.ifEmpty { null }
    }

    fun parseMode09Cvn(raw: String): String? {
        val payloads = extractHexPayloads(raw, expectCanHeaders = false)
        val bytes = payloads.values.firstOrNull() ?: return null
        val idx = findSequence(bytes, 0x49, 0x06)
        if (idx < 0 || idx + 3 >= bytes.size) return null
        val startOffset = if (bytes[idx + 2] in 1..8) idx + 3 else idx + 2
        return bytes.slice(startOffset until minOf(bytes.size, startOffset + 4))
            .joinToString("") { it.toString(16).uppercase().padStart(2, '0') }
            .ifEmpty { null }
    }

    fun parseFreezeFrameValue(raw: String, pidHex: String): IntArray? {
        val pidInt = pidHex.toIntOrNull(16) ?: return null
        val payloads = extractHexPayloads(raw, expectCanHeaders = false)
        val bytes = payloads.values.firstOrNull() ?: return null
        // Mode 02 response: `42 <pid> 00 <data...>`
        val idx = findSequence(bytes, 0x42, pidInt)
        if (idx < 0 || idx + 3 >= bytes.size) return null
        // Check if frame number byte `00` follows pid
        val dataStart = if (bytes[idx + 2] == 0x00) idx + 3 else idx + 2
        return if (dataStart < bytes.size) bytes.sliceArray(dataStart until bytes.size) else null
    }

    private fun findSequence(bytes: IntArray, b0: Int, b1: Int): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == b0 && bytes[i + 1] == b1) return i
        }
        return -1
    }
}
