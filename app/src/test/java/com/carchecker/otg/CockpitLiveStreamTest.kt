package com.carchecker.otg

import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.data.obd.ObdCommandEngine
import com.carchecker.otg.data.obd.ObdTransport
import com.carchecker.otg.data.obd.SimulatedCarTransport
import com.carchecker.otg.domain.analyzer.VehicleHealthAnalyzer
import com.carchecker.otg.model.DemoProfile
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.MeasurementUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CockpitLiveStreamTest {

    private fun createEngine(): ObdCommandEngine {
        val file = listOf(
            File("app/src/main/assets/dtc_database.json"),
            File("src/main/assets/dtc_database.json")
        ).first { it.exists() }
        val repo = DtcRepository { file.readText(Charsets.UTF_8) }
        return ObdCommandEngine(repo, VehicleHealthAnalyzer())
    }

    private class RecordingTransport(
        private val responder: (String) -> String
    ) : ObdTransport {
        val sentCommands = mutableListOf<String>()
        override val isConnected: Boolean = true
        override val description: String = "RecordingELM327"
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit

        override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
            val clean = command.trim().uppercase()
            sentCommands.add(clean)
            return Result.success(responder(clean))
        }
    }

    private fun encodeRpm(rpm: Int): String {
        val raw = rpm * 4
        return "41 0C %02X %02X".format((raw shr 8) and 0xFF, raw and 0xFF)
    }

    private fun encodeSpeed(kmh: Int): String = "41 0D %02X".format(kmh and 0xFF)

    @Test
    fun fastTick_tracksLiveThrottleRevving_andUsesSingleResponseOptimization() = runTest {
        val rpmSequence = ArrayDeque(listOf(740, 2850, 5100))
        val speedSequence = ArrayDeque(listOf(0, 45, 88))

        val transport = RecordingTransport { cmd ->
            when (cmd) {
                "010C1" -> encodeRpm(rpmSequence.removeFirst())
                "010D1" -> encodeSpeed(speedSequence.removeFirst())
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val cache = linkedMapOf<String, LiveSensorReading>()

        repeat(3) {
            val readings = engine.pollCockpitGaugesOnce(
                transport = transport,
                unit = MeasurementUnit.METRIC,
                existing = cache,
                includeSlowPids = false
            )
            readings.forEach { cache[it.pidHex] = it }
        }

        val rpm = cache["0C"]
        val speed = cache["0D"]
        assertNotNull(rpm)
        assertNotNull(speed)
        assertEquals(5100.0, rpm!!.value, 0.5)
        assertEquals(740.0, rpm.minRecorded, 0.5)
        assertEquals(5100.0, rpm.maxRecorded, 0.5)
        assertEquals(listOf(740.0, 2850.0, 5100.0), rpm.history)
        assertEquals(88.0, speed!!.value, 0.5)
        assertEquals(listOf(0.0, 45.0, 88.0), speed.history)

        // Only fast single-response RPM + Speed commands sent (no coolant/voltage/ATRV overhead)
        assertEquals(
            listOf("010C1", "010D1", "010C1", "010D1", "010C1", "010D1"),
            transport.sentCommands
        )
    }

    @Test
    fun cheapElmClone_rejectingFastSuffix_fallsBackAutomaticallyToStandardPids() = runTest {
        val rpmSequence = ArrayDeque(listOf(1950, 3400))
        val speedSequence = ArrayDeque(listOf(25, 62))

        val transport = RecordingTransport { cmd ->
            when (cmd) {
                "010C1", "010D1" -> "?" // Cheap clone rejects single-response suffix
                "010C" -> encodeRpm(rpmSequence.removeFirst())
                "010D" -> encodeSpeed(speedSequence.removeFirst())
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val cache = linkedMapOf<String, LiveSensorReading>()

        // Tick 1: probes 010C1 once, gets '?', falls back immediately to 010C & 010D
        val first = engine.pollCockpitGaugesOnce(transport, MeasurementUnit.METRIC, cache, includeSlowPids = false)
        first.forEach { cache[it.pidHex] = it }
        assertEquals(1950.0, cache["0C"]!!.value, 0.5)
        assertEquals(25.0, cache["0D"]!!.value, 0.5)

        // Tick 2: remembers clone does not support suffix -> sends ONLY 010C and 010D directly
        transport.sentCommands.clear()
        val second = engine.pollCockpitGaugesOnce(transport, MeasurementUnit.METRIC, cache, includeSlowPids = false)
        second.forEach { cache[it.pidHex] = it }
        assertEquals(3400.0, cache["0C"]!!.value, 0.5)
        assertEquals(62.0, cache["0D"]!!.value, 0.5)
        assertEquals(listOf("010C", "010D"), transport.sentCommands)
    }

    @Test
    fun slowTick_refreshesCoolantAndFallsBackToAtrvWhenPid42Unsupported() = runTest {
        val transport = RecordingTransport { cmd ->
            when (cmd) {
                "010C1" -> encodeRpm(1100)
                "010D1" -> encodeSpeed(18)
                "01051" -> "41 05 84" // 0x84 = 132 -> 132 - 40 = 92°C
                "01421", "0142" -> "NO DATA"
                "ATRV" -> "14.2V"
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val readings = engine.pollCockpitGaugesOnce(
            transport = transport,
            unit = MeasurementUnit.METRIC,
            existing = emptyMap(),
            includeSlowPids = true
        ).associateBy { it.pidHex }

        assertEquals(1100.0, readings["0C"]!!.value, 0.5)
        assertEquals(18.0, readings["0D"]!!.value, 0.5)
        assertEquals(92.0, readings["05"]!!.value, 0.5)
        assertEquals(14.2, readings["42"]!!.value, 0.05)
        assertTrue("ATRV" in transport.sentCommands)

        // Unsupported PID 42 must NOT disable fast single-response polling for RPM/Speed on the next tick
        transport.sentCommands.clear()
        val nextTick = engine.pollCockpitGaugesOnce(transport, MeasurementUnit.METRIC, readings, includeSlowPids = false)
        assertEquals(2, nextTick.size)
        assertEquals(listOf("010C1", "010D1"), transport.sentCommands)
    }

    @Test
    fun simulatedCarTransport_supportsFastSingleResponseCockpitPolling() = runTest {
        val sim = SimulatedCarTransport(DemoProfile.HEALTHY)
        sim.connect()
        val engine = createEngine()
        assertTrue(engine.initializeAdapter(sim).isSuccess)

        val slowReadings = engine.pollCockpitGaugesOnce(
            transport = sim,
            unit = MeasurementUnit.METRIC,
            existing = emptyMap(),
            includeSlowPids = true
        ).associateBy { it.pidHex }

        assertNotNull(slowReadings["0C"])
        assertNotNull(slowReadings["0D"])
        assertNotNull(slowReadings["05"])
        assertNotNull(slowReadings["42"])
        assertTrue(slowReadings["0C"]!!.value in 650.0..850.0)
        assertEquals(90.0, slowReadings["05"]!!.value, 0.5)
        assertEquals(14.12, slowReadings["42"]!!.value, 0.05)
    }
}
