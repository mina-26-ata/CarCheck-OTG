package com.carchecker.otg.data.obd

data class DecodedVin(
    val vin: String,
    val isValidFormat: Boolean,
    val make: String?,
    val country: String?,
    val modelYear: Int?
)

object VinDecoder {

    private val wmiMakes: Map<String, String> = mapOf(
        // Japan
        "JT" to "Toyota / Lexus",
        "JTD" to "Toyota",
        "JTE" to "Toyota",
        "JTH" to "Lexus",
        "JTJ" to "Lexus",
        "JTM" to "Toyota",
        "JTN" to "Toyota",
        "JN" to "Nissan / Infiniti",
        "JN1" to "Nissan",
        "JN8" to "Nissan",
        "JNK" to "Infiniti",
        "JH" to "Honda / Acura",
        "JHM" to "Honda",
        "JM" to "Mazda / Mitsubishi",
        "JM1" to "Mazda",
        "JMZ" to "Mazda",
        "JA" to "Mitsubishi / Isuzu",
        "JA3" to "Mitsubishi",
        "JA4" to "Mitsubishi",
        "JF" to "Subaru",
        "JF1" to "Subaru",
        "JF2" to "Subaru",
        "JS" to "Suzuki",
        // Korea
        "KM" to "Hyundai / Kia / Genesis",
        "KMH" to "Hyundai",
        "KM8" to "Hyundai",
        "KMT" to "Genesis",
        "KN" to "Kia",
        "KNA" to "Kia",
        "KND" to "Kia",
        "KL" to "GM Korea / Daewoo",
        "KP" to "SsangYong / KG Mobility",
        // USA / North America
        "1G" to "General Motors (USA)",
        "1G1" to "Chevrolet",
        "1G2" to "Pontiac",
        "1GC" to "Chevrolet Truck",
        "1GT" to "GMC",
        "1GY" to "Cadillac",
        "1F" to "Ford (USA)",
        "1FA" to "Ford",
        "1FT" to "Ford Truck",
        "1FM" to "Ford SUV",
        "1C" to "Chrysler / Jeep / Dodge / RAM",
        "1C3" to "Chrysler",
        "1C4" to "Jeep / Chrysler",
        "1C6" to "RAM",
        "1D" to "Dodge",
        "1J" to "Jeep",
        "1HG" to "Honda (USA)",
        "19U" to "Acura (USA)",
        "1N" to "Nissan (USA)",
        "1VW" to "Volkswagen (USA)",
        "1YV" to "Mazda (USA)",
        "2T" to "Toyota / Lexus (Canada)",
        "2HG" to "Honda (Canada)",
        "2G" to "General Motors (Canada)",
        "2F" to "Ford (Canada)",
        "3F" to "Ford (Mexico)",
        "3G" to "General Motors (Mexico)",
        "3N" to "Nissan (Mexico)",
        "3VW" to "Volkswagen (Mexico)",
        "4T" to "Toyota (USA)",
        "5T" to "Toyota (USA)",
        "5N" to "Hyundai / Nissan (USA)",
        "5NM" to "Hyundai (USA)",
        "5XY" to "Kia (USA)",
        "5YJ" to "Tesla",
        "7SA" to "Tesla",
        // Germany
        "WBA" to "BMW",
        "WBS" to "BMW M",
        "WBX" to "BMW X",
        "WBY" to "BMW i",
        "WDB" to "Mercedes-Benz",
        "WDD" to "Mercedes-Benz",
        "WDC" to "Mercedes-Benz SUV",
        "W1K" to "Mercedes-Benz",
        "W1N" to "Mercedes-Benz SUV",
        "WVW" to "Volkswagen",
        "WV1" to "Volkswagen Commercial",
        "WV2" to "Volkswagen Van",
        "WAU" to "Audi",
        "WA1" to "Audi SUV",
        "WP0" to "Porsche",
        "WP1" to "Porsche SUV",
        "W0L" to "Opel / Vauxhall",
        // UK / Europe / China
        "SAJ" to "Jaguar",
        "SAL" to "Land Rover",
        "SCC" to "Lotus",
        "VF1" to "Renault",
        "VF3" to "Peugeot",
        "VF7" to "Citroen",
        "VR3" to "Peugeot",
        "VR7" to "Citroen",
        "VSS" to "SEAT",
        "TMB" to "Skoda",
        "YV1" to "Volvo",
        "YV4" to "Volvo SUV",
        "ZFA" to "Fiat",
        "ZAR" to "Alfa Romeo",
        "ZFF" to "Ferrari",
        "ZHW" to "Lamborghini",
        "LVS" to "Ford (China)",
        "LHG" to "Honda (China)",
        "LFV" to "FAW-Volkswagen",
        "LSG" to "SAIC-GM",
        "LGX" to "BYD",
        "LVV" to "Chery",
        "LB3" to "Geely",
        "LGW" to "Great Wall / Haval",
        "L6T" to "Geely",
        "MNT" to "Nissan (Thailand)",
        "MR0" to "Toyota (Thailand)",
        "MMB" to "Mitsubishi (Thailand)",
        "MA3" to "Suzuki (India)",
        "MAL" to "Hyundai (India)"
    )

