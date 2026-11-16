package com.carchecker.otg

import android.app.Application
import com.carchecker.otg.data.ai.GeminiDiagnosticService
import com.carchecker.otg.data.ai.LocalAiDiagnosticEngine
import com.carchecker.otg.data.bluetooth.BluetoothObdManager
import com.carchecker.otg.data.dtc.DtcRepository
import com.carchecker.otg.data.obd.ObdCommandEngine
import com.carchecker.otg.data.storage.ScanHistoryRepository
import com.carchecker.otg.data.storage.SettingsStore
import com.carchecker.otg.data.update.AppUpdateChecker
import com.carchecker.otg.data.usb.UsbOtgManager
import com.carchecker.otg.domain.analyzer.VehicleHealthAnalyzer

class CarCheckerApp : Application() {

    lateinit var usbOtgManager: UsbOtgManager
        private set
    lateinit var bluetoothObdManager: BluetoothObdManager
        private set
    lateinit var dtcRepository: DtcRepository
        private set
    lateinit var healthAnalyzer: VehicleHealthAnalyzer
        private set
    lateinit var obdEngine: ObdCommandEngine
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var historyRepository: ScanHistoryRepository
        private set
    lateinit var geminiService: GeminiDiagnosticService
        private set
    lateinit var localAiEngine: LocalAiDiagnosticEngine
        private set
    lateinit var updateChecker: AppUpdateChecker
        private set

    override fun onCreate() {
        super.onCreate()
        usbOtgManager = UsbOtgManager(this)
        bluetoothObdManager = BluetoothObdManager(this)
        dtcRepository = DtcRepository(this)
        healthAnalyzer = VehicleHealthAnalyzer()
        obdEngine = ObdCommandEngine(dtcRepository, healthAnalyzer)
        settingsStore = SettingsStore(this)
        historyRepository = ScanHistoryRepository(this)
        geminiService = GeminiDiagnosticService()
        localAiEngine = LocalAiDiagnosticEngine()
        updateChecker = AppUpdateChecker()
    }
}
