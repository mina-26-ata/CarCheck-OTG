package com.carchecker.otg.data.dtc

import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.DtcSeverity
import com.carchecker.otg.model.DtcSystem

object SaeJ2012FallbackDecoder {

    fun decode(rawCode: String): DtcKnowledgeEntry {
        val code = rawCode.trim().uppercase()
        val systemChar = code.firstOrNull() ?: 'P'
        val typeChar = code.getOrNull(1) ?: '0'
        val subChar = code.getOrNull(2) ?: '0'

        val system = when (systemChar) {
            'P' -> DtcSystem.POWERTRAIN
            'C' -> DtcSystem.CHASSIS
            'B' -> DtcSystem.BODY
            'U' -> DtcSystem.NETWORK
            else -> DtcSystem.POWERTRAIN
        }

        val isGeneric = typeChar == '0' || typeChar == '2'
        val scopeEn = if (isGeneric) "SAE Generic" else "Manufacturer-Specific"
        val scopeAr = if (isGeneric) "كود قياسي عام (SAE)" else "كود خاص بالشركة المصنّعة"

        val (subEn, subAr) = subsystemLabel(system, subChar)
        val severity = inferSeverity(system, subChar)

        val titleEn = "$code — $subEn ($scopeEn)"
        val titleAr = "$code — $subAr ($scopeAr)"

        val descEn = "Diagnostic trouble code $code reported in the $subEn ($scopeEn). " +
            "Use the inspection steps below or consult the vehicle's factory service manual for exact circuit pinouts."
        val descAr = "تم تسجيل كود العطل $code ضمن نطاق: $subAr ($scopeAr). " +
            "اتبع خطوات الفحص أدناه أو راجع دليل الصيانة الخاص بطراز السيارة للتحقق من الحساس أو الضفيرة بدقة."

        val (causesEn, causesAr, stepsEn, stepsAr) = buildSubsystemGuidance(system, subEn, subAr)

        return DtcKnowledgeEntry(
            code = code,
            system = system,
            isGeneric = isGeneric,
            severity = severity,
            canDriveSafely = severity != DtcSeverity.CRITICAL,
            titleEn = titleEn,
            titleAr = titleAr,
            descriptionEn = descEn,
            descriptionAr = descAr,
            causesEn = causesEn,
            causesAr = causesAr,
            symptomsEn = listOf(
                "Warning indicator lamp (MIL / ABS / System warning) may illuminate",
                "Possible performance reduction, limp mode, or stored freeze frame"
            ),
            symptomsAr = listOf(
                "إضاءة لمبة الأعطال (Check Engine أو لمبة النظام المرتبط)",
                "احتمال ضعف الأداء أو دخول السيارة في وضع الحماية (Limp Mode)"
            ),
            fixStepsEn = stepsEn,
            fixStepsAr = stepsAr,
            estimatedDifficultyEn = "Moderate — Diagnostic Scan & Circuit Check",
            estimatedDifficultyAr = "متوسط — يحتاج فحصاً كهربائياً وقراءة بيانات حية",
            isFromBuiltInDb = false
        )
    }

