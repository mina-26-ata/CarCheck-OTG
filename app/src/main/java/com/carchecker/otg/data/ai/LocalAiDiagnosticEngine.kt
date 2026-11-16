package com.carchecker.otg.data.ai

import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.AiHypothesis
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.DiagnosedDtc
import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.DtcSeverity
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.SafetyVerdict
import com.carchecker.otg.model.SmartAiReport
import java.util.Locale

/**
 * Built-in on-device AI correlation and advisory engine.
 * Runs completely offline without requiring any API key so every user receives
 * prioritized root-cause analysis, safety verdicts, repair ordering, and Q&A.
 */
class LocalAiDiagnosticEngine {

    fun analyze(
        report: FullScanReport,
        liveSensors: List<LiveSensorReading>,
        unit: MeasurementUnit
    ): SmartAiReport {
        val activeSensors = if (liveSensors.isNotEmpty()) liveSensors else report.sensorSnapshot
        val sensorMap = activeSensors.associateBy { it.pidHex }
        val rpm = sensorMap["0C"]?.value
        val coolantRaw = sensorMap["05"]?.value
        val coolantC = coolantRaw?.let {
            if (unit == MeasurementUnit.IMPERIAL) (it - 32.0) * 5.0 / 9.0 else it
        }
        val voltage = sensorMap["42"]?.value ?: report.vehicleInfo.batteryVoltage
        val stftB1 = sensorMap["06"]?.value
        val ltftB1 = sensorMap["07"]?.value
        val combinedTrim = when {
            stftB1 != null && ltftB1 != null -> stftB1 + ltftB1
            else -> ltftB1 ?: stftB1
        }

        val codes = report.dtcs.map { it.code.uppercase() }.toSet()
        val hypotheses = mutableListOf<AiHypothesis>()

        // 1. Electrical / CAN Cascade Hypothesis
        val hasNetworkOrVoltCode = codes.any { it.startsWith("U") || it == "P0562" || it == "P0563" }
        val engineRunning = (rpm != null && rpm > 350.0)
        val hasBadVoltage = voltage != null && (
            (!engineRunning && voltage < 12.0) ||
                (engineRunning && (voltage < 13.1 || voltage > 15.0))
            )
        if (hasBadVoltage || hasNetworkOrVoltCode) {
            val vFmt = voltage?.let { String.format(Locale.US, "%.2fV", it) } ?: "Low/Unstable"
            val confidence = if (hasBadVoltage && codes.any { it.startsWith("U") }) 92 else 81
            hypotheses += AiHypothesis(
                id = "ai_elec_cascade",
                confidencePercent = confidence,
                zone = CarSubsystemZone.ELECTRICAL,
                relatedCodes = codes.filter { it.startsWith("U") || it.startsWith("P056") },
                titleEn = "Power Supply / Voltage Instability Driving Electronic Faults ($vFmt)",
                titleAr = "اضطراب البطارية أو الشحن يسبب أعطالاً كهربائية متسلسلة ($vFmt)",
                reasoningEn = "Automotive ECUs require steady 12.2–14.8V power. When voltage drops or surges ($vFmt), CAN modules (ECM, TCM, ABS) briefly drop offline and log phantom U-codes or transmission/ABS warnings.",
                reasoningAr = "كمبيوترات السيارة (ECM وTCM وABS) تحتاج فولتية مستقرة؛ ضعف البطارية أو تذبذب الدينامو ($vFmt) يؤدي لتسجيل أكواد شبكة U-codes وتنبيهات غير حقيقية في القير أو الفرامل.",
                firstChecksEn = listOf(
                    "Clean & tighten battery clamps and chassis/engine ground strap before replacing any ECU",
                    "Load-test battery CCA and check alternator charging (13.6–14.8V at idle with headlights on)"
                ),
                firstChecksAr = listOf(
                    "نظّف وشدّ أصابع البطارية وسلك التأريض قبل تبديل أي كمبيوتر أو حساس",
                    "افحص البطارية وشحن الدينامو تحت الحمل (13.6–14.8 فولت والمحرك يعمل)"
                )
            )
        }

        // 2. Thermal / Cooling Emergency or Thermostat Stuck Open
        if ((coolantC != null && coolantC >= 106.0) || "P0117" in codes || "P0118" in codes || "P0217" in codes) {
            val tempDisplay = coolantRaw?.let { "${it.toInt()}${if (unit == MeasurementUnit.METRIC) "°C" else "°F"}" } ?: "High"
            hypotheses += AiHypothesis(
                id = "ai_thermal_high",
                confidencePercent = 94,
                zone = CarSubsystemZone.ENGINE,
                relatedCodes = codes.filter { it in setOf("P0117", "P0118", "P0217") },
                titleEn = "Active Engine Overheating Risk ($tempDisplay)",
                titleAr = "خطر ارتفاع حرارة المحرك — أولوية قصوى ($tempDisplay)",
                reasoningEn = "Coolant temperature exceeds safe thermal limits. Driving while hot risks head gasket failure and warped cylinder heads.",
                reasoningAr = "درجة حرارة سائل التبريد تجاوزت الحد الآمن، والاستمرار بالقيادة يعرض وجه الرأس (القازقيت) للتلف الفوري.",
                firstChecksEn = listOf(
                    "Park safely and shut off engine; DO NOT open radiator cap while hot",
                    "Confirm cooling fans run when AC turns ON and inspect coolant hoses/thermostat once cool"
                ),
                firstChecksAr = listOf(
                    "أوقف السيارة وأطفئ المحرك فوراً ولا تفتح غطاء الرديتر وهو ساخن",
                    "تأكد من عمل مراوح الرديتر مع المكيف وافحص مستوى الماء وبلف الحرارة بعد أن يبرد المحرك"
                )
            )
        } else if ("P0128" in codes || (coolantC != null && coolantC < 73.0)) {
            hypotheses += AiHypothesis(
                id = "ai_thermostat_cold",
                confidencePercent = 87,
                zone = CarSubsystemZone.ENGINE,
                relatedCodes = listOfNotNull("P0128".takeIf { it in codes }),
                titleEn = "Thermostat Stuck Open / Under-Temperature Operation",
                titleAr = "بلف الحرارة (الثرموستات) عالق مفتوحاً أو المحرك لا يسخن للمستوى الطبيعي",
                reasoningEn = "Coolant stays below optimal operating window (~85–95°C), increasing fuel burn and keeping closed-loop enrichment active longer.",
                reasoningAr = "بقاء حرارة المحرك أقل من المعدل الطبيعي (~85–95°C) يرفع استهلاك الوقود ويمنع وصول المحرك لحرارة الاحتراق المثالية.",
                firstChecksEn = listOf(
                    "Check whether upper/lower radiator hoses warm up equally right after a cold start",
                    "Replace thermostat with OEM temperature spec and bleed trapped air"
                ),
                firstChecksAr = listOf(
                    "تأكد أن لي الرديتر لا يسخن فوراً عند بداية التشغيل البارد",
                    "استبدل بلف الحرارة بقطعة أصلية ونسم الهواء من دورة التبريد"
                )
            )
        }

        // 3. Misfire + Lean Fuel Trim + Catalytic Chain
        val misfireCodes = codes.filter { it.startsWith("P030") }
        val trimCodes = codes.filter { it in setOf("P0171", "P0172", "P0174", "P0175", "P0101", "P0102") }
        val catCodes = codes.filter { it in setOf("P0420", "P0430") }
        if (misfireCodes.isNotEmpty() || trimCodes.isNotEmpty() || (combinedTrim != null && kotlin.math.abs(combinedTrim) > 12.0)) {
            val isChainWithCat = catCodes.isNotEmpty() && (misfireCodes.isNotEmpty() || trimCodes.isNotEmpty())
            val trimTxt = combinedTrim?.let { String.format(Locale.US, "%+.1f%%", it) }
            val chainTitleEn = if (isChainWithCat) {
                "Air/Fuel Mixture or Misfire Triggering Downstream Catalytic Warning"
            } else if (misfireCodes.isNotEmpty() && trimCodes.isNotEmpty()) {
                "Lean Mixture / Air Leak Driving Cylinder Misfires"
            } else if (misfireCodes.isNotEmpty()) {
                "Combustion Misfire Detected (${misfireCodes.joinToString(", ")})"
            } else {
                "Fuel Trim Compensation Outside Normal Range (${trimTxt ?: "Alert"})"
            }
            val chainTitleAr = if (isChainWithCat) {
                "خلل الخليط أو التفتفة هو السبب الأرجح لظهور إنذار دبة البيئة (${catCodes.joinToString(", ")})"
            } else if (misfireCodes.isNotEmpty() && trimCodes.isNotEmpty()) {
                "دخول هواء غير مقاس أو ضعف الوقود يسبب تفتفة المحرك (${misfireCodes.joinToString(", ")})"
            } else if (misfireCodes.isNotEmpty()) {
                "تقطيع / عدم انتظام احتراق في الأسطوانات (${misfireCodes.joinToString(", ")})"
            } else {
                "اختلال نسبة خليط الهواء والبنزين (${trimTxt ?: "تنبيه"})"
            }

            val cylinderCode = misfireCodes.firstOrNull { it.length == 5 && it[4] in '1'..'8' }
            val extraTestEn = if (cylinderCode != null) {
                "Zero-cost swap test: swap ignition coil & spark plug from Cylinder ${cylinderCode[4]} to a neighbor cylinder and see if the DTC moves"
            } else {
                "Inspect intake boot, PCV hoses, and vacuum lines for cracks before replacing sensors"
            }
            val extraTestAr = if (cylinderCode != null) {
                "فحص مجاني قبل الشراء: بدّل مكان الكويل والشمعة من الأسطوانة ${cylinderCode[4]} لأسطوانة سليمة وراقب هل ينتقل الكود معها"
            } else {
                "افحص خراطيم الهواء وليات الفاكيوم وتبخير PCV من أي تشقق قبل شراء حساسات أو كويلات"
            }

            hypotheses += AiHypothesis(
                id = "ai_combustion_chain",
                confidencePercent = if (isChainWithCat || (misfireCodes.isNotEmpty() && trimCodes.isNotEmpty())) 90 else 82,
                zone = if (misfireCodes.isNotEmpty()) CarSubsystemZone.ENGINE else CarSubsystemZone.FUEL_EXHAUST,
                relatedCodes = (misfireCodes + trimCodes + catCodes).distinct(),
                titleEn = chainTitleEn,
                titleAr = chainTitleAr,
                reasoningEn = buildString {
                    if (trimTxt != null) append("Live combined fuel trim is $trimTxt. ")
                    if (isChainWithCat) {
                        append("Unburned fuel or lean combustion from misfire/fuel trim errors rapidly overheats the catalytic converter and triggers P0420/P0430. Always repair combustion & fuel mixture BEFORE buying a catalytic converter.")
                    } else {
                        append("Fixing air leaks, MAF contamination, or a single failing coil resolves multiple symptoms together.")
                    }
                },
                reasoningAr = buildString {
                    if (trimTxt != null) append("مجموع تعديل الوقود الحي حالياً هو $trimTxt. ")
                    if (isChainWithCat) {
                        append("استمرار التفتفة أو اختلال الخليط يرسل وقوداً غير محترق لدبة البيئة ويطلق كود ${catCodes.joinToString(", ")}؛ لا تغير دبة البيئة أبداً قبل إصلاح التفتفة والخليط أولاً.")
                    } else {
                        append("علاج تسريب الهواء أو تنظيف حساس MAF أو التأكد من الكويل يحل أكثر من عطل في وقت واحد.")
                    }
                },
                firstChecksEn = listOfNotNull(
                    extraTestEn,
                    "Clean MAF sensor with MAF-safe cleaner and verify fuel rail pressure under load",
                    if (isChainWithCat) "Do NOT replace the catalytic converter until trims & misfires stay clear for 2–3 drives" else null
                ),
                firstChecksAr = listOfNotNull(
                    extraTestAr,
                    "نظّف حساس الهواء (MAF) بمادة مخصصة وتأكد من ضغط طرمبة البنزين",
                    if (isChainWithCat) "لا تستبدل دبة البيئة حتى تثبت معالجة التفتفة وتستقر نسب الوقود بعد مسح الأعطال" else null
                )
            )
        } else if (catCodes.isNotEmpty()) {
            hypotheses += AiHypothesis(
                id = "ai_cat_standalone",
                confidencePercent = 79,
                zone = CarSubsystemZone.FUEL_EXHAUST,
                relatedCodes = catCodes,
                titleEn = "Catalytic Efficiency Below Threshold (${catCodes.joinToString(", ")})",
                titleAr = "انخفاض كفاءة دبة البيئة / المحول الحفاز (${catCodes.joinToString(", ")})",
                reasoningEn = "With fuel trims stable and zero misfires, P0420/P0430 points to an aging catalytic core, slow rear O2 sensor, or pre-cat exhaust leak.",
                reasoningAr = "بما أن التفتفة ونسب الوقود مستقرة، يشير الكود إلى ضعف فعلي في دبة البيئة أو بطء حساس الأكسجين الخلفي أو تنسيم في وصلة الشكمان.",
                firstChecksEn = listOf(
                    "Listen/inspect for exhaust flange leaks ahead of the rear O2 sensor",
                    "Watch Live O2 Sensors (PID 14 vs 15) at warm idle — rear sensor should stay much steadier than front"
                ),
                firstChecksAr = listOf(
                    "افحص وصلات الشكمان قبل الحساس الخلفي للتأكد من عدم وجود تنسيم عادم",
                    "قارن إشارة الحساس الأمامي والخلفي في تبويب «الحساسات» والمحرك ساخن؛ السليم أن يكون الخلفي أكثر ثباتاً"
                )
            )
        }

        // 4. Transmission + Wheel Speed Sensor Interaction
        val transCodes = codes.filter { it.startsWith("P07") || it.startsWith("P08") || it.startsWith("P09") }
        val absCodes = codes.filter { it.startsWith("C0") }
        if (transCodes.isNotEmpty() || absCodes.isNotEmpty()) {
            hypotheses += AiHypothesis(
                id = "ai_drivetrain_abs",
                confidencePercent = if (transCodes.isNotEmpty() && absCodes.isNotEmpty()) 85 else 80,
                zone = if (transCodes.isNotEmpty()) CarSubsystemZone.TRANSMISSION else CarSubsystemZone.BRAKES_CHASSIS,
                relatedCodes = (transCodes + absCodes).distinct(),
                titleEn = if (transCodes.isNotEmpty() && absCodes.isNotEmpty()) {
                    "Transmission Warning Linked With Wheel Speed / ABS Signal"
                } else if (transCodes.isNotEmpty()) {
                    "Transmission Control Module Alert (${transCodes.joinToString(", ")})"
                } else {
                    "ABS / Wheel Speed / Chassis Sensor Fault (${absCodes.joinToString(", ")})"
                },
                titleAr = if (transCodes.isNotEmpty() && absCodes.isNotEmpty()) {
                    "تنبيه القير قد يتأثر بعطل حساس سرعة العجلة (ABS)"
                } else if (transCodes.isNotEmpty()) {
                    "تنبيه منظومة ناقل الحركة / القير (${transCodes.joinToString(", ")})"
                } else {
                    "عطل في منظومة الفرامل / حساس العجلة ABS (${absCodes.joinToString(", ")})"
                },
                reasoningEn = "Modern automatic/CVT gearboxes use wheel speed sensors from ABS and stable battery power for shift timing. Always check fluid level, connectors, and ABS tone rings before internal transmission repairs.",
                reasoningAr = "القير الأوتوماتيك الحديث يعتمد على إشارة حساسات سرعة العجلات (ABS) واستقرار الكهرباء لتحديد التبديلات؛ لذا يجب فحص الفيش وزيت القير وحساس ABS قبل فك القير.",
                firstChecksEn = listOf(
                    "Inspect ATF level/color and external TCM/ABS harness connectors for oil or corrosion",
                    "Fix any low battery or ABS wheel speed fault before condemning internal transmission solenoids"
                ),
                firstChecksAr = listOf(
                    "افحص مستوى ولون زيت القير ونظافة الفيش الكهربائية للقير وحساس العجلة",
                    "عالج أي ضعف في البطارية أو عطل حساس ABS أولاً قبل افتراض عطل ميكانيكي داخل القير"
                )
            )
        }

        // 5. EVAP Small/Large Leak Priority
        val evapCodes = codes.filter { it.startsWith("P044") || it.startsWith("P045") }
        if (evapCodes.isNotEmpty()) {
            hypotheses += AiHypothesis(
                id = "ai_evap",
                confidencePercent = 83,
                zone = CarSubsystemZone.FUEL_EXHAUST,
                relatedCodes = evapCodes,
                titleEn = "EVAP Vapor Seal / Purge Valve Issue (${evapCodes.joinToString(", ")})",
                titleAr = "تسريب في منظومة أبخرة البنزين EVAP (${evapCodes.joinToString(", ")})",
                reasoningEn = "EVAP leaks rarely harm engine mechanics and are most frequently caused by a loose/cracked fuel cap rubber seal or a stuck purge solenoid.",
                reasoningAr = "أكواد EVAP لا تمنع القيادة غالباً وأكثر أسبابها شيوعاً تلف جلدة غطاء تانكي البنزين أو صمام الأبخرة (Purge Valve).",
                firstChecksEn = listOf(
                    "Tighten gas cap until it clicks 3 times and inspect its rubber O-ring for cracks",
                    "Inspect EVAP purge hose in the engine bay before paying for a full smoke test"
                ),
                firstChecksAr = listOf(
                    "أغلق غطاء البنزين بإحكام حتى تسمع صوت القفل وتأكد من سلامة الجلدة المطاطية",
                    "افحص لي وصمام EVAP Purge في حوض المحرك قبل عمل فحص الدخان"
                )
            )
        }

        // 6. Fallback Hypothesis for Any Uncovered Codes
        val coveredCodes = hypotheses.flatMap { it.relatedCodes }.toSet()
        val uncoveredDtcs = report.dtcs.filter { it.code.uppercase() !in coveredCodes }
        if (uncoveredDtcs.isNotEmpty()) {
            val top = uncoveredDtcs.sortedBy { severityRank(it.knowledge.severity) }.first()
            hypotheses += AiHypothesis(
                id = "ai_dtc_${top.code}",
                confidencePercent = 76,
                zone = CarZoneMapper.zoneForDtc(top),
                relatedCodes = uncoveredDtcs.map { it.code },
                titleEn = if (uncoveredDtcs.size == 1) top.knowledge.titleEn else "${uncoveredDtcs.size} Additional Diagnostic Code(s) Led by ${top.code}",
                titleAr = if (uncoveredDtcs.size == 1) top.knowledge.titleAr else "${uncoveredDtcs.size} أكواد أعطال إضافية أبرزها ${top.code}",
                reasoningEn = top.knowledge.descriptionEn,
                reasoningAr = top.knowledge.descriptionAr,
                firstChecksEn = top.knowledge.fixStepsEn.take(2),
                firstChecksAr = top.knowledge.fixStepsAr.take(2)
            )
        }

        val sortedHypotheses = hypotheses.sortedByDescending { it.confidencePercent }

        // Determine Safety Verdict
        val hasCritical = hypotheses.any { it.id == "ai_thermal_high" } ||
            report.dtcs.any { !it.knowledge.canDriveSafely || it.knowledge.severity == DtcSeverity.CRITICAL } ||
            report.observations.any { it.severity == com.carchecker.otg.model.ObservationSeverity.CRITICAL }

        val hasModerateIssues = report.dtcs.isNotEmpty() ||
            report.observations.any {
                it.severity == com.carchecker.otg.model.ObservationSeverity.WARNING &&
                    it.category != com.carchecker.otg.model.ObservationCategory.PRE_PURCHASE_CHECK
            }
        val hasPrePurchaseWarn = report.observations.any {
            it.severity == com.carchecker.otg.model.ObservationSeverity.WARNING &&
                it.category == com.carchecker.otg.model.ObservationCategory.PRE_PURCHASE_CHECK
        }

        val verdict = when {
            hasCritical -> SafetyVerdict.STOP_IMMEDIATELY
            hasModerateIssues -> SafetyVerdict.CAUTION_REPAIR_SOON
            hasPrePurchaseWarn -> SafetyVerdict.SAFE_MONITOR
            report.healthScore >= 90 && report.dtcs.isEmpty() -> SafetyVerdict.OPTIMAL
            else -> SafetyVerdict.SAFE_MONITOR
        }

        // Pre-purchase readiness insight
        val r = report.readiness
        var preEn: String? = null
        var preAr: String? = null
        if (r != null) {
            val incomplete = r.monitors.count { it.supported && !it.isContinuous && !it.completed }
            val distClear = r.distanceSinceClearKm
            val warmups = r.warmupsSinceClear
            if (incomplete > 0 && ((distClear != null && distClear < 80) || (warmups != null && warmups < 5))) {
                preEn = "Pre-Purchase Alert: DTC memory was recently cleared (${distClear ?: "?"} km / ${warmups ?: "?"} warm-ups ago) with $incomplete emission monitor(s) still incomplete. Drive 20–30 min and re-scan before buying."
                preAr = "تحذير ما قبل الشراء: تم مسح ذاكرة الأعطال مؤخراً (منذ ${distClear ?: "؟"} كم / ${warmups ?: "؟"} تشغيلات) وهناك $incomplete أنظمة لم تكمل الفحص الذاتي بعد؛ يُنصح بقيادة السيارة 20–30 دقيقة ثم إعادة الفحص."
            } else if (incomplete == 0 && report.dtcs.isEmpty()) {
                preEn = "Pre-Purchase Verified: All OBD-II readiness self-tests are completed with zero hidden or recently wiped fault patterns."
                preAr = "فحص الشراء ممتاز: جميع اختبارات الجاهزية الذاتية مكتملة ولا توجد دلائل على مسح أعطال مخفية."
            }
        }

        val vehicleLabel = listOfNotNull(report.vehicleInfo.make, report.vehicleInfo.modelYear?.toString())
            .joinToString(" ")
            .ifBlank { "Vehicle" }
        val vehicleLabelAr = listOfNotNull(report.vehicleInfo.make, report.vehicleInfo.modelYear?.toString())
            .joinToString(" ")
            .ifBlank { "السيارة" }

        val summaryEn = when (verdict) {
            SafetyVerdict.STOP_IMMEDIATELY ->
                "$vehicleLabel shows high-risk condition (${report.healthScore}/100, ${report.dtcs.size} DTCs). Prioritize the top root cause below before long or high-load driving."
            SafetyVerdict.CAUTION_REPAIR_SOON ->
                "$vehicleLabel is drivable cautiously (${report.healthScore}/100, ${report.dtcs.size} DTCs). Follow the cost-first repair plan below to resolve correlated causes together."
            SafetyVerdict.SAFE_MONITOR ->
                "$vehicleLabel shows minor observations (${report.healthScore}/100) with no critical driving restriction."
            SafetyVerdict.OPTIMAL ->
                "$vehicleLabel passed full diagnostics cleanly (${report.healthScore}/100, 0 faults). Systems and live readings look healthy."
        }

        val summaryAr = when (verdict) {
            SafetyVerdict.STOP_IMMEDIATELY ->
                "$vehicleLabelAr تسجل حالة عالية الخطورة (الصحة ${report.healthScore}%، الأعطال ${report.dtcs.size}). تجنب القيادة الطويلة وابدأ فوراً بالخطوة الأولى في خطة الحل."
            SafetyVerdict.CAUTION_REPAIR_SOON ->
                "يمكن قيادة $vehicleLabelAr بحذر حالياً (الصحة ${report.healthScore}%، الأعطال ${report.dtcs.size}). اتبع خطة الفحص المرتبة من الأقل تكلفة لحل السبب الجذري دون تبديل قطع سليمة."
            SafetyVerdict.SAFE_MONITOR ->
                "$vehicleLabelAr بحالة مستقرة (الصحة ${report.healthScore}%) مع ملاحظات بسيطة للمتابعة فقط."
            SafetyVerdict.OPTIMAL ->
                "$vehicleLabelAr اجتازت الفحص الشامل بنجاح (الصحة ${report.healthScore}%، بدون أعطال)، وجميع القراءات الحيوية في النطاق الطبيعي."
        }

        val priorityStepsEn = buildPriorityStepsEn(sortedHypotheses, report)
        val priorityStepsAr = buildPriorityStepsAr(sortedHypotheses, report)

        return SmartAiReport(
            verdict = verdict,
            summaryEn = summaryEn,
            summaryAr = summaryAr,
            hypotheses = sortedHypotheses,
            priorityStepsEn = priorityStepsEn,
            priorityStepsAr = priorityStepsAr,
            prePurchaseNoteEn = preEn,
            prePurchaseNoteAr = preAr
        )
    }

