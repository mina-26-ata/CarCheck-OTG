package com.carchecker.otg.data.dtc

import android.content.Context
import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.DtcSeverity
import com.carchecker.otg.model.DtcSystem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class DtcRepository(
    private val assetLoader: (() -> String)? = null
) {
    constructor(context: Context) : this({
        context.assets.open("dtc_database.json").use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).readText()
        }
    })

    private data class RawJsonEntry(
        val code: String,
        val system: String,
        val isGeneric: Boolean,
        val severity: String,
        val canDriveSafely: Boolean,
        val titleEn: String,
        val titleAr: String,
        val descriptionEn: String,
        val descriptionAr: String,
        val causesEn: List<String>,
        val causesAr: List<String>,
        val symptomsEn: List<String>,
        val symptomsAr: List<String>,
        val fixStepsEn: List<String>,
        val fixStepsAr: List<String>,
        val estimatedDifficultyEn: String,
        val estimatedDifficultyAr: String
    )

    private val entriesMap: Map<String, DtcKnowledgeEntry> by lazy {
        loadDatabase()
    }

    val allEntries: List<DtcKnowledgeEntry> by lazy {
        entriesMap.values.sortedBy { it.code }
    }

    private fun loadDatabase(): Map<String, DtcKnowledgeEntry> {
        val json = try {
            assetLoader?.invoke().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (json.isBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<List<RawJsonEntry>>() {}.type
            val list: List<RawJsonEntry> = Gson().fromJson(json, type)
            list.associate { raw ->
                val code = raw.code.trim().uppercase()
                code to DtcKnowledgeEntry(
                    code = code,
                    system = runCatching { DtcSystem.valueOf(raw.system) }.getOrDefault(DtcSystem.POWERTRAIN),
                    isGeneric = raw.isGeneric,
                    severity = runCatching { DtcSeverity.valueOf(raw.severity) }.getOrDefault(DtcSeverity.MODERATE),
                    canDriveSafely = raw.canDriveSafely,
                    titleEn = raw.titleEn,
                    titleAr = raw.titleAr,
                    descriptionEn = raw.descriptionEn,
                    descriptionAr = raw.descriptionAr,
                    causesEn = raw.causesEn,
                    causesAr = raw.causesAr,
                    symptomsEn = raw.symptomsEn,
                    symptomsAr = raw.symptomsAr,
                    fixStepsEn = raw.fixStepsEn,
                    fixStepsAr = raw.fixStepsAr,
                    estimatedDifficultyEn = raw.estimatedDifficultyEn,
                    estimatedDifficultyAr = raw.estimatedDifficultyAr,
                    isFromBuiltInDb = true
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun lookup(code: String): DtcKnowledgeEntry {
        val normalized = code.trim().uppercase()
        return entriesMap[normalized] ?: SaeJ2012FallbackDecoder.decode(normalized)
    }

    fun search(query: String, systemFilter: DtcSystem? = null): List<DtcKnowledgeEntry> {
        val q = query.trim().lowercase()
        val base = if (systemFilter == null) allEntries else allEntries.filter { it.system == systemFilter }
        if (q.isEmpty()) return base
        val exact = base.filter {
            it.code.lowercase().contains(q) ||
                it.titleEn.lowercase().contains(q) ||
                it.titleAr.lowercase().contains(q) ||
                it.descriptionEn.lowercase().contains(q) ||
                it.descriptionAr.lowercase().contains(q)
        }
        if (exact.isNotEmpty()) return exact

        // If user typed a valid OBD-II code format not in curated DB, show its SAE J2012 decoded card
        val upperQ = q.uppercase()
        if (Regex("""^[PCBU][0-3][0-9A-F]{3}$""").matches(upperQ)) {
            return listOf(SaeJ2012FallbackDecoder.decode(upperQ))
        }
        return emptyList()
    }
}
