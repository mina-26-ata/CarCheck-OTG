package com.carchecker.otg.domain.analyzer

import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.CarZoneSummary
import com.carchecker.otg.model.DiagnosedDtc
import com.carchecker.otg.model.DtcSeverity
import com.carchecker.otg.model.DtcSystem
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.ObservationCategory
import com.carchecker.otg.model.ObservationSeverity
import com.carchecker.otg.model.SensorCategory
import com.carchecker.otg.model.VehicleBodyStyle
import com.carchecker.otg.model.VehicleInfo
import com.carchecker.otg.model.VehicleObservation
import com.carchecker.otg.model.ZoneStatus

/**
 * Maps DTCs, health observations, and live sensors onto physical zones of the vehicle
 * so the X-Ray car wireframe highlights exact fault and warning locations.
 */
object CarZoneMapper {

    fun mapZones(
        report: FullScanReport?,
        liveSensors: List<LiveSensorReading>
    ): Map<CarSubsystemZone, CarZoneSummary> {
        val dtcsByZone = CarSubsystemZone.entries.associateWith { mutableListOf<DiagnosedDtc>() }
        val obsByZone = CarSubsystemZone.entries.associateWith { mutableListOf<VehicleObservation>() }
        val sensorsByZone = CarSubsystemZone.entries.associateWith { mutableListOf<LiveSensorReading>() }

        val activeSensors = if (liveSensors.isNotEmpty()) liveSensors else report?.sensorSnapshot.orEmpty()

        report?.dtcs?.forEach { dtc ->
            val zone = zoneForDtc(dtc)
            dtcsByZone[zone]?.add(dtc)
        }

        report?.observations?.forEach { obs ->
            val zone = zoneForObservation(obs)
            obsByZone[zone]?.add(obs)
        }

        activeSensors.forEach { reading ->
            val zone = zoneForSensor(reading)
            sensorsByZone[zone]?.add(reading)
        }

        val hasScan = report != null
        return CarSubsystemZone.entries.associateWith { zone ->
            val zoneDtcs = dtcsByZone[zone].orEmpty()
            val zoneObs = obsByZone[zone].orEmpty()
            val zoneSensors = sensorsByZone[zone].orEmpty()

            val status = computeZoneStatus(
                hasScan = hasScan,
                dtcs = zoneDtcs,
                observations = zoneObs,
                sensors = zoneSensors
            )

            CarZoneSummary(
                zone = zone,
                status = status,
                faultCodes = zoneDtcs,
                observations = zoneObs,
                liveHighlights = zoneSensors
            )
        }
    }

    fun zoneForDtc(dtc: DiagnosedDtc): CarSubsystemZone =
        zoneForCodeAndSystem(dtc.code, dtc.knowledge.system, dtc.ecuAddress)

    fun zoneForKnowledge(entry: com.carchecker.otg.model.DtcKnowledgeEntry): CarSubsystemZone =
        zoneForCodeAndSystem(entry.code, entry.system, "")

    fun zoneForCodeAndSystem(rawCode: String, system: DtcSystem, ecuAddress: String = ""): CarSubsystemZone {
        val code = rawCode.uppercase()
        val ecu = ecuAddress.uppercase()

        if (code.startsWith("B") || system == DtcSystem.BODY || ecu == "720" || ecu == "7C0") {
            return CarSubsystemZone.BODY_CABIN
        }
        if (code.startsWith("C") || system == DtcSystem.CHASSIS || ecu == "7B0" || ecu == "760") {
            return CarSubsystemZone.BRAKES_CHASSIS
        }
        if (code.startsWith("U") || system == DtcSystem.NETWORK || code.startsWith("P056") || code.startsWith("P06")) {
            return CarSubsystemZone.ELECTRICAL
        }
        if (code.startsWith("P07") || code.startsWith("P08") || code.startsWith("P09") || ecu == "7E1" || ecu == "7E9") {
            return CarSubsystemZone.TRANSMISSION
        }
        if (
            code.startsWith("P04") ||
            code.startsWith("P013") || code.startsWith("P014") ||
            code.startsWith("P015") || code.startsWith("P016") ||
            code.startsWith("P008") || code.startsWith("P009") ||
            code.startsWith("P019") || code.startsWith("P02") ||
            code.startsWith("P017")
        ) {
            return CarSubsystemZone.FUEL_EXHAUST
        }
        return CarSubsystemZone.ENGINE
    }

