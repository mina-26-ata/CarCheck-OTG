package com.carchecker.otg

import com.carchecker.otg.data.export.PdfReportContentBuilder
import com.carchecker.otg.model.AiHypothesis
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.DetectedEcu
import com.carchecker.otg.model.DiagnosedDtc
import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.DtcSeverity
import com.carchecker.otg.model.DtcStatus
import com.carchecker.otg.model.DtcSystem
import com.carchecker.otg.model.FreezeFrameReading
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.ObservationCategory
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.model.ReadinessSummary
import com.carchecker.otg.model.SafetyVerdict
import com.carchecker.otg.model.SensorCategory
import com.carchecker.otg.model.SmartAiReport
import com.carchecker.otg.model.VehicleInfo
import com.carchecker.otg.model.VehicleObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReportContentBuilderTest {

    private fun sampleReport(
        make: String? = "Kia",
        year: Int? = 2021,
        vin: String? = "KNAPM81ABM7123456",
        includeDtcs: Boolean = true
    ): FullScanReport {
        val dtcs = if (includeDtcs) {
            listOf(
                DiagnosedDtc(
                    code = "P0171",
                    status = DtcStatus.CONFIRMED,
                    ecuAddress = "7E8",
                    ecuNameEn = "Engine Control Module (ECM)",
                    ecuNameAr = "كمبيوتر المحرك (ECM)",
                    knowledge = DtcKnowledgeEntry(
                        code = "P0171",
                        system = DtcSystem.POWERTRAIN,
                        isGeneric = true,
                        severity = DtcSeverity.HIGH,
                        canDriveSafely = true,
                        titleEn = "System Too Lean (Bank 1)",
                        titleAr = "خليط الوقود فقير (بنك 1)",
                        descriptionEn = "Too much air or too little fuel in combustion.",
                        descriptionAr = "نسبة الهواء أعلى من الطبيعي مقارنة بالوقود.",
                        causesEn = listOf("Vacuum leak", "Dirty MAF sensor"),
                        causesAr = listOf("تسريب هواء", "اتساخ حساس الهواء MAF"),
                        symptomsEn = listOf("Rough idle"),
                        symptomsAr = listOf("اهتزاز المحرك"),
                        fixStepsEn = listOf("Inspect intake hoses", "Clean MAF sensor"),
                        fixStepsAr = listOf("افحص خراطيم الهواء", "نظّف حساس الهواء MAF"),
                        estimatedDifficultyEn = "Medium",
                        estimatedDifficultyAr = "متوسط"
                    )
                )
            )
        } else {
            emptyList()
        }

        return FullScanReport(
            id = "rep-1",
            timestampMs = 1732400000000L,
            isSimulated = false,
            adapterDescription = "ELM327 Bluetooth",
            healthScore = if (includeDtcs) 72 else 98,
            vehicleInfo = VehicleInfo(
                vin = vin,
                make = make,
                modelYear = year,
                protocolName = "ISO 15765-4 (CAN 11/500)",
                batteryVoltage = 14.1,
                detectedEcus = listOf(DetectedEcu("7E8", "Engine Control Module", "كمبيوتر المحرك", "CAN"))
            ),
            dtcs = dtcs,
            freezeFrame = if (includeDtcs) {
                listOf(FreezeFrameReading("0C", "Engine RPM", "دورات المحرك", "1850", "RPM"))
            } else {
                emptyList()
            },
            readiness = ReadinessSummary(
                milOn = includeDtcs,
                ecuReportedDtcCount = dtcs.size,
                isDiesel = false
            ),
            sensorSnapshot = listOf(
                LiveSensorReading("0C", "Engine RPM", "دورات المحرك", 760.0, "RPM", SensorCategory.ENGINE, decimals = 0),
                LiveSensorReading("05", "Coolant Temp", "حرارة التبريد", 90.0, "°C", SensorCategory.TEMPERATURE, decimals = 0)
            ),
            observations = listOf(
                VehicleObservation(
                    id = "obs-1",
                    severity = ObservationSeverity.WARNING,
                    category = ObservationCategory.FUEL_AIR,
                    titleEn = "Elevated Fuel Trim",
                    titleAr = "ارتفاع معامل تعديل الوقود",
                    summaryEn = "Long term fuel trim is elevated.",
                    summaryAr = "تعديل الوقود الطويل مرتفع عن النطاق المثالي.",
                    measuredValue = "+16.5%",
                    normalRange = "-10% to +10%",
                    impactEn = "Higher consumption",
                    impactAr = "زيادة استهلاك الوقود",
                    resolutionStepsEn = listOf("Smoke test intake"),
                    resolutionStepsAr = listOf("افحص تسريبات الهواء بالدخان")
                )
            )
        )
    }

    @Test
    fun buildSafePdfFileName_sanitizesTraversalAndSpecialCharacters() {
        val cleanName = PdfReportContentBuilder.buildSafePdfFileName(sampleReport(make = "Kia", year = 2021))
        assertTrue(cleanName.startsWith("CarCheck_OTG_Kia_2021_"))
        assertTrue(cleanName.endsWith(".pdf"))

        val malicious = PdfReportContentBuilder.buildSafePdfFileName(
            sampleReport(make = "../../evil/car:*?<>|", year = null, vin = null)
        )
        assertFalse(malicious.contains(".."))
        assertFalse(malicious.contains("/"))
        assertFalse(malicious.contains("\\"))
        assertFalse(malicious.contains(":"))
        assertTrue(malicious.endsWith(".pdf"))
    }

    @Test
    fun buildArabicStructuredReport_localizesAllSectionsAndIncludesSmartAi() {
        val ai = SmartAiReport(
            verdict = SafetyVerdict.CAUTION_REPAIR_SOON,
            summaryEn = "Inspect vacuum leak soon.",
            summaryAr = "يُنصح بفحص تسريب الهواء قريباً.",
            hypotheses = listOf(
                AiHypothesis(
                    id = "h1",
                    confidencePercent = 82,
                    zone = CarSubsystemZone.ENGINE,
                    relatedCodes = listOf("P0171"),
                    titleEn = "Vacuum Leak",
                    titleAr = "تسريب هواء",
                    reasoningEn = "Lean trim + P0171",
                    reasoningAr = "تطابق كود P0171 مع تعديل الوقود",
                    firstChecksEn = listOf("Check hose"),
                    firstChecksAr = listOf("افحص الخراطيم")
                )
            ),
            priorityStepsEn = listOf("Check intake boot"),
            priorityStepsAr = listOf("فحص خرطوم السحب أولاً")
        )

        val structured = PdfReportContentBuilder.build(sampleReport(), ai, AppLanguage.AR)
        assertTrue(structured.isAr)
        assertEquals("تقرير الفحص الشامل للسيارة", structured.title)
        assertEquals(72, structured.healthScore)
        assertNotNull(structured.aiSection)
        assertTrue(structured.aiSection!!.verdictLabel.contains("قيادة بحذر"))
        assertEquals(1, structured.dtcs.size)
        assertEquals("مؤكد", structured.dtcs.first().statusLabel)
        assertEquals("عالٍ", structured.dtcs.first().severityLabel)
        assertEquals(1, structured.observations.size)
        assertEquals("تحذير", structured.observations.first().severityLabel)
        assertEquals(1, structured.freezeFrameRows.size)
        assertEquals(2, structured.sensorRows.size)
        assertTrue(structured.vehicleInfoPairs.any { it.value.contains("مضاءة") })
    }

    @Test
    fun buildEnglishCleanVehicleReport_producesCleanNoFaultsMessage() {
        val structured = PdfReportContentBuilder.build(sampleReport(includeDtcs = false), null, AppLanguage.EN)
        assertFalse(structured.isAr)
        assertEquals("Vehicle Diagnostic Report", structured.title)
        assertEquals(0, structured.dtcs.size)
        assertTrue(structured.noDtcsMessage.contains("No trouble codes"))
        assertTrue(structured.vehicleInfoPairs.any { it.value.contains("OFF") })
    }
}
