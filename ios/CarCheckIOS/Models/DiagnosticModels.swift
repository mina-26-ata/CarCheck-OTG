import Foundation

enum AppLanguage: String, Codable, CaseIterable {
    case ar = "AR"
    case en = "EN"
}

enum MeasurementUnit: String, Codable, CaseIterable {
    case metric = "METRIC"
    case imperial = "IMPERIAL"
}

enum ConnectionMode: String, Codable {
    case disconnected
    case ble
    case wifi
}

enum ConnectionState: String, Codable {
    case disconnected
    case connecting
    case connected
    case error
}

struct BleDeviceInfo: Identifiable, Equatable {
    let id: UUID
    let name: String
    let rssi: Int
}

struct DetectedEcu: Codable, Equatable, Identifiable {
    var id: String { address }
    let address: String
    let nameEn: String
    let nameAr: String
    let protocolLabel: String
}

struct VehicleInfo: Codable, Equatable {
    var vin: String? = nil
    var make: String? = nil
    var country: String? = nil
    var modelYear: Int? = nil
    var protocolName: String? = nil
    var batteryVoltage: Double? = nil
    var calibrationId: String? = nil
    var cvn: String? = nil
    var ecuName: String? = nil
    var odometerKm: Double? = nil
    var detectedEcus: [DetectedEcu] = []
}

enum DtcStatus: String, Codable, CaseIterable {
    case confirmed = "CONFIRMED"
    case pending = "PENDING"
    case permanent = "PERMANENT"
    case storedUds = "STORED_UDS"

    func label(isAr: Bool) -> String {
        switch self {
        case .confirmed: return isAr ? "مؤكد" : "Confirmed"
        case .pending: return isAr ? "معلّق" : "Pending"
        case .permanent: return isAr ? "دائم" : "Permanent"
        case .storedUds: return isAr ? "مخزّن بالوحدة" : "Stored (UDS)"
        }
    }
}

enum DtcSeverity: String, Codable, CaseIterable {
    case critical = "CRITICAL"
    case high = "HIGH"
    case moderate = "MODERATE"
    case low = "LOW"
    case info = "INFO"

    func label(isAr: Bool) -> String {
        switch self {
        case .critical: return isAr ? "حرج" : "Critical"
        case .high: return isAr ? "عالٍ" : "High"
        case .moderate: return isAr ? "متوسط" : "Moderate"
        case .low: return isAr ? "منخفض" : "Low"
        case .info: return isAr ? "معلومة" : "Info"
        }
    }
}

enum DtcSystem: String, Codable, CaseIterable {
    case powertrain = "POWERTRAIN"
    case chassis = "CHASSIS"
    case body = "BODY"
    case network = "NETWORK"

    func label(isAr: Bool) -> String {
        switch self {
        case .powertrain: return isAr ? "المحرك والقير" : "Powertrain"
        case .chassis: return isAr ? "الشاصي والفرامل" : "Chassis"
        case .body: return isAr ? "الهيكل والمقصورة" : "Body"
        case .network: return isAr ? "الشبكة والاتصال" : "Network"
        }
    }
}

struct DtcKnowledgeEntry: Codable, Equatable, Identifiable {
    var id: String { code }
    let code: String
    let system: DtcSystem
    let isGeneric: Bool
    let severity: DtcSeverity
    let canDriveSafely: Bool
    let titleEn: String
    let titleAr: String
    let descriptionEn: String
    let descriptionAr: String
    let causesEn: [String]
    let causesAr: [String]
    let symptomsEn: [String]
    let symptomsAr: [String]
    let fixStepsEn: [String]
    let fixStepsAr: [String]
    let estimatedDifficultyEn: String
    let estimatedDifficultyAr: String
    var isFromBuiltInDb: Bool? = true
}

struct DiagnosedDtc: Codable, Equatable, Identifiable {
    var id: String { "\(code)-\(status.rawValue)-\(ecuAddress)" }
    let code: String
    let status: DtcStatus
    let ecuAddress: String
    let ecuNameEn: String
    let ecuNameAr: String
    let knowledge: DtcKnowledgeEntry
}

struct FreezeFrameReading: Codable, Equatable, Identifiable {
    var id: String { pidHex }
    let pidHex: String
    let labelEn: String
    let labelAr: String
    let valueDisplay: String
    let unit: String
}

struct ReadinessMonitor: Codable, Equatable, Identifiable {
    let id: String
    let nameEn: String
    let nameAr: String
    let supported: Bool
    let completed: Bool
    let isContinuous: Bool
}

