package com.carchecker.otg.domain.analyzer

import com.carchecker.otg.model.DiagnosedDtc
import com.carchecker.otg.model.DtcSeverity
import com.carchecker.otg.model.DtcStatus
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.ObservationCategory
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.model.ReadinessSummary
import com.carchecker.otg.model.VehicleInfo
import com.carchecker.otg.model.VehicleObservation
import java.util.Locale
import kotlin.math.abs

data class HealthAnalysisResult(
    val healthScore: Int,
    val observations: List<VehicleObservation>
)

class VehicleHealthAnalyzer {

    fun analyze(
        vehicleInfo: VehicleInfo,
        dtcs: List<DiagnosedDtc>,
        readiness: ReadinessSummary?,
        sensors: List<LiveSensorReading>,
        unit: MeasurementUnit
    ): HealthAnalysisResult {
        val observations = mutableListOf<VehicleObservation>()
        var penalty = 0

        val sensorMap = sensors.associateBy { it.pidHex }
        val rpm = sensorMap["0C"]?.value
        val speed = sensorMap["0D"]?.value
        val coolantDisplay = sensorMap["05"]?.value
        val coolantC = coolantDisplay?.let { if (unit == MeasurementUnit.IMPERIAL) (it - 32.0) * 5.0 / 9.0 else it }
        val voltage = sensorMap["42"]?.value ?: vehicleInfo.batteryVoltage
        val stftB1 = sensorMap["06"]?.value
        val ltftB1 = sensorMap["07"]?.value
        val stftB2 = sensorMap["08"]?.value
        val ltftB2 = sensorMap["09"]?.value
        val engineLoad = sensorMap["04"]?.value
        val runTimeMin = sensorMap["1F"]?.value
        val o2B1S1 = sensorMap["14"]?.value
        val o2B1S2 = sensorMap["15"]?.value

        // 1. Battery & Charging Analysis
        if (voltage != null) {
            val engineRunning = (rpm != null && rpm > 350.0)
            val vFmt = String.format(Locale.US, "%.2f V", voltage)
            when {
                engineRunning && voltage > 15.0 -> {
                    penalty += 22
                    observations.add(
                        VehicleObservation(
                            id = "bat_overcharge",
                            severity = ObservationSeverity.CRITICAL,
                            category = ObservationCategory.BATTERY_CHARGING,
                            titleEn = "Overcharging Voltage Detected ($vFmt)",
                            titleAr = "ارتفاع خطر في جهد شحن الدينامو ($vFmt)",
                            summaryEn = "Alternator voltage is above 15.0V while engine is running, risking damage to battery and vehicle ECUs.",
                            summaryAr = "جهد الشحن يتجاوز 15.0 فولت أثناء عمل المحرك، مما قد يتلف البطارية وكمبيوترات السيارة.",
                            measuredValue = vFmt,
                            normalRange = "13.6 V – 14.8 V",
                            impactEn = "Can boil battery electrolyte and burn sensitive electronic modules.",
                            impactAr = "قد يؤدي إلى تلف البطارية وحرق الدوائر الإلكترونية الحساسة.",
                            resolutionStepsEn = listOf(
                                "Stop driving long distances and have alternator voltage regulator tested immediately",
                                "Check battery terminal tightness and alternator sense wiring"
                            ),
                            resolutionStepsAr = listOf(
                                "تجنب القيادة لمسافات طويلة وافحص منظم الجهد (الكتاوت) في الدينامو فوراً",
                                "تأكد من ثبات أصابع البطارية وأسلاك التحكم بالدينامو"
                            )
                        )
                    )
                }
                engineRunning && voltage < 13.1 -> {
                    penalty += 18
                    observations.add(
                        VehicleObservation(
                            id = "bat_undercharge",
                            severity = ObservationSeverity.CRITICAL,
                            category = ObservationCategory.BATTERY_CHARGING,
                            titleEn = "Alternator Not Charging Properly ($vFmt)",
                            titleAr = "ضعف أو توقف شحن الدينامو ($vFmt)",
                            summaryEn = "With the engine running, system voltage should be 13.6V–14.8V. At $vFmt, the battery is discharging.",
                            summaryAr = "أثناء عمل المحرك يجب أن يكون الجهد بين 13.6 و 14.8 فولت، لكن القراءة الحالية $vFmt تعني أن البطارية لا تُشحن جيداً.",
                            measuredValue = vFmt,
                            normalRange = "13.6 V – 14.8 V",
                            impactEn = "Vehicle may stall once battery depletes and trigger false ABS/TCM/ECM voltage faults.",
                            impactAr = "قد تنطفئ السيارة أثناء السير وتظهر أعطال كهربائية متفرقة في القير وABS.",
                            resolutionStepsEn = listOf(
                                "Inspect alternator drive belt (serpentine belt) for slipping or breakage",
                                "Clean and tighten battery terminals and engine ground strap",
                                "Test alternator output at auto electrician; repair brushes/diode/regulator if needed"
                            ),
                            resolutionStepsAr = listOf(
                                "افحص سير المحرك (سير الدينامو) من الارتخاء أو التآكل",
                                "نظّف وشدّ أصابع البطارية وسلك التأريض (الأرضي)",
                                "افحص شحن الدينامو عند كهربائي سيارات واستبدل الفحمات أو الدينامو إذا لزم"
                            )
                        )
                    )
                }
                !engineRunning && voltage < 11.9 -> {
                    penalty += 12
                    observations.add(
                        VehicleObservation(
                            id = "bat_weak_koeo",
                            severity = ObservationSeverity.WARNING,
                            category = ObservationCategory.BATTERY_CHARGING,
                            titleEn = "Low Battery Voltage (Engine Off: $vFmt)",
                            titleAr = "انخفاض جهد البطارية والمحرك متوقف ($vFmt)",
                            summaryEn = "Resting/key-on battery voltage is below 11.9V, indicating a discharged or aging battery.",
                            summaryAr = "جهد البطارية أثناء الإطفاء أقل من 11.9 فولت، مما يشير لضعف البطارية أو استهلاك كهربائي عالٍ.",
                            measuredValue = vFmt,
                            normalRange = "12.2 V – 12.7 V",
                            impactEn = "Risk of hard starting or no-start and spurious low-voltage U-codes.",
                            impactAr = "احتمال تعطل التشغيل (السلف) وظهور أكواد شبكة CAN مؤقتة.",
                            resolutionStepsEn = listOf(
                                "Recharge the battery and perform a Cold Cranking Amps (CCA) load test",
                                "Check for parasitic drain if battery drains overnight"
                            ),
                            resolutionStepsAr = listOf(
                                "اشحن البطارية وقم بفحص كفاءتها (CCA Load Test)",
                                "تأكد من عدم وجود تهريب كهرباء إذا كانت تفرغ عند التوقف الطويل"
                            )
                        )
                    )
                }
                else -> {
                    observations.add(
                        VehicleObservation(
                            id = "bat_good",
                            severity = ObservationSeverity.GOOD,
                            category = ObservationCategory.BATTERY_CHARGING,
                            titleEn = "Battery & Charging Voltage Healthy ($vFmt)",
                            titleAr = "جهد البطارية ونظام الشحن سليم ($vFmt)",
                            summaryEn = "Electrical supply voltage is within expected operating range.",
                            summaryAr = "الجهد الكهربائي للسيارة ضمن النطاق الطبيعي والمستقر.",
                            measuredValue = vFmt,
                            normalRange = if (engineRunning) "13.6 V – 14.8 V" else "12.2 V – 12.7 V",
                            impactEn = "No electrical voltage anomalies detected.",
                            impactAr = "لا توجد ملاحظات على البطارية أو الدينامو حالياً.",
                            resolutionStepsEn = listOf("Continue regular terminal cleanliness checks"),
                            resolutionStepsAr = listOf("استمر في الحفاظ على نظافة أصابع البطارية بشكل دوري")
                        )
                    )
                }
            }
        }

        // 2. Cooling System & Thermostat Analysis
        if (coolantC != null && coolantDisplay != null) {
            val tempStr = if (unit == MeasurementUnit.METRIC) "${coolantC.toInt()} °C" else "${coolantDisplay.toInt()} °F"
            val normalStr = if (unit == MeasurementUnit.METRIC) "80 °C – 103 °C" else "176 °F – 217 °F"
            when {
                coolantC >= 108.0 -> {
                    penalty += 25
                    observations.add(
                        VehicleObservation(
                            id = "coolant_overheat",
                            severity = ObservationSeverity.CRITICAL,
                            category = ObservationCategory.COOLING,
                            titleEn = "Engine Overheating Warning ($tempStr)",
                            titleAr = "تحذير حرج: ارتفاع حرارة المحرك ($tempStr)",
                            summaryEn = "Coolant temperature is critically high! Immediate attention required to prevent head gasket or engine damage.",
                            summaryAr = "حرارة سائل التبريد مرتفعة بشكل خطير! يجب التوقف وفحص نظام التبريد فوراً لتجنب تلف المحرك.",
                            measuredValue = tempStr,
                            normalRange = normalStr,
                            impactEn = "Severe risk of warped cylinder head, blown head gasket, and engine seizure.",
                            impactAr = "خطر تلف وجه الرأس (القازقيت) أو انحناء رأس المكينة.",
                            resolutionStepsEn = listOf(
                                "Pull over safely and shut off engine immediately",
                                "NEVER open radiator cap while hot; wait until completely cool before checking coolant level",
                                "Verify radiator cooling fans spin when AC is switched on, inspect for coolant leaks, thermostat, and water pump"
                            ),
                            resolutionStepsAr = listOf(
                                "أوقف السيارة بمكان آمن وأطفئ المحرك فوراً",
                                "لا تفتح غطاء الرديتر والمحرك ساخن أبداً؛ انتظر حتى يبرد تماماً قبل فحص مستوى الماء",
                                "تأكد من عمل مراوح التبريد وافحص وجود تسريب ماء أو تلف بلف الحرارة أو طرمبة الماء"
                            )
                        )
                    )
                }
                coolantC in 103.0..107.9 -> {
                    penalty += 10
                    observations.add(
                        VehicleObservation(
                            id = "coolant_warm",
                            severity = ObservationSeverity.WARNING,
                            category = ObservationCategory.COOLING,
                            titleEn = "Coolant Running Slightly Hot ($tempStr)",
                            titleAr = "حرارة المحرك أعلى من المعتاد ($tempStr)",
                            summaryEn = "Engine temperature is near the upper threshold. Inspect cooling efficiency before summer or traffic loads.",
                            summaryAr = "حرارة المحرك تقترب من الحد الأعلى، يُنصح بفحص كفاءة التبريد.",
                            measuredValue = tempStr,
                            normalRange = normalStr,
                            impactEn = "May escalate to overheating in heavy traffic or AC load.",
                            impactAr = "قد ترتفع الحرارة أكثر في الزحام أو مع تشغيل المكيف.",
                            resolutionStepsEn = listOf(
                                "Inspect radiator fins for dirt/debris obstruction",
                                "Check cooling fan low and high speed operation and coolant mixture ratio"
                            ),
                            resolutionStepsAr = listOf(
                                "تأكد من نظافة زعانف الرديتر ومجاري الهواء الأمامية",
                                "افحص سرعات مراوح التبريد ومستوى وجودة ماء الرديتر"
                            )
                        )
                    )
                }
                coolantC < 73.0 && (runTimeMin == null || runTimeMin >= 10.0) -> {
                    penalty += 8
                    observations.add(
                        VehicleObservation(
                            id = "coolant_cold_thermostat",
                            severity = ObservationSeverity.WARNING,
                            category = ObservationCategory.COOLING,
                            titleEn = "Engine Running Below Normal Operating Temp ($tempStr)",
                            titleAr = "المحرك يعمل بحرارة أقل من الطبيعي — احتمال تلف بلف الحرارة ($tempStr)",
                            summaryEn = "After warm-up, coolant remains under 73°C (163°F), commonly indicating a thermostat stuck open.",
                            summaryAr = "بعد فترة تشغيل كافية ما زالت الحرارة أقل من 73°C، وهو مؤشر شائع على أن بلف الحرارة (الثرموستات) عالق مفتوحاً أو مُزال.",
                            measuredValue = tempStr,
                            normalRange = normalStr,
                            impactEn = "Increases fuel consumption, accelerates oil contamination, and triggers P0128.",
                            impactAr = "يزيد استهلاك البنزين ويقلل كفاءة التدفئة وقد يظهر كود P0128.",
                            resolutionStepsEn = listOf(
                                "Check if radiator fans run continuously right from a cold start",
                                "Inspect/replace thermostat with OEM temperature-rated unit and bleed cooling system"
                            ),
                            resolutionStepsAr = listOf(
                                "تأكد أن المراوح لا تعمل بشكل دائم عند برودة السيارة",
                                "افحص واستبدل بلف الحرارة (الثرموستات) بقطعة أصلية ونسم الهواء من الدورة"
                            )
                        )
                    )
                }
                else -> {
                    observations.add(
                        VehicleObservation(
                            id = "coolant_good",
                            severity = ObservationSeverity.GOOD,
                            category = ObservationCategory.COOLING,
                            titleEn = "Cooling Temperature Optimal ($tempStr)",
                            titleAr = "حرارة المحرك طبيعية ومثالية ($tempStr)",
                            summaryEn = "Engine coolant temperature is within the ideal operating window.",
                            summaryAr = "درجة حرارة سائل التبريد ضمن النطاق المثالي لعمل المحرك.",
                            measuredValue = tempStr,
                            normalRange = normalStr,
                            impactEn = "Optimal combustion efficiency and engine thermal protection.",
                            impactAr = "تبريد مستقر وكفاءة احتراق ممتازة.",
                            resolutionStepsEn = listOf("Check coolant level periodically when engine is cold"),
                            resolutionStepsAr = listOf("تابع فحص مستوى سائل التبريد عند برودة المحرك دورياً")
                        )
                    )
                }
            }
        }

        // 3. Fuel Trim & Air/Fuel Mixture Analysis
        val totalTrimB1 = if (stftB1 != null && ltftB1 != null) stftB1 + ltftB1 else ltftB1 ?: stftB1
        if (totalTrimB1 != null) {
            val trimStr = String.format(Locale.US, "%+.1f%%", totalTrimB1)
            when {
                totalTrimB1 > 12.0 -> {
                    penalty += 14
                    observations.add(
                        VehicleObservation(
                            id = "fuel_trim_lean",
                            severity = if (totalTrimB1 > 20.0) ObservationSeverity.CRITICAL else ObservationSeverity.WARNING,
                            category = ObservationCategory.FUEL_AIR,
                            titleEn = "Lean Air-Fuel Trim Imbalance (Bank 1: $trimStr)",
                            titleAr = "اختلال خليط الوقود باتجاه الفقر / زيادة هواء (بنك 1: $trimStr)",
                            summaryEn = "Combined Short+Long Term Fuel Trim is strongly positive ($trimStr), meaning the ECU is adding extra fuel to compensate for a vacuum leak, dirty MAF, or weak fuel supply.",
                            summaryAr = "مجموع تعديل الوقود (STFT+LTFT) مرتفع بالموجب ($trimStr)، ما يعني وجود تسريب هواء غير مقاس أو اتساخ حساس الهواء أو ضعف بضغط البنزين.",
                            measuredValue = trimStr,
                            normalRange = "-10% to +10%",
                            impactEn = "Causes rough idle, hesitation, misfires, and higher combustion temperatures.",
                            impactAr = "يسبب تفتفة عند الوقوف وتردد في العزم وقد يتطور لأكواد P0171 أو تفتفة.",
                            resolutionStepsEn = listOf(
                                "Inspect intake air boot, PCV hose, and vacuum lines for cracks or loose clamps",
                                "Clean Mass Air Flow (MAF) sensor with dedicated MAF cleaner",
                                "If trims improve at 2500 RPM, look for a vacuum leak; if trims worsen under load, test fuel pressure & injectors"
                            ),
                            resolutionStepsAr = listOf(
                                "افحص خرطوم الهواء ولي التبخير PCV وليات الفاكيوم من أي شقوق أو فصل",
                                "نظّف حساس الهواء (MAF) ببخاخ التنظيف المخصص",
                                "إذا انخفضت النسبة عند 2500 RPM فالسبب تسريب هواء، وإذا زادت مع الدعس فافحص طرمبة وبخاخات البنزين"
                            )
                        )
                    )
                }
                totalTrimB1 < -12.0 -> {
                    penalty += 14
                    observations.add(
                        VehicleObservation(
                            id = "fuel_trim_rich",
                            severity = if (totalTrimB1 < -20.0) ObservationSeverity.CRITICAL else ObservationSeverity.WARNING,
                            category = ObservationCategory.FUEL_AIR,
                            titleEn = "Rich Air-Fuel Trim Imbalance (Bank 1: $trimStr)",
                            titleAr = "اختلال خليط الوقود باتجاه الغنى / بنزين زائد (بنك 1: $trimStr)",
                            summaryEn = "Combined fuel trim is strongly negative ($trimStr); ECM is cutting fuel due to excess gasoline or restricted airflow.",
                            summaryAr = "مجموع تعديل الوقود سالب بشكل كبير ($trimStr)، أي أن كمية البنزين زائدة عن الطبيعي ويقوم الكمبيوتر بتقليل الضخ.",
                            measuredValue = trimStr,
                            normalRange = "-10% to +10%",
                            impactEn = "Increases fuel consumption, fouls spark plugs, and can overheat the catalytic converter.",
                            impactAr = "يرفع استهلاك البنزين ويسبب سواد البواجي ويجهد دبة البيئة.",
                            resolutionStepsEn = listOf(
                                "Check engine air filter for severe clogging and clean MAF sensor",
                                "Test EVAP purge valve for sticking open (drawing fuel tank vapors at idle)",
                                "Check for leaking fuel injectors or excessive fuel rail pressure"
                            ),
                            resolutionStepsAr = listOf(
                                "افحص فلتر الهواء ونظف حساس MAF",
                                "اختبر صمام أبخرة الوقود EVAP Purge للتأكد من عدم علقه مفتوحاً",
                                "افحص البخاخات من التسييل وتأكد من منظم ضغط الوقود"
                            )
                        )
                    )
                }
                else -> {
                    observations.add(
                        VehicleObservation(
                            id = "fuel_trim_good",
                            severity = ObservationSeverity.GOOD,
                            category = ObservationCategory.FUEL_AIR,
                            titleEn = "Fuel Trims Balanced ($trimStr)",
                            titleAr = "نسب خليط الهواء والوقود متوازنة ($trimStr)",
                            summaryEn = "Air-fuel metering and compensation are operating cleanly within normal tolerances.",
                            summaryAr = "نسب تعديل الوقود (STFT/LTFT) مستقرة ضمن الحدود الطبيعية.",
                            measuredValue = trimStr,
                            normalRange = "-10% to +10%",
                            impactEn = "Healthy air/fuel combustion and optimal economy.",
                            impactAr = "احتراق سليم وصرفية وقود مثالية.",
                            resolutionStepsEn = listOf("Replace air filter on schedule"),
                            resolutionStepsAr = listOf("استمر بتبديل فلتر الهواء في مواعيده")
                        )
                    )
                }
            }
        }

        if (stftB2 != null && ltftB2 != null && totalTrimB1 != null) {
            val totalB2 = stftB2 + ltftB2
            if (abs(totalB2 - totalTrimB1) > 9.0) {
                penalty += 6
                observations.add(
                    VehicleObservation(
                        id = "bank_imbalance",
                        severity = ObservationSeverity.WARNING,
                        category = ObservationCategory.FUEL_AIR,
                        titleEn = "Bank 1 vs Bank 2 Fuel Trim Imbalance",
                        titleAr = "تفاوت في خليط الوقود بين ضفتي المحرك (بنك 1 وبنك 2)",
                        summaryEn = "One engine bank differs from the other by more than 9%, suggesting a bank-specific vacuum/exhaust leak or injector/O2 issue.",
                        summaryAr = "يوجد فرق يتجاوز 9% بين بنك 1 وبنك 2، ما يدل على مشكلة محصورة في ضفة واحدة (تنسيم عادم/ثلاجة أو بخاخ أو حساس O2).",
                        measuredValue = String.format(Locale.US, "B1: %+.1f%% | B2: %+.1f%%", totalTrimB1, totalB2),
                        normalRange = "Diff < 8%",
                        impactEn = "Uneven engine power across cylinder banks.",
                        impactAr = "عدم توازن الاحتراق بين نصفي المحرك.",
                        resolutionStepsEn = listOf(
                            "Inspect exhaust manifold and intake gaskets on the deviating bank",
                            "Compare upstream O2 sensor response on Bank 1 vs Bank 2"
                        ),
                        resolutionStepsAr = listOf(
                            "افحص وجه الثلاجة والشكمان للضفة المختلفة",
                            "قارن أداء حساس الأكسجين الأمامي لبنك 1 مقابل بنك 2"
                        )
                    )
                )
            }
        }

        // 4. Idle & Load Check
        if (rpm != null && speed != null && speed < 2.0 && coolantC != null && coolantC >= 70.0) {
            if (rpm > 1050.0 || (rpm in 100.0..500.0)) {
                penalty += 8
                observations.add(
                    VehicleObservation(
                        id = "idle_abnormal",
                        severity = ObservationSeverity.WARNING,
                        category = ObservationCategory.IGNITION_ENGINE,
                        titleEn = "Abnormal Warm Idle RPM (${rpm.toInt()} RPM)",
                        titleAr = "سرعة دوران المحرك عند التوقف غير معتادة (${rpm.toInt()} RPM)",
                        summaryEn = "At warm idle with vehicle stopped, RPM is outside the typical 580–950 RPM window.",
                        summaryAr = "عند توقف السيارة والمحرك ساخن، سرعة الدوران خارج النطاق المعتاد (580–950 RPM).",
                        measuredValue = "${rpm.toInt()} RPM",
                        normalRange = "580 – 950 RPM",
                        impactEn = "Can cause stalling at stops (if low) or harsh gear engagement & fuel waste (if high).",
                        impactAr = "قد يسبب طفي السيارة عند التوقف (إذا انخفض) أو نتعة القير وصرفية بنزين (إذا ارتفع).",
                        resolutionStepsEn = listOf(
                            "Clean carbon buildup inside the throttle body and perform idle air relearn",
                            "Check for intake vacuum leaks if RPM is high"
                        ),
                        resolutionStepsAr = listOf(
                            "نظّف بوابة الدعسة من الكربون وأعد برمجة السلانسيه",
                            "افحص تسريبات الفاكيوم إذا كان RPM مرتفعاً"
                        )
                    )
                )
            }
            if (engineLoad != null && engineLoad > 38.0) {
                penalty += 5
                observations.add(
                    VehicleObservation(
                        id = "idle_high_load",
                        severity = ObservationSeverity.INFO,
                        category = ObservationCategory.IGNITION_ENGINE,
                        titleEn = "Elevated Engine Load at Idle (${String.format(Locale.US, "%.1f%%", engineLoad)})",
                        titleAr = "ارتفاع حمل المحرك أثناء التوقف (${String.format(Locale.US, "%.1f%%", engineLoad)})",
                        summaryEn = "Engine load at warm idle is higher than typical 15%–32% (may be caused by AC compressor, weak battery charging load, or misfire/air restriction).",
                        summaryAr = "حمل المحرك عند الوقوف أعلى من المعتاد (15%–32%)، قد يكون بسبب تشغيل المكيف أو جهد عالٍ أو خلل بالاحتراق.",
                        measuredValue = String.format(Locale.US, "%.1f%%", engineLoad),
                        normalRange = "15% – 32%",
                        impactEn = "Higher idle fuel burn and engine vibration if AC is off.",
                        impactAr = "يزيد استهلاك الوقود عند الوقوف إذا كان المكيف مطفأ.",
                        resolutionStepsEn = listOf(
                            "Check engine load with AC & headlights turned OFF",
                            "Inspect spark plugs, air filter, and MAF sensor"
                        ),
                        resolutionStepsAr = listOf(
                            "افحص القراءة بعد إطفاء المكيف والأحمال الكهربائية",
                            "تأكد من نظافة فلتر الهواء وحالة البواجي وحساس الهواء"
                        )
                    )
                )
            }
        }

        // 5. Readiness & Pre-Purchase Reset Fraud / Self-Test Check
        if (readiness != null) {
            val incompleteNonCont = readiness.monitors.filter { it.supported && !it.isContinuous && !it.completed }
            val distClear = readiness.distanceSinceClearKm
            val warmups = readiness.warmupsSinceClear
            if (incompleteNonCont.isNotEmpty() && ((distClear != null && distClear < 80) || (warmups != null && warmups < 5))) {
                penalty += 6
                observations.add(
                    VehicleObservation(
                        id = "recent_reset_warning",
                        severity = ObservationSeverity.WARNING,
                        category = ObservationCategory.PRE_PURCHASE_CHECK,
                        titleEn = "DTCs Recently Cleared — Self-Tests Incomplete",
                        titleAr = "تنبيه فحص ما قبل الشراء: تم مسح الأعطال مؤخراً والأنظمة لم تكمل الفحص",
                        summaryEn = "Codes were cleared only ${distClear ?: "?"} km / ${warmups ?: "?"} warm-ups ago, and ${incompleteNonCont.size} emission monitors are still Not Ready. Hidden faults may reappear after a drive cycle.",
                        summaryAr = "تم تصفير كمبيوتر السيارة قبل ${distClear ?: "؟"} كم / ${warmups ?: "؟"} تشغيلات فقط، وما زال هناك ${incompleteNonCont.size} أنظمة لم تكمل الفحص الذاتي. إذا كنت بصدد شراء السيارة فقد تعود أعطال مخفية بعد القيادة!",
                        measuredValue = "${distClear ?: "?"} km | ${incompleteNonCont.size} incomplete",
                        normalRange = "> 80 km & All Ready",
                        impactEn = "Vehicle may fail emissions inspection or hide pending catalytic/EVAP/O2 faults.",
                        impactAr = "قد ترسب السيارة في الفحص الدوري أو تخفي أعطالاً مؤجلة مثل دبة البيئة أو EVAP.",
                        resolutionStepsEn = listOf(
                            "Drive a mixed city + highway cycle (15–30 minutes at steady 80–90 km/h) so all OBD monitors complete",
                            "Re-scan the vehicle after monitors turn Ready, and check Permanent DTCs (Mode 0A)"
                        ),
                        resolutionStepsAr = listOf(
                            "قم بقيادة السيارة لمدة 15–30 دقيقة (مدينة + خط سريع) حتى تكمل جميع الأنظمة فحصها الذاتي",
                            "أعد الفحص بعد اكتمال الجاهزية وتحقق من تبويب الأعطال الدائمة"
                        )
                    )
                )
            } else if (incompleteNonCont.isEmpty() && readiness.monitors.any { it.supported }) {
                observations.add(
                    VehicleObservation(
                        id = "readiness_all_ready",
                        severity = ObservationSeverity.GOOD,
                        category = ObservationCategory.EMISSIONS_READINESS,
                        titleEn = "All OBD-II Readiness Monitors Passed",
                        titleAr = "جميع أنظمة المراقبة والفحص الدوري مكتملة (Ready)",
                        summaryEn = "All supported emission & self-diagnostic monitors have completed successfully.",
                        summaryAr = "أكملت السيارة جميع اختبارات الجاهزية الذاتية للبيئة والأنظمة بنجاح.",
                        measuredValue = "100% Ready",
                        normalRange = "All Supported Ready",
                        impactEn = "No unverified hidden drive-cycle tests remaining.",
                        impactAr = "لا توجد اختبارات ذاتية معلقة.",
                        resolutionStepsEn = listOf("Ready for periodic emissions inspection"),
                        resolutionStepsAr = listOf("السيارة جاهزة للفحص الدوري من ناحية أنظمة OBD-II")
                    )
                )
            }
        }

        // 6. Multi-DTC Root-Cause Correlation & Severity Penalties
        val codes = dtcs.map { it.code }.toSet()
        for (dtc in dtcs) {
            val weight = when (dtc.knowledge.severity) {
                DtcSeverity.CRITICAL -> 22
                DtcSeverity.HIGH -> 14
                DtcSeverity.MODERATE -> 8
                DtcSeverity.LOW -> 4
                DtcSeverity.INFO -> 1
            }
            val statusFactor = if (dtc.status == DtcStatus.PENDING) 0.6 else 1.0
            penalty += (weight * statusFactor).toInt()
        }

        if ("P0300" in codes && ("P0171" in codes || "P0174" in codes)) {
            observations.add(
                VehicleObservation(
                    id = "corr_misfire_lean",
                    severity = ObservationSeverity.WARNING,
                    category = ObservationCategory.IGNITION_ENGINE,
                    titleEn = "Correlated Faults: Lean Mixture (P0171) May Be Causing Misfire (P0300)",
                    titleAr = "ربط الأعطال الذكي: خليط الهواء الزائد (P0171) قد يكون سبب التفتفة (P0300)",
                    summaryEn = "When P0300 occurs alongside P0171/P0174, a vacuum leak or low fuel pressure often starves cylinders of fuel—do not replace all coils/plugs before checking air & fuel.",
                    summaryAr = "ظهور تفتفة عشوائية (P0300) مع كود خليط فقير (P0171) يدل غالباً على تسريب هواء أو ضعف طرمبة بنزين تسببت في التفتفة؛ لا تبدّل جميع البواجي والكويلات قبل فحص الهواء والوقود!",
                    impactEn = "Saves unnecessary part replacement costs.",
                    impactAr = "يوفر عليك تبديل قطع سليمة دون حل السبب الجذري.",
                    resolutionStepsEn = listOf(
                        "Smoke-test intake/PCV lines and inspect MAF sensor first",
                        "Verify fuel pressure before replacing ignition coils"
                    ),
                    resolutionStepsAr = listOf(
                        "افحص تسريبات الهواء وليات التبخير ونظف حساس الهواء أولاً",
                        "تأكد من ضغط طرمبة البنزين قبل تبديل الكويلات"
                    )
                )
            )
        }

        if ("P0420" in codes && (codes.any { it.startsWith("P030") } || "P0171" in codes || "P0172" in codes || "P0133" in codes)) {
            observations.add(
                VehicleObservation(
                    id = "corr_cat_upstream",
                    severity = ObservationSeverity.WARNING,
                    category = ObservationCategory.EMISSIONS_READINESS,
                    titleEn = "Fix Engine/O2 Faults Before Replacing Catalytic Converter (P0420)",
                    titleAr = "تنبيه مهم: عالج التفتفة أو حساس الأكسجين قبل تغيير دبة البيئة (P0420)",
                    summaryEn = "Active misfire, O2, or fuel trim faults will rapidly destroy a new catalytic converter and can falsely trigger P0420.",
                    summaryAr = "وجود تفتفة أو خلل خليط أو بطء حساس الأكسجين الأمامي يسبب ظهور P0420 وسيدمر دبة البيئة الجديدة إذا تم تركيبها قبل الإصلاح!",
                    impactEn = "Prevents costly catalytic converter repeat failure.",
                    impactAr = "يحمي دبة البيئة الجديدة من التلف السريع ويوفر تكلفة عالية.",
                    resolutionStepsEn = listOf(
                        "Repair all misfire (P030x), O2 (P013x), and fuel trim (P017x) issues first",
                        "Clear codes and drive several trips to confirm whether P0420 actually returns"
                    ),
                    resolutionStepsAr = listOf(
                        "أصلح أعطال التفتفة والوقود وحساسات الأكسجين أولاً",
                        "امسح الأعطال وجرب السيارة عدة مشاوير للتأكد إن كان P0420 سيعود فعلاً"
                    )
                )
            )
        }

        if ("P0700" in codes && dtcs.none { it.code.startsWith("P07") && it.code != "P0700" }) {
            observations.add(
                VehicleObservation(
                    id = "corr_p0700",
                    severity = ObservationSeverity.INFO,
                    category = ObservationCategory.TRANSMISSION_CHASSIS,
                    titleEn = "P0700 Requires Reading Transmission Module (TCM)",
                    titleAr = "كود P0700 يشير لعطل داخل كمبيوتر القير (TCM)",
                    summaryEn = "P0700 is a generic alert from the Engine ECM indicating the Transmission ECU requested the Check Engine Light.",
                    summaryAr = "الكود P0700 هو تنبيه عام يرسله كمبيوتر القير لكمبيوتر المحرك، وقد يتطلب قراءة تفصيلية من كمبيوتر القير.",
                    impactEn = "Transmission protection mode may engage.",
                    impactAr = "قد يدخل القير في وضع الحماية (تعليق نمرة واحدة).",
                    resolutionStepsEn = listOf(
                        "Check ATF fluid level and condition",
                        "Inspect external transmission wiring plugs and scan TCM directly if no P07xx sub-code appeared"
                    ),
                    resolutionStepsAr = listOf(
                        "افحص مستوى وحالة زيت القير",
                        "افحص فيشة القير الخارجية والضفيرة"
                    )
                )
            )
        }

        // Check if O2 B1S2 is fluctuating like B1S1 (extra confirmation note)
        if (o2B1S1 != null && o2B1S2 != null && "P0420" in codes) {
            observations.add(
                VehicleObservation(
                    id = "o2_snapshot",
                    severity = ObservationSeverity.INFO,
                    category = ObservationCategory.EMISSIONS_READINESS,
                    titleEn = "O2 Sensors Snapshot: Pre-Cat ${String.format(Locale.US, "%.2fV", o2B1S1)} / Post-Cat ${String.format(Locale.US, "%.2fV", o2B1S2)}",
                    titleAr = "قراءة حساسات الأكسجين: الأمامي ${String.format(Locale.US, "%.2fV", o2B1S1)} / الخلفي ${String.format(Locale.US, "%.2fV", o2B1S2)}",
                    summaryEn = "Monitor live O2 graphs in the Live Sensors tab while idling warm: post-cat O2 should stay relatively steady compared to rapid pre-cat oscillation.",
                    summaryAr = "راقب تذبذب الحساسين في تبويب البيانات الحية والمحرك ساخن: السليم أن يكون الحساس الخلفي أكثر ثباتاً من الأمامي.",
                    impactEn = "Helps distinguish a worn catalytic converter from an O2 sensor or exhaust leak.",
                    impactAr = "يساعد على التفريق بين تلف دبة البيئة وتلف الحساس أو التنسيم.",
                    resolutionStepsEn = listOf("Open Live Sensors -> Emissions to observe both O2 sensors over 20 seconds"),
                    resolutionStepsAr = listOf("افتح تبويب البيانات الحية -> الانبعاثات وراقب استقرار الحساس الخلفي لمدة 20 ثانية")
                )
            )
        }

        if (dtcs.isEmpty() && observations.none { it.severity == ObservationSeverity.CRITICAL || it.severity == ObservationSeverity.WARNING }) {
            observations.add(
                VehicleObservation(
                    id = "overall_clean",
                    severity = ObservationSeverity.GOOD,
                    category = ObservationCategory.IGNITION_ENGINE,
                    titleEn = "No Active or Pending Faults Found",
                    titleAr = "لم يتم العثور على أعطال نشطة أو معلقة بالسيارة",
                    summaryEn = "All scanned modules report zero trouble codes and key live telemetry looks healthy.",
                    summaryAr = "جميع الوحدات المفحوصة خالية من أكواد الأعطال والقيم الأساسية تبدو سليمة.",
                    impactEn = "Vehicle electronic diagnostics look healthy.",
                    impactAr = "الحالة الإلكترونية للسيارة ممتازة.",
                    resolutionStepsEn = listOf("Keep up scheduled oil, filter, coolant, and fluid maintenance"),
                    resolutionStepsAr = listOf("حافظ على الصيانة الدورية للزيوت والفلاتر وسوائل التبريد")
                )
            )
        }

        val sortedObservations = observations.sortedBy {
            when (it.severity) {
                ObservationSeverity.CRITICAL -> 0
                ObservationSeverity.WARNING -> 1
                ObservationSeverity.INFO -> 2
                ObservationSeverity.GOOD -> 3
            }
        }

        val finalScore = (100 - penalty).coerceIn(10, 100)
        return HealthAnalysisResult(
            healthScore = finalScore,
            observations = sortedObservations
        )
    }
}
