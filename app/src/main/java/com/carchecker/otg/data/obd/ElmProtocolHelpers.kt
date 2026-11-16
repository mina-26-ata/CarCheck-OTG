package com.carchecker.otg.data.obd

/**
 * Pure ELM327 / OBD-II protocol helpers shared across USB OTG, Bluetooth Classic (SPP),
 * and Bluetooth Low Energy (BLE GATT) transports.
 */
object ElmProtocolHelpers {

    data class GattCharCandidate(
        val serviceUuid: String,
        val charUuid: String,
        val canNotify: Boolean,
        val canIndicate: Boolean,
        val canWrite: Boolean
    )

    data class SelectedBleEndpoints(
        val serviceUuid: String,
        val writeCharUuid: String,
        val notifyCharUuid: String,
        val preferIndicate: Boolean
    )

    private val obdNameKeywords = listOf(
        "OBD", "ELM", "EML327", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK",
        "CARISTA", "KONNWEI", "BAFX", "SCAN", "AUTEL", "THINKCAR", "MUCAR",
        "TORQUE", "KW90", "TONWON", "NEXAS", "FIXD", "BLUEDRIVER"
    )

    private val standardBleGattServices = setOf(
        "00001800-0000-1000-8000-00805f9b34fb", // Generic Access
        "00001801-0000-1000-8000-00805f9b34fb", // Generic Attribute
        "0000180a-0000-1000-8000-00805f9b34fb", // Device Info
        "0000180f-0000-1000-8000-00805f9b34fb"  // Battery Service
    )

    private data class KnownBleProfile(
        val servicePrefix: String,
        val writeUuidPrefix: String,
        val notifyUuidPrefix: String
    )

    // Ordered by real-world prevalence across ELM327 BLE 4.0, Vgate, Veepeak, TI CC2541 & Nordic dongles
    private val knownBleProfiles = listOf(
        // Generic ELM327 BLE 4.0 / iOS+Android adapters (FFF0 service)
        KnownBleProfile("0000fff0", "0000fff2", "0000fff1"),
        KnownBleProfile("0000fff0", "0000fff1", "0000fff1"),
        // Vgate iCar Pro BLE / Veepeak BLE (18F0 service)
        KnownBleProfile("000018f0", "00002af1", "00002af0"),
        // TI CC2541 / HM-10 serial-over-BLE clones (FFE0 service)
        KnownBleProfile("0000ffe0", "0000ffe1", "0000ffe1"),
        KnownBleProfile("0000ffe0", "0000ffe2", "0000ffe1"),
        // Nordic UART Service (NUS — OBDLink CX, modern BLE adapters)
        KnownBleProfile("6e400001", "6e400002", "6e400003"),
        // Microchip ISSC Transparent UART
        KnownBleProfile("49535343-fe7d-4ae5-8fa9-9fafd205e455", "49535343-8841-43f4-a8d4-ecbe34729bb3", "49535343-1e4d-4bd9-ba61-23c647249616")
    )

    fun looksLikeObdDeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val upper = name.uppercase()
        return obdNameKeywords.any { upper.contains(it) }
    }

    fun isElmPromptReady(buffer: CharSequence): Boolean {
        for (i in 0 until buffer.length) {
            if (buffer[i] == '>') return true
        }
        return false
    }

    fun cleanElmResponse(raw: String, sentCommand: String? = null): String {
        val cleaned = raw
            .replace(">", "")
            .replace("\u0000", "")
            .trim()
        if (sentCommand.isNullOrBlank()) return cleaned
        val target = sentCommand.trim().uppercase()
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val withoutEcho = if (lines.firstOrNull()?.uppercase() == target) {
            lines.drop(1)
        } else {
            lines
        }
        return withoutEcho.joinToString("\n").trim()
    }

    fun extractElmFirmwareLabel(rawAtiResponse: String, fallback: String): String {
        val upper = rawAtiResponse.uppercase()
        if (!(upper.contains("ELM") || upper.contains("STN") || upper.contains("OBD") || upper.contains("OK") || upper.contains(">"))) {
            return fallback
        }
        return rawAtiResponse.lines()
            .map { it.trim().replace(">", "") }
            .firstOrNull { it.isNotBlank() && !it.equals("ATI", ignoreCase = true) }
            ?: fallback
    }

    fun looksLikeValidAtiResponse(rawAtiResponse: String): Boolean {
        val upper = rawAtiResponse.uppercase()
        return upper.contains("ELM") || upper.contains("STN") || upper.contains("OBD") || upper.contains("OK") || upper.contains(">")
    }

    fun selectBestBleEndpoints(candidates: List<GattCharCandidate>): SelectedBleEndpoints? {
        if (candidates.isEmpty()) return null
        val byService = candidates.groupBy { it.serviceUuid.lowercase() }

        // 1. Check known ELM327 BLE service/characteristic profiles first
        for (profile in knownBleProfiles) {
            val matchingService = byService.entries.firstOrNull { (svc, _) ->
                svc.startsWith(profile.servicePrefix.lowercase())
            } ?: continue
            val chars = matchingService.value
            val writeChar = chars.firstOrNull {
                it.canWrite && it.charUuid.lowercase().startsWith(profile.writeUuidPrefix.lowercase())
            }
            val notifyChar = chars.firstOrNull {
                (it.canNotify || it.canIndicate) && it.charUuid.lowercase().startsWith(profile.notifyUuidPrefix.lowercase())
            }
            if (writeChar != null && notifyChar != null) {
                return SelectedBleEndpoints(
                    serviceUuid = matchingService.key,
                    writeCharUuid = writeChar.charUuid,
                    notifyCharUuid = notifyChar.charUuid,
                    preferIndicate = !notifyChar.canNotify && notifyChar.canIndicate
                )
            }
        }

        // 2. Fallback: pick any non-standard service containing both writeable + notifiable characteristics
        for ((svc, chars) in byService) {
            if (svc in standardBleGattServices) continue
            val writeChar = chars.firstOrNull { it.canWrite } ?: continue
            val notifyChar = chars.firstOrNull { it.canNotify || it.canIndicate } ?: continue
            return SelectedBleEndpoints(
                serviceUuid = svc,
                writeCharUuid = writeChar.charUuid,
                notifyCharUuid = notifyChar.charUuid,
                preferIndicate = !notifyChar.canNotify && notifyChar.canIndicate
            )
        }

        return null
    }
}
