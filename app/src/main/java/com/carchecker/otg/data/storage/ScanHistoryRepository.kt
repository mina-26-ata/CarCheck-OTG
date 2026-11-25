package com.carchecker.otg.data.storage

import android.content.Context
import com.carchecker.otg.model.FullScanReport
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ScanHistoryRepository(context: Context) {

    private val historyFile = File(context.filesDir, "scan_history.json")
    private val gson = Gson()

    suspend fun loadHistory(): List<FullScanReport> = withContext(Dispatchers.IO) {
        if (!historyFile.exists()) return@withContext emptyList()
        try {
            val json = historyFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<FullScanReport>>() {}.type
            val items: List<FullScanReport>? = gson.fromJson(json, type)
            (items ?: emptyList())
                .filterNot { it.isSimulated }
                .sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveReport(report: FullScanReport): List<FullScanReport> = withContext(Dispatchers.IO) {
        val current = loadHistory().filterNot { it.id == report.id }.toMutableList()
        current.add(0, report)
        val trimmed = current.take(25)
        try {
            historyFile.writeText(gson.toJson(trimmed), Charsets.UTF_8)
        } catch (_: Exception) {
        }
        trimmed
    }

    suspend fun deleteReport(id: String): List<FullScanReport> = withContext(Dispatchers.IO) {
        val updated = loadHistory().filterNot { it.id == id }
        try {
            historyFile.writeText(gson.toJson(updated), Charsets.UTF_8)
        } catch (_: Exception) {
        }
        updated
    }

    suspend fun clearAll(): List<FullScanReport> = withContext(Dispatchers.IO) {
        try {
            if (historyFile.exists()) historyFile.delete()
        } catch (_: Exception) {
        }
        emptyList()
    }
}
