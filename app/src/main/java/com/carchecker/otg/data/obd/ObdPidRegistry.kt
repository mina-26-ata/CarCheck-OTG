package com.carchecker.otg.data.obd

import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.SensorCategory

data class SensorPidDefinition(
    val pidHex: String,
    val nameEn: String,
    val nameAr: String,
    val unitMetric: String,
    val unitImperial: String,
    val category: SensorCategory,
    val normalMinMetric: Double? = null,
    val normalMaxMetric: Double? = null,
    val decimals: Int = 1,
    val decodeMetric: (IntArray) -> Double?,
    val convertToImperial: (Double) -> Double = { it }
) {
    fun unitFor(unit: MeasurementUnit): String =
        if (unit == MeasurementUnit.METRIC) unitMetric else unitImperial

    fun valueFor(metricValue: Double, unit: MeasurementUnit): Double =
        if (unit == MeasurementUnit.METRIC) metricValue else convertToImperial(metricValue)

    fun minFor(unit: MeasurementUnit): Double? = normalMinMetric?.let { valueFor(it, unit) }
    fun maxFor(unit: MeasurementUnit): Double? = normalMaxMetric?.let { valueFor(it, unit) }
}

object ObdPidRegistry {

    private fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0
    private fun kmhToMph(kmh: Double): Double = kmh * 0.621371
    private fun kpaToPsi(kpa: Double): Double = kpa * 0.145038
    private fun kmToMiles(km: Double): Double = km * 0.621371