    fun answerQuestion(
        question: String,
        report: FullScanReport?,
        liveSensors: List<LiveSensorReading>,
        unit: MeasurementUnit,
        language: AppLanguage,
        focusedZone: CarSubsystemZone? = null,
        focusedDtc: DtcKnowledgeEntry? = null
    ): String {
        val isAr = language == AppLanguage.AR
        val q = question.trim()
        val qLower = q.lowercase()

        if (report == null && focusedDtc == null) {
            return if (isAr) {
                "قم أولاً بتشغيل «الفحص الشامل» عبر كابل OTG أو وضع المحاكاة حتى أتمكن من تحليل قراءات وأعطال سيارتك بدقة."
            } else {
                "Run a Full Vehicle Scan via USB OTG or Simulation Mode first so I can analyze your car's real fault codes and sensor telemetry."
            }
        }

        // Match any explicit DTC typed in question (e.g., P0171)
        val mentionedCode = Regex("[PCBU][0-9A-F]{4}", RegexOption.IGNORE_CASE).find(q)?.value?.uppercase()
        val matchingDtc = mentionedCode?.let { code ->
            report?.dtcs?.firstOrNull { it.code.equals(code, ignoreCase = true) }?.knowledge
        } ?: focusedDtc

        if (matchingDtc != null && (mentionedCode != null || focusedDtc != null && ("شرح" in qLower || "معن" in qLower || "explain" in qLower || "what" in qLower || "كيف" in qLower || "حل" in qLower))) {
            return buildFaultAiInsight(matchingDtc, report, liveSensors, unit, language)
        }

        val aiReport = report?.let { analyze(it, liveSensors, unit) }

        return when {
            // Safety / can I drive?
            anyIn(qLower, "اسوق", "أسوق", "سوق", "قيادة", "امان", "أمان", "امن", "آمن", "خطر", "طريق", "safe", "drive", "danger", "stop") -> {
                if (aiReport == null) return ""
                val badge = verdictLabel(aiReport.verdict, isAr)
                val firstStep = if (isAr) aiReport.priorityStepsAr.firstOrNull() else aiReport.priorityStepsEn.firstOrNull()
                if (isAr) {
                    buildString {
                        appendLine("الحكم الذكي على أمان القيادة: $badge")
                        appendLine(aiReport.summaryAr)
                        if (!firstStep.isNullOrBlank()) {
                            appendLine()
                            append("أهم خطوة قبل التحرك: $firstStep")
                        }
                    }
                } else {
                    buildString {
                        appendLine("AI Driving Safety Verdict: $badge")
                        appendLine(aiReport.summaryEn)
                        if (!firstStep.isNullOrBlank()) {
                            appendLine()
                            append("Priority action before driving: $firstStep")
                        }
                    }
                }
            }

            // Repair order / where to start / avoid wasting money
            anyIn(qLower, "ابدا", "أبدأ", "بدايه", "ترتيب", "تصليح", "اصلاح", "حل", "خطوات", "قطع", "fix", "start", "repair", "order", "first", "cost") -> {
                if (aiReport == null) return ""
                val steps = if (isAr) aiReport.priorityStepsAr else aiReport.priorityStepsEn
                val header = if (isAr) "خطة التصليح الذكية (من الأسهل والأقل تكلفة للأصعب):" else "Cost-First Smart Repair Sequence:"
                buildString {
                    appendLine(header)
                    steps.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
                }.trim()
            }

            // Where on the car / location
            anyIn(qLower, "مكان", "وين", "فين", "اين", "أين", "خريطة", "منطقة", "location", "where", "zone", "part") -> {
                val activeZones = report?.let {
                    CarZoneMapper.mapZones(it, liveSensors).values.filter { z ->
                        z.faultCodes.isNotEmpty() || z.status.name in setOf("CRITICAL", "WARNING")
                    }
                }.orEmpty()
                if (activeZones.isEmpty()) {
                    if (isAr) "جميع مناطق السيارة في المخطط سليمة حالياً ولا تظهر أي أعطال موضعية." else "All vehicle zones on the X-Ray car map look healthy right now."
                } else {
                    val header = if (isAr) "مواقع الملاحظات والأعطال على السيارة:" else "Detected Issue Locations on Your Car:"
                    buildString {
                        appendLine(header)
                        activeZones.forEach { z ->
                            val title = CarZoneMapper.zoneTitle(z.zone, isAr)
                            val codesStr = z.faultCodes.joinToString(", ") { it.code }.ifBlank {
                                z.observations.firstOrNull()?.let { if (isAr) it.titleAr else it.titleEn }.orEmpty()
                            }
                            appendLine("• $title: $codesStr")
                        }
                    }.trim()
                }
            }

            // Pre-purchase / reset check
            anyIn(qLower, "شراء", "تصفير", "مسح", "مشتري", "مخفي", "فحص دوري", "جاهزية", "buy", "purchase", "reset", "clear", "readiness") -> {
                val preNote = if (isAr) aiReport?.prePurchaseNoteAr else aiReport?.prePurchaseNoteEn
                if (!preNote.isNullOrBlank()) preNote
                else if (isAr) "لم يكتشف مسح حديث مشبوه؛ تفحص سجل الأعطال والملاحظات للتأكد من حالة البطارية وحرارة المحرك."
                else "No suspicious recent fault reset pattern detected; review Readiness Monitors and battery/temperature notes."
            }

            // Focused zone query or system keyword
            focusedZone != null || anyIn(qLower, "قير", "جير", "بطارية", "حرارة", "دبة", "كتاليزر", "فرامل", "abs", "trans", "battery", "coolant", "cat") -> {
                val inferredZone = focusedZone ?: when {
                    anyIn(qLower, "قير", "جير", "trans") -> CarSubsystemZone.TRANSMISSION
                    anyIn(qLower, "بطارية", "شحن", "دينامو", "كهرب", "battery", "volt") -> CarSubsystemZone.ELECTRICAL
                    anyIn(qLower, "فرامل", "abs", "عجلة", "brake") -> CarSubsystemZone.BRAKES_CHASSIS
                    anyIn(qLower, "دبة", "كتاليزر", "وقود", "بنزين", "cat", "fuel", "evap") -> CarSubsystemZone.FUEL_EXHAUST
                    else -> CarSubsystemZone.ENGINE
                }
                val summary = report?.let { CarZoneMapper.mapZones(it, liveSensors)[inferredZone] }
                val title = CarZoneMapper.zoneTitle(inferredZone, isAr)
                if (summary == null || (summary.faultCodes.isEmpty() && summary.observations.isEmpty())) {
                    if (isAr) "منطقة «$title» تبدو سليمة بناءً على آخر فحص." else "$title looks healthy based on your latest scan."
                } else {
                    buildString {
                        appendLine(if (isAr) "تحليل منطقة ($title):" else "$title Diagnosis:")
                        summary.faultCodes.forEach { dtc ->
                            appendLine("• ${dtc.code}: ${if (isAr) dtc.knowledge.titleAr else dtc.knowledge.titleEn}")
                            val fix = if (isAr) dtc.knowledge.fixStepsAr.firstOrNull() else dtc.knowledge.fixStepsEn.firstOrNull()
                            if (!fix.isNullOrBlank()) appendLine("   ↳ ${if (isAr) "الحل الأول" else "First check"}: $fix")
                        }
                        summary.observations.take(2).forEach { obs ->
                            appendLine("• ${if (isAr) obs.titleAr else obs.titleEn}")
                        }
                    }.trim()
                }
            }

            // Default holistic AI answer (e.g., "ما السبب الأهم؟")
            else -> {
                if (aiReport == null) return ""
                val topHyp = aiReport.hypotheses.firstOrNull()
                if (isAr) {
                    buildString {
                        appendLine(aiReport.summaryAr)
                        if (topHyp != null) {
                            appendLine()
                            appendLine("السبب الجذري الأرجح (${topHyp.confidencePercent}% ثقة): ${topHyp.titleAr}")
                            appendLine(topHyp.reasoningAr)
                            val check = topHyp.firstChecksAr.firstOrNull()
                            if (!check.isNullOrBlank()) appendLine("الخطوة الموصى بها أولاً: $check")
                        }
                    }.trim()
                } else {
                    buildString {
                        appendLine(aiReport.summaryEn)
                        if (topHyp != null) {
                            appendLine()
                            appendLine("Most Probable Root Cause (${topHyp.confidencePercent}%): ${topHyp.titleEn}")
                            appendLine(topHyp.reasoningEn)
                            val check = topHyp.firstChecksEn.firstOrNull()
                            if (!check.isNullOrBlank()) appendLine("Recommended first check: $check")
                        }
                    }.trim()
                }
            }
        }
    }

