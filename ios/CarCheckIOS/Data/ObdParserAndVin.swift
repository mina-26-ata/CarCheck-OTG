import Foundation

struct RawDtcEntry: Equatable {
    let code: String
    let ecuAddress: String
}

enum ObdResponseParser {
    private static let errorTokens = [
        "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR",
        "DATA ERROR", "BUFFER FULL", "STOPPED", "ERROR", "?"
    ]

    static func isErrorOrEmpty(_ raw: String) -> Bool {
        let upper = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if upper.isEmpty { return true }
        for token in errorTokens {
            if token == "?" {
                if upper == "?" { return true }
            } else if upper.contains(token) {
                return true
            }
        }
        return false
    }

    static func parseVoltage(_ raw: String) -> Double? {
        guard let regex = try? NSRegularExpression(pattern: #"(\d{1,2}\.\d{1,2})\s*V?"#, options: .caseInsensitive) else {
            return nil
        }
        let range = NSRange(raw.startIndex..<raw.endIndex, in: raw)
        guard let match = regex.firstMatch(in: raw, range: range),
              let numRange = Range(match.range(at: 1), in: raw) else {
            return nil
        }
        return Double(raw[numRange])
    }

    static func parseHexTokens(_ text: String) -> [Int] {
        let cleaned = text.uppercased().replacingOccurrences(of: " ", with: "")
        guard cleaned.count >= 2 else { return [] }
        var bytes: [Int] = []
        var idx = cleaned.startIndex
        while idx < cleaned.endIndex {
            let next = cleaned.index(idx, offsetBy: 2, limitedBy: cleaned.endIndex) ?? cleaned.endIndex
            let sub = String(cleaned[idx..<next])
            if sub.count == 2, let b = Int(sub, radix: 16) {
                bytes.append(b)
            }
            idx = next
        }
        return bytes
    }

    static func extractHexPayloads(_ raw: String, expectCanHeaders: Bool = false) -> [String: [Int]] {
        if isErrorOrEmpty(raw) { return [:] }
        let lines = raw
            .replacingOccurrences(of: "\r", with: "\n")
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { line in
                !line.isEmpty &&
                !line.uppercased().hasPrefix("SEARCHING") &&
                !line.uppercased().hasPrefix("BUS INIT") &&
                line != ">"
            }
        if lines.isEmpty { return [:] }

        let isIndexed = lines.contains { $0.contains(":") && $0.split(separator: ":").first?.count ?? 9 <= 2 }
        if isIndexed {
            var allBytes: [Int] = []
            for line in lines {
                let after = line.split(separator: ":", maxSplits: 1).last.map(String.init) ?? line
                allBytes.append(contentsOf: parseHexTokens(after))
            }
            return allBytes.isEmpty ? [:] : ["7E8": allBytes]
        }

        if !expectCanHeaders {
            var combined: [Int] = []
            for line in lines {
                combined.append(contentsOf: parseHexTokens(line))
            }
            return combined.isEmpty ? [:] : ["7E8": combined]
        }

        var ecuFrames: [String: [String]] = [:]
        for line in lines {
            let compact = line.replacingOccurrences(of: " ", with: "").uppercased()
            if compact.count > 3, compact.count % 2 == 1 {
                let ecu = String(compact.prefix(3))
                let frame = String(compact.dropFirst(3))
                ecuFrames[ecu, default: []].append(frame)
            } else if compact.hasPrefix("18DA"), compact.count > 8 {
                let ecu = String(compact.prefix(8))
                let frame = String(compact.dropFirst(8))
                ecuFrames[ecu, default: []].append(frame)
            }
        }
        if ecuFrames.isEmpty {
            return extractHexPayloads(raw, expectCanHeaders: false)
        }

        var result: [String: [Int]] = [:]
        for (ecu, frames) in ecuFrames {
            let assembled = assembleIsoTp(frames)
            if !assembled.isEmpty {
                result[ecu] = assembled
            }
        }
        return result
    }

