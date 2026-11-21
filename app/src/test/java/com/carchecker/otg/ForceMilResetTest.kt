package com.carchecker.otg

import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.data.obd.ObdCommandEngine
import com.carchecker.otg.data.obd.ObdTransport
import com.carchecker.otg.domain.analyzer.VehicleHealthAnalyzer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForceMilResetTest {

    private fun createEngine(): ObdCommandEngine {
        val file = listOf(
            File("app/src/main/assets/dtc_database.json"),
            File("src/main/assets/dtc_database.json")
        ).first { it.exists() }
        val repo = DtcRepository { file.readText(Charsets.UTF_8) }
        return ObdCommandEngine(repo, VehicleHealthAnalyzer())
    }

    private class ScriptedTransport(
        private val protoCode: String,
        private val protoName: String,
        private val responder: (cmd: String, header: String) -> String
    ) : ObdTransport {
        val sentCommands = mutableListOf<Pair<String, String>>()
        var currentHeader: String = "7DF"
        override val isConnected: Boolean = true
        override val description: String = "Scripted($protoName)"

        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit

        override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
            val clean = command.trim().uppercase()
            if (clean.startsWith("ATSH")) {
                currentHeader = clean.removePrefix("ATSH").trim()
                sentCommands.add(currentHeader to clean)
                return Result.success("OK")
            }
            sentCommands.add(currentHeader to clean)
            val resp = when (clean) {
                "ATDPN" -> protoCode
                "ATDP" -> protoName
                "ATH0", "ATH1", "ATE0", "ATL0", "ATS0", "ATSP0", "ATZ" -> "OK"
                else -> responder(clean, currentHeader)
            }
            return Result.success(resp)
        }
    }

    @Test
    fun can11Bit_requiresUdsExtendedSession_succeedsAndTurnsMilOff() = runTest {
        var inExtSession = false
        var milOn = true
        val transport = ScriptedTransport("A6", "ISO 15765-4 (CAN 11/500)") { cmd, header ->
            when {
                cmd == "04" -> "NO DATA" // Broadcast/plain Mode 04 ignored by strict ECM
                cmd == "10 03" && header == "7E0" -> {
                    inExtSession = true
                    "50 03"
                }
                cmd == "14 FF FF FF" && header == "7E0" && inExtSession -> {
                    milOn = false
                    "54"
                }
                cmd == "10 01" && header == "7E0" -> {
                    inExtSession = false
                    "50 01"
                }
                cmd == "0101" -> if (milOn) "41 01 81 07 65 00" else "41 01 00 07 65 00"
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val outcome = engine.forceTurnOffCheckEngine(transport).getOrThrow()

        assertTrue(outcome.clearedConfirmed)
        assertEquals(true, outcome.milIsNowOff)
        assertFalse(outcome.engineRunningBlocked)
        assertTrue(outcome.clearedPaths.contains("UDS 7E0"))
        assertEquals("7DF", transport.currentHeader)
    }

    @Test
    fun can11Bit_engineRunningNrc22_detectsEngineRunningBlock() = runTest {
        val transport = ScriptedTransport("A6", "ISO 15765-4 (CAN 11/500)") { cmd, _ ->
            when (cmd) {
                "04" -> "7F 04 22"
                "10 03" -> "50 03"
                "14 FF FF FF" -> "7F 14 22"
                "10 01" -> "50 01"
                "0101" -> "41 01 82 07 65 00"
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val outcome = engine.forceTurnOffCheckEngine(transport).getOrThrow()

        assertFalse(outcome.clearedConfirmed)
        assertEquals(false, outcome.milIsNowOff)
        assertTrue(outcome.engineRunningBlocked)
    }

    @Test
    fun kwp2000_kLineVehicle_usesKwpClearWithoutCanHeaders() = runTest {
        var milOn = true
        val transport = ScriptedTransport("A5", "ISO 14230-4 (KWP FAST)") { cmd, _ ->
            when (cmd) {
                "04" -> "NO DATA"
                "14 FF 00" -> {
                    milOn = false
                    "54"
                }
                "0101" -> if (milOn) "41 01 81 07 65 00" else "41 01 00 07 65 00"
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val outcome = engine.forceTurnOffCheckEngine(transport).getOrThrow()

        assertTrue(outcome.clearedConfirmed)
        assertEquals(true, outcome.milIsNowOff)
        assertTrue(outcome.clearedPaths.contains("KWP2000 14 FF 00"))
        assertTrue(transport.sentCommands.none { it.second.startsWith("ATSH 7DF") || it.second.startsWith("ATSH 7E0") })
    }

    @Test
    fun can29Bit_uses29BitHeadersAndDoesNotCorruptWith11Bit7DF() = runTest {
        var milOn = true
        val transport = ScriptedTransport("A7", "ISO 15765-4 (CAN 29/500)") { cmd, header ->
            when {
                cmd == "04" && header == "18DB33F1" -> {
                    milOn = false
                    "44"
                }
                cmd == "0101" -> if (milOn) "41 01 81 07 65 00" else "41 01 00 07 65 00"
                else -> "NO DATA"
            }
        }

        val engine = createEngine()
        val outcome = engine.forceTurnOffCheckEngine(transport).getOrThrow()

        assertTrue(outcome.clearedConfirmed)
        assertEquals(true, outcome.milIsNowOff)
        assertEquals("18DB33F1", transport.currentHeader)
        assertTrue(transport.sentCommands.none { it.second.contains("7DF") })
    }
}
