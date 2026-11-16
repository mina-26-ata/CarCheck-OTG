package com.carchecker.otg

import com.carchecker.otg.data.obd.VinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {

    @Test
    fun decodeToyotaVin_extractsMakeYearCountry() {
        val decoded = VinDecoder.decode("4T1G11AK5NU612345")
        assertTrue(decoded.isValidFormat)
        assertEquals("Toyota (USA)", decoded.make)
        assertEquals("United States", decoded.country)
        assertEquals(2022, decoded.modelYear)
    }

    @Test
    fun decodeHyundaiVin_extractsMakeYearCountry() {
        val decoded = VinDecoder.decode("KMHD84LF9KU812345")
        assertTrue(decoded.isValidFormat)
        assertEquals("Hyundai", decoded.make)
        assertEquals("South Korea", decoded.country)
        assertEquals(2019, decoded.modelYear)
    }

    @Test
    fun decodeFordVin_extractsMakeYearCountry() {
        val decoded = VinDecoder.decode("1FM5K8D84HGB12345")
        assertTrue(decoded.isValidFormat)
        assertEquals("Ford SUV", decoded.make)
        assertEquals("United States", decoded.country)
        assertEquals(2017, decoded.modelYear)
    }

    @Test
    fun invalidVinCharacters_flaggedInvalidFormat() {
        val decoded = VinDecoder.decode("1FA6P8CF0I1000001") // contains forbidden 'I'
        assertFalse(decoded.isValidFormat)
    }
}