struct ReadinessSummary: Codable, Equatable {
    let milOn: Bool
    let ecuReportedDtcCount: Int
    let isDiesel: Bool
    var distanceWithMilKm: Int? = nil
    var warmupsSinceClear: Int? = nil
    var distanceSinceClearKm: Int? = nil
    var monitors: [ReadinessMonitor] = []
}

enum SensorCategory: String, Codable, CaseIterable {
    case engine = "ENGINE"
    case fuel = "FUEL"
    case airIntake = "AIR_INTAKE"
    case temperature = "TEMPERATURE"
    case electrical = "ELECTRICAL"
    case emissions = "EMISSIONS"
    case other = "OTHER"

    func label(isAr: Bool) -> String {
        switch self {
        case .engine: return isAr ? "المحرك" : "Engine"
        case .fuel: return isAr ? "الوقود" : "Fuel"
        case .airIntake: return isAr ? "الهواء" : "Air"
        case .temperature: return isAr ? "الحرارة" : "Temp"
        case .electrical: return isAr ? "الكهرباء" : "Electrical"
        case .emissions: return isAr ? "الانبعاثات" : "Emissions"
        case .other: return isAr ? "أخرى" : "Other"
        }
    }
}

struct LiveSensorReading: Codable, Equatable, Identifiable {
    var id: String { pidHex }
    let pidHex: String
    let nameEn: String
    let nameAr: String
    let value: Double
    let unit: String
    let category: SensorCategory
    var normalMin: Double? = nil
    var normalMax: Double? = nil
    var minRecorded: Double
    var maxRecorded: Double
    var history: [Double] = []
    let decimals: Int
    let timestampMs: Int64

    var isOutOfRange: Bool {
        if let nMin = normalMin, value < nMin { return true }
        if let nMax = normalMax, value > nMax { return true }
        return false
    }
}

enum ObservationSeverity: String, Codable {
    case critical = "CRITICAL"
    case warning = "WARNING"
    case info = "INFO"
    case good = "GOOD"

    func label(isAr: Bool) -> String {
        switch self {
        case .critical: return isAr ? "حرج" : "Critical"
        case .warning: return isAr ? "تحذير" : "Warning"
        case .info: return isAr ? "ملاحظة" : "Info"
        case .good: return isAr ? "جيد" : "Good"
        }
    }
}

enum ObservationCategory: String, Codable {
    case batteryCharging
    case cooling
    case fuelAir
    case ignitionEngine
    case emissionsReadiness
    case transmissionChassis
    case prePurchaseCheck
}

struct VehicleObservation: Codable, Equatable, Identifiable {
    let id: String
    let severity: ObservationSeverity
    let category: ObservationCategory
    let titleEn: String
    let titleAr: String
    let summaryEn: String
    let summaryAr: String
    var measuredValue: String? = nil
    var normalRange: String? = nil
    let impactEn: String
    let impactAr: String
    let resolutionStepsEn: [String]
    let resolutionStepsAr: [String]
}

struct FullScanReport: Codable, Equatable, Identifiable {
    let id: String
    let timestampMs: Int64
    let adapterDescription: String
    let healthScore: Int
    let vehicleInfo: VehicleInfo
    let dtcs: [DiagnosedDtc]
    let freezeFrame: [FreezeFrameReading]
    let readiness: ReadinessSummary?
    let sensorSnapshot: [LiveSensorReading]
    let observations: [VehicleObservation]
}

struct ScanProgress: Equatable {
    var isScanning: Bool = false
    var stepIndex: Int = 0
    var totalSteps: Int = 7
    var stepLabelEn: String = ""
    var stepLabelAr: String = ""
}

struct MilResetResult: Equatable {
    let clearedConfirmed: Bool
    let milIsNowOff: Bool?
    let engineRunningBlocked: Bool
    let clearedPaths: [String]
    let rawDetail: String
}

enum CarSubsystemZone: String, CaseIterable, Identifiable {
    case engine
    case cooling
    case battery
    case fuelAir
    case transmission
    case exhaust
    case brakesChassis
    case bodyNetwork

    var id: String { rawValue }

    func title(isAr: Bool) -> String {
        switch self {
        case .engine: return isAr ? "المحرك والإشعال" : "Engine & Ignition"
        case .cooling: return isAr ? "نظام التبريد" : "Cooling System"
        case .battery: return isAr ? "البطارية والدينمو" : "Battery & Charging"
        case .fuelAir: return isAr ? "الوقود والهواء" : "Fuel & Air Intake"
        case .transmission: return isAr ? "ناقل الحركة (القير)" : "Transmission"
        case .exhaust: return isAr ? "العادم ودبة التلوث" : "Exhaust & Catalyst"
        case .brakesChassis: return isAr ? "الفرامل والثبات ABS" : "Brakes & ABS"
        case .bodyNetwork: return isAr ? "المقصورة والشبكة" : "Body & Network"
        }
    }
}

