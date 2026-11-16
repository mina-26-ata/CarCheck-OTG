package com.carchecker.otg.data.storage

import android.content.Context
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.AppSettings
import com.carchecker.otg.model.DemoProfile
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

        val demoProfile = runCatching {
            DemoProfile.valueOf(prefs.getString("demo_profile", DemoProfile.ENGINE_MISFIRE_FUEL.name) ?: DemoProfile.ENGINE_MISFIRE_FUEL.name)
        }.getOrDefault(DemoProfile.ENGINE_MISFIRE_FUEL)

        return AppSettings(
            language = lang,
            unit = unit,
            preferredBaudRate = prefs.getInt("baud_rate", 0),
            demoProfile = demoProfile,
            geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
            geminiModel = prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("language", settings.language.name)
            .putString("unit", settings.unit.name)
            .putInt("baud_rate", settings.preferredBaudRate)
            .putString("demo_profile", settings.demoProfile.name)
            .putString("gemini_api_key", settings.geminiApiKey)
            .putString("gemini_model", settings.geminiModel)
            .apply()
    }
}
