package com.carchecker.otg.domain.analyzer

import com.carchecker.otg.model.MeasurementUnit

enum class TelemetryRatingLevel {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    IDLE
}

data class TelemetryRating(
    val level: TelemetryRatingLevel,
    val labelAr: String,
    val labelEn: String
)

/**
 * Evaluates key vehicle cockpit readings into an intuitive 4-tier quality grade:
 * ممتاز (Excellent), جيد (Good), مقبول (Fair), سيئ (Poor), or متوقف (Off/Idle).
 */
object TelemetryEvaluator {

    private fun rating(level: TelemetryRatingLevel): TelemetryRating = when (level) {
        TelemetryRatingLevel.EXCELLENT -> TelemetryRating(level, "ممتاز", "Excellent")
        TelemetryRatingLevel.GOOD -> TelemetryRating(level, "جيد", "Good")
        TelemetryRatingLevel.FAIR -> TelemetryRating(level, "مقبول", "Fair")
        TelemetryRatingLevel.POOR -> TelemetryRating(level, "سيئ", "Poor")
        TelemetryRatingLevel.IDLE -> TelemetryRating(level, "متوقف", "Off")
    }

    fun rateRpm(rpm: Double?): TelemetryRating? {
        rpm ?: return null
        return when {
            rpm <= 0.0 -> rating(TelemetryRatingLevel.IDLE)
            rpm in 550.0..1050.0 -> rating(TelemetryRatingLevel.EXCELLENT)
            rpm in 1050.001..3000.0 -> rating(TelemetryRatingLevel.GOOD)
            rpm in 1.0..549.999 || rpm in 3000.001..4500.0 -> rating(TelemetryRatingLevel.FAIR)
            else -> rating(TelemetryRatingLevel.POOR)
        }
    }

    fun rateCoolant(tempValue: Double?, unit: MeasurementUnit): TelemetryRating? {
        tempValue ?: return null
        val tempC = if (unit == MeasurementUnit.IMPERIAL) {
            (tempValue - 32.0) * 5.0 / 9.0
        } else {
            tempValue
        }
        return when {
            tempC in 80.0..100.0 -> rating(TelemetryRatingLevel.EXCELLENT)
            tempC in 60.0..79.999 || tempC in 100.001..106.0 -> rating(TelemetryRatingLevel.GOOD)
            tempC in 20.0..59.999 || tempC in 106.001..112.0 -> rating(TelemetryRatingLevel.FAIR)
            else -> rating(TelemetryRatingLevel.POOR)
        }
    }

    fun rateBatteryVoltage(voltage: Double?): TelemetryRating? {
        voltage ?: return null
        return when {
            voltage in 13.4..14.7 || voltage in 12.5..12.9 -> rating(TelemetryRatingLevel.EXCELLENT)
            voltage in 12.2..12.499 || voltage in 12.901..13.399 || voltage in 14.701..14.95 ->
                rating(TelemetryRatingLevel.GOOD)
            voltage in 11.8..12.199 || voltage in 14.951..15.2 ->
                rating(TelemetryRatingLevel.FAIR)
            else -> rating(TelemetryRatingLevel.POOR)
        }
    }

    fun rateFaultCount(faultCount: Int?): TelemetryRating? {
        faultCount ?: return null
        return when {
            faultCount == 0 -> rating(TelemetryRatingLevel.EXCELLENT)
            faultCount in 1..2 -> rating(TelemetryRatingLevel.FAIR)
            else -> rating(TelemetryRatingLevel.POOR)
        }
    }

    fun rateHealthScore(score: Int): TelemetryRating = when {
        score >= 90 -> rating(TelemetryRatingLevel.EXCELLENT)
        score >= 75 -> rating(TelemetryRatingLevel.GOOD)
        score >= 50 -> rating(TelemetryRatingLevel.FAIR)
        else -> rating(TelemetryRatingLevel.POOR)
    }
}