    val sensors: List<SensorPidDefinition> = listOf(
        SensorPidDefinition(
            pidHex = "0C",
            nameEn = "Engine RPM",
            nameAr = "دورات المحرك (RPM)",
            unitMetric = "RPM",
            unitImperial = "RPM",
            category = SensorCategory.ENGINE,
            normalMinMetric = 600.0,
            normalMaxMetric = 4500.0,
            decimals = 0,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) / 4.0 else null }
        ),
        SensorPidDefinition(
            pidHex = "0D",
            nameEn = "Vehicle Speed",
            nameAr = "سرعة السيارة",
            unitMetric = "km/h",
            unitImperial = "mph",
            category = SensorCategory.ENGINE,
            normalMinMetric = 0.0,
            normalMaxMetric = 200.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.toDouble() },
            convertToImperial = ::kmhToMph
        ),
        SensorPidDefinition(
            pidHex = "05",
            nameEn = "Coolant Temperature",
            nameAr = "حرارة سائل التبريد",
            unitMetric = "°C",
            unitImperial = "°F",
            category = SensorCategory.TEMPERATURE,
            normalMinMetric = 75.0,
            normalMaxMetric = 103.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.let { it - 40.0 } },
            convertToImperial = ::cToF
        ),
        SensorPidDefinition(
            pidHex = "42",
            nameEn = "Control Module Voltage",
            nameAr = "جهد البطارية / الكمبيوتر",
            unitMetric = "V",
            unitImperial = "V",
            category = SensorCategory.ELECTRICAL,
            normalMinMetric = 12.2,
            normalMaxMetric = 14.8,
            decimals = 2,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) / 1000.0 else null }
        ),
        SensorPidDefinition(
            pidHex = "04",
            nameEn = "Calculated Engine Load",
            nameAr = "حمل المحرك المحسوب",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.ENGINE,
            normalMinMetric = 10.0,
            normalMaxMetric = 85.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "06",
            nameEn = "Short Term Fuel Trim (B1)",
            nameAr = "تعديل الوقود القصير (بنك 1)",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.FUEL,
            normalMinMetric = -10.0,
            normalMaxMetric = 10.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { (it - 128.0) * 100.0 / 128.0 } }
        ),
        SensorPidDefinition(
            pidHex = "07",
            nameEn = "Long Term Fuel Trim (B1)",
            nameAr = "تعديل الوقود الطويل (بنك 1)",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.FUEL,
            normalMinMetric = -10.0,
            normalMaxMetric = 10.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { (it - 128.0) * 100.0 / 128.0 } }
        ),
        SensorPidDefinition(
            pidHex = "08",
            nameEn = "Short Term Fuel Trim (B2)",
            nameAr = "تعديل الوقود القصير (بنك 2)",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.FUEL,
            normalMinMetric = -10.0,
            normalMaxMetric = 10.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { (it - 128.0) * 100.0 / 128.0 } }
        ),
        SensorPidDefinition(
            pidHex = "09",
            nameEn = "Long Term Fuel Trim (B2)",
            nameAr = "تعديل الوقود الطويل (بنك 2)",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.FUEL,
            normalMinMetric = -10.0,
            normalMaxMetric = 10.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { (it - 128.0) * 100.0 / 128.0 } }
        ),
        SensorPidDefinition(
            pidHex = "0A",
            nameEn = "Fuel Pressure (Gauge)",
            nameAr = "ضغط الوقود",
            unitMetric = "kPa",
            unitImperial = "psi",
            category = SensorCategory.FUEL,
            normalMinMetric = 200.0,
            normalMaxMetric = 450.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 3.0 } },
            convertToImperial = ::kpaToPsi
        ),
        SensorPidDefinition(
            pidHex = "23",
            nameEn = "Fuel Rail Gauge Pressure (DI)",
            nameAr = "ضغط مسطرة الوقود المباشر",
            unitMetric = "kPa",
            unitImperial = "psi",
            category = SensorCategory.FUEL,
            normalMinMetric = 2000.0,
            normalMaxMetric = 20000.0,
            decimals = 0,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) * 10.0 else null },
            convertToImperial = ::kpaToPsi
        ),
        SensorPidDefinition(
            pidHex = "0B",
            nameEn = "Intake Manifold Pressure (MAP)",
            nameAr = "ضغط مشعب السحب (MAP)",
            unitMetric = "kPa",
            unitImperial = "psi",
            category = SensorCategory.AIR_INTAKE,
            normalMinMetric = 20.0,
            normalMaxMetric = 105.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.toDouble() },
            convertToImperial = ::kpaToPsi
        ),
        SensorPidDefinition(
            pidHex = "0E",
            nameEn = "Ignition Timing Advance",
            nameAr = "تقديم توقيت الإشعال",
            unitMetric = "°",
            unitImperial = "°",
            category = SensorCategory.ENGINE,
            normalMinMetric = -5.0,
            normalMaxMetric = 45.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { (it / 2.0) - 64.0 } }
        ),
        SensorPidDefinition(
            pidHex = "0F",
            nameEn = "Intake Air Temperature (IAT)",
            nameAr = "حرارة هواء السحب (IAT)",
            unitMetric = "°C",
            unitImperial = "°F",
            category = SensorCategory.TEMPERATURE,
            normalMinMetric = 5.0,
            normalMaxMetric = 65.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.let { it - 40.0 } },
            convertToImperial = ::cToF
        ),
        SensorPidDefinition(
            pidHex = "10",
            nameEn = "Mass Air Flow (MAF)",
            nameAr = "تدفق الهواء (MAF)",
            unitMetric = "g/s",
            unitImperial = "g/s",
            category = SensorCategory.AIR_INTAKE,
            normalMinMetric = 1.5,
            normalMaxMetric = 150.0,
            decimals = 2,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) / 100.0 else null }
        ),
        SensorPidDefinition(
            pidHex = "11",
            nameEn = "Throttle Position (TPS)",
            nameAr = "فتحة البوابة / الخانق (TPS)",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.AIR_INTAKE,
            normalMinMetric = 0.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "14",
            nameEn = "O2 Sensor Voltage (B1 S1)",
            nameAr = "حساس الأكسجين (B1 S1)",
            unitMetric = "V",
            unitImperial = "V",
            category = SensorCategory.EMISSIONS,
            normalMinMetric = 0.1,
            normalMaxMetric = 0.9,
            decimals = 3,
            decodeMetric = { d -> d.firstOrNull()?.let { it / 200.0 } }
        ),
        SensorPidDefinition(
            pidHex = "15",
            nameEn = "O2 Sensor Voltage (B1 S2)",
            nameAr = "حساس الأكسجين بعد المحفز (B1 S2)",
            unitMetric = "V",
            unitImperial = "V",
            category = SensorCategory.EMISSIONS,
            normalMinMetric = 0.1,
            normalMaxMetric = 0.9,
            decimals = 3,
            decodeMetric = { d -> d.firstOrNull()?.let { it / 200.0 } }
        ),
        SensorPidDefinition(
            pidHex = "24",
            nameEn = "Wideband O2 Equivalence Ratio (B1 S1)",
            nameAr = "نسبة الوقود/الهواء الواسعة (B1 S1)",
            unitMetric = "λ",
            unitImperial = "λ",
            category = SensorCategory.EMISSIONS,
            normalMinMetric = 0.92,
            normalMaxMetric = 1.08,
            decimals = 3,
            decodeMetric = { d -> if (d.size >= 2) (2.0 / 65536.0) * ((d[0] * 256.0) + d[1]) else null }
        ),
        SensorPidDefinition(
            pidHex = "2C",
            nameEn = "Commanded EGR",
            nameAr = "نسبة فتح صمام EGR",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.EMISSIONS,
            normalMinMetric = 0.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "2E",
            nameEn = "Commanded EVAP Purge",
            nameAr = "نسبة تصريف أبخرة الوقود (EVAP)",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.EMISSIONS,
            normalMinMetric = 0.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "2F",
            nameEn = "Fuel Tank Level",
            nameAr = "مستوى الوقود بالخزان",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.FUEL,
            normalMinMetric = 10.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "33",
            nameEn = "Barometric Pressure",
            nameAr = "الضغط الجوي المحيط",
            unitMetric = "kPa",
            unitImperial = "psi",
            category = SensorCategory.AIR_INTAKE,
            normalMinMetric = 85.0,
            normalMaxMetric = 105.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.toDouble() },
            convertToImperial = ::kpaToPsi
        ),
        SensorPidDefinition(
            pidHex = "3C",
            nameEn = "Catalyst Temperature (B1 S1)",
            nameAr = "حرارة دبة البيئة / المحفز (B1 S1)",
            unitMetric = "°C",
            unitImperial = "°F",
            category = SensorCategory.TEMPERATURE,
            normalMinMetric = 250.0,
            normalMaxMetric = 850.0,
            decimals = 0,
            decodeMetric = { d -> if (d.size >= 2) (((d[0] * 256.0) + d[1]) / 10.0) - 40.0 else null },
            convertToImperial = ::cToF
        ),
        SensorPidDefinition(
            pidHex = "43",
            nameEn = "Absolute Engine Load",
            nameAr = "الحمل المطلق للمحرك",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.ENGINE,
            normalMinMetric = 10.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) * 100.0 / 255.0 else null }
        ),
        SensorPidDefinition(
            pidHex = "45",
            nameEn = "Relative Throttle Position",
            nameAr = "وضع الخانق النسبي",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.AIR_INTAKE,
            normalMinMetric = 0.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "46",
            nameEn = "Ambient Air Temperature",
            nameAr = "درجة الحرارة الخارجية",
            unitMetric = "°C",
            unitImperial = "°F",
            category = SensorCategory.TEMPERATURE,
            normalMinMetric = -10.0,
            normalMaxMetric = 55.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.let { it - 40.0 } },
            convertToImperial = ::cToF
        ),
        SensorPidDefinition(
            pidHex = "49",
            nameEn = "Accelerator Pedal Position D",
            nameAr = "موضع دواسة البنزين D",
            unitMetric = "%",
            unitImperial = "%",
            category = SensorCategory.ENGINE,
            normalMinMetric = 0.0,
            normalMaxMetric = 100.0,
            decimals = 1,
            decodeMetric = { d -> d.firstOrNull()?.let { it * 100.0 / 255.0 } }
        ),
        SensorPidDefinition(
            pidHex = "5C",
            nameEn = "Engine Oil Temperature",
            nameAr = "حرارة زيت المحرك",
            unitMetric = "°C",
            unitImperial = "°F",
            category = SensorCategory.TEMPERATURE,
            normalMinMetric = 75.0,
            normalMaxMetric = 125.0,
            decimals = 0,
            decodeMetric = { d -> d.firstOrNull()?.let { it - 40.0 } },
            convertToImperial = ::cToF
        ),
        SensorPidDefinition(
            pidHex = "5E",
            nameEn = "Engine Fuel Rate",
            nameAr = "معدل استهلاك الوقود",
            unitMetric = "L/h",
            unitImperial = "L/h",
            category = SensorCategory.FUEL,
            normalMinMetric = 0.4,
            normalMaxMetric = 35.0,
            decimals = 2,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) / 20.0 else null }
        ),
        SensorPidDefinition(
            pidHex = "1F",
            nameEn = "Engine Run Time",
            nameAr = "مدة تشغيل المحرك",
            unitMetric = "min",
            unitImperial = "min",
            category = SensorCategory.ENGINE,
            decimals = 1,
            decodeMetric = { d -> if (d.size >= 2) ((d[0] * 256.0) + d[1]) / 60.0 else null }
        ),
        SensorPidDefinition(
            pidHex = "A6",
            nameEn = "Odometer",
            nameAr = "عداد المسافات (Odometer)",
            unitMetric = "km",
            unitImperial = "mi",
            category = SensorCategory.OTHER,
            decimals = 0,
            decodeMetric = { d ->
                if (d.size >= 4) {
                    val raw = (d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()
                    raw / 10.0
                } else null
            },
            convertToImperial = ::kmToMiles
        )
    )

    val byPidHex: Map<String, SensorPidDefinition> = sensors.associateBy { it.pidHex.uppercase() }
}
