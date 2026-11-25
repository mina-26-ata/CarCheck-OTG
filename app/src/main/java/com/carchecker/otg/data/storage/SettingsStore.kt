package com.carchecker.otg.data.storage

import android.content.Context
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.AppSettings
import com.carchecker.otg.model.MeasurementUnit

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("car_checker_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val lang = runCatching {
            AppLanguage.valueOf(prefs.getString("language", AppLanguage.AR.name) ?: AppLanguage.AR.name)
        }.getOrDefault(AppLanguage.AR)

        val unit = runCatching {
            MeasurementUnit.valueOf(prefs.getString("unit", MeasurementUnit.METRIC.name) ?: MeasurementUnit.METRIC.name)
        }.getOrDefault(MeasurementUnit.METRIC)

        return AppSettings(
            language = lang,
            unit = unit,
            preferredBaudRate = prefs.getInt("baud_rate", 0),
            geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
            geminiModel = prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("language", settings.language.name)
            .putString("unit", settings.unit.name)
            .putInt("baud_rate", settings.preferredBaudRate)
            .putString("gemini_api_key", settings.geminiApiKey)
            .putString("gemini_model", settings.geminiModel)
            .apply()
    }
}
