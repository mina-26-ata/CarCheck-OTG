package com.carchecker.otg.data.export

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.ui.components.dtcStatusLabel
import com.carchecker.otg.ui.components.observationSeverityLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    fun buildPlainTextReport(report: FullScanReport, lang: AppLanguage): String {
        val isAr = lang == AppLanguage.AR
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dateStr = fmt.format(Date(report.timestampMs))
        val v = report.vehicleInfo

        return buildString {
            if (isAr) {
                appendLine("====================================")
                appendLine("تقرير الفحص الشامل للسيارة — CarCheck OTG")
                appendLine("====================================")
                appendLine("التاريخ: $dateStr")
                appendLine("درجة صحة السيارة: ${report.healthScore}%")
                appendLine("المصدر: ${report.adapterDescription}")
                appendLine("رقم الشاسيه (VIN): ${v.vin ?: "غير متاح"}")
                appendLine("الشركة / الموديل: ${v.make ?: "غير معروف"} ${v.modelYear?.toString() ?: ""}")
                appendLine("البروتوكول: ${v.protocolName ?: "غير محدد"}")
                appendLine("جهد البطارية: ${v.batteryVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "غير متاح"}")
                appendLine()
                appendLine("--- الملاحظات والتحليل الذكي (${report.observations.size}) ---")
                report.observations.forEachIndexed { idx, obs ->
                    appendLine("${idx + 1}. [${observationSeverityLabel(obs.severity, true)}] ${obs.titleAr}")
                    appendLine("   التفاصيل: ${obs.summaryAr}")
                    if (!obs.measuredValue.isNullOrBlank()) {
                        appendLine("   القراءة: ${obs.measuredValue} (الطبيعي: ${obs.normalRange ?: "-"})")
                    }
                    appendLine("   طرق الحل:")
                    obs.resolutionStepsAr.forEach { step -> appendLine("   • $step") }
                    appendLine()
                }
                appendLine("--- الأعطال المكتشفة (${report.dtcs.size}) ---")
                if (report.dtcs.isEmpty()) {
                    appendLine("لا توجد أكواد أعطال مسجلة.")
                } else {
                    report.dtcs.forEach { dtc ->
                        appendLine("• ${dtc.code} (${dtcStatusLabel(dtc.status, true)}) — ${dtc.knowledge.titleAr}")
                        appendLine("  الوحدة: ${dtc.ecuNameAr} (${dtc.ecuAddress})")
                        appendLine("  الشرح: ${dtc.knowledge.descriptionAr}")
                        appendLine("  خطوات الإصلاح:")
                        dtc.knowledge.fixStepsAr.forEach { s -> appendLine("  - $s") }
                        appendLine()
                    }
                }
                if (report.sensorSnapshot.isNotEmpty()) {
                    appendLine("--- قراءة الحساسات ---")
                    report.sensorSnapshot.forEach { s ->
                        appendLine("• ${s.nameAr}: ${String.format(Locale.US, "%.${s.decimals}f", s.value)} ${s.unit}")
                    }
                }
                if (!report.aiAnalysisText.isNullOrBlank()) {
                    appendLine()
                    appendLine("--- تشخيص الذكاء الاصطناعي ---")
                    appendLine(report.aiAnalysisText)
                }
            } else {
                appendLine("====================================")
                appendLine("CarCheck OTG — Vehicle Diagnostic Report")
                appendLine("====================================")
                appendLine("Date: $dateStr")
                appendLine("Health Score: ${report.healthScore}%")
                appendLine("Adapter: ${report.adapterDescription}")
                appendLine("VIN: ${v.vin ?: "N/A"}")
                appendLine("Make / Year: ${v.make ?: "Unknown"} ${v.modelYear?.toString() ?: ""}")
                appendLine("Protocol: ${v.protocolName ?: "N/A"}")
                appendLine("Battery Voltage: ${v.batteryVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "N/A"}")
                appendLine()
                appendLine("--- Observations & Health Notes (${report.observations.size}) ---")
                report.observations.forEachIndexed { idx, obs ->
                    appendLine("${idx + 1}. [${obs.severity}] ${obs.titleEn}")
                    appendLine("   Summary: ${obs.summaryEn}")
                    if (!obs.measuredValue.isNullOrBlank()) {
                        appendLine("   Measured: ${obs.measuredValue} (Normal: ${obs.normalRange ?: "-"})")
                    }
                    appendLine("   Resolution Steps:")
                    obs.resolutionStepsEn.forEach { step -> appendLine("   • $step") }
                    appendLine()
                }
                appendLine("--- Trouble Codes (${report.dtcs.size}) ---")
                if (report.dtcs.isEmpty()) {
                    appendLine("No trouble codes detected.")
                } else {
                    report.dtcs.forEach { dtc ->
                        appendLine("• ${dtc.code} (${dtc.status}) — ${dtc.knowledge.titleEn}")
                        appendLine("  Module: ${dtc.ecuNameEn} (${dtc.ecuAddress})")
                        appendLine("  Details: ${dtc.knowledge.descriptionEn}")
                        appendLine("  Fix Steps:")
                        dtc.knowledge.fixStepsEn.forEach { s -> appendLine("  - $s") }
                        appendLine()
                    }
                }
                if (report.sensorSnapshot.isNotEmpty()) {
                    appendLine("--- Sensor Snapshot ---")
                    report.sensorSnapshot.forEach { s ->
                        appendLine("• ${s.nameEn}: ${String.format(Locale.US, "%.${s.decimals}f", s.value)} ${s.unit}")
                    }
                }
                if (!report.aiAnalysisText.isNullOrBlank()) {
                    appendLine()
                    appendLine("--- AI Deep Diagnosis ---")
                    appendLine(report.aiAnalysisText)
                }
            }
        }
    }

    fun shareTextReport(context: Context, report: FullScanReport, lang: AppLanguage) {
        val text = buildPlainTextReport(report, lang)
        val subject = if (lang == AppLanguage.AR) {
            "تقرير فحص السيارة - ${report.vehicleInfo.make ?: "OBD-II"}"
        } else {
            "Car Diagnostic Report - ${report.vehicleInfo.make ?: "OBD-II"}"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }

    fun printOrSavePdf(context: Context, report: FullScanReport, lang: AppLanguage) {
        val html = buildHtmlReport(report, lang)
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "CarCheck_OTG_${report.vehicleInfo.vin ?: "Report"}"
                val adapter = webView.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, adapter, PrintAttributes.Builder().build())
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
    }

    private fun buildHtmlReport(report: FullScanReport, lang: AppLanguage): String {
        val isAr = lang == AppLanguage.AR
        val dir = if (isAr) "rtl" else "ltr"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dateStr = fmt.format(Date(report.timestampMs))
        val v = report.vehicleInfo

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val obsHtml = report.observations.joinToString("") { obs ->
            val sevLabel = esc(observationSeverityLabel(obs.severity, isAr))
            val title = esc(if (isAr) obs.titleAr else obs.titleEn)
            val sum = esc(if (isAr) obs.summaryAr else obs.summaryEn)
            val steps = (if (isAr) obs.resolutionStepsAr else obs.resolutionStepsEn)
                .joinToString("") { "<li>${esc(it)}</li>" }
            """
            <div class="card">
              <div class="badge">$sevLabel</div>
              <h3>$title</h3>
              <p>$sum</p>
              <ul>$steps</ul>
            </div>
            """.trimIndent()
        }

        val dtcHtml = if (report.dtcs.isEmpty()) {
            "<p>${if (isAr) "لا توجد أعطال مسجلة." else "No trouble codes detected."}</p>"
        } else {
            report.dtcs.joinToString("") { dtc ->
                val statLabel = esc(dtcStatusLabel(dtc.status, isAr))
                val title = esc(if (isAr) dtc.knowledge.titleAr else dtc.knowledge.titleEn)
                val desc = esc(if (isAr) dtc.knowledge.descriptionAr else dtc.knowledge.descriptionEn)
                val steps = (if (isAr) dtc.knowledge.fixStepsAr else dtc.knowledge.fixStepsEn)
                    .joinToString("") { "<li>${esc(it)}</li>" }
                """
                <div class="card">
                  <strong>${esc(dtc.code)}</strong> ($statLabel) — $title
                  <p>$desc</p>
                  <ul>$steps</ul>
                </div>
                """.trimIndent()
            }
        }

        val sensorRows = report.sensorSnapshot.joinToString("") { s ->
            val name = esc(if (isAr) s.nameAr else s.nameEn)
            val valStr = String.format(Locale.US, "%.${s.decimals}f %s", s.value, esc(s.unit))
            "<tr><td>$name</td><td>${esc(s.pidHex)}</td><td><strong>$valStr</strong></td></tr>"
        }

        return """
        <!DOCTYPE html>
        <html dir="$dir" lang="${if (isAr) "ar" else "en"}">
        <head>
          <meta charset="utf-8" />
          <style>
            body { font-family: sans-serif; color: #1a2433; margin: 24px; line-height: 1.5; }
            h1 { color: #0b4f6c; margin-bottom: 4px; }
            .meta { background: #f2f6fa; padding: 12px 16px; border-radius: 8px; margin-bottom: 18px; }
            .card { border: 1px solid #d5e0ea; border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; page-break-inside: avoid; }
            .badge { display: inline-block; font-size: 11px; font-weight: bold; padding: 2px 8px; border-radius: 4px; background: #e3eef7; color: #0b4f6c; }
            table { width: 100%; border-collapse: collapse; margin-top: 10px; }
            th, td { border: 1px solid #dce5ee; padding: 6px 10px; text-align: ${if (isAr) "right" else "left"}; font-size: 13px; }
            th { background: #eef4f9; }
          </style>
        </head>
        <body>
          <h1>${if (isAr) "تقرير الفحص الشامل للسيارة — CarCheck OTG" else "CarCheck OTG — Vehicle Diagnostic Report"}</h1>
          <div class="meta">
            <div><strong>${if (isAr) "التاريخ:" else "Date:"}</strong> $dateStr | <strong>${if (isAr) "درجة الصحة:" else "Health Score:"}</strong> ${report.healthScore}%</div>
            <div><strong>VIN:</strong> ${esc(v.vin ?: "-")} | <strong>${if (isAr) "السيارة:" else "Vehicle:"}</strong> ${esc(v.make ?: "-")} ${v.modelYear ?: ""}</div>
            <div><strong>${if (isAr) "البروتوكول:" else "Protocol:"}</strong> ${esc(v.protocolName ?: "-")} | <strong>${if (isAr) "البطارية:" else "Battery:"}</strong> ${v.batteryVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "-"}</div>
          </div>
          <h2>${if (isAr) "الملاحظات وطرق الحل" else "Health Observations & Solutions"}</h2>
          $obsHtml
          <h2>${if (isAr) "أكواد الأعطال (${report.dtcs.size})" else "Trouble Codes (${report.dtcs.size})"}</h2>
          $dtcHtml
          <h2>${if (isAr) "قراءات الحساسات" else "Sensor Telemetry Snapshot"}</h2>
          <table>
            <thead><tr><th>${if (isAr) "الحساس" else "Sensor"}</th><th>PID</th><th>${if (isAr) "القيمة" else "Value"}</th></tr></thead>
            <tbody>$sensorRows</tbody>
          </table>
        </body>
        </html>
        """.trimIndent()
    }
}