    private val yearCodes = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun decode(rawVin: String): DecodedVin {
        val cleaned = rawVin.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        val isValid = cleaned.length == 17 && !cleaned.contains('I') && !cleaned.contains('O') && !cleaned.contains('Q')

        if (cleaned.length < 3) {
            return DecodedVin(cleaned, false, null, null, null)
        }

        val wmi3 = cleaned.substring(0, 3)
        val wmi2 = cleaned.substring(0, 2)
        val make = wmiMakes[wmi3] ?: wmiMakes[wmi2] ?: guessRegionMake(cleaned[0])
        val country = decodeCountry(cleaned[0], cleaned[1])
        val modelYear = if (cleaned.length >= 10) decodeYear(cleaned[9], cleaned.getOrNull(6)) else null

        return DecodedVin(
            vin = cleaned,
            isValidFormat = isValid,
            make = make,
            country = country,
            modelYear = modelYear
        )
    }

    private fun decodeYear(yearChar: Char, pos7: Char?): Int? {
        val idx = yearCodes.indexOf(yearChar)
        if (idx < 0) return null
        // Standard 30-year cycle: 1980..2009 vs 2010..2039.
        // Per ISO 3779 / SAE J272, if position 7 is alphabetic, model year >= 2010.
        // Also for modern OBD-II CAN vehicles, if year in 2010+ range <= 2028, prefer 2010+ unless pos7 is digit AND ambiguous.
        val base1980 = 1980 + idx
        val base2010 = 2010 + idx
        return when {
            pos7 != null && pos7.isLetter() -> base2010
            base2010 <= 2028 -> base2010
            else -> base1980
        }
    }

    private fun decodeCountry(c1: Char, c2: Char): String? = when (c1) {
        '1', '4', '5', '7' -> "United States"
        '2' -> "Canada"
        '3' -> "Mexico"
        '6' -> "Australia"
        '9' -> "Brazil / South America"
        'J' -> "Japan"
        'K' -> if (c2 in 'L'..'R') "South Korea" else "Asia"
        'L' -> "China"
        'M' -> when (c2) {
            in 'A'..'E' -> "India"
            in 'F'..'K' -> "Indonesia"
            in 'L'..'R' -> "Thailand"
            else -> "Asia"
        }
        'S' -> if (c2 in 'A'..'M') "United Kingdom" else "Europe"
        'T' -> when (c2) {
            in 'J'..'P' -> "Czech Republic"
            else -> "Europe"
        }
        'V' -> when (c2) {
            in 'F'..'R' -> "France"
            in 'S'..'W' -> "Spain"
            else -> "Europe"
        }
        'W' -> "Germany"
        'Y' -> when (c2) {
            in 'S'..'W' -> "Sweden"
            else -> "Northern Europe"
        }
        'Z' -> if (c2 in 'A'..'R') "Italy" else "Europe"
        else -> null
    }

    private fun guessRegionMake(c1: Char): String? = when (c1) {
        'J' -> "Japanese Manufacturer"
        'K' -> "Korean Manufacturer"
        'W' -> "German Manufacturer"
        else -> null
    }
}