    fun zoneForObservation(obs: VehicleObservation): CarSubsystemZone {
        return when (obs.category) {
            ObservationCategory.BATTERY_CHARGING -> CarSubsystemZone.ELECTRICAL
            ObservationCategory.COOLING, ObservationCategory.IGNITION_ENGINE -> CarSubsystemZone.ENGINE
            ObservationCategory.FUEL_AIR, ObservationCategory.EMISSIONS_READINESS -> CarSubsystemZone.FUEL_EXHAUST
            ObservationCategory.TRANSMISSION_CHASSIS -> {
                val text = (obs.id + " " + obs.titleEn).lowercase()
                if ("abs" in text || "brake" in text || "wheel" in text || "chassis" in text) {
                    CarSubsystemZone.BRAKES_CHASSIS
                } else {
                    CarSubsystemZone.TRANSMISSION
                }
            }
            ObservationCategory.PRE_PURCHASE_CHECK -> CarSubsystemZone.BODY_CABIN
        }
    }

    private fun zoneForSensor(sensor: LiveSensorReading): CarSubsystemZone = when (sensor.category) {
        SensorCategory.ELECTRICAL -> CarSubsystemZone.ELECTRICAL
        SensorCategory.FUEL, SensorCategory.EMISSIONS -> CarSubsystemZone.FUEL_EXHAUST
        SensorCategory.ENGINE, SensorCategory.AIR_INTAKE, SensorCategory.TEMPERATURE -> CarSubsystemZone.ENGINE
        SensorCategory.OTHER -> CarSubsystemZone.BODY_CABIN
    }

    private fun computeZoneStatus(
        hasScan: Boolean,
        dtcs: List<DiagnosedDtc>,
        observations: List<VehicleObservation>,
        sensors: List<LiveSensorReading>
    ): ZoneStatus {
        val hasCriticalDtc = dtcs.any {
            it.knowledge.severity == DtcSeverity.CRITICAL ||
                it.knowledge.severity == DtcSeverity.HIGH ||
                !it.knowledge.canDriveSafely
        }
        val hasCriticalObs = observations.any { it.severity == ObservationSeverity.CRITICAL }
        if (hasCriticalDtc || hasCriticalObs) return ZoneStatus.CRITICAL

        val hasAnyDtc = dtcs.isNotEmpty()
        val hasWarningObs = observations.any { it.severity == ObservationSeverity.WARNING }
        val hasOutOfRangeSensor = sensors.any { it.isOutOfRange }
        if (hasAnyDtc || hasWarningObs || hasOutOfRangeSensor) return ZoneStatus.WARNING

        if (!hasScan && sensors.isEmpty()) return ZoneStatus.IDLE
        return ZoneStatus.HEALTHY
    }

    fun zoneTitle(zone: CarSubsystemZone, isAr: Boolean): String = when (zone) {
        CarSubsystemZone.ENGINE -> if (isAr) "المحرك والتبريد" else "Engine & Cooling"
        CarSubsystemZone.TRANSMISSION -> if (isAr) "ناقل الحركة (القير)" else "Transmission"
        CarSubsystemZone.ELECTRICAL -> if (isAr) "البطارية والكهرباء" else "Battery & CAN"
        CarSubsystemZone.FUEL_EXHAUST -> if (isAr) "الوقود والعادم" else "Fuel & Exhaust"
        CarSubsystemZone.BRAKES_CHASSIS -> if (isAr) "الفرامل والشاسيه ABS" else "Brakes & ABS"
        CarSubsystemZone.BODY_CABIN -> if (isAr) "الهيكل والمقصورة" else "Body & ECU Check"
    }

