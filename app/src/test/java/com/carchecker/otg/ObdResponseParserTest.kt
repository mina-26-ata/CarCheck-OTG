package com.carchecker.otg

import com.carchecker.otg.data.obd.ObdPidRegistry
import com.carchecker.otg.data.obd.ObdResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdResponseParserTest {

    @Test
    fun parseCanDtcs_decodesMultipleCodesCorrectly() {
        val raw = "43 03 03 00 01 71 04 20"
        val dtcs = ObdResponseParser.parseDtcs(
            raw = raw,
            expectedModeResponse = 0x43,
            isCanProtocol = true,
            hasCanHeaders = false
        )
        assertEquals(listOf("P0300", "P0171", "P0420"), dtcs.map { it.code })
    }

    @Test
    fun parseCanMultiFrameDtcsWithHeaders_andHeaderlessFallback_workCorrectly() {
        val multiFrame = """
            7E8 10 08 43 03 03 00 01 71
            7E8 21 04 20 00 00 00 00 00
        """.trimIndent()
        val parsed = ObdResponseParser.parseDtcs(multiFrame, 0x43, isCanProtocol = true, hasCanHeaders = true)
        assertEquals(listOf("P0300", "P0171", "P0420"), parsed.map { it.code })

        // Also test when ATH1 was requested but adapter omitted headers
        val fallback = ObdResponseParser.parseDtcs("43 03 03 00 01 71 04 20", 0x43, isCanProtocol = true, hasCanHeaders = true)
        assertEquals(listOf("P0300", "P0171", "P0420"), fallback.map { it.code })
    }

    @Test
    fun parseLegacyNonCanDtcs_doesNotTreatFirstByteAsCount() {
        // Legacy ISO9141/J1850 format: 43 + 3 DTC pairs (no count byte!)
        val raw = "43 01 33 00 00 00 00"
        val dtcs = ObdResponseParser.parseDtcs(
            raw = raw,
            expectedModeResponse = 0x43,
            isCanProtocol = false,
            hasCanHeaders = false
        )
        assertEquals(listOf("P0133"), dtcs.map { it.code })
    }

    @Test
    fun parseUds1902_decodesChassisAndNetworkCodes() {
        val raw = "59 02 FF 40 35 00 09 C1 00 00 09"
        val dtcs = ObdResponseParser.parseUdsDtcs(raw, "7B8")
        assertEquals(listOf("C0035", "U0100"), dtcs.map { it.code })
    }

    @Test
    fun parseMultiFrameVin_extractsExact17CharVin() {
        val raw = """
            0: 49 02 01 34 54 31
            1: 47 31 31 41 4B 35 4E
            2: 55 36 31 32 33 34 35
        """.trimIndent()
        val vin = ObdResponseParser.parseMode09Ascii(raw, 0x02)
        assertEquals("4T1G11AK5NU612345", vin)
    }

    @Test
    fun parseSupportedPids_extractsBitmaskPids() {
        val pids = ObdResponseParser.parseSupportedPids("41 00 BE 3F A8 13", 0x00)
        assertTrue("04" in pids)
        assertTrue("05" in pids)
        assertTrue("0C" in pids)
        assertTrue("0D" in pids)
        assertTrue("20" in pids)
    }

    @Test
    fun parseMultiEcuHeaders_groupsFramesByEcuAddress() {
        val raw = """
            7E8 06 41 00 BE 3F A8 13
            7EA 06 41 00 80 00 00 01
        """.trimIndent()
        val map = ObdResponseParser.extractHexPayloads(raw, expectCanHeaders = true)
        assertTrue(map.containsKey("7E8"))
        assertTrue(map.containsKey("7EA"))
    }

    @Test
    fun parseReadiness_decodesMilDtcCountAndMonitors() {
        val summary = ObdResponseParser.parseReadiness("41 01 83 57 65 21")
        assertNotNull(summary)
        assertTrue(summary!!.milOn)
        assertEquals(3, summary.ecuReportedDtcCount)
        assertFalse(summary.isDiesel)
        val cat = summary.monitors.first { it.id == "CATALYST" }
        assertTrue(cat.supported)
        assertFalse(cat.completed)
    }

    @Test
    fun parseLiveRpmAndVoltage_matchStandardFormulas() {
        val rpmBytes = ObdResponseParser.parseMode01SensorBytes("41 0C 1A F8", "0C")!!
        val rpm = ObdPidRegistry.byPidHex["0C"]!!.decodeMetric(rpmBytes)!!
        assertEquals(1726.0, rpm, 0.1)

        assertEquals(13.85, ObdResponseParser.parseVoltage("13.85V")!!, 0.01)
    }
}