    private static func assembleIsoTp(_ frames: [String]) -> [Int] {
        var output: [Int] = []
        var expectedLen: Int? = nil
        for f in frames {
            let bytes = parseHexTokens(f)
            guard let pci = bytes.first else { continue }
            let frameType = (pci >> 4) & 0x0F
            switch frameType {
            case 0:
                let len = pci & 0x0F
                return Array(bytes.dropFirst().prefix(len))
            case 1:
                guard bytes.count >= 2 else { continue }
                expectedLen = ((pci & 0x0F) << 8) | bytes[1]
                output.append(contentsOf: bytes.dropFirst(2))
            case 2:
                output.append(contentsOf: bytes.dropFirst(1))
            default:
                output.append(contentsOf: bytes)
            }
        }
        if let exp = expectedLen {
            return Array(output.prefix(exp))
        }
        return output
    }

    static func parseMode01SensorBytes(_ raw: String, pidHex: String) -> [Int]? {
        guard let targetPid = Int(pidHex, radix: 16) else { return nil }
        for (_, bytes) in extractHexPayloads(raw, expectCanHeaders: false) {
            if bytes.count >= 2 {
                for i in 0..<(bytes.count - 1) {
                    if bytes[i] == 0x41 && bytes[i + 1] == targetPid {
                        let data = Array(bytes.dropFirst(i + 2))
                        if !data.isEmpty { return data }
                    }
                }
            }
        }
        return nil
    }

    static func parseSupportedPids(_ raw: String, basePid: Int) -> Set<String> {
        let baseHex = String(format: "%02X", basePid)
        guard let data = parseMode01SensorBytes(raw, pidHex: baseHex), data.count >= 4 else {
            return []
        }
        var set = Set<String>()
        for byteIdx in 0..<4 {
            let b = data[byteIdx]
            for bit in 0..<8 {
                if (b & (1 << (7 - bit))) != 0 {
                    let pidNum = basePid + byteIdx * 8 + bit + 1
                    set.insert(String(format: "%02X", pidNum))
                }
            }
        }
        return set
    }

    static func decodeTwoByteDtc(_ b1: Int, _ b2: Int) -> String? {
        if b1 == 0 && b2 == 0 { return nil }
        let sysLetters = ["P", "C", "B", "U"]
        let prefix = sysLetters[(b1 >> 6) & 0x03]
        let d1 = (b1 >> 4) & 0x03
        let d2 = b1 & 0x0F
        let d3 = (b2 >> 4) & 0x0F
        let d4 = b2 & 0x0F
        return String(format: "%@%1X%1X%1X%1X", prefix, d1, d2, d3, d4)
    }

    static func parseModeDtcResponse(_ raw: String, responseServiceByte: Int, expectCanHeaders: Bool) -> [RawDtcEntry] {
        let payloads = extractHexPayloads(raw, expectCanHeaders: expectCanHeaders)
        var list: [RawDtcEntry] = []
        for (ecu, bytes) in payloads {
            guard let svcIndex = bytes.firstIndex(of: responseServiceByte) else { continue }
            let afterSvc = Array(bytes.dropFirst(svcIndex + 1))
            if afterSvc.isEmpty { continue }
            // CAN Mode 43/47/4A includes count byte first when odd remaining count or standard ISO 15765-4
            let startOffset = (afterSvc.count % 2 == 1) ? 1 : 0
            var i = startOffset
            while i + 1 < afterSvc.count {
                if let code = decodeTwoByteDtc(afterSvc[i], afterSvc[i + 1]) {
                    list.append(RawDtcEntry(code: code, ecuAddress: ecu))
                }
                i += 2
            }
        }
        return list
    }

    static func parseUds1902Response(_ raw: String, defaultEcu: String) -> [RawDtcEntry] {
        let payloads = extractHexPayloads(raw, expectCanHeaders: true)
        var list: [RawDtcEntry] = []
        for (ecu, bytes) in payloads {
            guard let idx = bytes.firstIndex(of: 0x59), idx + 2 < bytes.count, bytes[idx + 1] == 0x02 else {
                continue
            }
            var pos = idx + 3 // skip 59 02 availabilityMask
            while pos + 3 < bytes.count {
                let b1 = bytes[pos]
                let b2 = bytes[pos + 1]
                let status = bytes[pos + 3]
                if status != 0, let code = decodeTwoByteDtc(b1, b2) {
                    list.append(RawDtcEntry(code: code, ecuAddress: ecu.isEmpty ? defaultEcu : ecu))
                }
                pos += 4
            }
        }
        return list
    }

