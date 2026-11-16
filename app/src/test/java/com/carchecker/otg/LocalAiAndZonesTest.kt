package com.carchecker.otg

import com.carchecker.otg.data.ai.LocalAiDiagnosticEngine
import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.data.obd.ObdCommandEngine
import com.carchecker.otg.data.obd.SimulatedCarTransport
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.domain.analyzer.VehicleHealthAnalyzer
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.DemoProfile
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.SafetyVerdict
import com.carchecker.otg.model.VehicleBodyStyle
import com.carchecker.otg.model.VehicleInfo
import com.carchecker.otg.model.ZoneStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalAiAndZonesTest {

    private fun createEngine(): ObdCommandEngine {
        val file = listOf(
            File("app/src/main/assets/dtc_database.json"),
            File("src/main/assets/dtc_database.json")
        ).first { it.exists() }
        val repo = DtcRepository { file.readText(Charsets.UTF_8) }
        return ObdCommandEngine(repo, VehicleHealthAnalyzer())
    }

    @Test
    fun healthyProfile_mapsZonesHealthyAndProducesOptimalAiVerdict() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.HEALTHY)
        sim.connect()
        val report = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()

        val zones = CarZoneMapper.mapZones(report, report.sensorSnapshot)
        assertEquals(ZoneStatus.HEALTHY, zones[CarSubsystemZone.ENGINE]?.status)
        assertEquals(ZoneStatus.HEALTHY, zones[CarSubsystemZone.TRANSMISSION]?.status)
        assertEquals(ZoneStatus.HEALTHY, zones[CarSubsystemZone.ELECTRICAL]?.status)
        assertEquals(ZoneStatus.HEALTHY, zones[CarSubsystemZone.FUEL_EXHAUST]?.status)
        assertEquals(ZoneStatus.HEALTHY, zones[CarSubsystemZone.BRAKES_CHASSIS]?.status)

        val ai = LocalAiDiagnosticEngine().analyze(report, report.sensorSnapshot, MeasurementUnit.METRIC)
        assertEquals(SafetyVerdict.SAFE_MONITOR, ai.verdict)
        assertTrue(ai.hypotheses.isEmpty())
        assertTrue(ai.prePurchaseNoteAr != null)
        assertTrue(ai.priorityStepsAr.isNotEmpty())

        // Verify OPTIMAL verdict when readiness monitors are all completed
        val completedReadiness = report.readiness?.copy(
            distanceSinceClearKm = 350,
            warmupsSinceClear = 15,
            monitors = report.readiness?.monitors?.map { it.copy(completed = true) }.orEmpty()
        )
        val cleanObservations = report.observations.filter { it.id != "recent_reset_warning" }
        val fullyReadyReport = report.copy(
            healthScore = 100,
            readiness = completedReadiness,
            observations = cleanObservations
        )
        val optimalAi = LocalAiDiagnosticEngine().analyze(fullyReadyReport, fullyReadyReport.sensorSnapshot, MeasurementUnit.METRIC)
        assertEquals(SafetyVerdict.OPTIMAL, optimalAi.verdict)
    }

    @Test
    fun misfireFuelProfile_mapsEngineAndExhaustFaultsAndWarnsBeforeCatReplacement() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.ENGINE_MISFIRE_FUEL)
        sim.connect()
        val report = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()

        val zones = CarZoneMapper.mapZones(report, report.sensorSnapshot)
        assertTrue(zones[CarSubsystemZone.ENGINE]?.faultCodes?.any { it.code == "P0300" } == true)
        assertTrue(zones[CarSubsystemZone.FUEL_EXHAUST]?.faultCodes?.any { it.code == "P0171" || it.code == "P0420" } == true)
        assertTrue(zones[CarSubsystemZone.ENGINE]?.status in setOf(ZoneStatus.WARNING, ZoneStatus.CRITICAL))

        val aiEngine = LocalAiDiagnosticEngine()
        val ai = aiEngine.analyze(report, report.sensorSnapshot, MeasurementUnit.METRIC)
        assertTrue(ai.hypotheses.any { it.id == "ai_combustion_chain" })
        assertTrue(ai.summaryAr.contains("الصحة"))

        val qAnswer = aiEngine.answerQuestion(
            question = "هل القيادة آمنة وأين مكان الأعطال؟",
            report = report,
            liveSensors = report.sensorSnapshot,
            unit = MeasurementUnit.METRIC,
            language = AppLanguage.AR
        )
        assertTrue(qAnswer.isNotBlank())

        val codeAnswer = aiEngine.answerQuestion(
            question = "اشرح كود P0420",
            report = report,
            liveSensors = report.sensorSnapshot,
            unit = MeasurementUnit.METRIC,
            language = AppLanguage.AR
        )
        assertTrue(codeAnswer.contains("دبة البيئة") || codeAnswer.contains("الموقع على السيارة"))
    }

    @Test
    fun transAbsCoolingProfile_detectsElectricalAndAbsZonesAndAiCascade() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.TRANS_ABS_COOLING)
        sim.connect()
        val report = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()

        val zones = CarZoneMapper.mapZones(report, report.sensorSnapshot)
        assertTrue(zones[CarSubsystemZone.TRANSMISSION]?.faultCodes?.any { it.code == "P0700" } == true)
        assertTrue(zones[CarSubsystemZone.BRAKES_CHASSIS]?.faultCodes?.any { it.code == "C0035" } == true)
        assertEquals(ZoneStatus.CRITICAL, zones[CarSubsystemZone.ELECTRICAL]?.status)

        val ai = LocalAiDiagnosticEngine().analyze(report, report.sensorSnapshot, MeasurementUnit.METRIC)
        assertTrue(ai.hypotheses.any { it.id == "ai_elec_cascade" })
        assertTrue(ai.hypotheses.any { it.id == "ai_drivetrain_abs" })
        assertEquals(SafetyVerdict.STOP_IMMEDIATELY, ai.verdict)
    }

    @Test
    fun detectBodyStyle_matchesSedanSuvAndPickupVehicles() = runTest {
        val engine = createEngine()

        val healthySim = SimulatedCarTransport(DemoProfile.HEALTHY).apply { connect() }
        val healthyReport = engine.runFullScan(healthySim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()
        assertEquals(
            VehicleBodyStyle.SEDAN,
            CarZoneMapper.detectBodyStyle(healthyReport.vehicleInfo, healthyReport.adapterDescription)
        )

        val suvSim = SimulatedCarTransport(DemoProfile.TRANS_ABS_COOLING).apply { connect() }
        val suvReport = engine.runFullScan(suvSim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()
        assertEquals(
            VehicleBodyStyle.SUV,
            CarZoneMapper.detectBodyStyle(suvReport.vehicleInfo, suvReport.adapterDescription)
        )

        assertEquals(
            VehicleBodyStyle.PICKUP_TRUCK,
            CarZoneMapper.detectBodyStyle(
                VehicleInfo(vin = "1FTFW1E84MFA00001", make = "Ford Truck"),
                null
            )
        )
    }

    @Test
    fun clearDtcs_turnsOffCheckEngineMilAndClearsConfirmedPendingUdsFaults() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.TRANS_ABS_COOLING).apply { connect() }

        val before = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()
        assertEquals(true, before.readiness?.milOn)
        assertTrue(before.dtcs.isNotEmpty())

        val clearResult = engine.clearDtcs(sim)
        assertTrue(clearResult.isSuccess)

        val after = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()
        assertEquals(false, after.readiness?.milOn)
        assertTrue(after.dtcs.none { it.status != com.carchecker.otg.model.DtcStatus.PERMANENT })
    }
}
