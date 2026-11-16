package com.carchecker.otg.data.ai

import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.FullScanReport
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeminiDiagnosticService {

    private val gson = Gson()

    suspend fun analyzeScan(
        apiKey: String,
        model: String,
        report: FullScanReport,
        language: AppLanguage
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty"))
        }

        val prompt = buildPrompt(report, language)
        val safeModel = model.trim().ifEmpty { "gemini-2.5-flash" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent"

        var conn: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 35000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }

            val payload = mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to prompt)
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.3,
                    "maxOutputTokens" to 1600
                )
            )

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(gson.toJson(payload))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            }.orEmpty()

            if (code !in 200..299) {
                val errMsg = extractErrorMessage(body) ?: "HTTP $code"
                return@withContext Result.failure(IllegalStateException("Gemini API error: $errMsg"))
            }

            val text = extractCandidateText(body)
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("Empty response from Gemini API"))
            } else {
                Result.success(text.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractCandidateText(json: String): String? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val candidates = root.getAsJsonArray("candidates") ?: return null
            if (candidates.size() == 0) return null
            val content = candidates[0].asJsonObject.getAsJsonObject("content") ?: return null
            val parts = content.getAsJsonArray("parts") ?: return null
            parts.mapNotNull { it.asJsonObject.get("text")?.asString }.joinToString("\n")
        } catch (_: Exception) {
            null
        }
    }

    private fun extractErrorMessage(json: String): String? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            root.getAsJsonObject("error")?.get("message")?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPrompt(report: FullScanReport, language: AppLanguage): String {
        val langInstruction = if (language == AppLanguage.AR) {
            "أجب باللغة العربية الفصحى الواضحة مع إبقاء أكواد الأعطال والمصطلحات الفنية الشائعة بالإنجليزية بين قوسين."
        } else {
            "Respond clearly in English."
        }

        val dtcLines = if (report.dtcs.isEmpty()) "None" else report.dtcs.joinToString("\n") {
            "- ${it.code} [${it.status}] (${it.ecuAddress}): ${it.knowledge.titleEn}"
        }
        val freezeLines = if (report.freezeFrame.isEmpty()) "None" else report.freezeFrame.joinToString(", ") {
            "${it.labelEn}: ${it.valueDisplay} ${it.unit}"
        }
        val sensorLines = report.sensorSnapshot.joinToString("\n") {
            "- ${it.nameEn} (${it.pidHex}): ${it.value} ${it.unit}"
        }
        val obsLines = report.observations.joinToString("\n") {
            "- [${it.severity}] ${it.titleEn}: ${it.summaryEn}"
        }

        return """
            You are a master automotive diagnostic technician analyzing an OBD-II scan.
            $langInstruction

            Vehicle Info:
            - VIN: ${report.vehicleInfo.vin ?: "Unknown"}
            - Make/Year: ${report.vehicleInfo.make ?: "Unknown"} ${report.vehicleInfo.modelYear ?: ""}
            - Battery Voltage: ${report.vehicleInfo.batteryVoltage ?: "Unknown"} V
            - MIL (Check Engine Light): ${report.readiness?.milOn ?: "Unknown"}
            - Health Score: ${report.healthScore}/100

            Diagnostic Trouble Codes (DTCs):
            $dtcLines

            Freeze Frame Data:
            $freezeLines

            Live Sensor Snapshot:
            $sensorLines

            Automated Pre-Analysis Notes:
            $obsLines

            Provide a concise, practical, mechanic-grade diagnosis with these exact sections:
            1. Root Cause Correlation (how these faults/readings connect together)
            2. Step-by-Step Repair Order (cheapest/easiest checks first, avoid unnecessary part swapping)
            3. Driving Safety Warning (safe to drive, drive gently to shop, or stop immediately?)
            4. Parts & Tests to Verify
        """.trimIndent()
    }
}