    private fun subsystemLabel(system: DtcSystem, sub: Char): Pair<String, String> = when (system) {
        DtcSystem.POWERTRAIN -> when (sub) {
            '0' -> "Fuel & Air Metering and Auxiliary Emission Controls" to "قياس الوقود والهواء وأنظمة الانبعاثات المساعدة"
            '1' -> "Fuel & Air Metering System" to "نظام قياس الوقود والهواء"
            '2' -> "Fuel & Air Metering (Injector / Boost Circuit)" to "نظام الوقود والبخاخات / التيربو"
            '3' -> "Ignition System or Misfire" to "نظام الإشعال أو التفتفة (Misfire)"
            '4' -> "Auxiliary Emission Controls (Catalyst / EVAP / EGR)" to "أنظمة الانبعاثات (دبة البيئة / EVAP / EGR)"
            '5' -> "Vehicle Speed, Idle Control & Auxiliary Inputs" to "حساس السرعة، التحكم بالسلانسيه (Idle) والجهد"
            '6' -> "Computer & Output Circuits (ECM / PCM)" to "كمبيوتر المحرك ودوائر الخرج"
            '7', '8', '9' -> "Transmission / Gearbox System" to "ناقل الحركة (القير) ودوائره"
            'A', 'B', 'C' -> "Hybrid / Electric Propulsion System" to "منظومة الدفع الهجين / الكهربائي"
            else -> "Powertrain Subsystem" to "منظومة المحرك ونقل الحركة"
        }
        DtcSystem.CHASSIS -> "Chassis / ABS / Stability / Steering System" to "نظام الشاصي والفرامل ABS والثبات والتوجيه"
        DtcSystem.BODY -> "Body / Airbag (SRS) / Climate / BCM" to "نظام الهيكل / الوسائد الهوائية / التكييف / وحدة BCM"
        DtcSystem.NETWORK -> "CAN Bus / Module Network Communication" to "شبكة الاتصال بين كمبيوترات السيارة (CAN Bus)"
    }

    private fun inferSeverity(system: DtcSystem, sub: Char): DtcSeverity = when {
        system == DtcSystem.POWERTRAIN && sub == '3' -> DtcSeverity.HIGH
        system == DtcSystem.POWERTRAIN && sub in listOf('6', '7', '8') -> DtcSeverity.HIGH
        system == DtcSystem.CHASSIS -> DtcSeverity.HIGH
        system == DtcSystem.NETWORK -> DtcSeverity.MODERATE
        else -> DtcSeverity.MODERATE
    }

    private data class Guidance(
        val causesEn: List<String>,
        val causesAr: List<String>,
        val stepsEn: List<String>,
        val stepsAr: List<String>
    )

