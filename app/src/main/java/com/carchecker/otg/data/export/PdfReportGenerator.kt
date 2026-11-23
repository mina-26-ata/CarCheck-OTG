package com.carchecker.otg.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.model.SafetyVerdict
import java.io.ByteArrayOutputStream

/**
 * Native Android A4 vector PDF renderer with full HarfBuzz Arabic RTL shaping,
 * automatic multi-page flow, color-coded diagnostic cards, and crisp sensor tables.
 */
object PdfReportGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 34f
    private const val MARGIN_TOP = 32f
    private const val FOOTER_AREA = 38f
    private const val CONTENT_WIDTH = (PAGE_WIDTH - (MARGIN_X * 2)).toInt()
    private const val MAX_Y = PAGE_HEIGHT - FOOTER_AREA

    // Palette
    private val COLOR_NAVY = Color.parseColor("#0B2545")
    private val COLOR_NAVY_LIGHT = Color.parseColor("#163A63")
    private val COLOR_CYAN = Color.parseColor("#00A8CC")
    private val COLOR_GREEN = Color.parseColor("#10965A")
    private val COLOR_AMBER = Color.parseColor("#D97706")
    private val COLOR_RED = Color.parseColor("#DC2626")
    private val COLOR_TEXT_PRIMARY = Color.parseColor("#142130")
    private val COLOR_TEXT_SECONDARY = Color.parseColor("#4B5E73")
    private val COLOR_CARD_BG = Color.parseColor("#F4F8FC")
    private val COLOR_ROW_ALT = Color.parseColor("#F7FAFD")
    private val COLOR_BORDER = Color.parseColor("#D5E1EC")

    fun generatePdfBytes(content: StructuredPdfReport): ByteArray {
        val document = PdfDocument()
        try {
            val writer = PagedWriter(document, content.isAr, content.footerBrand)
            writer.startFirstPage()

            drawHeaderBanner(writer, content)
            drawVehicleInfoCard(writer, content)
            content.aiSection?.let { drawAiSection(writer, content.isAr, it) }
            drawDtcsSection(writer, content)
            drawObservationsSection(writer, content)
            if (content.freezeFrameRows.isNotEmpty()) {
                drawFreezeFrameSection(writer, content)
            }
            if (content.sensorRows.isNotEmpty()) {
                drawSensorsTableSection(writer, content)
            }
            content.cloudAiText?.let { drawCloudAiSection(writer, content.isAr, it) }

            writer.finishLastPage()

            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun drawHeaderBanner(writer: PagedWriter, content: StructuredPdfReport) {
        val bannerHeight = 86f
        val top = writer.cursorY
        val rect = RectF(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + bannerHeight)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_NAVY }
        writer.canvas.drawRoundRect(rect, 10f, 10f, bgPaint)

        // Bottom cyan accent strip inside header
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CYAN }
        writer.canvas.drawRect(MARGIN_X, top + bannerHeight - 4f, PAGE_WIDTH - MARGIN_X, top + bannerHeight, accentPaint)

        // Health score badge box (left side in RTL, right side in LTR)
        val badgeW = 92f
        val badgeH = 58f
        val badgeLeft = if (content.isAr) MARGIN_X + 14f else (PAGE_WIDTH - MARGIN_X - 14f - badgeW)
        val badgeTop = top + 12f
        val scoreColor = when {
            content.healthScore >= 85 -> COLOR_GREEN
            content.healthScore >= 60 -> COLOR_AMBER
            else -> COLOR_RED
        }
        val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)
        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#123156") }
        val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scoreColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        writer.canvas.drawRoundRect(badgeRect, 8f, 8f, badgeBg)
        writer.canvas.drawRoundRect(badgeRect, 8f, 8f, badgeStroke)

        val scorePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        writer.canvas.drawText("${content.healthScore}%", badgeRect.centerX(), badgeTop + 28f, scorePaint)

        val gradePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scoreColor
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        writer.canvas.drawText(content.healthGradeLabel, badgeRect.centerX(), badgeTop + 46f, gradePaint)

        // Header title & subtitle
        val textAreaWidth = (CONTENT_WIDTH - badgeW - 38f).toInt().coerceAtLeast(180)
        val textStartX = if (content.isAr) badgeLeft + badgeW + 12f else MARGIN_X + 14f

        val titlePaint = makeTextPaint(16.5f, Color.WHITE, bold = true)
        val titleLayout = makeLayout(content.title, titlePaint, textAreaWidth, content.isAr)
        writer.drawLayout(titleLayout, textStartX, top + 16f)

        val subPaint = makeTextPaint(9.8f, Color.parseColor("#B8D4EA"), bold = false)
        val subLayout = makeLayout(content.subtitle, subPaint, textAreaWidth, content.isAr)
        writer.drawLayout(subLayout, textStartX, top + 16f + titleLayout.height + 5f)

        writer.cursorY = top + bannerHeight + 14f
    }

    private fun drawVehicleInfoCard(writer: PagedWriter, content: StructuredPdfReport) {
        val sectionTitle = if (content.isAr) "معلومات السيارة وملخص الفحص" else "Vehicle Identity & Scan Summary"
        drawSectionTitle(writer, sectionTitle, COLOR_NAVY)

        val pairs = content.vehicleInfoPairs
        val paired = pairs.dropLast(1) // first 8 pairs in 2 columns
        val lastFullWidth = pairs.lastOrNull() // detected ECUs full width

        val colGap = 14f
        val innerPad = 12f
        val colWidth = ((CONTENT_WIDTH - innerPad * 2 - colGap) / 2f).toInt()
        val labelPaint = makeTextPaint(8.8f, COLOR_TEXT_SECONDARY, bold = true)
        val valuePaint = makeTextPaint(9.8f, COLOR_TEXT_PRIMARY, bold = false)

        val rows = paired.chunked(2)
        val measuredRows = rows.map { pairRow ->
            pairRow.map { pair ->
                val text = "${pair.label}: ${pair.value}"
                val l = makeLayout(text, valuePaint, colWidth, content.isAr)
                Pair(pair, l)
            }
        }
        val ecuLayout = lastFullWidth?.let {
            makeLayout("${it.label}: ${it.value}", valuePaint, (CONTENT_WIDTH - innerPad * 2).toInt(), content.isAr)
        }

        val totalHeight = innerPad * 2 +
            measuredRows.sumOf { row -> (row.maxOfOrNull { it.second.height } ?: 0) + 6 } +
            (ecuLayout?.let { it.height + 6 } ?: 0)

        writer.ensureSpace(totalHeight + 8f)
        val cardTop = writer.cursorY
        val cardRect = RectF(MARGIN_X, cardTop, PAGE_WIDTH - MARGIN_X, cardTop + totalHeight)
        drawCardBox(writer.canvas, cardRect, COLOR_CARD_BG, COLOR_BORDER)

        var y = cardTop + innerPad
        for (row in measuredRows) {
            val rowH = row.maxOfOrNull { it.second.height } ?: 0
            row.forEachIndexed { idx, (pair, _) ->
                val x = if (content.isAr) {
                    if (idx == 0) PAGE_WIDTH - MARGIN_X - innerPad - colWidth else MARGIN_X + innerPad
                } else {
                    if (idx == 0) MARGIN_X + innerPad else MARGIN_X + innerPad + colWidth + colGap
                }
                val combined = "${pair.label}: ${pair.value}"
                val layout = makeLayout(combined, valuePaint, colWidth, content.isAr)
                writer.drawLayout(layout, x, y)
            }
            y += rowH + 6f
        }

        if (ecuLayout != null) {
            val dividerPaint = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.8f }
            writer.canvas.drawLine(MARGIN_X + innerPad, y - 2f, PAGE_WIDTH - MARGIN_X - innerPad, y - 2f, dividerPaint)
            y += 3f
            writer.drawLayout(ecuLayout, MARGIN_X + innerPad, y)
        }

        // Prevent unused warning on labelPaint while keeping consistent styling
        labelPaint.color = COLOR_TEXT_SECONDARY
        writer.cursorY = cardTop + totalHeight + 12f
    }

    private fun drawAiSection(writer: PagedWriter, isAr: Boolean, ai: PdfAiSection) {
        val title = if (isAr) "ملخص التحليل الذكي وخطة الإصلاح" else "Smart AI Verdict & Repair Plan"
        val accentColor = when (ai.verdict) {
            SafetyVerdict.STOP_IMMEDIATELY -> COLOR_RED
            SafetyVerdict.CAUTION_REPAIR_SOON -> COLOR_AMBER
            SafetyVerdict.SAFE_MONITOR -> COLOR_CYAN
            SafetyVerdict.OPTIMAL -> COLOR_GREEN
        }
        drawSectionTitle(writer, title, accentColor)

        val pad = 12f
        val innerW = (CONTENT_WIDTH - pad * 2 - 6f).toInt()
        val verdictPaint = makeTextPaint(10.5f, accentColor, bold = true)
        val bodyPaint = makeTextPaint(9.5f, COLOR_TEXT_PRIMARY, bold = false)
        val subHeadPaint = makeTextPaint(9.5f, COLOR_NAVY, bold = true)

        val verdictText = (if (isAr) "التقييم العام: " else "Overall Verdict: ") + ai.verdictLabel
        val blocks = mutableListOf<StaticLayout>()
        blocks.add(makeLayout(verdictText, verdictPaint, innerW, isAr))
        blocks.add(makeLayout(ai.summary, bodyPaint, innerW, isAr))

        if (ai.topCauses.isNotEmpty()) {
            blocks.add(makeLayout(if (isAr) "الأسباب المرجّحة:" else "Probable Root Causes:", subHeadPaint, innerW, isAr))
            ai.topCauses.forEach { cause ->
                blocks.add(makeLayout("• $cause", bodyPaint, innerW, isAr))
            }
        }
        if (ai.repairSteps.isNotEmpty()) {
            blocks.add(makeLayout(if (isAr) "خطة الإصلاح المقترحة:" else "Recommended Repair Plan:", subHeadPaint, innerW, isAr))
            ai.repairSteps.forEachIndexed { i, step ->
                blocks.add(makeLayout("${i + 1}. $step", bodyPaint, innerW, isAr))
            }
        }

        drawAccentCard(writer, blocks, accentColor, Color.parseColor("#F8FBFD"), isAr)
    }

    private fun drawDtcsSection(writer: PagedWriter, content: StructuredPdfReport) {
        val title = if (content.isAr) {
            "أكواد الأعطال المكتشفة (${content.dtcs.size})"
        } else {
            "Detected Trouble Codes (${content.dtcs.size})"
        }
        drawSectionTitle(writer, title, if (content.dtcs.isEmpty()) COLOR_GREEN else COLOR_RED)

        if (content.dtcs.isEmpty()) {
            val okPaint = makeTextPaint(10f, COLOR_GREEN, bold = true)
            val layout = makeLayout(content.noDtcsMessage, okPaint, CONTENT_WIDTH - 28, content.isAr)
            drawAccentCard(writer, listOf(layout), COLOR_GREEN, Color.parseColor("#F0FAF5"), content.isAr)
            return
        }

        val innerW = CONTENT_WIDTH - 30
        val headPaint = makeTextPaint(11f, COLOR_RED, bold = true)
        val metaPaint = makeTextPaint(8.8f, COLOR_TEXT_SECONDARY, bold = true)
        val bodyPaint = makeTextPaint(9.3f, COLOR_TEXT_PRIMARY, bold = false)
        val subPaint = makeTextPaint(9.2f, COLOR_NAVY, bold = true)

        for (dtc in content.dtcs) {
            val accent = if (dtc.isCritical) COLOR_RED else COLOR_AMBER
            val hPaint = TextPaint(headPaint).apply { color = accent }
            val blocks = mutableListOf<StaticLayout>()
            blocks.add(makeLayout("${dtc.code} — ${dtc.title}", hPaint, innerW, content.isAr))
            val metaLine = if (content.isAr) {
                "الحالة: ${dtc.statusLabel}  •  الخطورة: ${dtc.severityLabel}  •  الوحدة: ${dtc.ecuLabel}"
            } else {
                "Status: ${dtc.statusLabel}  •  Severity: ${dtc.severityLabel}  •  Module: ${dtc.ecuLabel}"
            }
            blocks.add(makeLayout(metaLine, metaPaint, innerW, content.isAr))
            if (dtc.description.isNotBlank()) {
                blocks.add(makeLayout(dtc.description, bodyPaint, innerW, content.isAr))
            }
            if (dtc.causes.isNotEmpty()) {
                blocks.add(makeLayout(if (content.isAr) "الأسباب المحتملة:" else "Probable Causes:", subPaint, innerW, content.isAr))
                dtc.causes.forEach { blocks.add(makeLayout("• $it", bodyPaint, innerW, content.isAr)) }
            }
            if (dtc.fixSteps.isNotEmpty()) {
                blocks.add(makeLayout(if (content.isAr) "خطوات الفحص والإصلاح:" else "Diagnostic & Repair Steps:", subPaint, innerW, content.isAr))
                dtc.fixSteps.forEachIndexed { idx, step ->
                    blocks.add(makeLayout("${idx + 1}. $step", bodyPaint, innerW, content.isAr))
                }
            }
            drawAccentCard(writer, blocks, accent, Color.WHITE, content.isAr)
        }
    }

    private fun drawObservationsSection(writer: PagedWriter, content: StructuredPdfReport) {
        if (content.observations.isEmpty()) return
        val title = if (content.isAr) {
            "الملاحظات الفنية وتوصيات الفحص (${content.observations.size})"
        } else {
            "Diagnostic Observations & Recommendations (${content.observations.size})"
        }
        drawSectionTitle(writer, title, COLOR_CYAN)

        val innerW = CONTENT_WIDTH - 30
        val bodyPaint = makeTextPaint(9.3f, COLOR_TEXT_PRIMARY, bold = false)
        val metaPaint = makeTextPaint(8.8f, COLOR_NAVY_LIGHT, bold = true)

        for (obs in content.observations) {
            val accent = when (obs.severity) {
                ObservationSeverity.CRITICAL -> COLOR_RED
                ObservationSeverity.WARNING -> COLOR_AMBER
                ObservationSeverity.INFO -> COLOR_CYAN
                ObservationSeverity.GOOD -> COLOR_GREEN
            }
            val headPaint = makeTextPaint(10.3f, accent, bold = true)
            val blocks = mutableListOf<StaticLayout>()
            blocks.add(makeLayout("[${obs.severityLabel}]  ${obs.title}", headPaint, innerW, content.isAr))
            blocks.add(makeLayout(obs.summary, bodyPaint, innerW, content.isAr))
            obs.measurementLine?.let {
                blocks.add(makeLayout(it, metaPaint, innerW, content.isAr))
            }
            if (obs.steps.isNotEmpty()) {
                obs.steps.forEach { step ->
                    blocks.add(makeLayout("• $step", bodyPaint, innerW, content.isAr))
                }
            }
            drawAccentCard(writer, blocks, accent, COLOR_CARD_BG, content.isAr)
        }
    }

    private fun drawFreezeFrameSection(writer: PagedWriter, content: StructuredPdfReport) {
        val title = if (content.isAr) "لقطة بيانات لحظة تسجيل العطل (Freeze Frame)" else "Freeze Frame Snapshot"
        drawSectionTitle(writer, title, COLOR_AMBER)

        val col1W = (CONTENT_WIDTH * 0.65f).toInt()
        val col2W = CONTENT_WIDTH - col1W
        val headerPaint = makeTextPaint(9.2f, Color.WHITE, bold = true)
        val cellPaint = makeTextPaint(9.2f, COLOR_TEXT_PRIMARY, bold = false)
        val valPaint = makeTextPaint(9.2f, COLOR_NAVY, bold = true)

        val col1Title = if (content.isAr) "القراءة" else "Parameter"
        val col2Title = if (content.isAr) "القيمة وقت العطل" else "Captured Value"

        fun drawTableHeader() {
            val h = 22f
            writer.ensureSpace(h + 22f)
            val top = writer.cursorY
            writer.canvas.drawRect(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + h, Paint().apply { color = COLOR_NAVY_LIGHT })
            val l1 = makeLayout(col1Title, headerPaint, col1W - 12, content.isAr)
            val l2 = makeLayout(col2Title, headerPaint, col2W - 12, content.isAr)
            if (content.isAr) {
                writer.drawLayout(l1, MARGIN_X + col2W + 6f, top + 5f)
                writer.drawLayout(l2, MARGIN_X + 6f, top + 5f)
            } else {
                writer.drawLayout(l1, MARGIN_X + 6f, top + 5f)
                writer.drawLayout(l2, MARGIN_X + col1W + 6f, top + 5f)
            }
            writer.cursorY = top + h
        }

        drawTableHeader()
        val borderPaint = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.7f; style = Paint.Style.STROKE }

        content.freezeFrameRows.forEachIndexed { idx, row ->
            val l1 = makeLayout(row.label, cellPaint, col1W - 12, content.isAr)
            val l2 = makeLayout(row.valueWithUnit, valPaint, col2W - 12, content.isAr)
            val rowH = maxOf(l1.height, l2.height) + 10f
            if (writer.cursorY + rowH > MAX_Y) {
                writer.startNextPage()
                drawTableHeader()
            }
            val top = writer.cursorY
            if (idx % 2 == 1) {
                writer.canvas.drawRect(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + rowH, Paint().apply { color = COLOR_ROW_ALT })
            }
            writer.canvas.drawRect(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + rowH, borderPaint)
            if (content.isAr) {
                writer.drawLayout(l1, MARGIN_X + col2W + 6f, top + 5f)
                writer.drawLayout(l2, MARGIN_X + 6f, top + 5f)
            } else {
                writer.drawLayout(l1, MARGIN_X + 6f, top + 5f)
                writer.drawLayout(l2, MARGIN_X + col1W + 6f, top + 5f)
            }
            writer.cursorY = top + rowH
        }
        writer.cursorY += 12f
    }

    private fun drawSensorsTableSection(writer: PagedWriter, content: StructuredPdfReport) {
        val title = if (content.isAr) "قراءات الحساسات والبيانات الحية (${content.sensorRows.size})" else "Sensor Telemetry Snapshot (${content.sensorRows.size})"
        drawSectionTitle(writer, title, COLOR_NAVY)

        val nameW = (CONTENT_WIDTH * 0.58f).toInt()
        val pidW = (CONTENT_WIDTH * 0.14f).toInt()
        val valW = CONTENT_WIDTH - nameW - pidW

        val headerPaint = makeTextPaint(9.2f, Color.WHITE, bold = true)
        val namePaint = makeTextPaint(9.1f, COLOR_TEXT_PRIMARY, bold = false)
        val pidPaint = makeTextPaint(8.8f, COLOR_TEXT_SECONDARY, bold = false)
        val valPaint = makeTextPaint(9.2f, COLOR_NAVY, bold = true)

        val hName = if (content.isAr) "الحساس" else "Sensor"
        val hPid = "PID"
        val hVal = if (content.isAr) "القيمة" else "Value"

        fun drawTableHeader() {
            val h = 22f
            writer.ensureSpace(h + 22f)
            val top = writer.cursorY
            writer.canvas.drawRect(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + h, Paint().apply { color = COLOR_NAVY_LIGHT })
            val lName = makeLayout(hName, headerPaint, nameW - 10, content.isAr)
            val lPid = makeLayout(hPid, headerPaint, pidW - 8, false)
            val lVal = makeLayout(hVal, headerPaint, valW - 10, content.isAr)
            if (content.isAr) {
                writer.drawLayout(lName, MARGIN_X + valW + pidW + 5f, top + 5f)
                writer.drawLayout(lPid, MARGIN_X + valW + 4f, top + 5f)
                writer.drawLayout(lVal, MARGIN_X + 5f, top + 5f)
            } else {
                writer.drawLayout(lName, MARGIN_X + 5f, top + 5f)
                writer.drawLayout(lPid, MARGIN_X + nameW + 4f, top + 5f)
                writer.drawLayout(lVal, MARGIN_X + nameW + pidW + 5f, top + 5f)
            }
            writer.cursorY = top + h
        }

        drawTableHeader()
        val borderPaint = Paint().apply { color = COLOR_BORDER; strokeWidth = 0.7f; style = Paint.Style.STROKE }

        content.sensorRows.forEachIndexed { idx, row ->
            val lName = makeLayout(row.name, namePaint, nameW - 10, content.isAr)
            val lPid = makeLayout(row.pidHex, pidPaint, pidW - 8, false)
            val lVal = makeLayout(row.valueWithUnit, valPaint, valW - 10, content.isAr)
            val rowH = maxOf(lName.height, lPid.height, lVal.height) + 9f
            if (writer.cursorY + rowH > MAX_Y) {
                writer.startNextPage()
                drawTableHeader()
            }
            val top = writer.cursorY
            if (idx % 2 == 1) {
                writer.canvas.drawRect(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + rowH, Paint().apply { color = COLOR_ROW_ALT })
            }
            writer.canvas.drawRect(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + rowH, borderPaint)
            if (content.isAr) {
                writer.drawLayout(lName, MARGIN_X + valW + pidW + 5f, top + 4.5f)
                writer.drawLayout(lPid, MARGIN_X + valW + 4f, top + 4.5f)
                writer.drawLayout(lVal, MARGIN_X + 5f, top + 4.5f)
            } else {
                writer.drawLayout(lName, MARGIN_X + 5f, top + 4.5f)
                writer.drawLayout(lPid, MARGIN_X + nameW + 4f, top + 4.5f)
                writer.drawLayout(lVal, MARGIN_X + nameW + pidW + 5f, top + 4.5f)
            }
            writer.cursorY = top + rowH
        }
        writer.cursorY += 12f
    }

    private fun drawCloudAiSection(writer: PagedWriter, isAr: Boolean, text: String) {
        val title = if (isAr) "التحليل السحابي الإضافي (AI)" else "Cloud AI Deep Diagnosis"
        drawSectionTitle(writer, title, COLOR_CYAN)
        val bodyPaint = makeTextPaint(9.3f, COLOR_TEXT_PRIMARY, bold = false)
        val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val innerW = CONTENT_WIDTH - 24
        for (p in paragraphs) {
            val layout = makeLayout(p, bodyPaint, innerW, isAr)
            writer.ensureSpace(layout.height + 6f)
            writer.drawLayout(layout, MARGIN_X + 12f, writer.cursorY)
            writer.cursorY += layout.height + 5f
        }
        writer.cursorY += 8f
    }

    private fun drawSectionTitle(writer: PagedWriter, title: String, accentColor: Int) {
        val titlePaint = makeTextPaint(12f, COLOR_NAVY, bold = true)
        val layout = makeLayout(title, titlePaint, CONTENT_WIDTH - 14, writer.isAr)
        val needed = layout.height + 14f
        writer.ensureSpace(needed + 26f)

        val top = writer.cursorY
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        if (writer.isAr) {
            writer.canvas.drawRoundRect(
                RectF(PAGE_WIDTH - MARGIN_X - 4f, top + 1f, PAGE_WIDTH - MARGIN_X, top + layout.height - 1f),
                2f, 2f, barPaint
            )
            writer.drawLayout(layout, MARGIN_X, top)
        } else {
            writer.canvas.drawRoundRect(
                RectF(MARGIN_X, top + 1f, MARGIN_X + 4f, top + layout.height - 1f),
                2f, 2f, barPaint
            )
            writer.drawLayout(layout, MARGIN_X + 10f, top)
        }

        val lineY = top + layout.height + 4f
        writer.canvas.drawLine(MARGIN_X, lineY, PAGE_WIDTH - MARGIN_X, lineY, Paint().apply {
            color = COLOR_BORDER
            strokeWidth = 0.9f
        })
        writer.cursorY = lineY + 8f
    }

    private fun drawAccentCard(
        writer: PagedWriter,
        blocks: List<StaticLayout>,
        accentColor: Int,
        bgColor: Int,
        isAr: Boolean
    ) {
        val pad = 10f
        val spacing = 4.5f
        val totalHeight = pad * 2 + blocks.sumOf { it.height } + spacing * (blocks.size - 1).coerceAtLeast(0)

        // If card fits on a single page, keep it intact; otherwise stream blocks across pages
        if (totalHeight <= (MAX_Y - MARGIN_TOP - 10f)) {
            writer.ensureSpace(totalHeight + 8f)
            val top = writer.cursorY
            val rect = RectF(MARGIN_X, top, PAGE_WIDTH - MARGIN_X, top + totalHeight)
            drawCardBox(writer.canvas, rect, bgColor, COLOR_BORDER)

            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
            val barRect = if (isAr) {
                RectF(PAGE_WIDTH - MARGIN_X - 4f, top + 3f, PAGE_WIDTH - MARGIN_X, top + totalHeight - 3f)
            } else {
                RectF(MARGIN_X, top + 3f, MARGIN_X + 4f, top + totalHeight - 3f)
            }
            writer.canvas.drawRoundRect(barRect, 2f, 2f, barPaint)

            val textX = if (isAr) MARGIN_X + pad else MARGIN_X + pad + 4f
            var y = top + pad
            for (b in blocks) {
                writer.drawLayout(b, textX, y)
                y += b.height + spacing
            }
            writer.cursorY = top + totalHeight + 9f
        } else {
            val textX = if (isAr) MARGIN_X + pad else MARGIN_X + pad + 4f
            for (b in blocks) {
                writer.ensureSpace(b.height + spacing + 4f)
                writer.drawLayout(b, textX, writer.cursorY)
                writer.cursorY += b.height + spacing
            }
            writer.cursorY += 8f
        }
    }

    private fun drawCardBox(canvas: Canvas, rect: RectF, fillColor: Int, borderColor: Int) {
        canvas.drawRoundRect(rect, 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        canvas.drawRoundRect(rect, 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
        })
    }

    private fun makeTextPaint(sizePt: Float, textColor: Int, bold: Boolean): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = sizePt
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun makeLayout(text: String, paint: TextPaint, width: Int, isAr: Boolean): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(40))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (isAr) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(2.0f, 1.08f)
            .setIncludePad(false)
            .build()
    }

    private class PagedWriter(
        private val document: PdfDocument,
        val isAr: Boolean,
        private val footerBrand: String
    ) {
        private var currentPage: PdfDocument.Page? = null
        var pageNumber: Int = 0
        var cursorY: Float = MARGIN_TOP

        val canvas: Canvas
            get() = requireNotNull(currentPage).canvas

        fun startFirstPage() {
            startPage()
        }

        fun ensureSpace(neededHeight: Float) {
            if (cursorY + neededHeight > MAX_Y) {
                startNextPage()
            }
        }

        fun startNextPage() {
            finishCurrentPage()
            startPage()
        }

        fun finishLastPage() {
            finishCurrentPage()
        }

        fun drawLayout(layout: StaticLayout, x: Float, y: Float) {
            val c = canvas
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
        }

        private fun startPage() {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            currentPage = document.startPage(info)
            cursorY = MARGIN_TOP
            // Clean white page background
            canvas.drawColor(Color.WHITE)
        }

        private fun finishCurrentPage() {
            val page = currentPage ?: return
            val c = page.canvas
            val footerTop = PAGE_HEIGHT - FOOTER_AREA + 10f
            c.drawLine(MARGIN_X, footerTop, PAGE_WIDTH - MARGIN_X, footerTop, Paint().apply {
                color = COLOR_BORDER
                strokeWidth = 0.8f
            })

            val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_TEXT_SECONDARY
                textSize = 8.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val pageLabel = if (isAr) "صفحة $pageNumber" else "Page $pageNumber"
            val brandLayout = makeLayout(footerBrand, footerPaint, 340, isAr)
            val pageLayout = makeLayout(pageLabel, footerPaint, 120, !isAr)

            if (isAr) {
                drawLayout(brandLayout, PAGE_WIDTH - MARGIN_X - 340f, footerTop + 6f)
                drawLayout(pageLayout, MARGIN_X, footerTop + 6f)
            } else {
                drawLayout(brandLayout, MARGIN_X, footerTop + 6f)
                drawLayout(pageLayout, PAGE_WIDTH - MARGIN_X - 120f, footerTop + 6f)
            }

            document.finishPage(page)
            currentPage = null
        }
    }
}
