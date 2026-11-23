package com.carchecker.otg.data.export

import com.carchecker.otg.domain.analyzer.TelemetryEvaluator
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.model.SafetyVerdict
import com.carchecker.otg.model.SmartAiReport
import com.carchecker.otg.ui.components.dtcSeverityLabel
import com.carchecker.otg.ui.components.dtcStatusLabel
import com.carchecker.otg.ui.components.formatVehicleDisplayName
import com.carchecker.otg.ui.components.localizeAdapterLabel
import com.carchecker.otg.ui.components.observationSeverityLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PdfInfoPair(
    val label: String,
    val value: String
)

data class PdfAiSection(
    val verdict: SafetyVerdict,
    val verdictLabel: String,
    val summary: String,
    val topCauses: List<String>,
    val repairSteps: List<String>
)

data class PdfDtcBlock(
    val code: String,
    val statusLabel: String,
    val severityLabel: String,
    val title: String,
    val ecuLabel: String,
    val description: String,
    val causes: List<String>,
    val fixSteps: List<String>,
    val isCritical: Boolean
)

data class PdfObservationBlock(
    val severity: ObservationSeverity,
    val severityLabel: String,
    val title: String,
    val summary: String,
    val measurementLine: String?,
    val steps: List<String>
)

data class PdfFreezeFrameRow(
    val label: String,
    val valueWithUnit: String
)

data class PdfSensorRow(
    val name: String,
    val pidHex: String,
    val valueWithUnit: String
)

data class StructuredPdfReport(
    val isAr: Boolean,
    val title: String,
    val subtitle: String,
    val dateFormatted: String,
    val healthScore: Int,
    val healthGradeLabel: String,
    val vehicleInfoPairs: List<PdfInfoPair>,
    val aiSection: PdfAiSection?,
    val dtcs: List<PdfDtcBlock>,
    val noDtcsMessage: String,
    val observations: List<PdfObservationBlock>,
    val freezeFrameRows: List<PdfFreezeFrameRow>,
    val sensorRows: List<PdfSensorRow>,
    val cloudAiText: String?,
    val footerBrand: String
)

object PdfReportContentBuilder {