    private fun buildSubsystemGuidance(system: DtcSystem, subEn: String, subAr: String): Guidance {
        return when (system) {
            DtcSystem.NETWORK -> Guidance(
                causesEn = listOf(
                    "Low battery voltage or poor ground connection on a control module",
                    "Loose, corroded, or damaged CAN bus connector / wiring",
                    "Blown fuse supplying power to the target ECU",
                    "Faulty control module interrupting CAN high/low lines"
                ),
                causesAr = listOf(
                    "ضعف البطارية أو ضعف التأريض (الأرضي) لأحد الكمبيوترات",
                    "ارتخاء أو أكسدة أو قطع في فيشة أو أسلاك شبكة CAN",
                    "تلف فيوز تغذية الكمبيوتر المفقود الاتصال به",
                    "عطل داخلي في إحدى الوحدات يعيق اتصال الشبكة"
                ),
                stepsEn = listOf(
                    "Check battery voltage (> 12.2V key-off, 13.6–14.8V running) and clean battery terminals",
                    "Inspect all fuses related to ECM, TCM, ABS, and BCM",
                    "Check main ECU connectors for moisture, bent pins, or loose locking tabs",
                    "Clear codes after fixing power/grounds and verify if communication restores"
                ),
                stepsAr = listOf(
                    "افحص جهد البطارية (فوق 12.2V عند الإطفاء و 13.6–14.8V عند التشغيل) ونظّف الأقطاب",
                    "افحص جميع الفيوزات الخاصة بكمبيوتر المحرك والقير وABS وBCM",
                    "افحص أفياش الكمبيوترات بحثاً عن رطوبة أو ارتخاء أو تآكل",
                    "امسح الأعطال بعد إصلاح التغذية والأرضي وتأكد من عودة الاتصال"
                )
            )
            DtcSystem.CHASSIS -> Guidance(
                causesEn = listOf(
                    "Dirty, damaged, or misaligned wheel speed sensor / tone ring",
                    "Damaged wheel speed sensor wiring harness near suspension",
                    "Low brake fluid level or hydraulic ABS module fault",
                    "Steering angle sensor needing calibration after alignment"
                ),
                causesAr = listOf(
                    "اتساخ أو تلف حساس سرعة العجلة (ABS) أو ترس الاستشعار",
                    "قطع أو تلامس في سلك الحساس قرب المساعد أو الهوب",
                    "نقص زيت الفرامل أو عطل بوحدة ABS الهيدروليكية",
                    "حاجة حساس زاوية الدركسون لمعايرة بعد ضبط الزوايا"
                ),
                stepsEn = listOf(
                    "Check brake fluid reservoir level and inspect for leaks",
                    "Visually inspect wheel speed sensor wires behind the wheel hub for rubbing or cuts",
                    "Clean metallic dust/debris from the wheel speed sensor tip",
                    "If steering alignment was done recently, recalibrate the steering angle sensor"
                ),
                stepsAr = listOf(
                    "تأكد من مستوى زيت الفرامل وعدم وجود تسريب",
                    "افحص أسلاك حساسات السرعة خلف العجلات بحثاً عن قطع أو احتكاك",
                    "نظّف رأس حساس ABS من برادة الحديد والأوساخ",
                    "إذا تم عمل ميزان للدركسون مؤخراً، قم بمعايرة حساس زاوية المقود"
                )
            )
            DtcSystem.BODY -> Guidance(
                causesEn = listOf(
                    "Open/short circuit in body harness, door boot, or under-seat connector",
                    "Weak 12V battery triggering body module undervoltage errors",
                    "Faulty switch, actuator, climate sensor, or SRS clock spring"
                ),
                causesAr = listOf(
                    "قطع أو تماس في ضفيرة الهيكل أو أفياش أسفل المقاعد أو الأبواب",
                    "ضعف بطارية 12 فولت مما يسبب أخطاء انخفاض الجهد في وحدة BCM",
                    "تلف مفتاح أو موتور باب/تكييف أو شريط الإيرباج (Clock Spring)"
                ),
                stepsEn = listOf(
                    "Verify 12V battery health and clear transient low-voltage codes",
                    "Check under-seat and door-jamb wiring connectors (common source of SRS/BCM faults)",
                    "Inspect corresponding fuses and relays in the interior fuse box"
                ),
                stepsAr = listOf(
                    "تأكد من سلامة بطارية السيارة وامسح أي أعطال ناتجة عن هبوط الجهد",
                    "افحص الأفياش أسفل المقاعد وفي مفاصل الأبواب (سبب شائع لأعطال الإيرباج والهيكل)",
                    "افحص الفيوزات والريليهات في علبة الفيوزات الداخلية"
                )
            )
            DtcSystem.POWERTRAIN -> Guidance(
                causesEn = listOf(
                    "Faulty or contaminated sensor/actuator in $subEn",
                    "Damaged wiring harness, corroded connector, or blown fuse",
                    "Vacuum leak, fuel pressure irregularity, or mechanical wear (depending on subsystem)"
                ),
                causesAr = listOf(
                    "تلف أو اتساخ الحساس أو المشغل المرتبط بـ $subAr",
                    "قطع أو أكسدة في الأسلاك أو الفيشة أو تلف فيوز التغذية",
                    "تسريب هواء أو ضعف بضغط الوقود أو خلل ميكانيكي ضمن النظام"
                ),
                stepsEn = listOf(
                    "Inspect Live Sensors & Freeze Frame data in this app to see abnormal readings at the time of fault",
                    "Check wiring connectors, hoses, and fuses serving the affected subsystem",
                    "Test the suspected sensor/actuator before replacing parts, then clear DTCs and road-test"
                ),
                stepsAr = listOf(
                    "راجع البيانات الحية ولقطة التجميد (Freeze Frame) داخل التطبيق لملاحظة القيم غير الطبيعية",
                    "افحص الأفياش والأسلاك والليات والفيوزات المغذية للنظام المعني",
                    "اختبر الحساس أو القطعة قبل التبديل العشوائي، ثم امسح العطل وجرّب السيارة"
                )
            )
        }
    }
}