    static func parseReadiness0101(_ raw: String) -> ReadinessSummary? {
        guard let d = parseMode01SensorBytes(raw, pidHex: "01"), d.count >= 4 else { return nil }
        let a = d[0], b = d[1], c = d[2], e = d[3]
        let milOn = (a & 0x80) != 0
        let dtcCount = a & 0x7F
        let isDiesel = (b & 0x08) != 0

        var monitors: [ReadinessMonitor] = [
            ReadinessMonitor(id: "MIS", nameEn: "Misfire", nameAr: "مراقبة التفتفة", supported: (b & 0x01) != 0, completed: (b & 0x10) == 0, isContinuous: true),
            ReadinessMonitor(id: "FUEL", nameEn: "Fuel System", nameAr: "نظام الوقود", supported: (b & 0x02) != 0, completed: (b & 0x20) == 0, isContinuous: true),
            ReadinessMonitor(id: "CCM", nameEn: "Comprehensive Components", nameAr: "المكونات الشاملة", supported: (b & 0x04) != 0, completed: (b & 0x40) == 0, isContinuous: true)
        ]

        if !isDiesel {
            let sparkDefs: [(Int, String, String, String)] = [
                (0, "CAT", "Catalyst", "دبة التلوث (المحفز)"),
                (2, "EVAP", "Evaporative System", "منظومة أبخرة الوقود EVAP"),
                (5, "O2S", "O2 Sensor", "حساس الأكسجين"),
                (6, "HO2S", "O2 Sensor Heater", "سخان حساس الأكسجين"),
                (7, "EGR", "EGR / VVT System", "نظام EGR / التوقيت المتغير")
            ]
            for (bit, id, en, ar) in sparkDefs {
                let sup = (c & (1 << bit)) != 0
                let comp = (e & (1 << bit)) == 0
                if sup {
                    monitors.append(ReadinessMonitor(id: id, nameEn: en, nameAr: ar, supported: true, completed: comp, isContinuous: false))
                }
            }
        }
        return ReadinessSummary(milOn: milOn, ecuReportedDtcCount: dtcCount, isDiesel: isDiesel, monitors: monitors)
    }

    static func parseVin0902(_ raw: String) -> String? {
        for (_, bytes) in extractHexPayloads(raw, expectCanHeaders: false) {
            if let idx = bytes.firstIndex(of: 0x49), idx + 1 < bytes.count, bytes[idx + 1] == 0x02 {
                let asciiBytes = bytes.dropFirst(idx + 3).filter { $0 >= 0x30 && $0 <= 0x5A }
                let chars = asciiBytes.compactMap { UnicodeScalar($0).map(Character.init) }
                let text = String(chars)
                if text.count >= 17 {
                    return String(text.prefix(17))
                }
            }
        }
        return nil
    }
}

enum VinDecoder {
    private static let wmiMap: [String: (String, String)] = [
        "KNA": ("Kia", "كوريا الجنوبية"),
        "KND": ("Kia", "كوريا الجنوبية"),
        "KNC": ("Kia", "كوريا الجنوبية"),
        "5XY": ("Kia", "الولايات المتحدة"),
        "3KP": ("Kia", "المكسيك"),
        "U5Y": ("Kia", "أوروبا"),
        "KMH": ("Hyundai", "كوريا الجنوبية"),
        "KM8": ("Hyundai", "كوريا الجنوبية"),
        "5NP": ("Hyundai", "الولايات المتحدة"),
        "JT": ("Toyota", "اليابان"),
        "4T1": ("Toyota", "الولايات المتحدة"),
        "2T1": ("Toyota", "كندا"),
        "JTH": ("Lexus", "اليابان"),
        "JN1": ("Nissan", "اليابان"),
        "JN8": ("Nissan", "اليابان"),
        "1N4": ("Nissan", "الولايات المتحدة"),
        "JHM": ("Honda", "اليابان"),
        "1HG": ("Honda", "الولايات المتحدة"),
        "JM1": ("Mazda", "اليابان"),
        "JM3": ("Mazda", "اليابان"),
        "1FA": ("Ford", "الولايات المتحدة"),
        "1FM": ("Ford", "الولايات المتحدة"),
        "1FT": ("Ford", "الولايات المتحدة"),
        "1G1": ("Chevrolet", "الولايات المتحدة"),
        "1GC": ("Chevrolet", "الولايات المتحدة"),
        "1GT": ("GMC", "الولايات المتحدة"),
        "1C4": ("Jeep", "الولايات المتحدة"),
        "WBA": ("BMW", "ألمانيا"),
        "WDB": ("Mercedes-Benz", "ألمانيا"),
        "WDD": ("Mercedes-Benz", "ألمانيا"),
        "WAU": ("Audi", "ألمانيا"),
        "WVW": ("Volkswagen", "ألمانيا"),
        "LSJ": ("MG", "الصين"),
        "LGW": ("Haval / GWM", "الصين"),
        "L6T": ("Geely", "الصين"),
        "LVV": ("Chery", "الصين")
    ]

