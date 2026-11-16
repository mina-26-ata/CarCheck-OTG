package com.carchecker.otg

import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.model.DtcSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtcDecoderTest {

    private fun loadAssetsRepo(): DtcRepository {
        val file = listOf(
            File("app/src/main/assets/dtc_database.json"),
            File("src/main/assets/dtc_database.json")
        ).first { it.exists() }
        return DtcRepository { file.readText(Charsets.UTF_8) }
    }

    @Test
    fun databaseLoadsAndFindsCuratedBilingualCodes() {
        val repo = loadAssetsRepo()
        assertTrue(repo.allEntries.size >= 150)

        val p0171 = repo.lookup("p0171")
        assertTrue(p0171.isFromBuiltInDb)
        assertEquals("P0171", p0171.code)
        assertTrue(p0171.fixStepsAr.isNotEmpty())
        assertTrue(p0171.fixStepsEn.isNotEmpty())
    }

    @Test
    fun fallbackDecoderHandlesUnlistedCodesAcrossAllSystems() {
        val repo = loadAssetsRepo()

        val unlistedPowertrain = repo.lookup("P0457")
        assertFalse(unlistedPowertrain.isFromBuiltInDb)
        assertEquals(DtcSystem.POWERTRAIN, unlistedPowertrain.system)
        assertTrue(unlistedPowertrain.fixStepsAr.isNotEmpty())

        val unlistedChassis = repo.lookup("C0196")
        assertEquals(DtcSystem.CHASSIS, unlistedChassis.system)

        val unlistedBody = repo.lookup("B1420")
        assertEquals(DtcSystem.BODY, unlistedBody.system)
        assertFalse(unlistedBody.isGeneric)

        val unlistedNet = repo.lookup("U0168")
        assertEquals(DtcSystem.NETWORK, unlistedNet.system)
    }

    @Test
    fun searchSupportsArabicKeywordsAndDirectCodeInput() {
        val repo = loadAssetsRepo()
        val arMatches = repo.search("تفتفة")
        assertTrue(arMatches.any { it.code == "P0300" })

        val directFallback = repo.search("P1450")
        assertEquals(1, directFallback.size)
        assertEquals("P1450", directFallback[0].code)
    }
}