    fun buildFaultAiInsight(
        knowledge: DtcKnowledgeEntry,
        report: FullScanReport?,
        liveSensors: List<LiveSensorReading>,
        unit: MeasurementUnit,
        language: AppLanguage
    ): String {
        val isAr = language == AppLanguage.AR
        val zone = CarZoneMapper.zoneForKnowledge(knowledge)
        val zoneLabel = CarZoneMapper.zoneTitle(zone, isAr)
        val activeSensors = if (liveSensors.isNotEmpty()) liveSensors else report?.sensorSnapshot.orEmpty()
        val sensorMap = activeSensors.associateBy { it.pidHex }
        val voltage = sensorMap["42"]?.value ?: report?.vehicleInfo?.batteryVoltage
        val stft = sensorMap["06"]?.value
        val ltft = sensorMap["07"]?.value
        val allCodes = report?.dtcs?.map { it.code.uppercase() }.orEmpty().toSet()

        val contextHints = mutableListOf<String>()
        if (knowledge.code.uppercase() in setOf("P0420", "P0430") && (allCodes.any { it.startsWith("P030") } || "P0171" in allCodes)) {
            contextHints += if (isAr) {
                "تنبيه ذكي: وجود التفتفة أو اختلال الخليط مع هذا الكود قد يكون السبب المباشر؛ لا تغير دبة البيئة قبل إصلاحهما."
            } else {
                "AI Correlation: Active misfire or lean mixture can trigger this catalytic code; repair combustion/air-fuel issues before replacing the catalytic converter."
            }
        }
        if ((knowledge.code.startsWith("U") || knowledge.code == "P0700" || knowledge.code.startsWith("C")) && voltage != null && voltage < 12.6) {
            val vFmt = String.format(Locale.US, "%.2fV", voltage)
            contextHints += if (isAr) {
                "تنبيه كهربائي: جهد البطارية ($vFmt) منخفض، وقد يسبب أعطالاً وهمية في الكمبيوترات والحساسات."
            } else {
                "Power Correlation: System voltage ($vFmt) is low, which frequently induces false module/ABS/TCM communication codes."
            }
        }
        if (knowledge.code.uppercase() in setOf("P0171", "P0174") && (stft != null || ltft != null)) {
            val total = (stft ?: 0.0) + (ltft ?: 0.0)
            val tFmt = String.format(Locale.US, "%+.1f%%", total)
            contextHints += if (isAr) {
                "القراءة الحية تؤكد ارتفاع تعديل الوقود ($tFmt) مما يدعم احتمال وجود تنسيم هواء أو اتساخ حساس MAF."
            } else {
                "Live telemetry confirms elevated fuel trim ($tFmt), supporting a vacuum leak or dirty MAF sensor diagnosis."
            }
        }

        val firstFix = if (isAr) knowledge.fixStepsAr.firstOrNull() else knowledge.fixStepsEn.firstOrNull()
        val safetyText = if (knowledge.canDriveSafely) {
            if (isAr) "يمكن القيادة بحذر لمسافة قصيرة حتى الفحص." else "Drivable cautiously over short distances until repaired."
        } else {
            if (isAr) "غير آمن للقيادة الطويلة — افحص قبل التحرك." else "Unsafe for extended driving — inspect immediately."
        }

        return if (isAr) {
            buildString {
                appendLine("الموقع على السيارة: $zoneLabel")
                appendLine("تقييم السلامة: $safetyText")
                contextHints.forEach { appendLine("• $it") }
                if (!firstFix.isNullOrBlank()) append("ابدأ بهذه الخطوة أولاً: $firstFix")
            }.trim()
        } else {
            buildString {
                appendLine("Location on Vehicle: $zoneLabel")
                appendLine("Safety Verdict: $safetyText")
                contextHints.forEach { appendLine("• $it") }
                if (!firstFix.isNullOrBlank()) append("Start with this check first: $firstFix")
            }.trim()
        }
    }