    private static let yearCodes: [Character: Int] = [
        "A": 2010, "B": 2011, "C": 2012, "D": 2013, "E": 2014,
        "F": 2015, "G": 2016, "H": 2017, "J": 2018, "K": 2019,
        "L": 2020, "M": 2021, "N": 2022, "P": 2023, "R": 2024,
        "S": 2025, "T": 2026, "V": 2027,
        "1": 2001, "2": 2002, "3": 2003, "4": 2004, "5": 2005,
        "6": 2006, "7": 2007, "8": 2008, "9": 2009
    ]

    static func decode(_ vinRaw: String?) -> (make: String?, country: String?, year: Int?) {
        guard let vin = vinRaw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              vin.count == 17 else {
            return (nil, nil, nil)
        }
        let wmi3 = String(vin.prefix(3))
        let wmi2 = String(vin.prefix(2))
        let match = wmiMap[wmi3] ?? wmiMap[wmi2]
        let tenth = vin[vin.index(vin.startIndex, offsetBy: 9)]
        let year = yearCodes[tenth]
        return (match?.0, match?.1, year)
    }

    static func detectBodyStyle(vin: String?, make: String?) -> CarBodyStyle {
        let v = (vin ?? "").uppercased()
        if v.hasPrefix("KND") || v.hasPrefix("5XY") || v.hasPrefix("KM8") || v.hasPrefix("1FM") || v.hasPrefix("JN8") || v.hasPrefix("JM3") || v.hasPrefix("1C4") {
            return .suv
        }
        if v.hasPrefix("1FT") || v.hasPrefix("1GC") || v.hasPrefix("1GT") {
            return .pickup
        }
        let m = (make ?? "").lowercased()
        if m.contains("jeep") || m.contains("haval") || m.contains("gmc") {
            return .suv
        }
        return .sedan
    }
}

final class DtcRepository {
    private var byCode: [String: DtcKnowledgeEntry] = [:]
    private(set) var allEntries: [DtcKnowledgeEntry] = []

    init() {
        loadBundledJson()
    }

    private func loadBundledJson() {
        let bundles = [Bundle.main, Bundle(for: DtcRepository.self)]
        for bundle in bundles {
            if let url = bundle.url(forResource: "dtc_database", withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let decoded = try? JSONDecoder().decode([DtcKnowledgeEntry].self, from: data) {
                self.allEntries = decoded
                for item in decoded {
                    byCode[item.code.uppercased()] = item
                }
                return
            }
        }
    }

    func lookup(_ rawCode: String) -> DtcKnowledgeEntry {
        let code = rawCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if let found = byCode[code] {
            return found
        }
        return buildFallbackEntry(code)
    }

    func search(query: String, systemFilter: DtcSystem?) -> [DtcKnowledgeEntry] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = allEntries.filter { entry in
            if let sys = systemFilter, entry.system != sys { return false }
            if q.isEmpty { return true }
            return entry.code.lowercased().contains(q) ||
                entry.titleAr.lowercased().contains(q) ||
                entry.titleEn.lowercased().contains(q) ||
                entry.descriptionAr.lowercased().contains(q)
        }
        return Array(filtered.prefix(80))
    }

