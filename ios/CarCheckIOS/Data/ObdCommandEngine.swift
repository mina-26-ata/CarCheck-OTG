import Foundation

final class ObdCommandEngine {
    private enum BusFamily {
        case can11Bit, can29Bit, kwp2000, legacyOther
    }

    private let dtcRepo: DtcRepository
    private var busFamily: BusFamily = .can11Bit
    private var supportedPidsCache: Set<String> = []
    private var fastSingleResponseSupported: Bool? = nil

    private var isCanProtocol: Bool {
        busFamily == .can11Bit || busFamily == .can29Bit
    }

    init(dtcRepo: DtcRepository) {
        self.dtcRepo = dtcRepo
    }

    func initializeAdapter(_ transport: ObdTransport) async throws {
        fastSingleResponseSupported = nil
        _ = try await transport.sendCommand("ATZ", timeoutMs: 2500)
        try? await Task.sleep(nanoseconds: 220_000_000)
        _ = try? await transport.sendCommand("ATE0", timeoutMs: 1200)
        _ = try? await transport.sendCommand("ATL0", timeoutMs: 1200)
        _ = try? await transport.sendCommand("ATS0", timeoutMs: 1200)
        _ = try? await transport.sendCommand("ATH0", timeoutMs: 1200)
        _ = try? await transport.sendCommand("ATSP0", timeoutMs: 2000)

        let probe = (try? await transport.sendCommand("0100", timeoutMs: 6000)) ?? ""
        if ObdResponseParser.isErrorOrEmpty(probe) {
            throw ObdTransportError.connectionFailed("تم الاتصال بالقطعة، لكن كمبيوتر السيارة لم يستجب. تأكد من فتح السويتش (ON) أو تشغيل المحرك.")
        }

        let protoCode = ((try? await transport.sendCommand("ATDPN", timeoutMs: 1200)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let protoName = (try? await transport.sendCommand("ATDP", timeoutMs: 1200)) ?? ""
        busFamily = Self.detectBusFamily(code: protoCode, name: protoName)

        var supported = ObdResponseParser.parseSupportedPids(probe, basePid: 0x00)
        if supported.contains("20"), let r20 = try? await transport.sendCommand("0120", timeoutMs: 2200) {
            supported.formUnion(ObdResponseParser.parseSupportedPids(r20, basePid: 0x20))
        }
        if supported.contains("40"), let r40 = try? await transport.sendCommand("0140", timeoutMs: 2200) {
            supported.formUnion(ObdResponseParser.parseSupportedPids(r40, basePid: 0x40))
        }
        supportedPidsCache = supported
    }

    func runFullScan(
        transport: ObdTransport,
        unit: MeasurementUnit,
        onProgress: @Sendable (ScanProgress) async -> Void
    ) async throws -> FullScanReport {
        await onProgress(ScanProgress(isScanning: true, stepIndex: 1, totalSteps: 7, stepLabelEn: "Initializing OBD-II adapter...", stepLabelAr: "تهيئة وصلة الفحص وبروتوكول السيارة..."))
        try await initializeAdapter(transport)

        await onProgress(ScanProgress(isScanning: true, stepIndex: 2, totalSteps: 7, stepLabelEn: "Reading Vehicle Identity (VIN & Voltage)...", stepLabelAr: "قراءة هوية السيارة (رقم الشاسيه والجهد)..."))
        var vInfo = await readVehicleInfo(transport)

        await onProgress(ScanProgress(isScanning: true, stepIndex: 3, totalSteps: 7, stepLabelEn: "Scanning Control Modules (ECM, TCM, ABS)...", stepLabelAr: "مسح وحدات التحكم بالسيارة..."))
        let ecus = await discoverEcus(transport)
        vInfo.detectedEcus = ecus

        await onProgress(ScanProgress(isScanning: true, stepIndex: 4, totalSteps: 7, stepLabelEn: "Reading Confirmed, Pending & Permanent Faults...", stepLabelAr: "قراءة الأعطال المؤكدة والمعلقة والدائمة..."))
        let dtcs = await readAllDtcs(transport)

        await onProgress(ScanProgress(isScanning: true, stepIndex: 5, totalSteps: 7, stepLabelEn: "Checking Readiness & Freeze Frame...", stepLabelAr: "فحص جاهزية الأنظمة ولقطة تجميد الأعطال..."))
        let readiness = await readReadiness(transport)
        let freeze = dtcs.isEmpty ? [] : await readFreezeFrame(transport, unit: unit)

        await onProgress(ScanProgress(isScanning: true, stepIndex: 6, totalSteps: 7, stepLabelEn: "Sampling Live Sensors...", stepLabelAr: "أخذ قراءات الحساسات والبيانات الحية..."))
        let sensors = await pollSensorsOnce(transport: transport, unit: unit, existing: [:])

        await onProgress(ScanProgress(isScanning: true, stepIndex: 7, totalSteps: 7, stepLabelEn: "Analyzing Vehicle Health...", stepLabelAr: "تحليل حالة السيارة وتجهيز الملاحظات..."))
        let analysis = VehicleHealthAnalyzer.analyze(vehicleInfo: vInfo, dtcs: dtcs, readiness: readiness, sensors: sensors, unit: unit)

        await onProgress(ScanProgress(isScanning: false))
        return FullScanReport(
            id: UUID().uuidString,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            adapterDescription: transport.adapterDescription,
            healthScore: analysis.score,
            vehicleInfo: vInfo,
            dtcs: dtcs,
            freezeFrame: freeze,
            readiness: readiness,
            sensorSnapshot: sensors,
            observations: analysis.observations
        )
    }

    /// High-frequency cockpit telemetry poller dedicated to the top 3 circular gauges (RPM, Speed, Coolant/Voltage).
    func pollCockpitGaugesOnce(
        transport: ObdTransport,
        unit: MeasurementUnit,
        existing: [String: LiveSensorReading],
        includeSlowPids: Bool
    ) async -> [LiveSensorReading] {
        var results: [LiveSensorReading] = []

        if let rpmDef = ObdPidRegistry.byPidHex["0C"],
           let r = await readSingleSensor(transport: transport, def: rpmDef, unit: unit, existing: existing, timeoutMs: 700, allowFastSuffix: true) {
            results.append(r)
        }

        if supportedPidsCache.isEmpty || supportedPidsCache.contains("0D"),
           let spdDef = ObdPidRegistry.byPidHex["0D"],
           let r = await readSingleSensor(transport: transport, def: spdDef, unit: unit, existing: existing, timeoutMs: 700, allowFastSuffix: true) {
            results.append(r)
        }

        if includeSlowPids {
            if supportedPidsCache.isEmpty || supportedPidsCache.contains("05"),
               let coolDef = ObdPidRegistry.byPidHex["05"],
               let r = await readSingleSensor(transport: transport, def: coolDef, unit: unit, existing: existing, timeoutMs: 900, allowFastSuffix: true) {
                results.append(r)
            }

            if let voltDef = ObdPidRegistry.byPidHex["42"] {
                var pid42: LiveSensorReading? = nil
                if supportedPidsCache.isEmpty || supportedPidsCache.contains("42") {
                    pid42 = await readSingleSensor(transport: transport, def: voltDef, unit: unit, existing: existing, timeoutMs: 900, allowFastSuffix: true)
                }
                if let pid42 {
                    results.append(pid42)
                } else if let atrv = try? await transport.sendCommand("ATRV", timeoutMs: 900),
                          let parsedV = ObdResponseParser.parseVoltage(atrv) {
                    results.append(Self.buildReading(def: voltDef, displayValue: parsedV, unit: unit, prev: existing["42"]))
                }
            }
        }
        return results
    }

    func pollSensorsOnce(
        transport: ObdTransport,
        unit: MeasurementUnit,
        existing: [String: LiveSensorReading]
    ) async -> [LiveSensorReading] {
        let defs = supportedPidsCache.isEmpty
            ? ObdPidRegistry.sensors
            : ObdPidRegistry.sensors.filter { supportedPidsCache.contains($0.pidHex) }
        let activeDefs = defs.isEmpty ? ObdPidRegistry.sensors : defs
        var out: [LiveSensorReading] = []
        for def in activeDefs {
            if let r = await readSingleSensor(transport: transport, def: def, unit: unit, existing: existing, timeoutMs: 1400, allowFastSuffix: false) {
                out.append(r)
            }
        }
        return out
    }

    /// Deep multi-protocol Check Engine Light (MIL) & DTC clear across CAN 11/29-bit, UDS (10 03 -> 14 FF FF FF), and KWP2000.
    func forceTurnOffCheckEngine(_ transport: ObdTransport) async -> MilResetResult {
        var clearedPaths: [String] = []
        var engineRunningBlocked = false
        var lastRaw = ""

        func inspectNrc22(_ raw: String) {
            let compact = raw.uppercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "\n", with: "")
            if compact.contains("7F0422") || compact.contains("7F1422") {
                engineRunningBlocked = true
            }
        }

        func acceptedClear(_ raw: String) -> Bool {
            if ObdResponseParser.isErrorOrEmpty(raw) { return false }
            let tokens = ObdResponseParser.parseHexTokens(raw)
            if tokens.contains(0x44) || tokens.contains(0x54) { return true }
            return raw.uppercased().contains("OK")
        }

        // 1. Broadcast OBD-II Mode 04
        if let r04 = try? await transport.sendCommand("04", timeoutMs: 3200) {
            lastRaw = r04
            inspectNrc22(r04)
            if acceptedClear(r04) { clearedPaths.append("OBD-04") }
        }

        // 2. CAN 11-bit physical ECM/TCM/ABS Mode 04 + UDS Extended Session 10 03 -> 14 FF FF FF
        if busFamily == .can11Bit {
            let targets = ["7E0", "7E1", "7E2", "7B0", "7D0", "760"]
            for header in targets {
                _ = try? await transport.sendCommand("ATSH \(header)", timeoutMs: 900)
                if header == "7E0" || header == "7E1",
                   let rPhys04 = try? await transport.sendCommand("04", timeoutMs: 2200) {
                    inspectNrc22(rPhys04)
                    if acceptedClear(rPhys04) { clearedPaths.append("\(header)-04") }
                }
                _ = try? await transport.sendCommand("10 03", timeoutMs: 1400)
                if let rUds = try? await transport.sendCommand("14 FF FF FF", timeoutMs: 2500) {
                    lastRaw = rUds
                    inspectNrc22(rUds)
                    if acceptedClear(rUds) { clearedPaths.append("\(header)-UDS14") }
                }
            }
            _ = try? await transport.sendCommand("ATSH 7DF", timeoutMs: 900)
        } else if busFamily == .kwp2000 {
            if let rKwp = try? await transport.sendCommand("14 FF 00", timeoutMs: 2600) {
                lastRaw = rKwp
                inspectNrc22(rKwp)
                if acceptedClear(rKwp) { clearedPaths.append("KWP-14FF00") }
            }
        }

        try? await Task.sleep(nanoseconds: 350_000_000)
        var milOff: Bool? = nil
        if let r0101 = try? await transport.sendCommand("0101", timeoutMs: 2200),
           let summary = ObdResponseParser.parseReadiness0101(r0101) {
            milOff = !summary.milOn
        }

        return MilResetResult(
            clearedConfirmed: !clearedPaths.isEmpty,
            milIsNowOff: milOff,
            engineRunningBlocked: engineRunningBlocked,
            clearedPaths: clearedPaths,
            rawDetail: lastRaw
        )
    }

    private func readSingleSensor(
        transport: ObdTransport,
        def: SensorPidDefinition,
        unit: MeasurementUnit,
        existing: [String: LiveSensorReading],
        timeoutMs: Int,
        allowFastSuffix: Bool
    ) async -> LiveSensorReading? {
        var bytes: [Int]? = nil
        var triedFast = false

        if allowFastSuffix && isCanProtocol && fastSingleResponseSupported != false {
            triedFast = true
            if let fastRaw = try? await transport.sendCommand("01\(def.pidHex)1", timeoutMs: timeoutMs) {
                if let parsed = ObdResponseParser.parseMode01SensorBytes(fastRaw, pidHex: def.pidHex) {
                    fastSingleResponseSupported = true
                    bytes = parsed
                } else if fastRaw.trimmingCharacters(in: .whitespacesAndNewlines) == "?" || fastRaw.uppercased().contains("ERROR") {
                    fastSingleResponseSupported = false
                }
            }
        }

        if bytes == nil {
            guard let stdRaw = try? await transport.sendCommand("01\(def.pidHex)", timeoutMs: timeoutMs),
                  let parsed = ObdResponseParser.parseMode01SensorBytes(stdRaw, pidHex: def.pidHex) else {
                return nil
            }
            if triedFast {
                fastSingleResponseSupported = false
            }
            bytes = parsed
        }

        guard let dataBytes = bytes, let metric = def.decodeMetric(dataBytes) else { return nil }
        let displayVal = def.valueFor(metric, unit: unit)
        return Self.buildReading(def: def, displayValue: displayVal, unit: unit, prev: existing[def.pidHex])
    }

    private static func buildReading(
        def: SensorPidDefinition,
        displayValue: Double,
        unit: MeasurementUnit,
        prev: LiveSensorReading?
    ) -> LiveSensorReading {
        let hist = Array(((prev?.history ?? []) + [displayValue]).suffix(30))
        return LiveSensorReading(
            pidHex: def.pidHex,
            nameEn: def.nameEn,
            nameAr: def.nameAr,
            value: displayValue,
            unit: def.unitFor(unit),
            category: def.category,
            normalMin: def.minFor(unit),
            normalMax: def.maxFor(unit),
            minRecorded: min(prev?.minRecorded ?? displayValue, displayValue),
            maxRecorded: max(prev?.maxRecorded ?? displayValue, displayValue),
            history: hist,
            decimals: def.decimals,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    private func readVehicleInfo(_ transport: ObdTransport) async -> VehicleInfo {
        let proto = (try? await transport.sendCommand("ATDP", timeoutMs: 1200))?.trimmingCharacters(in: .whitespacesAndNewlines)
        let voltRaw = (try? await transport.sendCommand("ATRV", timeoutMs: 1200)) ?? ""
        let voltage = ObdResponseParser.parseVoltage(voltRaw)
        let vinRaw = (try? await transport.sendCommand("0902", timeoutMs: 3500)) ?? ""
        let vin = ObdResponseParser.parseVin0902(vinRaw)
        let decoded = VinDecoder.decode(vin)
        return VehicleInfo(
            vin: vin,
            make: decoded.make,
            country: decoded.country,
            modelYear: decoded.year,
            protocolName: proto,
            batteryVoltage: voltage
        )
    }

    private func discoverEcus(_ transport: ObdTransport) async -> [DetectedEcu] {
        guard isCanProtocol else {
            return [DetectedEcu(address: "ECM", nameEn: "Engine Control Module", nameAr: "كمبيوتر المحرك (ECM)", protocolLabel: "OBD-II")]
        }
        _ = try? await transport.sendCommand("ATH1", timeoutMs: 1000)
        let raw = (try? await transport.sendCommand("0100", timeoutMs: 2500)) ?? ""
        _ = try? await transport.sendCommand("ATH0", timeoutMs: 1000)
        let payloads = ObdResponseParser.extractHexPayloads(raw, expectCanHeaders: true)
        if payloads.isEmpty {
            return [DetectedEcu(address: "7E8", nameEn: "Engine Control Module (ECM)", nameAr: "كمبيوتر المحرك (ECM)", protocolLabel: "OBD-II CAN")]
        }
        return payloads.keys.sorted().map { Self.mapEcu($0) }
    }

    private func readAllDtcs(_ transport: ObdTransport) async -> [DiagnosedDtc] {
        var list: [DiagnosedDtc] = []
        var seen = Set<String>()

        if isCanProtocol { _ = try? await transport.sendCommand("ATH1", timeoutMs: 1000) }

        let modes: [(String, Int, DtcStatus)] = [
            ("03", 0x43, .confirmed),
            ("07", 0x47, .pending),
            ("0A", 0x4A, .permanent)
        ]
        for (cmd, svc, status) in modes {
            if let raw = try? await transport.sendCommand(cmd, timeoutMs: 2600) {
                for entry in ObdResponseParser.parseModeDtcResponse(raw, responseServiceByte: svc, expectCanHeaders: isCanProtocol) {
                    let key = "\(entry.code)-\(status.rawValue)"
                    if seen.insert(key).inserted {
                        let ecu = Self.mapEcu(entry.ecuAddress)
                        list.append(DiagnosedDtc(
                            code: entry.code,
                            status: status,
                            ecuAddress: entry.ecuAddress,
                            ecuNameEn: ecu.nameEn,
                            ecuNameAr: ecu.nameAr,
                            knowledge: dtcRepo.lookup(entry.code)
                        ))
                    }
                }
            }
        }

        if busFamily == .can11Bit {
            for (tx, rx) in [("7E1", "7E9"), ("7B0", "7B8")] {
                _ = try? await transport.sendCommand("ATSH \(tx)", timeoutMs: 900)
                if let raw = try? await transport.sendCommand("19 02 AF", timeoutMs: 1800) {
                    for entry in ObdResponseParser.parseUds1902Response(raw, defaultEcu: rx) {
                        let key = "\(entry.code)-\(DtcStatus.storedUds.rawValue)"
                        if seen.insert(key).inserted {
                            let ecu = Self.mapEcu(entry.ecuAddress)
                            list.append(DiagnosedDtc(
                                code: entry.code,
                                status: .storedUds,
                                ecuAddress: entry.ecuAddress,
                                ecuNameEn: ecu.nameEn,
                                ecuNameAr: ecu.nameAr,
                                knowledge: dtcRepo.lookup(entry.code)
                            ))
                        }
                    }
                }
            }
            _ = try? await transport.sendCommand("ATSH 7DF", timeoutMs: 900)
        }

        if isCanProtocol { _ = try? await transport.sendCommand("ATH0", timeoutMs: 1000) }
        return list
    }

    private func readReadiness(_ transport: ObdTransport) async -> ReadinessSummary? {
        guard let raw = try? await transport.sendCommand("0101", timeoutMs: 2200) else { return nil }
        return ObdResponseParser.parseReadiness0101(raw)
    }

    private func readFreezeFrame(_ transport: ObdTransport, unit: MeasurementUnit) async -> [FreezeFrameReading] {
        var rows: [FreezeFrameReading] = []
        for pid in ["0C", "0D", "05", "04", "06", "07", "42"] {
            guard let def = ObdPidRegistry.byPidHex[pid],
                  let raw = try? await transport.sendCommand("02\(pid)00", timeoutMs: 1200) else { continue }
            for (_, bytes) in ObdResponseParser.extractHexPayloads(raw, expectCanHeaders: false) {
                if let pInt = Int(pid, radix: 16),
                   let idx = bytes.firstIndex(of: 0x42), idx + 2 < bytes.count, bytes[idx + 1] == pInt {
                    let data = Array(bytes.dropFirst(idx + 3))
                    if let metric = def.decodeMetric(data) {
                        let val = def.valueFor(metric, unit: unit)
                        rows.append(FreezeFrameReading(
                            pidHex: pid,
                            labelEn: def.nameEn,
                            labelAr: def.nameAr,
                            valueDisplay: String(format: "%.\(def.decimals)f", val),
                            unit: def.unitFor(unit)
                        ))
                        break
                    }
                }
            }
        }
        return rows
    }

    private static func detectBusFamily(code: String, name: String) -> BusFamily {
        let clean = code.replacingOccurrences(of: "A", with: "")
        let up = name.uppercased()
        if ["7", "9", "C"].contains(clean) || up.contains("29/") { return .can29Bit }
        if ["6", "8", "B"].contains(clean) || up.contains("CAN") || up.contains("15765") { return .can11Bit }
        if ["4", "5"].contains(clean) || up.contains("14230") || up.contains("KWP") { return .kwp2000 }
        return .legacyOther
    }

    private static func mapEcu(_ addr: String) -> DetectedEcu {
        switch addr.uppercased() {
        case "7E8": return DetectedEcu(address: "7E8", nameEn: "Engine Control Module (ECM)", nameAr: "كمبيوتر المحرك (ECM)", protocolLabel: "OBD-II CAN")
        case "7E9", "7EA": return DetectedEcu(address: addr, nameEn: "Transmission Control Module (TCM)", nameAr: "كمبيوتر القير (TCM)", protocolLabel: "UDS / CAN")
        case "7B8", "7D9", "768": return DetectedEcu(address: addr, nameEn: "ABS / Stability Control", nameAr: "وحدة الفرامل والثبات (ABS)", protocolLabel: "UDS CAN")
        default: return DetectedEcu(address: addr, nameEn: "Control Module (\(addr))", nameAr: "وحدة تحكم (\(addr))", protocolLabel: "CAN ECU")
        }
    }
}

enum VehicleHealthAnalyzer {
    static func analyze(
        vehicleInfo: VehicleInfo,
        dtcs: [DiagnosedDtc],
        readiness: ReadinessSummary?,
        sensors: [LiveSensorReading],
        unit: MeasurementUnit
    ) -> (score: Int, observations: [VehicleObservation]) {
        var penalty = 0
        var obs: [VehicleObservation] = []
        let byPid = Dictionary(uniqueKeysWithValues: sensors.map { ($0.pidHex, $0) })

        for dtc in dtcs {
            switch dtc.knowledge.severity {
            case .critical: penalty += 22
            case .high: penalty += 15
            case .moderate: penalty += 10
            case .low, .info: penalty += 5
            }
        }
        if readiness?.milOn == true { penalty += 8 }

        // Battery / Charging check
        let voltage = byPid["42"]?.value ?? vehicleInfo.batteryVoltage
        if let v = voltage {
            let rpm = byPid["0C"]?.value ?? 0
            if rpm > 500 && v < 13.1 {
                penalty += 12
                obs.append(VehicleObservation(
                    id: "charging-low", severity: .warning, category: .batteryCharging,
                    titleEn: "Weak Alternator Charging Voltage",
                    titleAr: "ضعف شحن الدينمو أثناء عمل المحرك",
                    summaryEn: "Charging voltage is below optimal 13.4V–14.7V while engine is running.",
                    summaryAr: "جهد الشحن أقل من النطاق المثالي (13.4V–14.7V) والمحرك يعمل.",
                    measuredValue: String(format: "%.2f V", v), normalRange: "13.4V - 14.7V",
                    impactEn: "May cause battery drain or electrical faults.",
                    impactAr: "قد يؤدي إلى تفريغ البطارية وضعف الكهرباء.",
                    resolutionStepsEn: ["Inspect battery terminals", "Test alternator output under load"],
                    resolutionStepsAr: ["افحص أصابع البطارية ونظافتها", "اختبر شحن الدينمو تحت الحمل الكهربائي"]
                ))
            } else if v >= 12.4 && v <= 14.8 {
                obs.append(VehicleObservation(
                    id: "charging-ok", severity: .good, category: .batteryCharging,
                    titleEn: "Battery & Charging Within Spec",
                    titleAr: "جهد البطارية والشحن طبيعي",
                    summaryEn: "Electrical system voltage is healthy.",
                    summaryAr: "قراءة جهد المنظومة الكهربائية ضمن المعدل السليم.",
                    measuredValue: String(format: "%.2f V", v), normalRange: "12.4V - 14.7V",
                    impactEn: "Stable ECU operation.", impactAr: "استقرار ممتاز لوحدات التحكم.",
                    resolutionStepsEn: [], resolutionStepsAr: []
                ))
            }
        }

        // Coolant check
        if let cool = byPid["05"]?.value {
            let tempC = unit == .imperial ? (cool - 32.0) * 5.0 / 9.0 : cool
            if tempC >= 106 {
                penalty += 18
                obs.append(VehicleObservation(
                    id: "coolant-hot", severity: .critical, category: .cooling,
                    titleEn: "Elevated Engine Coolant Temperature",
                    titleAr: "ارتفاع حرارة سائل تبريد المحرك",
                    summaryEn: "Coolant temperature exceeds normal operating threshold.",
                    summaryAr: "درجة حرارة المحرك أعلى من المعدل الطبيعي وتستدعي الانتباه فوراً.",
                    measuredValue: "\(Int(cool)) \(byPid["05"]?.unit ?? "°C")", normalRange: unit == .imperial ? "175°F - 216°F" : "80°C - 102°C",
                    impactEn: "Risk of head gasket or engine overheating damage.",
                    impactAr: "خطر تلف وجه السلندر في حال استمرار الحرارة.",
                    resolutionStepsEn: ["Check coolant level when cold", "Verify radiator fan & thermostat operation"],
                    resolutionStepsAr: ["افحص مستوى ماء الرديتر بعد أن يبرد المحرك تماماً", "تأكد من عمل مراوح التبريد وبلف الحرارة"]
                ))
            }
        }

        // Fuel trim check
        if let ltft = byPid["07"]?.value, abs(ltft) > 12.0 {
            penalty += 8
            obs.append(VehicleObservation(
                id: "ltft-skew", severity: .warning, category: .fuelAir,
                titleEn: ltft > 0 ? "Positive Long-Term Fuel Trim (Lean Tendency)" : "Negative Long-Term Fuel Trim (Rich Tendency)",
                titleAr: ltft > 0 ? "ارتفاع تعديل الوقود الطويل (خليط فقير / احتمال تسريب هواء)" : "انخفاض تعديل الوقود الطويل (خليط غني)",
                summaryEn: "ECU is compensating fuel delivery significantly beyond ±10%.",
                summaryAr: "يقوم كمبيوتر المحرك بتعويض نسبة الوقود بأكثر من الحد الطبيعي (±10%).",
                measuredValue: String(format: "%+.1f%%", ltft), normalRange: "-10% ... +10%",
                impactEn: "Increased fuel consumption and rough idle.",
                impactAr: "زيادة صرفية البنزين واحتمال رجفة بالمحرك.",
                resolutionStepsEn: ["Check intake hoses for vacuum leaks", "Clean MAF sensor & throttle body"],
                resolutionStepsAr: ["افحص خراطيم الهواء للتأكد من عدم وجود تنسيم", "نظّف حساس الهواء MAF والبوابة"]
            ))
        }

        if dtcs.isEmpty && obs.allSatisfy({ $0.severity == .good || $0.severity == .info }) {
            obs.insert(VehicleObservation(
                id: "overall-clean", severity: .good, category: .ignitionEngine,
                titleEn: "No Active Fault Codes Detected",
                titleAr: "لا توجد أعطال مسجلة بكمبيوتر السيارة",
                summaryEn: "Scanned modules reported zero diagnostic trouble codes.",
                summaryAr: "جميع الوحدات التي تم فحصها خالية من أكواد الأعطال.",
                measuredValue: nil, normalRange: nil,
                impactEn: "Normal operation.", impactAr: "حالة التشغيل طبيعية.",
                resolutionStepsEn: [], resolutionStepsAr: []
            ), at: 0)
        }

        let finalScore = max(12, min(100, 100 - penalty))
        return (finalScore, obs)
    }
}

enum CarZoneMapper {
    static func mapZones(report: FullScanReport?, liveSensors: [LiveSensorReading]) -> [CarSubsystemZone: CarZoneSummary] {
        guard let report else {
            return Dictionary(uniqueKeysWithValues: CarSubsystemZone.allCases.map {
                ($0, CarZoneSummary(zone: $0, status: .unscanned, faultCodes: [], observations: []))
            })
        }
        var dtcsByZone: [CarSubsystemZone: [DiagnosedDtc]] = [:]
        var obsByZone: [CarSubsystemZone: [VehicleObservation]] = [:]

        for dtc in report.dtcs {
            let z = zoneForDtc(dtc)
            dtcsByZone[z, default: []].append(dtc)
        }
        for o in report.observations where o.severity == .critical || o.severity == .warning {
            let z = zoneForObs(o)
            obsByZone[z, default: []].append(o)
        }

        var result: [CarSubsystemZone: CarZoneSummary] = [:]
        for zone in CarSubsystemZone.allCases {
            let codes = dtcsByZone[zone] ?? []
            let notes = obsByZone[zone] ?? []
            let status: ZoneStatus
            if codes.contains(where: { $0.knowledge.severity == .critical || $0.knowledge.severity == .high }) || notes.contains(where: { $0.severity == .critical }) {
                status = .critical
            } else if !codes.isEmpty || !notes.isEmpty {
                status = .warning
            } else {
                status = .healthy
            }
            result[zone] = CarZoneSummary(zone: zone, status: status, faultCodes: codes, observations: notes)
        }
        return result
    }

    private static func zoneForDtc(_ dtc: DiagnosedDtc) -> CarSubsystemZone {
        let c = dtc.code.uppercased()
        if c.hasPrefix("C") { return .brakesChassis }
        if c.hasPrefix("B") || c.hasPrefix("U") { return .bodyNetwork }
        if c.hasPrefix("P07") || c.hasPrefix("P08") { return .transmission }
        if c.hasPrefix("P011") || c.hasPrefix("P0125") || c.hasPrefix("P0128") || c.hasPrefix("P048") { return .cooling }
        if c.hasPrefix("P042") || c.hasPrefix("P043") || c.hasPrefix("P013") || c.hasPrefix("P014") || c.hasPrefix("P044") || c.hasPrefix("P045") { return .exhaust }
        if c.hasPrefix("P017") || c.hasPrefix("P010") || c.hasPrefix("P008") || c.hasPrefix("P02") { return .fuelAir }
        if c.hasPrefix("P056") || c.hasPrefix("P062") { return .battery }
        return .engine
    }

    private static func zoneForObs(_ o: VehicleObservation) -> CarSubsystemZone {
        switch o.category {
        case .batteryCharging: return .battery
        case .cooling: return .cooling
        case .fuelAir: return .fuelAir
        case .ignitionEngine: return .engine
        case .emissionsReadiness: return .exhaust
        case .transmissionChassis: return .transmission
        case .prePurchaseCheck: return .bodyNetwork
        }
    }
}

enum LocalAiDiagnosticEngine {
    static func analyze(report: FullScanReport) -> SmartAiReport {
        let hasCritical = report.dtcs.contains { !$0.knowledge.canDriveSafely || $0.knowledge.severity == .critical } ||
            report.observations.contains { $0.severity == .critical }
        let hasAnyFault = !report.dtcs.isEmpty || report.observations.contains { $0.severity == .warning }

        let verdict: SafetyVerdict
        if hasCritical {
            verdict = .stopImmediately
        } else if hasAnyFault {
            verdict = .cautionRepairSoon
        } else if report.healthScore >= 90 {
            verdict = .optimal
        } else {
            verdict = .safeMonitor
        }

        let summaryAr: String
        let summaryEn: String
        if report.dtcs.isEmpty {
            summaryAr = "السيارة خالية من أكواد الأعطال المسجلة حالياً بدرجة صحة \(report.healthScore)%."
            summaryEn = "No diagnostic trouble codes detected. Vehicle health score is \(report.healthScore)%."
        } else {
            let codesList = report.dtcs.prefix(4).map(\.code).joined(separator: "، ")
            summaryAr = "تم رصد \(report.dtcs.count) عطل مسجل (\(codesList)). راجع خطة الإصلاح المرتبة من الأسهل والأقل تكلفة."
            summaryEn = "Detected \(report.dtcs.count) trouble code(s) (\(codesList)). Follow the prioritized cost-first repair steps below."
        }

        let hypotheses: [AiHypothesis] = report.dtcs.prefix(3).enumerated().map { idx, d in
            AiHypothesis(
                id: "hyp-\(idx)",
                confidencePercent: max(68, 88 - idx * 7),
                zone: .engine,
                titleEn: "\(d.code): \(d.knowledge.titleEn)",
                titleAr: "\(d.code): \(d.knowledge.titleAr)",
                reasoningEn: d.knowledge.causesEn.first ?? d.knowledge.descriptionEn,
                reasoningAr: d.knowledge.causesAr.first ?? d.knowledge.descriptionAr
            )
        }

        var stepsAr: [String] = []
        var stepsEn: [String] = []
        for d in report.dtcs.prefix(3) {
            if let sAr = d.knowledge.fixStepsAr.first { stepsAr.append("\(d.code): \(sAr)") }
            if let sEn = d.knowledge.fixStepsEn.first { stepsEn.append("\(d.code): \(sEn)") }
        }
        for o in report.observations where o.severity == .critical || o.severity == .warning {
            if let sAr = o.resolutionStepsAr.first { stepsAr.append(sAr) }
            if let sEn = o.resolutionStepsEn.first { stepsEn.append(sEn) }
        }
        if stepsAr.isEmpty {
            stepsAr = ["حافظ على الصيانة الدورية وفحص السوائل والفلاتر في مواعيدها."]
            stepsEn = ["Continue routine fluid, filter, and tire maintenance."]
        }

        return SmartAiReport(
            verdict: verdict,
            summaryEn: summaryEn,
            summaryAr: summaryAr,
            hypotheses: hypotheses,
            priorityStepsEn: stepsEn,
            priorityStepsAr: stepsAr
        )
    }

    static func answerQuestion(_ question: String, report: FullScanReport?, dtcRepo: DtcRepository, isAr: Bool) -> String {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        if let regex = try? NSRegularExpression(pattern: "[PCBU][0-9A-F]{4}", options: .caseInsensitive),
           let match = regex.firstMatch(in: q, range: NSRange(q.startIndex..<q.endIndex, in: q)),
           let r = Range(match.range, in: q) {
            let code = String(q[r]).uppercased()
            let entry = dtcRepo.lookup(code)
            if isAr {
                let fixes = entry.fixStepsAr.prefix(3).map { "• \($0)" }.joined(separator: "\n")
                return "كود \(entry.code) — \(entry.titleAr)\n\(entry.descriptionAr)\n\nخطوات الإصلاح المقترحة:\n\(fixes)"
            } else {
                let fixes = entry.fixStepsEn.prefix(3).map { "• \($0)" }.joined(separator: "\n")
                return "\(entry.code) — \(entry.titleEn)\n\(entry.descriptionEn)\n\nSuggested Fixes:\n\(fixes)"
            }
        }

        guard let report else {
            return isAr
                ? "قم أولاً بالاتصال بالسيارة عبر البلوتوث BLE أو الـ Wi-Fi وتشغيل «الفحص الشامل» حتى أتمكن من تحليل حالة سيارتك."
                : "Connect to your vehicle via BLE or Wi-Fi and run a Full Scan first so I can analyze your vehicle."
        }

        let ai = analyze(report: report)
        if isAr {
            let steps = ai.priorityStepsAr.prefix(3).enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
            return "التقييم الحالي: \(ai.verdict.label(isAr: true))\n\(ai.summaryAr)\n\nأولويات الفحص:\n\(steps)"
        } else {
            let steps = ai.priorityStepsEn.prefix(3).enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
            return "Verdict: \(ai.verdict.label(isAr: false))\n\(ai.summaryEn)\n\nPriority Steps:\n\(steps)"
        }
    }
}
