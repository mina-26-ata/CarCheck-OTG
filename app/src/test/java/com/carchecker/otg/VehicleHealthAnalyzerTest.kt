package com.carchecker.otg

import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.data.obd.ObdCommandEngine
import com.carchecker.otg.data.obd.SimulatedCarTransport
import com.carchecker.otg.domain.analyzer.VehicleHealthAnalyzer
import com.carchecker.otg.model.DemoProfile
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.ObservationSeverity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VehicleHealthAnalyzerTest {

    private fun createEngine(): ObdCommandEngine {
        val file = listOf(
            File("app/src/main/assets/dtc_database.json"),
            File("src/main/assets/dtc_database.json")
        ).first { it.exists() }
        val repo = DtcRepository { file.readText(Charsets.UTF_8) }
        return ObdCommandEngine(repo, VehicleHealthAnalyzer())
    }

    @Test
    fun healthyProfile_producesHighScoreAndNoFaults() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.HEALTHY)
        sim.connect()
        val report = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()

        assertTrue(report.dtcs.isEmpty())
        assertTrue(report.healthScore >= 85)
        assertTrue(report.observations.any { it.severity == ObservationSeverity.GOOD })
    }

    @Test
    fun misfireFuelProfile_detectsFaultsAndCorrelatedObservations() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.ENGINE_MISFIRE_FUEL)
        sim.connect()
        val report = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()

        val codes = report.dtcs.map { it.code }.toSet()
        assertTrue("P0300" in codes)
        assertTrue("P0171" in codes)
        assertTrue("P0420" in codes)
        assertTrue(report.observations.any { it.id == "corr_misfire_lean" })
        assertTrue(report.observations.any { it.id == "fuel_trim_lean" })
        assertTrue(report.healthScore < 65)
    }

    @Test
    fun transAbsCoolingProfile_detectsMultiEcuUdsAndBatteryAlert() = runTest {
        val engine = createEngine()
        val sim = SimulatedCarTransport(DemoProfile.TRANS_ABS_COOLING)
        sim.connect()
        val report = engine.runFullScan(sim, MeasurementUnit.METRIC, isSimulated = true) {}.getOrThrow()

        val codes = report.dtcs.map { it.code }.toSet()
        assertTrue("P0128" in codes)
        assertTrue("P0700" in codes)
        assertTrue("C0035" in codes)
        assertTrue("U0100" in codes)
        assertTrue(report.observations.any { it.id == "bat_undercharge" })
        assertTrue(report.observations.any { it.id == "coolant_cold_thermostat" })
    }
}