    fun buildSafePdfFileName(report: FullScanReport): String {
        val dateStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(report.timestampMs))
        val rawCar = listOfNotNull(
            report.vehicleInfo.make?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) },
            report.vehicleInfo.modelYear?.toString()
        ).joinToString("_").ifBlank {
            report.vehicleInfo.vin?.takeLast(6).orEmpty()
        }

        val sanitizedCar = rawCar
            .replace("..", "")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(28)

        return if (sanitizedCar.isNotBlank()) {
            "CarCheck_OTG_${sanitizedCar}_$dateStamp.pdf"
        } else {
            "CarCheck_OTG_Report_$dateStamp.pdf"
        }
    }

    fun build(
        report: FullScanReport,
        smartAi: SmartAiReport?,
        lang: AppLanguage
    ): StructuredPdfReport {
        val isAr = lang == AppLanguage.AR
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(report.timestampMs))
        val vehicleName = formatVehicleDisplayName(report)
        val adapterLocalized = localizeAdapterLabel(report.adapterDescription, isAr)
        val healthRating = TelemetryEvaluator.rateHealthScore(report.healthScore)
        val v = report.vehicleInfo

        val milText = report.readiness?.let { r ->
            if (r.milOn) {
                if (isAr) "مضاءة (تحذير نشط)" else "ON (Active Warning)"
            } else {
                if (isAr) "مطفأة (سليمة)" else "OFF (Normal)"
            }
        } ?: if (isAr) "غير متاح" else "N/A"

        val voltageText = v.batteryVoltage?.let { String.format(Locale.US, "%.2f V", it) }
            ?: report.sensorSnapshot.firstOrNull { it.pidHex == "42" }?.let {
                String.format(Locale.US, "%.2f V", it.value)
            }
            ?: if (isAr) "غير متاح" else "N/A"

        val ecuText = if (v.detectedEcus.isNotEmpty()) {
            v.detectedEcus.joinToString(" • ") { if (isAr) it.nameAr else it.nameEn }
        } else {
            if (isAr) "وحدة المحرك القياسية" else "Standard ECM"
        }

        val infoPairs = if (isAr) {
            listOf(
                PdfInfoPair("السيارة", vehicleName),
                PdfInfoPair("رقم الشاسيه (VIN)", v.vin ?: "غير متاح"),
                PdfInfoPair("تاريخ الفحص", dateFormatted),
                PdfInfoPair("مصدر الفحص", adapterLocalized),
                PdfInfoPair("بروتوكول الاتصال", v.protocolName ?: "غير محدد"),
                PdfInfoPair("جهد البطارية", voltageText),
                PdfInfoPair("لمبة تحذير المحرك", milText),
                PdfInfoPair("الأعطال / الملاحظات", "${report.dtcs.size} أعطال • ${report.observations.size} ملاحظات"),
                PdfInfoPair("وحدات التحكم", ecuText)
            )
        } else {
            listOf(
                PdfInfoPair("Vehicle", vehicleName),
                PdfInfoPair("VIN", v.vin ?: "N/A"),
                PdfInfoPair("Scan Date", dateFormatted),
                PdfInfoPair("Adapter", adapterLocalized),
                PdfInfoPair("OBD Protocol", v.protocolName ?: "N/A"),
                PdfInfoPair("Battery Voltage", voltageText),
                PdfInfoPair("Check Engine (MIL)", milText),
                PdfInfoPair("Faults / Notes", "${report.dtcs.size} Faults • ${report.observations.size} Notes"),
                PdfInfoPair("Detected ECUs", ecuText)
            )
        }

        val aiSection = smartAi?.let { ai ->
            val verdictLabel = when (ai.verdict) {
                SafetyVerdict.STOP_IMMEDIATELY -> if (isAr) "توقّف وافحص فوراً" else "Stop & Inspect Immediately"
                SafetyVerdict.CAUTION_REPAIR_SOON -> if (isAr) "قيادة بحذر — يُنصح بالصيانة قريباً" else "Drive with Caution — Repair Soon"
                SafetyVerdict.SAFE_MONITOR -> if (isAr) "آمنة للقيادة مع المتابعة" else "Safe to Drive — Monitor"
                SafetyVerdict.OPTIMAL -> if (isAr) "الحالة ممتازة" else "Optimal Condition"
            }
            PdfAiSection(
                verdict = ai.verdict,
                verdictLabel = verdictLabel,
                summary = if (isAr) ai.summaryAr else ai.summaryEn,
                topCauses = ai.hypotheses.take(3).map { h ->
                    val title = if (isAr) h.titleAr else h.titleEn
                    val reason = if (isAr) h.reasoningAr else h.reasoningEn
                    "$title (${h.confidencePercent}%) — $reason"
                },
                repairSteps = if (isAr) ai.priorityStepsAr else ai.priorityStepsEn
            )
        }

        val dtcBlocks = report.dtcs.map { dtc ->
            val k = dtc.knowledge
            PdfDtcBlock(
                code = dtc.code,
                statusLabel = dtcStatusLabel(dtc.status, isAr),
                severityLabel = dtcSeverityLabel(k.severity, isAr),
                title = if (isAr) k.titleAr else k.titleEn,
                ecuLabel = "${if (isAr) dtc.ecuNameAr else dtc.ecuNameEn} (${dtc.ecuAddress})",
                description = if (isAr) k.descriptionAr else k.descriptionEn,
                causes = (if (isAr) k.causesAr else k.causesEn).take(4),
                fixSteps = (if (isAr) k.fixStepsAr else k.fixStepsEn).take(5),
                isCritical = !k.canDriveSafely || k.severity.name in setOf("CRITICAL", "HIGH")
            )
        }

        val obsBlocks = report.observations.map { obs ->
            val measurementLine = if (!obs.measuredValue.isNullOrBlank()) {
                if (isAr) {
                    "القيمة المقاسة: ${obs.measuredValue}  |  الطبيعي: ${obs.normalRange ?: "-"}"
                } else {
                    "Measured: ${obs.measuredValue}  |  Normal: ${obs.normalRange ?: "-"}"
                }
            } else {
                null
            }
            PdfObservationBlock(
                severity = obs.severity,
                severityLabel = observationSeverityLabel(obs.severity, isAr),
                title = if (isAr) obs.titleAr else obs.titleEn,
                summary = if (isAr) obs.summaryAr else obs.summaryEn,
                measurementLine = measurementLine,
                steps = (if (isAr) obs.resolutionStepsAr else obs.resolutionStepsEn).take(4)
            )
        }

        val freezeRows = report.freezeFrame.map { ff ->
            PdfFreezeFrameRow(
                label = if (isAr) ff.labelAr else ff.labelEn,
                valueWithUnit = listOf(ff.valueDisplay, ff.unit).filter { it.isNotBlank() }.joinToString(" ")
            )
        }

        val sensorRows = report.sensorSnapshot.map { s ->
            PdfSensorRow(
                name = if (isAr) s.nameAr else s.nameEn,
                pidHex = s.pidHex,
                valueWithUnit = String.format(Locale.US, "%.${s.decimals}f %s", s.value, s.unit).trim()
            )
        }

        return StructuredPdfReport(
            isAr = isAr,
            title = if (isAr) "تقرير الفحص الشامل للسيارة" else "Vehicle Diagnostic Report",
            subtitle = "$vehicleName  •  $dateFormatted",
            dateFormatted = dateFormatted,
            healthScore = report.healthScore,
            healthGradeLabel = if (isAr) healthRating.labelAr else healthRating.labelEn,
            vehicleInfoPairs = infoPairs,
            aiSection = aiSection,
            dtcs = dtcBlocks,
            noDtcsMessage = if (isAr) {
                "لا توجد أكواد أعطال مسجلة — جميع أنظمة الفحص خالية من الأعطال."
            } else {
                "No trouble codes detected — all scanned systems are clean."
            },
            observations = obsBlocks,
            freezeFrameRows = freezeRows,
            sensorRows = sensorRows,
            cloudAiText = report.aiAnalysisText?.trim()?.takeIf { it.isNotBlank() },
            footerBrand = if (isAr) "CarCheck OTG • تقرير فحص إلكتروني شامل" else "CarCheck OTG • Electronic Diagnostic Report"
        )
    }
}
