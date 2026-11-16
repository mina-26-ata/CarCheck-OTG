package com.carchecker.otg.model

/**
 * Physical subsystems represented on the interactive X-Ray car schematic.
 */
enum class CarSubsystemZone {
    ENGINE,
    TRANSMISSION,
    ELECTRICAL,
    FUEL_EXHAUST,
    BRAKES_CHASSIS,
    BODY_CABIN
}

/**
 * Detected vehicle body geometry rendered inside the 3D X-Ray car schematic.
 */
enum class VehicleBodyStyle {
    SEDAN,
    SUV,
    PICKUP_TRUCK,
    HATCHBACK,
    COUPE
}

enum class ZoneStatus {
    CRITICAL,
    WARNING,
    HEALTHY,
    IDLE
}

data class CarZoneSummary(
    val zone: CarSubsystemZone,
    val status: ZoneStatus,
    val faultCodes: List<DiagnosedDtc> = emptyList(),
    val observations: List<VehicleObservation> = emptyList(),
    val liveHighlights: List<LiveSensorReading> = emptyList()
) {
    val issueCount: Int get() = faultCodes.size + observations.count {
        it.severity == ObservationSeverity.CRITICAL || it.severity == ObservationSeverity.WARNING
    }
}

enum class SafetyVerdict {
    STOP_IMMEDIATELY,
    CAUTION_REPAIR_SOON,
    SAFE_MONITOR,
    OPTIMAL
}

data class AiHypothesis(
    val id: String,
    val confidencePercent: Int,
    val zone: CarSubsystemZone,
    val relatedCodes: List<String>,
    val titleEn: String,
    val titleAr: String,
    val reasoningEn: String,
    val reasoningAr: String,
    val firstChecksEn: List<String>,
    val firstChecksAr: List<String>
)

data class SmartAiReport(
    val verdict: SafetyVerdict,
    val summaryEn: String,
    val summaryAr: String,
    val hypotheses: List<AiHypothesis>,
    val priorityStepsEn: List<String>,
    val priorityStepsAr: List<String>,
    val prePurchaseNoteEn: String? = null,
    val prePurchaseNoteAr: String? = null
)

data class AiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)
