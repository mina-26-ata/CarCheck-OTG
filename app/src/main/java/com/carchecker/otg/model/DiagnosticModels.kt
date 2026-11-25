package com.carchecker.otg.model

enum class AppLanguage {
    AR,
    EN
}

enum class MeasurementUnit {
    METRIC,
    IMPERIAL
}

enum class ConnectionMode {
    DISCONNECTED,
    USB_OTG,
    BLUETOOTH
}

enum class ConnectionState {
    DISCONNECTED,
    REQUESTING_PERMISSION,
    CONNECTING,
    INITIALIZING,
    CONNECTED,
    ERROR
}

enum class BluetoothTransportType {
    CLASSIC_SPP,
    BLE_GATT,
    DUAL
}

data class BluetoothAdapterInfo(
    val address: String,
    val name: String,
    val transportType: BluetoothTransportType,
    val isPaired: Boolean,
    val rssi: Int? = null,
    val looksLikeObd: Boolean = false
)

data class UsbAdapterInfo(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val chipsetLabel: String,
    val driverName: String,
    val hasPermission: Boolean,
    val baudRate: Int = 38400,
    val firmwareVersion: String? = null
)

data class DetectedEcu(
    val address: String,
    val nameEn: String,
    val nameAr: String,
    val protocolType: String
)

data class VehicleInfo(
    val vin: String? = null,
    val make: String? = null,
    val country: String? = null,
    val modelYear: Int? = null,
    val protocolName: String? = null,
    val protocolCode: String? = null,
    val calibrationId: String? = null,
    val cvn: String? = null,
    val ecuName: String? = null,
    val batteryVoltage: Double? = null,
    val odometerKm: Double? = null,
    val detectedEcus: List<DetectedEcu> = emptyList()
)

enum class DtcStatus {
    CONFIRMED,
    PENDING,
    PERMANENT,
    STORED_UDS
}

enum class DtcSeverity {
    CRITICAL,
    HIGH,
    MODERATE,
    LOW,
    INFO
}

enum class DtcSystem {
    POWERTRAIN,
    CHASSIS,
    BODY,
    NETWORK
}

data class DtcKnowledgeEntry(
    val code: String,
    val system: DtcSystem,
    val isGeneric: Boolean,
    val severity: DtcSeverity,
    val canDriveSafely: Boolean,
    val titleEn: String,
    val titleAr: String,
    val descriptionEn: String,
    val descriptionAr: String,
    val causesEn: List<String>,
    val causesAr: List<String>,
    val symptomsEn: List<String>,
    val symptomsAr: List<String>,
    val fixStepsEn: List<String>,
    val fixStepsAr: List<String>,
    val estimatedDifficultyEn: String,
    val estimatedDifficultyAr: String,
    val isFromBuiltInDb: Boolean = true
)

data class DiagnosedDtc(
    val code: String,
    val status: DtcStatus,
    val ecuAddress: String = "7E8",
    val ecuNameEn: String = "Engine Control Module (ECM)",
    val ecuNameAr: String = "وحدة التحكم بالمحرك (ECM)",
    val knowledge: DtcKnowledgeEntry
)

data class FreezeFrameReading(
    val pidHex: String,
    val labelEn: String,
    val labelAr: String,
    val valueDisplay: String,
    val unit: String
)

data class ReadinessMonitor(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val supported: Boolean,
    val completed: Boolean,
    val isContinuous: Boolean
)

data class ReadinessSummary(
    val milOn: Boolean,
    val ecuReportedDtcCount: Int,
    val isDiesel: Boolean,
    val distanceWithMilKm: Int? = null,
    val warmupsSinceClear: Int? = null,
    val distanceSinceClearKm: Int? = null,
    val timeWithMilMinutes: Int? = null,
    val timeSinceClearMinutes: Int? = null,
    val monitors: List<ReadinessMonitor> = emptyList()
)

enum class SensorCategory {
    ENGINE,
    FUEL,
    AIR_INTAKE,
    TEMPERATURE,
    ELECTRICAL,
    EMISSIONS,
    OTHER
}

data class LiveSensorReading(
    val pidHex: String,
    val nameEn: String,
    val nameAr: String,
    val value: Double,
    val unit: String,
    val category: SensorCategory,
    val normalMin: Double? = null,
    val normalMax: Double? = null,
    val minRecorded: Double = value,
    val maxRecorded: Double = value,
    val history: List<Double> = listOf(value),
    val decimals: Int = 1,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isOutOfRange: Boolean
        get() = (normalMin != null && value < normalMin) || (normalMax != null && value > normalMax)
}

enum class ObservationSeverity {
    CRITICAL,
    WARNING,
    INFO,
    GOOD
}

enum class ObservationCategory {
    BATTERY_CHARGING,
    COOLING,
    FUEL_AIR,
    IGNITION_ENGINE,
    EMISSIONS_READINESS,
    TRANSMISSION_CHASSIS,
    PRE_PURCHASE_CHECK
}

data class VehicleObservation(
    val id: String,
    val severity: ObservationSeverity,
    val category: ObservationCategory,
    val titleEn: String,
    val titleAr: String,
    val summaryEn: String,
    val summaryAr: String,
    val measuredValue: String? = null,
    val normalRange: String? = null,
    val impactEn: String,
    val impactAr: String,
    val resolutionStepsEn: List<String>,
    val resolutionStepsAr: List<String>
)

data class FullScanReport(
    val id: String,
    val timestampMs: Long,
    val isSimulated: Boolean = false,
    val adapterDescription: String,
    val healthScore: Int,
    val vehicleInfo: VehicleInfo,
    val dtcs: List<DiagnosedDtc>,
    val freezeFrame: List<FreezeFrameReading>,
    val readiness: ReadinessSummary?,
    val sensorSnapshot: List<LiveSensorReading>,
    val observations: List<VehicleObservation>,
    val aiAnalysisText: String? = null
)

data class ScanProgress(
    val isScanning: Boolean = false,
    val stepIndex: Int = 0,
    val totalSteps: Int = 7,
    val stepLabelEn: String = "",
    val stepLabelAr: String = ""
)

data class MilResetResult(
    val clearedConfirmed: Boolean,
    val milIsNowOff: Boolean?,
    val engineRunningBlocked: Boolean,
    val clearedPaths: List<String> = emptyList(),
    val rawDetail: String = ""
)

data class AppSettings(
    val language: AppLanguage = AppLanguage.AR,
    val unit: MeasurementUnit = MeasurementUnit.METRIC,
    val preferredBaudRate: Int = 0, // 0 = Auto-detect
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash"
)