    private func buildFallbackEntry(_ code: String) -> DtcKnowledgeEntry {
        let first = code.first ?? "P"
        let sys: DtcSystem = switch first {
        case "C": .chassis
        case "B": .body
        case "U": .network
        default: .powertrain
        }
        return DtcKnowledgeEntry(
            code: code,
            system: sys,
            isGeneric: code.dropFirst().first == "0",
            severity: sys == .powertrain ? .moderate : .low,
            canDriveSafely: true,
            titleEn: "Diagnostic Trouble Code \(code)",
            titleAr: "كود عطل مسجل بالسيارة (\(code))",
            descriptionEn: "Control module reported fault code \(code). Inspect wiring, connectors, and live sensor values for this circuit.",
            descriptionAr: "سجلت وحدة التحكم كود العطل \(code). يُنصح بفحص الفيش والضفيرة وقراءات الحساس المرتبط بهذه الدائرة.",
            causesEn: ["Sensor signal out of expected range", "Connector corrosion or loose harness", "Component wear"],
            causesAr: ["قراءة الحساس خارج النطاق المتوقع", "ارتخاء أو أكسدة في فيشة التوصيل أو الضفيرة", "ضعف القطعة المرتبطة بالكود"],
            symptomsEn: ["Check Engine / warning indicator illuminated"],
            symptomsAr: ["إضاءة لمبة التحذير في الطبلون"],
            fixStepsEn: ["Inspect harness & connector pins first", "Compare live sensor readings", "Clear code after repair and verify"],
            fixStepsAr: ["افحص الفيشة والأسلاك المتصلة بالدائرة أولاً", "راجع القراءات الحية للحساسات المرتبطة", "امسح الكود بعد الإصلاح وجرّب السيارة للتأكد"],
            estimatedDifficultyEn: "Moderate",
            estimatedDifficultyAr: "متوسط",
            isFromBuiltInDb: false
        )
    }
}

struct SensorPidDefinition {
    let pidHex: String
    let nameEn: String
    let nameAr: String
    let unitMetric: String
    let unitImperial: String
    let category: SensorCategory
    let normalMinMetric: Double?
    let normalMaxMetric: Double?
    let decimals: Int
    let decodeMetric: ([Int]) -> Double?
    let toImperial: (Double) -> Double

    func unitFor(_ unit: MeasurementUnit) -> String {
        unit == .metric ? unitMetric : unitImperial
    }

    func valueFor(_ metric: Double, unit: MeasurementUnit) -> Double {
        unit == .metric ? metric : toImperial(metric)
    }

    func minFor(_ unit: MeasurementUnit) -> Double? {
        normalMinMetric.map { valueFor($0, unit: unit) }
    }

    func maxFor(_ unit: MeasurementUnit) -> Double? {
        normalMaxMetric.map { valueFor($0, unit: unit) }
    }
}

enum ObdPidRegistry {
    private static func cToF(_ c: Double) -> Double { c * 9.0 / 5.0 + 32.0 }
    private static func kmhToMph(_ k: Double) -> Double { k * 0.621371 }
    private static func kpaToPsi(_ p: Double) -> Double { p * 0.145038 }

