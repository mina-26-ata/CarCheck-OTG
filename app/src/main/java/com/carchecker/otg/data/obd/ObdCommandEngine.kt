package com.carchecker.otg.data.obd

import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.domain.analyzer.VehicleHealthAnalyzer
import com.carchecker.otg.model.DetectedEcu
import com.carchecker.otg.model.DiagnosedDtc
import com.carchecker.otg.model.DtcStatus
import com.carchecker.otg.model.FreezeFrameReading
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.ReadinessSummary
import com.carchecker.otg.model.ScanProgress
import com.carchecker.otg.model.VehicleInfo
import kotlinx.coroutines.delay
import java.util.UUID

class ObdCommandEngine(
    private val dtcRepository: DtcRepository,
    private val analyzer: VehicleHealthAnalyzer
) {

    private var isCanProtocol = true
    private var supportedPidsCache: Set<String> = emptySet()

    suspend fun initializeAdapter(transport: ObdTransport): Result<String> {
        val atz = transport.sendCommand("ATZ", 2500L)
        if (atz.isFailure) return atz
        delay(250)
        transport.sendCommand("ATE0", 1200L)
        transport.sendCommand("ATL0", 1200L)
        transport.sendCommand("ATS0", 1200L)
        transport.sendCommand("ATH0", 1200L)
        transport.sendCommand("ATSP0", 2000L)

        // Probe vehicle connection with 0100
        val probe = transport.sendCommand("0100", 6000L)
        val probeText = probe.getOrElse { "" }
        if (ObdResponseParser.isErrorOrEmpty(probeText)) {
            return Result.failure(
                IllegalStateException("Adapter initialized, but vehicle ECU did not respond. Ensure ignition is ON (KOEO or Engine Running).")
            )
        }

        val protoCode = transport.sendCommand("ATDPN", 1200L).getOrNull()?.trim()?.uppercase().orEmpty()
        isCanProtocol = detectIsCan(protoCode, transport.sendCommand("ATDP", 1200L).getOrNull().orEmpty())

        // Discover all supported Mode 01 PIDs
        val supported = linkedSetOf<String>()
        supported.addAll(ObdResponseParser.parseSupportedPids(probeText, 0x00))
        if ("20" in supported) {
            transport.sendCommand("0120", 2500L).getOrNull()?.let {
                supported.addAll(ObdResponseParser.parseSupportedPids(it, 0x20))
            }
        }
        if ("40" in supported) {
            transport.sendCommand("0140", 2500L).getOrNull()?.let {
                supported.addAll(ObdResponseParser.parseSupportedPids(it, 0x40))
            }
        }
        if ("60" in supported) {
            transport.sendCommand("0160", 2500L).getOrNull()?.let {
                supported.addAll(ObdResponseParser.parseSupportedPids(it, 0x60))
            }
        }
        if ("80" in supported) {
            transport.sendCommand("0180", 2500L).getOrNull()?.let {
                supported.addAll(ObdResponseParser.parseSupportedPids(it, 0x80))
            }
        }
        if ("A0" in supported) {
            transport.sendCommand("01A0", 2500L).getOrNull()?.let {
                supported.addAll(ObdResponseParser.parseSupportedPids(it, 0xA0))
            }
        }
        supportedPidsCache = supported
        return Result.success(transport.description)
    }

    fun getSupportedSensorDefinitions(): List<SensorPidDefinition> {
        if (supportedPidsCache.isEmpty()) {
            // Return primary common sensors if cache empty
            return ObdPidRegistry.sensors.take(16)
        }
        val filtered = ObdPidRegistry.sensors.filter { it.pidHex in supportedPidsCache }
        return filtered.ifEmpty { ObdPidRegistry.sensors.take(12) }
    }

    suspend fun runFullScan(
        transport: ObdTransport,
        unit: MeasurementUnit,
        isSimulated: Boolean,
        onProgress: suspend (ScanProgress) -> Unit
    ): Result<FullScanReport> {
        return try {
            onProgress(ScanProgress(true, 1, 7, "Initializing OBD-II adapter & protocol...", "تهيئة وصلة الفحص وبروتوكول السيارة..."))
            val initResult = initializeAdapter(transport)
            if (initResult.isFailure) {
                return Result.failure(initResult.exceptionOrNull() ?: IllegalStateException("Init failed"))
            }

            onProgress(ScanProgress(true, 2, 7, "Reading Vehicle Identity (VIN, Protocol, Voltage)...", "قراءة هوية السيارة (رقم الشاسيه والجهد والبروتوكول)..."))
            val vehicleInfo = readVehicleInfo(transport)

            onProgress(ScanProgress(true, 3, 7, "Scanning Control Modules (ECM, TCM, ABS/UDS)...", "مسح وحدات التحكم بالسيارة (المحرك، القير، الأنظمة)..."))
            val ecus = discoverEcus(transport)
            val updatedVehicleInfo = vehicleInfo.copy(detectedEcus = ecus)

            onProgress(ScanProgress(true, 4, 7, "Reading Confirmed, Pending & Permanent Faults...", "قراءة الأعطال المؤكدة والمعلقة والدائمة..."))
            val dtcs = readAllDtcs(transport, ecus)

            onProgress(ScanProgress(true, 5, 7, "Checking I/M Readiness Monitors & Freeze Frame...", "فحص جاهزية الأنظمة ولقطة تجميد الأعطال..."))
            val readiness = readReadinessSummary(transport)
            val freezeFrame = if (dtcs.isNotEmpty()) readFreezeFrame(transport, unit) else emptyList()

            onProgress(ScanProgress(true, 6, 7, "Sampling Live Sensors & Telemetry...", "أخذ قراءات الحساسات والبيانات الحية..."))
            val sensors = pollSensorsOnce(transport, unit)

            onProgress(ScanProgress(true, 7, 7, "Analyzing Vehicle Health & Generating Solutions...", "تحليل حالة السيارة وتجهيز الملاحظات وطرق الحل..."))
            val analysis = analyzer.analyze(
                vehicleInfo = updatedVehicleInfo,
                dtcs = dtcs,
                readiness = readiness,
                sensors = sensors,
                unit = unit
            )

            val report = FullScanReport(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                isSimulated = isSimulated,
                adapterDescription = transport.description,
                healthScore = analysis.healthScore,
                vehicleInfo = updatedVehicleInfo,
                dtcs = dtcs,
                freezeFrame = freezeFrame,
                readiness = readiness,
                sensorSnapshot = sensors,
                observations = analysis.observations
            )

            Result.success(report)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            onProgress(ScanProgress(isScanning = false))
        }
    }

    suspend fun pollSensorsOnce(
        transport: ObdTransport,
        unit: MeasurementUnit,
        existing: Map<String, LiveSensorReading> = emptyMap(),
        priorityPidsOnly: Boolean = false
    ): List<LiveSensorReading> {
        val defs = getSupportedSensorDefinitions().let { list ->
            if (priorityPidsOnly) list.take(12) else list
        }
        val results = mutableListOf<LiveSensorReading>()
        for (def in defs) {
            val raw = transport.sendCommand("01${def.pidHex}", 1500L).getOrNull() ?: continue
            val dataBytes = ObdResponseParser.parseMode01SensorBytes(raw, def.pidHex) ?: continue
            val metricValue = def.decodeMetric(dataBytes) ?: continue
            val displayValue = def.valueFor(metricValue, unit)
            val prev = existing[def.pidHex]
            val newHistory = ((prev?.history ?: emptyList()) + displayValue).takeLast(30)
            results.add(
                LiveSensorReading(
                    pidHex = def.pidHex,
                    nameEn = def.nameEn,
                    nameAr = def.nameAr,
                    value = displayValue,
                    unit = def.unitFor(unit),
                    category = def.category,
                    normalMin = def.minFor(unit),
                    normalMax = def.maxFor(unit),
                    minRecorded = minOf(prev?.minRecorded ?: displayValue, displayValue),
                    maxRecorded = maxOf(prev?.maxRecorded ?: displayValue, displayValue),
                    history = newHistory,
                    decimals = def.decimals,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
        return results
    }

    suspend fun clearDtcs(transport: ObdTransport): Result<Unit> {
        if (isCanProtocol) {
            transport.sendCommand("ATSH 7DF", 1000L)
        }
        val res = transport.sendCommand("04", 4500L)
        val body = res.getOrElse { return Result.failure(it) }
        var cleared = body.uppercase().contains("44") || !ObdResponseParser.isErrorOrEmpty(body)

        // Fallback physical ECM clear on 7E0 if broadcast was not acknowledged cleanly
        if (!cleared && isCanProtocol) {
            transport.sendCommand("ATSH 7E0", 1000L)
            val physBody = transport.sendCommand("04", 3500L).getOrNull().orEmpty()
            cleared = physBody.uppercase().contains("44") || !ObdResponseParser.isErrorOrEmpty(physBody)
        }

        // Best-effort UDS ClearDiagnosticInformation (14 FF FF FF) across CAN modules (ECM/TCM/ABS)
        if (isCanProtocol) {
            val udsTargets = listOf("7E0", "7E1", "7B0", "760")
            for (addr in udsTargets) {
                transport.sendCommand("ATSH $addr", 900L)
                val udsResp = transport.sendCommand("14 FF FF FF", 1800L).getOrNull().orEmpty()
                if (udsResp.uppercase().contains("54")) {
                    cleared = true
                }
            }
            transport.sendCommand("ATSH 7DF", 1000L)
        }

        return if (cleared) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("ECU refused or did not confirm DTC clear: $body"))
        }
    }

    private suspend fun readVehicleInfo(transport: ObdTransport): VehicleInfo {
        val protocolName = transport.sendCommand("ATDP", 1200L).getOrNull()?.trim()?.takeIf { !ObdResponseParser.isErrorOrEmpty(it) }
        val protocolCode = transport.sendCommand("ATDPN", 1200L).getOrNull()?.trim()?.takeIf { !ObdResponseParser.isErrorOrEmpty(it) }
        val atrvRaw = transport.sendCommand("ATRV", 1200L).getOrNull()
        val atrvVoltage = atrvRaw?.let { ObdResponseParser.parseVoltage(it) }

        val moduleVoltage = if ("42" in supportedPidsCache) {
            transport.sendCommand("0142", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "42") }
                ?.let { ObdPidRegistry.byPidHex["42"]?.decodeMetric?.invoke(it) }
        } else null

        val odometerKm = if ("A6" in supportedPidsCache) {
            transport.sendCommand("01A6", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "A6") }
                ?.let { ObdPidRegistry.byPidHex["A6"]?.decodeMetric?.invoke(it) }
        } else null

        val vinRaw = transport.sendCommand("0902", 3500L).getOrNull()
        val vinString = vinRaw?.let { ObdResponseParser.parseMode09Ascii(it, 0x02) }
        val decodedVin = vinString?.let { VinDecoder.decode(it) }

        val calId = transport.sendCommand("0904", 2500L).getOrNull()
            ?.let { ObdResponseParser.parseMode09Ascii(it, 0x04) }
        val cvn = transport.sendCommand("0906", 2500L).getOrNull()
            ?.let { ObdResponseParser.parseMode09Cvn(it) }
        val ecuName = transport.sendCommand("090A", 2500L).getOrNull()
            ?.let { ObdResponseParser.parseMode09Ascii(it, 0x0A) }

        return VehicleInfo(
            vin = decodedVin?.vin ?: vinString,
            make = decodedVin?.make,
            country = decodedVin?.country,
            modelYear = decodedVin?.modelYear,
            protocolName = protocolName,
            protocolCode = protocolCode,
            calibrationId = calId,
            cvn = cvn,
            ecuName = ecuName,
            batteryVoltage = moduleVoltage ?: atrvVoltage,
            odometerKm = odometerKm
        )
    }

    private suspend fun discoverEcus(transport: ObdTransport): List<DetectedEcu> {
        val found = linkedMapOf<String, DetectedEcu>()
        if (isCanProtocol) {
            transport.sendCommand("ATH1", 1200L)
            val raw = transport.sendCommand("0100", 3000L).getOrNull().orEmpty()
            val payloads = ObdResponseParser.extractHexPayloads(raw, expectCanHeaders = true)
            for (addr in payloads.keys) {
                found[addr] = mapEcuAddress(addr)
            }
            transport.sendCommand("ATH0", 1200L)
        }
        if (found.isEmpty()) {
            found["7E8"] = mapEcuAddress("7E8")
        }
        return found.values.toList()
    }

    private suspend fun readAllDtcs(
        transport: ObdTransport,
        ecus: List<DetectedEcu>
    ): List<DiagnosedDtc> {
        val all = mutableListOf<DiagnosedDtc>()

        suspend fun queryMode(cmd: String, respByte: Int, status: DtcStatus) {
            if (isCanProtocol) {
                transport.sendCommand("ATH1", 1200L)
            }
            val raw = transport.sendCommand(cmd, 4500L).getOrNull().orEmpty()
            if (isCanProtocol) {
                transport.sendCommand("ATH0", 1200L)
            }
            val parsed = ObdResponseParser.parseDtcs(
                raw = raw,
                expectedModeResponse = respByte,
                isCanProtocol = isCanProtocol,
                hasCanHeaders = isCanProtocol
            )
            for (entry in parsed) {
                val ecu = mapEcuAddress(entry.ecuAddress)
                all.add(
                    DiagnosedDtc(
                        code = entry.code,
                        status = status,
                        ecuAddress = entry.ecuAddress,
                        ecuNameEn = ecu.nameEn,
                        ecuNameAr = ecu.nameAr,
                        knowledge = dtcRepository.lookup(entry.code)
                    )
                )
            }
        }

        queryMode("03", 0x43, DtcStatus.CONFIRMED)
        queryMode("07", 0x47, DtcStatus.PENDING)
        queryMode("0A", 0x4A, DtcStatus.PERMANENT)

        // Safe UDS ReadDTC probe (19 02 AF) on common CAN physical modules if CAN is active
        if (isCanProtocol) {
            val udsTargets = listOf(
                "7E1" to "7E9", // TCM
                "7B0" to "7B8", // ABS / VSC (Toyota/Asian)
                "760" to "768"  // ABS / ESC (Ford/GM/Mazda)
            )
            for ((reqAddr, respAddr) in udsTargets) {
                transport.sendCommand("ATSH $reqAddr", 1000L)
                val udsRaw = transport.sendCommand("19 02 AF", 1500L).getOrNull().orEmpty()
                if (!ObdResponseParser.isErrorOrEmpty(udsRaw)) {
                    val udsDtcs = ObdResponseParser.parseUdsDtcs(udsRaw, respAddr)
                    val ecu = mapEcuAddress(respAddr)
                    for (entry in udsDtcs) {
                        if (all.none { it.code == entry.code && it.ecuAddress == entry.ecuAddress }) {
                            all.add(
                                DiagnosedDtc(
                                    code = entry.code,
                                    status = DtcStatus.STORED_UDS,
                                    ecuAddress = respAddr,
                                    ecuNameEn = ecu.nameEn,
                                    ecuNameAr = ecu.nameAr,
                                    knowledge = dtcRepository.lookup(entry.code)
                                )
                            )
                        }
                    }
                }
            }
            transport.sendCommand("ATSH 7DF", 1000L)
        }

        // Ensure default Engine ECU appears if ecus list missing UDS ones? Kept clean in report.
        return all.distinctBy { "${it.code}:${it.status}:${it.ecuAddress}" }
    }

    private suspend fun readReadinessSummary(transport: ObdTransport): ReadinessSummary? {
        val raw01 = transport.sendCommand("0101", 2500L).getOrNull() ?: return null
        val base = ObdResponseParser.parseReadiness(raw01) ?: return null

        val distMil = if ("21" in supportedPidsCache) {
            transport.sendCommand("0121", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "21") }
                ?.takeIf { it.size >= 2 }
                ?.let { (it[0] shl 8) or it[1] }
        } else null

        val warmups = if ("30" in supportedPidsCache) {
            transport.sendCommand("0130", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "30") }
                ?.firstOrNull()
        } else null

        val distClear = if ("31" in supportedPidsCache) {
            transport.sendCommand("0131", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "31") }
                ?.takeIf { it.size >= 2 }
                ?.let { (it[0] shl 8) or it[1] }
        } else null

        val timeMil = if ("4D" in supportedPidsCache) {
            transport.sendCommand("014D", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "4D") }
                ?.takeIf { it.size >= 2 }
                ?.let { (it[0] shl 8) or it[1] }
        } else null

        val timeClear = if ("4E" in supportedPidsCache) {
            transport.sendCommand("014E", 1500L).getOrNull()
                ?.let { ObdResponseParser.parseMode01SensorBytes(it, "4E") }
                ?.takeIf { it.size >= 2 }
                ?.let { (it[0] shl 8) or it[1] }
        } else null

        return base.copy(
            distanceWithMilKm = distMil,
            warmupsSinceClear = warmups,
            distanceSinceClearKm = distClear,
            timeWithMilMinutes = timeMil,
            timeSinceClearMinutes = timeClear
        )
    }

    private suspend fun readFreezeFrame(
        transport: ObdTransport,
        unit: MeasurementUnit
    ): List<FreezeFrameReading> {
        val list = mutableListOf<FreezeFrameReading>()
        val dtcRaw = transport.sendCommand("02 02 00", 1500L).getOrNull()
        dtcRaw?.let { ObdResponseParser.parseFreezeFrameValue(it, "02") }?.takeIf { it.size >= 2 }?.let { b ->
            ObdResponseParser.decodeTwoByteDtc(b[0], b[1])?.let { code ->
                list.add(FreezeFrameReading("02", "Triggering DTC", "كود العطل المسجل", code, ""))
            }
        }

        val ffPids = listOf("0C", "0D", "05", "04", "06", "07", "0F", "11", "42")
        for (pid in ffPids) {
            val def = ObdPidRegistry.byPidHex[pid] ?: continue
            val raw = transport.sendCommand("02 $pid 00", 1200L).getOrNull() ?: continue
            val bytes = ObdResponseParser.parseFreezeFrameValue(raw, pid) ?: continue
            val metricVal = def.decodeMetric(bytes) ?: continue
            val valConverted = def.valueFor(metricVal, unit)
            val formatted = String.format(java.util.Locale.US, "%.${def.decimals}f", valConverted)
            list.add(
                FreezeFrameReading(
                    pidHex = pid,
                    labelEn = def.nameEn,
                    labelAr = def.nameAr,
                    valueDisplay = formatted,
                    unit = def.unitFor(unit)
                )
            )
        }
        return list
    }

    private fun detectIsCan(protoCode: String, protoName: String): Boolean {
        val cleanCode = protoCode.removePrefix("A")
        if (cleanCode in listOf("6", "7", "8", "9", "A", "B", "C")) return true
        return protoName.contains("CAN", ignoreCase = true) || protoName.contains("15765", ignoreCase = true)
    }

    private fun mapEcuAddress(addr: String): DetectedEcu = when (addr.uppercase()) {
        "7E8" -> DetectedEcu("7E8", "Engine Control Module (ECM)", "كمبيوتر المحرك (ECM)", "OBD-II CAN")
        "7EA" -> DetectedEcu("7EA", "Transmission Control Module (TCM)", "كمبيوتر ناقل الحركة / القير (TCM)", "OBD-II CAN")
        "7E9" -> DetectedEcu("7E9", "Transmission / Hybrid ECU", "وحدة القير أو النظام الهجين", "UDS / CAN")
        "7EB" -> DetectedEcu("7EB", "Hybrid / Battery Management ECU", "وحدة البطارية / الهجين", "OBD-II CAN")
        "7B8", "7B0" -> DetectedEcu(addr, "ABS / Stability Control (ABS/VSC)", "وحدة مانع الانغلاق والثبات (ABS)", "UDS CAN")
        "768", "760" -> DetectedEcu(addr, "ABS / Brake Control Module", "وحدة الفرامل ABS", "UDS CAN")
        else -> DetectedEcu(addr, "Control Module ($addr)", "وحدة تحكم ($addr)", "CAN ECU")
    }
}