    fun verdictLabel(verdict: SafetyVerdict, isAr: Boolean): String = when (verdict) {
        SafetyVerdict.STOP_IMMEDIATELY -> if (isAr) "خطورة عالية — توقف وافحص فوراً" else "High Risk — Stop & Inspect"
        SafetyVerdict.CAUTION_REPAIR_SOON -> if (isAr) "قيادة بحذر — أصلح قريباً" else "Caution — Repair Soon"
        SafetyVerdict.SAFE_MONITOR -> if (isAr) "آمن للقيادة مع المتابعة" else "Safe — Monitor"
        SafetyVerdict.OPTIMAL -> if (isAr) "ممتاز وآمن تماماً" else "Optimal & Safe"
    }

    private fun buildPriorityStepsEn(hypotheses: List<AiHypothesis>, report: FullScanReport): List<String> {
        val steps = linkedSetOf<String>()
        hypotheses.forEach { h -> h.firstChecksEn.forEach { steps += it } }
        if (steps.isEmpty()) {
            report.observations.firstOrNull()?.resolutionStepsEn?.forEach { steps += it }
        }
        if (steps.isEmpty()) {
            steps += "Maintain scheduled fluid, filter, and battery checks"
        }
        return steps.take(5)
    }

    private fun buildPriorityStepsAr(hypotheses: List<AiHypothesis>, report: FullScanReport): List<String> {
        val steps = linkedSetOf<String>()
        hypotheses.forEach { h -> h.firstChecksAr.forEach { steps += it } }
        if (steps.isEmpty()) {
            report.observations.firstOrNull()?.resolutionStepsAr?.forEach { steps += it }
        }
        if (steps.isEmpty()) {
            steps += "حافظ على مواعيد الصيانة الدورية للزيوت والفلاتر والبطارية"
        }
        return steps.take(5)
    }

    private fun severityRank(sev: DtcSeverity): Int = when (sev) {
        DtcSeverity.CRITICAL -> 0
        DtcSeverity.HIGH -> 1
        DtcSeverity.MODERATE -> 2
        DtcSeverity.LOW -> 3
        DtcSeverity.INFO -> 4
    }

    private fun anyIn(text: String, vararg tokens: String): Boolean = tokens.any { it in text }
}