    static let sensors: [SensorPidDefinition] = [
        SensorPidDefinition(
            pidHex: "0C", nameEn: "Engine RPM", nameAr: "دورات المحرك (RPM)",
            unitMetric: "RPM", unitImperial: "RPM", category: .engine,
            normalMinMetric: 600, normalMaxMetric: 4500, decimals: 0,
            decodeMetric: { d in d.count >= 2 ? Double(d[0] * 256 + d[1]) / 4.0 : nil },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "0D", nameEn: "Vehicle Speed", nameAr: "سرعة السيارة",
            unitMetric: "km/h", unitImperial: "mph", category: .engine,
            normalMinMetric: 0, normalMaxMetric: 200, decimals: 0,
            decodeMetric: { d in d.first.map(Double.init) },
            toImperial: kmhToMph
        ),
        SensorPidDefinition(
            pidHex: "05", nameEn: "Coolant Temperature", nameAr: "حرارة سائل التبريد",
            unitMetric: "°C", unitImperial: "°F", category: .temperature,
            normalMinMetric: 75, normalMaxMetric: 103, decimals: 0,
            decodeMetric: { d in d.first.map { Double($0) - 40.0 } },
            toImperial: cToF
        ),
        SensorPidDefinition(
            pidHex: "42", nameEn: "Control Module Voltage", nameAr: "جهد البطارية / الكمبيوتر",
            unitMetric: "V", unitImperial: "V", category: .electrical,
            normalMinMetric: 12.2, normalMaxMetric: 14.8, decimals: 2,
            decodeMetric: { d in d.count >= 2 ? Double(d[0] * 256 + d[1]) / 1000.0 : nil },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "04", nameEn: "Calculated Engine Load", nameAr: "حمل المحرك المحسوب",
            unitMetric: "%", unitImperial: "%", category: .engine,
            normalMinMetric: 10, normalMaxMetric: 85, decimals: 1,
            decodeMetric: { d in d.first.map { Double($0) * 100.0 / 255.0 } },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "06", nameEn: "Short Term Fuel Trim (B1)", nameAr: "تعديل الوقود القصير (بنك 1)",
            unitMetric: "%", unitImperial: "%", category: .fuel,
            normalMinMetric: -10, normalMaxMetric: 10, decimals: 1,
            decodeMetric: { d in d.first.map { (Double($0) - 128.0) * 100.0 / 128.0 } },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "07", nameEn: "Long Term Fuel Trim (B1)", nameAr: "تعديل الوقود الطويل (بنك 1)",
            unitMetric: "%", unitImperial: "%", category: .fuel,
            normalMinMetric: -10, normalMaxMetric: 10, decimals: 1,
            decodeMetric: { d in d.first.map { (Double($0) - 128.0) * 100.0 / 128.0 } },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "0B", nameEn: "Intake Manifold Pressure (MAP)", nameAr: "ضغط ثلاجة المحرك (MAP)",
            unitMetric: "kPa", unitImperial: "psi", category: .airIntake,
            normalMinMetric: 20, normalMaxMetric: 105, decimals: 1,
            decodeMetric: { d in d.first.map(Double.init) },
            toImperial: kpaToPsi
        ),
        SensorPidDefinition(
            pidHex: "0F", nameEn: "Intake Air Temp (IAT)", nameAr: "حرارة الهواء الداخل",
            unitMetric: "°C", unitImperial: "°F", category: .temperature,
            normalMinMetric: 5, normalMaxMetric: 65, decimals: 0,
            decodeMetric: { d in d.first.map { Double($0) - 40.0 } },
            toImperial: cToF
        ),
        SensorPidDefinition(
            pidHex: "10", nameEn: "MAF Air Flow Rate", nameAr: "تدفق الهواء (MAF)",
            unitMetric: "g/s", unitImperial: "g/s", category: .airIntake,
            normalMinMetric: 1.5, normalMaxMetric: 120, decimals: 2,
            decodeMetric: { d in d.count >= 2 ? Double(d[0] * 256 + d[1]) / 100.0 : nil },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "11", nameEn: "Throttle Position (TPS)", nameAr: "نسبة فتح البوابة (TPS)",
            unitMetric: "%", unitImperial: "%", category: .airIntake,
            normalMinMetric: 0, normalMaxMetric: 100, decimals: 1,
            decodeMetric: { d in d.first.map { Double($0) * 100.0 / 255.0 } },
            toImperial: { $0 }
        ),
        SensorPidDefinition(
            pidHex: "2F", nameEn: "Fuel Tank Level", nameAr: "مستوى الوقود بالخزان",
            unitMetric: "%", unitImperial: "%", category: .fuel,
            normalMinMetric: 10, normalMaxMetric: 100, decimals: 0,
            decodeMetric: { d in d.first.map { Double($0) * 100.0 / 255.0 } },
            toImperial: { $0 }
        )
    ]

    static let byPidHex: [String: SensorPidDefinition] = Dictionary(uniqueKeysWithValues: sensors.map { ($0.pidHex, $0) })
}