    fun zoneShortLabel(zone: CarSubsystemZone, isAr: Boolean): String = when (zone) {
        CarSubsystemZone.ENGINE -> if (isAr) "المحرك" else "Engine"
        CarSubsystemZone.TRANSMISSION -> if (isAr) "القير" else "Gearbox"
        CarSubsystemZone.ELECTRICAL -> if (isAr) "الكهرباء" else "Battery"
        CarSubsystemZone.FUEL_EXHAUST -> if (isAr) "الوقود والبيئة" else "Fuel/Exh"
        CarSubsystemZone.BRAKES_CHASSIS -> if (isAr) "الفرامل ABS" else "Brakes"
        CarSubsystemZone.BODY_CABIN -> if (isAr) "المقصورة" else "Body"
    }

    /**
     * Infers the physical body silhouette (Sedan, SUV, Pickup, Hatchback, Coupe)
     * from decoded VIN/WMI, manufacturer name, and adapter/vehicle description.
     */
    fun detectBodyStyle(
        vehicleInfo: VehicleInfo?,
        adapterDescription: String? = null
    ): VehicleBodyStyle {
        val vin = vehicleInfo?.vin.orEmpty().uppercase()
        val combined = listOfNotNull(
            vehicleInfo?.make,
            adapterDescription
        ).joinToString(" ").lowercase()

        val pickupWmis = setOf("1FT", "1GC", "1GT", "1C6", "5TF", "3TM", "MR0")
        if (vin.take(3) in pickupWmis || listOf(
                "truck", "pickup", "f-150", "f150", "silverado", "sierra",
                "hilux", "tacoma", "tundra", "ranger", "ram ", "navara"
            ).any { it in combined }
        ) {
            return VehicleBodyStyle.PICKUP_TRUCK
        }

        val suvWmis3 = setOf("1FM", "1C4", "WDC", "W1N", "WBX", "WA1", "WP1", "SAL", "YV4", "KM8", "JTM", "JTE", "JN8", "JA4", "LGW", "5XY", "WV2")
        val suvWmis2 = setOf("1J")
        if (vin.take(3) in suvWmis3 || vin.take(2) in suvWmis2 || listOf(
                "suv", "explorer", "crossover", "jeep", "land rover", "prado", "land cruiser",
                "fortuner", "pajero", "tucson", "santa fe", "sportage", "sorento", "rav4",
                "cr-v", "crv", "patrol", "tahoe", "yukon", "suburban", "expedition",
                "cherokee", "wrangler", "tiguan", "touareg", "cayenne", "macan", "x3", "x5", "q5", "q7", "van"
            ).any { it in combined }
        ) {
            return VehicleBodyStyle.SUV
        }

        if (listOf(
                "hatch", "yaris", "golf", "polo", "fiesta", "i20", "i30",
                "rio", "picanto", "spark", "micra", "swift", "jazz", "fit"
            ).any { it in combined }
        ) {
            return VehicleBodyStyle.HATCHBACK
        }

        val coupeWmis = setOf("ZFF", "ZHW", "WP0", "SCC")
        if (vin.take(3) in coupeWmis || listOf(
                "coupe", "mustang", "camaro", "corvette", "challenger", "porsche", "86", "370z"
            ).any { it in combined }
        ) {
            return VehicleBodyStyle.COUPE
        }

        return VehicleBodyStyle.SEDAN
    }

    fun bodyStyleLabel(style: VehicleBodyStyle, isAr: Boolean): String = when (style) {
        VehicleBodyStyle.SEDAN -> if (isAr) "سيدان" else "Sedan"
        VehicleBodyStyle.SUV -> if (isAr) "دفع رباعي SUV" else "SUV"
        VehicleBodyStyle.PICKUP_TRUCK -> if (isAr) "بيك أب" else "Pickup Truck"
        VehicleBodyStyle.HATCHBACK -> if (isAr) "هاتشباك" else "Hatchback"
        VehicleBodyStyle.COUPE -> if (isAr) "كوبيه" else "Coupe"
    }
}
