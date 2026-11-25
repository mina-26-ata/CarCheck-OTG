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
import com.carchecker.otg.model.MilResetResult
import com.carchecker.otg.model.ReadinessSummary
import com.carchecker.otg.model.ScanProgress
import com.carchecker.otg.model.VehicleInfo
import kotlinx.coroutines.delay
import java.util.UUID

class ObdCommandEngine(
    private val dtcRepository: DtcRepository,
    private val analyzer: VehicleHealthAnalyzer
) {

    private enum class ObdBusFamily {
        CAN_11BIT,
        CAN_29BIT,
        KWP_2000,
        LEGACY_OTHER
    }

    private var busFamily: ObdBusFamily = ObdBusFamily.CAN_11BIT
    private val isCanProtocol: Boolean
        get() = busFamily == ObdBusFamily.CAN_11BIT || busFamily == ObdBusFamily.CAN_29BIT
    private var supportedPidsCache: Set<String> = emptySet()
    private var fastSingleResponseSupported: Boolean? = null

    suspend fun initializeAdapter(transport: ObdTransport): Result<String> {
        fastSingleResponseSupported = null
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
        val protoName = transport.sendCommand("ATDP", 1200L).getOrNull().orEmpty()
        busFamily = detectBusFamily(protoCode, protoName)

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
        isSimulated: Boolean = false,
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
            readSingleSensor(
                transport = transport,
                def = def,
                unit = unit,
                existing = existing,
                timeoutMs = 1500L,
                allowFastSingleResponse = false
            )?.let { results.add(it) }
        }
        return results
    }

    /**
     * High-frequency cockpit telemetry poller dedicated to the top 3 HUD gauges.
     * Always polls fast-changing throttle/motion PIDs (010C RPM, 010D Speed) with automatic
     * ELM327 single-response optimization (010C1 on CAN -> skips ~200ms multi-ECU wait, with
     * automatic fallback to standard 010C on adapters that do not support the response-count suffix).
     * Slow-changing gauges (0105 Coolant, 0142 Voltage / ATRV) are refreshed periodically.
     */
    suspend fun pollCockpitGaugesOnce(
        transport: ObdTransport,
        unit: MeasurementUnit,
        existing: Map<String, LiveSensorReading> = emptyMap(),
        includeSlowPids: Boolean = false
    ): List<LiveSensorReading> {
        val results = mutableListOf<LiveSensorReading>()

        // Fast PIDs: Engine RPM (0C) always, Vehicle Speed (0D) whenever supported
        ObdPidRegistry.byPidHex["0C"]?.let { def ->
            readSingleSensor(
                transport = transport,
                def = def,
                unit = unit,
                existing = existing,
                timeoutMs = 700L,
                allowFastSingleResponse = true
            )?.let { results.add(it) }
        }

        if (supportedPidsCache.isEmpty() || "0D" in supportedPidsCache) {
            ObdPidRegistry.byPidHex["0D"]?.let { def ->
                readSingleSensor(
                    transport = transport,
                    def = def,
                    unit = unit,
                    existing = existing,
                    timeoutMs = 700L,
                    allowFastSingleResponse = true
                )?.let { results.add(it) }
            }
        }

        if (includeSlowPids) {
            // Coolant Temperature (05)
            if (supportedPidsCache.isEmpty() || "05" in supportedPidsCache) {
                ObdPidRegistry.byPidHex["05"]?.let { def ->
                    readSingleSensor(
                        transport = transport,
                        def = def,
                        unit = unit,
                        existing = existing,
                        timeoutMs = 900L,
                        allowFastSingleResponse = true
                    )?.let { results.add(it) }
                }
            }

            // Battery / Control Module Voltage (0142 with ATRV fallback for ECUs lacking PID 42)
            val voltDef = ObdPidRegistry.byPidHex["42"]
            if (voltDef != null) {
                val pid42Reading = if (supportedPidsCache.isEmpty() || "42" in supportedPidsCache) {
                    readSingleSensor(
                        transport = transport,
                        def = voltDef,
                        unit = unit,
                        existing = existing,
                        timeoutMs = 900L,
                        allowFastSingleResponse = true
                    )
                } else {
                    null
                }
                if (pid42Reading != null) {
                    results.add(pid42Reading)
                } else {
                    val atrvRaw = transport.sendCommand("ATRV", 900L).getOrNull()
                    val parsedVolt = atrvRaw?.let { ObdResponseParser.parseVoltage(it) }
                    if (parsedVolt != null) {
                        results.add(buildSensorReading(voltDef, parsedVolt, unit, existing["42"]))
                    }
                }
            }
        }

        return results
    }

    private suspend fun readSingleSensor(
        transport: ObdTransport,
        def: SensorPidDefinition,
        unit: MeasurementUnit,
        existing: Map<String, LiveSensorReading>,
        timeoutMs: Long,
        allowFastSingleResponse: Boolean
    ): LiveSensorReading? {
        var dataBytes: IntArray? = null
        var triedFastSuffix = false

        if (allowFastSingleResponse && isCanProtocol && fastSingleResponseSupported != false) {
            triedFastSuffix = true
            val fastRaw = transport.sendCommand("01${def.pidHex}1", timeoutMs).getOrNull()
            val fastParsed = fastRaw?.let { ObdResponseParser.parseMode01SensorBytes(it, def.pidHex) }
            if (fastParsed != null) {
                fastSingleResponseSupported = true
                dataBytes = fastParsed
            } else if (fastRaw != null && (fastRaw.trim() == "?" || fastRaw.uppercase().contains("ERROR"))) {
                fastSingleResponseSupported = false
            }
        }

        if (dataBytes == null) {
            val raw = transport.sendCommand("01${def.pidHex}", timeoutMs).getOrNull() ?: return null
            val parsed = ObdResponseParser.parseMode01SensorBytes(raw, def.pidHex) ?: return null
            if (triedFastSuffix) {
                fastSingleResponseSupported = false
            }
            dataBytes = parsed
        }

        val metricValue = def.decodeMetric(dataBytes) ?: return null
        val displayValue = def.valueFor(metricValue, unit)
        return buildSensorReading(def, displayValue, unit, existing[def.pidHex])
    }

    private fun buildSensorReading(
        def: SensorPidDefinition,
        displayValue: Double,
        unit: MeasurementUnit,
        prev: LiveSensorReading?
    ): LiveSensorReading {
        val newHistory = ((prev?.history ?: emptyList()) + displayValue).takeLast(30)
        return LiveSensorReading(
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
    }

    suspend fun clearDtcs(transport: ObdTransport): Result<Unit> {
        val resetRes = forceTurnOffCheckEngine(transport)
        return resetRes.fold(
            onSuccess = { outcome ->
                if (outcome.clearedConfirmed || outcome.milIsNowOff == true) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(outcome.rawDetail.ifBlank { "ECU did not confirm DTC clear" }))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Multi-protocol deep Check Engine (MIL) & DTC clear sequence supporting:
     * - CAN 11-bit: Broadcast Mode 04 + Physical ECM 7E0 Mode 04 + UDS Extended Session (10 03)
     *   followed by ClearDiagnosticInformation (14 FF FF FF) across ECM/TCM/Hybrid/ABS/Cluster
     * - CAN 29-bit: Functional 18DB33F1 Mode 04 + Physical 18DA10F1 Mode 04 / UDS 14 FF FF FF
     * - KWP2000 (ISO 14230-4): Mode 04 + KWP ClearDiagnosticInformation (14 FF 00)
     * - Legacy ISO 9141-2 / J1850: Standard Mode 04 with wake retry
     * Followed by live Mode 01 PID 01 verification of the MIL status bit.
     */
    suspend fun forceTurnOffCheckEngine(transport: ObdTransport): Result<MilResetResult> {
        return try {
            val clearedPaths = linkedSetOf<String>()
            var engineRunningBlocked = false
            var lastRaw = ""

            fun checkNrc22(raw: String) {
                val compact = raw.uppercase().replace(Regex("[^0-9A-F]"), "")
                if (compact.contains("7F0422") || compact.contains("7F1422")) {
                    engineRunningBlocked = true
                }
            }

            fun isPositiveMode04(raw: String): Boolean {
                val upper = raw.uppercase()
                if (upper.contains("7F 04") || upper.contains("7F04")) return false
                return upper.contains("44") || !ObdResponseParser.isErrorOrEmpty(raw)
            }

            fun isPositiveUdsClear(raw: String): Boolean {
                val upper = raw.uppercase()
                if (upper.contains("7F 14") || upper.contains("7F14")) return false
                return upper.contains("54")
            }

            // Ensure protocol family is fresh if clear was invoked before a full scan
            val protoCode = transport.sendCommand("ATDPN", 1000L).getOrNull()?.trim()?.uppercase().orEmpty()
            val protoName = transport.sendCommand("ATDP", 1000L).getOrNull().orEmpty()
            if (protoCode.isNotEmpty() || protoName.isNotEmpty()) {
                busFamily = detectBusFamily(protoCode, protoName)
            }
            transport.sendCommand("ATH0", 900L)

            // Stage 1: Standard OBD-II Mode 04 on protocol-appropriate functional address
            when (busFamily) {
                ObdBusFamily.CAN_11BIT -> transport.sendCommand("ATSH 7DF", 1000L)
                ObdBusFamily.CAN_29BIT -> transport.sendCommand("ATSH 18DB33F1", 1000L)
                else -> Unit
            }

            val stage1Raw = transport.sendCommand("04", 4500L).getOrElse { return Result.failure(it) }
            lastRaw = stage1Raw
            checkNrc22(stage1Raw)
            if (isPositiveMode04(stage1Raw)) {
                clearedPaths.add("Mode 04")
            }

            // Stage 2: Deep protocol-specific clear across physical ECUs & Extended Diagnostic Sessions
            when (busFamily) {
                ObdBusFamily.CAN_11BIT -> {
                    // Direct physical ECM Mode 04
                    transport.sendCommand("ATSH 7E0", 900L)
                    val phys04 = transport.sendCommand("04", 3500L).getOrNull().orEmpty()
                    if (phys04.isNotBlank()) lastRaw = phys04
                    checkNrc22(phys04)
                    if (isPositiveMode04(phys04)) {
                        clearedPaths.add("ECM 7E0 Mode 04")
                    }

                    // UDS Extended Session (10 03) + ClearDiagnosticInformation (14 FF FF FF) across key modules
                    val udsModules = listOf("7E0", "7E1", "7E2", "7D1", "7B0", "760", "7C6")
                    for (addr in udsModules) {
                        transport.sendCommand("ATSH $addr", 900L)
                        val sessionResp = transport.sendCommand("10 03", 1200L).getOrNull().orEmpty()
                        val enteredExt = sessionResp.uppercase().replace(" ", "").contains("5003")

                        val udsClear = transport.sendCommand("14 FF FF FF", 2000L).getOrNull().orEmpty()
                        checkNrc22(udsClear)
                        if (isPositiveUdsClear(udsClear)) {
                            clearedPaths.add("UDS $addr")
                        } else if (addr == "7E0" && enteredExt) {
                            // Some ECMs accept Mode 04 inside Extended Session after rejecting default session
                            val retry04 = transport.sendCommand("04", 2500L).getOrNull().orEmpty()
                            checkNrc22(retry04)
                            if (isPositiveMode04(retry04)) {
                                clearedPaths.add("ECM 7E0 Ext Mode 04")
                            }
                        }

                        if (enteredExt) {
                            transport.sendCommand("10 01", 800L)
                        }
                    }
                    transport.sendCommand("ATSH 7DF", 1000L)
                }

                ObdBusFamily.CAN_29BIT -> {
                    transport.sendCommand("ATSH 18DA10F1", 1000L)
                    val phys04 = transport.sendCommand("04", 3500L).getOrNull().orEmpty()
                    checkNrc22(phys04)
                    if (isPositiveMode04(phys04)) {
                        clearedPaths.add("ECM 29-bit Mode 04")
                    }
                    val sess = transport.sendCommand("10 03", 1200L).getOrNull().orEmpty()
                    val enteredExt = sess.uppercase().replace(" ", "").contains("5003")
                    val udsClear = transport.sendCommand("14 FF FF FF", 2000L).getOrNull().orEmpty()
                    checkNrc22(udsClear)
                    if (isPositiveUdsClear(udsClear)) {
                        clearedPaths.add("UDS 29-bit ECM")
                    }
                    if (enteredExt) {
                        transport.sendCommand("10 01", 800L)
                    }
                    transport.sendCommand("ATSH 18DB33F1", 1000L)
                }

                ObdBusFamily.KWP_2000 -> {
                    // KWP2000 ClearDiagnosticInformation (ISO 14230-4: 14 FF 00) + follow-up Mode 04
                    val kwpClear = transport.sendCommand("14 FF 00", 3000L).getOrNull().orEmpty()
                    checkNrc22(kwpClear)
                    if (isPositiveUdsClear(kwpClear)) {
                        clearedPaths.add("KWP2000 14 FF 00")
                    }
                    if (clearedPaths.isEmpty()) {
                        delay(250)
                        val retry04 = transport.sendCommand("04", 3500L).getOrNull().orEmpty()
                        checkNrc22(retry04)
                        if (isPositiveMode04(retry04)) {
                            clearedPaths.add("KWP2000 Mode 04")
                        }
                    }
                }

                ObdBusFamily.LEGACY_OTHER -> {
                    if (clearedPaths.isEmpty()) {
                        delay(300)
                        val retry04 = transport.sendCommand("04", 3500L).getOrNull().orEmpty()
                        checkNrc22(retry04)
                        if (isPositiveMode04(retry04)) {
                            clearedPaths.add("Legacy Mode 04")
                        }
                    }
                }
            }

            // Stage 3: Verify actual MIL lamp bit via live Mode 01 PID 01
            delay(400)
            val readinessRaw = transport.sendCommand("0101", 2500L).getOrNull()
            val readinessAfter = readinessRaw?.let { ObdResponseParser.parseReadiness(it) }
            val milNowOff = readinessAfter?.let { !it.milOn }

            Result.success(
                MilResetResult(
                    clearedConfirmed = clearedPaths.isNotEmpty(),
                    milIsNowOff = milNowOff,
                    engineRunningBlocked = engineRunningBlocked,
                    clearedPaths = clearedPaths.toList(),
                    rawDetail = lastRaw
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
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

        // Safe UDS ReadDTC probe (19 02 AF) on common 11-bit CAN physical modules
        if (busFamily == ObdBusFamily.CAN_11BIT) {
            val udsTargets = listOf(
                "7E1" to "7E9", // TCM
                "7E2" to "7EA", // Hybrid / Aux Powertrain
                "7D1" to "7D9", // ABS / ESC (Asian/Korean)
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

    private fun detectBusFamily(protoCode: String, protoName: String): ObdBusFamily {
        val cleanCode = protoCode.removePrefix("A")
        val upperName = protoName.uppercase()
        return when {
            cleanCode in listOf("7", "9", "C") || upperName.contains("29/") -> ObdBusFamily.CAN_29BIT
            cleanCode in listOf("6", "8", "A", "B") || upperName.contains("CAN") || upperName.contains("15765") -> ObdBusFamily.CAN_11BIT
            cleanCode in listOf("4", "5") || upperName.contains("14230") || upperName.contains("KWP") -> ObdBusFamily.KWP_2000
            else -> ObdBusFamily.LEGACY_OTHER
        }
    }

    private fun mapEcuAddress(addr: String): DetectedEcu = when (addr.uppercase()) {
        "7E8" -> DetectedEcu("7E8", "Engine Control Module (ECM)", "كمبيوتر المحرك (ECM)", "OBD-II CAN")
        "7EA" -> DetectedEcu("7EA", "Transmission / Secondary ECU", "كمبيوتر ناقل الحركة / وحدة مساعدة", "OBD-II CAN")
        "7E9" -> DetectedEcu("7E9", "Transmission / Hybrid ECU", "وحدة القير أو النظام الهجين", "UDS / CAN")
        "7EB" -> DetectedEcu("7EB", "Hybrid / Battery Management ECU", "وحدة البطارية / الهجين", "OBD-II CAN")
        "7D9", "7D1" -> DetectedEcu(addr, "ABS / Stability Control (ABS/ESC)", "وحدة مانع الانغلاق والثبات (ABS/ESC)", "UDS CAN")
        "7B8", "7B0" -> DetectedEcu(addr, "ABS / Stability Control (ABS/VSC)", "وحدة مانع الانغلاق والثبات (ABS)", "UDS CAN")
        "768", "760" -> DetectedEcu(addr, "ABS / Brake Control Module", "وحدة الفرامل ABS", "UDS CAN")
        "7C6", "7CE" -> DetectedEcu(addr, "Instrument Cluster (CLU)", "وحدة لوحة العدادات", "UDS CAN")
        else -> DetectedEcu(addr, "Control Module ($addr)", "وحدة تحكم ($addr)", "CAN ECU")
    }
}