enum CarBodyStyle: String, Codable {
    case sedan
    case suv
    case pickup
    case hatchback
    case coupe
}

enum ZoneStatus: String {
    case healthy
    case warning
    case critical
    case unscanned
}

struct CarZoneSummary: Identifiable {
    var id: String { zone.rawValue }
    let zone: CarSubsystemZone
    let status: ZoneStatus
    let faultCodes: [DiagnosedDtc]
    let observations: [VehicleObservation]
}

enum SafetyVerdict: String, Codable {
    case stopImmediately
    case cautionRepairSoon
    case safeMonitor
    case optimal

    func label(isAr: Bool) -> String {
        switch self {
        case .stopImmediately: return isAr ? "توقّف وافحص فوراً" : "Stop & Inspect Immediately"
        case .cautionRepairSoon: return isAr ? "قيادة بحذر — يُنصح بالصيانة قريباً" : "Drive with Caution — Repair Soon"
        case .safeMonitor: return isAr ? "آمنة للقيادة مع المتابعة" : "Safe to Drive — Monitor"
        case .optimal: return isAr ? "الحالة ممتازة" : "Optimal Condition"
        }
    }
}

struct AiHypothesis: Identifiable {
    let id: String
    let confidencePercent: Int
    let zone: CarSubsystemZone
    let titleEn: String
    let titleAr: String
    let reasoningEn: String
    let reasoningAr: String
}

struct SmartAiReport {
    let verdict: SafetyVerdict
    let summaryEn: String
    let summaryAr: String
    let hypotheses: [AiHypothesis]
    let priorityStepsEn: [String]
    let priorityStepsAr: [String]
}

struct AiChatMessage: Identifiable {
    let id = UUID().uuidString
    let isUser: Bool
    let text: String
}

enum TelemetryRatingLevel {
    case excellent, good, fair, poor, idle
}

struct TelemetryRating {
    let level: TelemetryRatingLevel
    let labelAr: String
    let labelEn: String
}

enum TelemetryEvaluator {
    private static func make(_ level: TelemetryRatingLevel) -> TelemetryRating {
        switch level {
        case .excellent: return TelemetryRating(level: level, labelAr: "ممتاز", labelEn: "Excellent")
        case .good: return TelemetryRating(level: level, labelAr: "جيد", labelEn: "Good")
        case .fair: return TelemetryRating(level: level, labelAr: "مقبول", labelEn: "Fair")
        case .poor: return TelemetryRating(level: level, labelAr: "سيئ", labelEn: "Poor")
        case .idle: return TelemetryRating(level: level, labelAr: "متوقف", labelEn: "Off")
        }
    }

    static func rateRpm(_ rpm: Double?) -> TelemetryRating? {
        guard let rpm else { return nil }
        if rpm <= 0 { return make(.idle) }
        if (550...1050).contains(rpm) { return make(.excellent) }
        if rpm > 1050 && rpm <= 3000 { return make(.good) }
        if (rpm > 0 && rpm < 550) || (rpm > 3000 && rpm <= 4500) { return make(.fair) }
        return make(.poor)
    }

    static func rateCoolant(_ temp: Double?, unit: MeasurementUnit) -> TelemetryRating? {
        guard let temp else { return nil }
        let tempC = unit == .imperial ? (temp - 32.0) * 5.0 / 9.0 : temp
        if (80...100).contains(tempC) { return make(.excellent) }
        if (60..<80).contains(tempC) || (tempC > 100 && tempC <= 106) { return make(.good) }
        if (20..<60).contains(tempC) || (tempC > 106 && tempC <= 112) { return make(.fair) }
        return make(.poor)
    }

    static func rateBatteryVoltage(_ v: Double?) -> TelemetryRating? {
        guard let v else { return nil }
        if (13.4...14.7).contains(v) || (12.5...12.9).contains(v) { return make(.excellent) }
        if (12.2..<12.5).contains(v) || (v > 12.9 && v < 13.4) || (v > 14.7 && v <= 14.95) { return make(.good) }
        if (11.8..<12.2).contains(v) || (v > 14.95 && v <= 15.2) { return make(.fair) }
        return make(.poor)
    }

    static func rateHealthScore(_ score: Int) -> TelemetryRating {
        if score >= 90 { return make(.excellent) }
        if score >= 75 { return make(.good) }
        if score >= 50 { return make(.fair) }
        return make(.poor)
    }
}

struct AppSettings: Codable {
    var language: AppLanguage = .ar
    var unit: MeasurementUnit = .metric
    var wifiHost: String = "192.168.0.10"
    var wifiPort: Int = 35000
}
