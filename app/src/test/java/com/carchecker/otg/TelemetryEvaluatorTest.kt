package com.carchecker.otg

import com.carchecker.otg.domain.analyzer.TelemetryEvaluator
import com.carchecker.otg.domain.analyzer.TelemetryRatingLevel
import com.carchecker.otg.model.MeasurementUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryEvaluatorTest {

    @Test
    fun rateRpm_coversOffExcellentGoodFairPoor() {
        assertNull(TelemetryEvaluator.rateRpm(null))
        assertEquals(TelemetryRatingLevel.IDLE, TelemetryEvaluator.rateRpm(0.0)?.level)
        assertEquals("متوقف", TelemetryEvaluator.rateRpm(0.0)?.labelAr)
        assertEquals(TelemetryRatingLevel.EXCELLENT, TelemetryEvaluator.rateRpm(690.0)?.level)
        assertEquals("ممتاز", TelemetryEvaluator.rateRpm(690.0)?.labelAr)
        assertEquals(TelemetryRatingLevel.GOOD, TelemetryEvaluator.rateRpm(2200.0)?.level)
        assertEquals("جيد", TelemetryEvaluator.rateRpm(2200.0)?.labelAr)
        assertEquals(TelemetryRatingLevel.FAIR, TelemetryEvaluator.rateRpm(3600.0)?.level)
        assertEquals("مقبول", TelemetryEvaluator.rateRpm(3600.0)?.labelAr)
        assertEquals(TelemetryRatingLevel.POOR, TelemetryEvaluator.rateRpm(5500.0)?.level)
        assertEquals("سيئ", TelemetryEvaluator.rateRpm(5500.0)?.labelAr)
    }

    @Test
    fun rateCoolant_handlesMetricAndImperialUnits() {
        assertNull(TelemetryEvaluator.rateCoolant(null, MeasurementUnit.METRIC))
        assertEquals(TelemetryRatingLevel.EXCELLENT, TelemetryEvaluator.rateCoolant(94.0, MeasurementUnit.METRIC)?.level)
        assertEquals(TelemetryRatingLevel.GOOD, TelemetryEvaluator.rateCoolant(72.0, MeasurementUnit.METRIC)?.level)
        assertEquals(TelemetryRatingLevel.FAIR, TelemetryEvaluator.rateCoolant(45.0, MeasurementUnit.METRIC)?.level)
        assertEquals(TelemetryRatingLevel.POOR, TelemetryEvaluator.rateCoolant(118.0, MeasurementUnit.METRIC)?.level)
        // 201°F is ~93.9°C -> Excellent
        assertEquals(TelemetryRatingLevel.EXCELLENT, TelemetryEvaluator.rateCoolant(201.0, MeasurementUnit.IMPERIAL)?.level)
    }

    @Test
    fun rateBatteryFaultsAndHealthScore_gradeCorrectly() {
        assertEquals(TelemetryRatingLevel.EXCELLENT, TelemetryEvaluator.rateBatteryVoltage(13.9)?.level)
        assertEquals(TelemetryRatingLevel.GOOD, TelemetryEvaluator.rateBatteryVoltage(12.3)?.level)
        assertEquals(TelemetryRatingLevel.FAIR, TelemetryEvaluator.rateBatteryVoltage(11.9)?.level)
        assertEquals(TelemetryRatingLevel.POOR, TelemetryEvaluator.rateBatteryVoltage(11.2)?.level)

        assertEquals(TelemetryRatingLevel.EXCELLENT, TelemetryEvaluator.rateFaultCount(0)?.level)
        assertEquals(TelemetryRatingLevel.FAIR, TelemetryEvaluator.rateFaultCount(1)?.level)
        assertEquals(TelemetryRatingLevel.POOR, TelemetryEvaluator.rateFaultCount(6)?.level)

        assertEquals(TelemetryRatingLevel.EXCELLENT, TelemetryEvaluator.rateHealthScore(95).level)
        assertEquals(TelemetryRatingLevel.GOOD, TelemetryEvaluator.rateHealthScore(82).level)
        assertEquals(TelemetryRatingLevel.FAIR, TelemetryEvaluator.rateHealthScore(60).level)
        assertEquals(TelemetryRatingLevel.POOR, TelemetryEvaluator.rateHealthScore(19).level)
    }
}
