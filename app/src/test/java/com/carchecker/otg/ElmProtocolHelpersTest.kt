package com.carchecker.otg

import com.carchecker.otg.data.obd.ElmProtocolHelpers
import com.carchecker.otg.data.obd.ElmProtocolHelpers.GattCharCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmProtocolHelpersTest {

    @Test
    fun looksLikeObdDeviceName_identifiesElmAndPopularAdapters() {
        assertTrue(ElmProtocolHelpers.looksLikeObdDeviceName("OBDII"))
        assertTrue(ElmProtocolHelpers.looksLikeObdDeviceName("ELM327-BLE"))
        assertTrue(ElmProtocolHelpers.looksLikeObdDeviceName("EML327 Wireless"))
        assertTrue(ElmProtocolHelpers.looksLikeObdDeviceName("IOS-Vlink"))
        assertTrue(ElmProtocolHelpers.looksLikeObdDeviceName("Vgate iCar Pro"))
        assertFalse(ElmProtocolHelpers.looksLikeObdDeviceName("JBL Flip 6"))
        assertFalse(ElmProtocolHelpers.looksLikeObdDeviceName(null))
    }

    @Test
    fun selectBestBleEndpoints_matchesTemuFff0AndVgateAndFallbackServices() {
        // Generic ELM327 BLE 4.0 (Temu / iOS+Android): FFF0 -> FFF1 Notify + FFF2 Write
        val temuBle = ElmProtocolHelpers.selectBestBleEndpoints(
            listOf(
                GattCharCandidate("00001800-0000-1000-8000-00805f9b34fb", "00002a00-0000-1000-8000-00805f9b34fb", canNotify = false, canIndicate = false, canWrite = false),
                GattCharCandidate("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb", canNotify = true, canIndicate = false, canWrite = false),
                GattCharCandidate("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb", canNotify = false, canIndicate = false, canWrite = true)
            )
        )
        assertNotNull(temuBle)
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", temuBle?.writeCharUuid)
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", temuBle?.notifyCharUuid)

        // TI CC2541 / HM-10 FFE0 -> FFE1 single RW+Notify characteristic
        val cc2541 = ElmProtocolHelpers.selectBestBleEndpoints(
            listOf(
                GattCharCandidate("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb", canNotify = true, canIndicate = false, canWrite = true)
            )
        )
        assertNotNull(cc2541)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", cc2541?.writeCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", cc2541?.notifyCharUuid)

        // Unknown custom vendor service fallback ignores standard 1800/180F and picks custom UART
        val vendorFallback = ElmProtocolHelpers.selectBestBleEndpoints(
            listOf(
                GattCharCandidate("0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb", canNotify = true, canIndicate = false, canWrite = true),
                GattCharCandidate("12345678-0000-1000-8000-00805f9b34fb", "12345679-0000-1000-8000-00805f9b34fb", canNotify = false, canIndicate = true, canWrite = false),
                GattCharCandidate("12345678-0000-1000-8000-00805f9b34fb", "1234567a-0000-1000-8000-00805f9b34fb", canNotify = false, canIndicate = false, canWrite = true)
            )
        )
        assertNotNull(vendorFallback)
        assertEquals("1234567a-0000-1000-8000-00805f9b34fb", vendorFallback?.writeCharUuid)
        assertEquals("12345679-0000-1000-8000-00805f9b34fb", vendorFallback?.notifyCharUuid)
        assertTrue(vendorFallback?.preferIndicate == true)

        assertNull(ElmProtocolHelpers.selectBestBleEndpoints(emptyList()))
    }

    @Test
    fun cleanElmResponse_and_extractFirmwareLabel_stripEchoAndPrompt() {
        assertTrue(ElmProtocolHelpers.isElmPromptReady("ATI\rELM327 v1.5\r\r>"))
        assertFalse(ElmProtocolHelpers.isElmPromptReady("SEARCHING...\r"))

        assertEquals(
            "ELM327 v1.5",
            ElmProtocolHelpers.extractElmFirmwareLabel("ATI\rELM327 v1.5\r>", "Fallback")
        )
        assertEquals(
            "41 0C 0A C8",
            ElmProtocolHelpers.cleanElmResponse("010C\r41 0C 0A C8\r\r>", sentCommand = "010C")
        )
    }
}
