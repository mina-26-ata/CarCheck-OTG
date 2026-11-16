package com.carchecker.otg.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carchecker.otg.BuildConfig
import com.carchecker.otg.CarCheckerApp
import com.carchecker.otg.data.obd.ObdTransport
import com.carchecker.otg.data.obd.SimulatedCarTransport
import com.carchecker.otg.data.update.AppUpdateState
import com.carchecker.otg.data.usb.UsbElm327Transport
import com.carchecker.otg.domain.analyzer.CarZoneMapper
import com.carchecker.otg.model.AiChatMessage
import com.carchecker.otg.model.AppLanguage
import com.carchecker.otg.model.AppSettings
import com.carchecker.otg.model.BluetoothAdapterInfo
import com.carchecker.otg.model.CarSubsystemZone
import com.carchecker.otg.model.CarZoneSummary
import com.carchecker.otg.model.ConnectionMode
import com.carchecker.otg.model.ConnectionState
import com.carchecker.otg.model.DemoProfile
import com.carchecker.otg.model.DtcKnowledgeEntry
import com.carchecker.otg.model.DtcSystem
import com.carchecker.otg.model.FullScanReport
import com.carchecker.otg.model.LiveSensorReading
import com.carchecker.otg.model.MeasurementUnit
import com.carchecker.otg.model.ScanProgress
import com.carchecker.otg.model.SmartAiReport
import com.carchecker.otg.model.UsbAdapterInfo
import com.carchecker.otg.model.ZoneStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DiagnosticUiState(
    val settings: AppSettings = AppSettings(),
    val usbAdapters: List<UsbAdapterInfo> = emptyList(),
    val bluetoothDevices: List<BluetoothAdapterInfo> = emptyList(),
    val isBluetoothScanning: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val connectionMode: ConnectionMode = ConnectionMode.DISCONNECTED,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val activeAdapterLabel: String = "",
    val connectionError: String? = null,
    val scanProgress: ScanProgress = ScanProgress(),
    val currentReport: FullScanReport? = null,
    val scanHistory: List<FullScanReport> = emptyList(),
    val liveSensors: List<LiveSensorReading> = emptyList(),
    val zoneMap: Map<CarSubsystemZone, CarZoneSummary> = CarZoneMapper.mapZones(null, emptyList()),
    val focusedZone: CarSubsystemZone? = null,
    val smartAiReport: SmartAiReport? = null,
    val aiChatHistory: List<AiChatMessage> = emptyList(),
    val isLiveStreaming: Boolean = false,
    val libraryQuery: String = "",
    val librarySystemFilter: DtcSystem? = null,
    val libraryResults: List<DtcKnowledgeEntry> = emptyList(),
    val isAiAnalyzing: Boolean = false,
    val aiError: String? = null,
    val updateState: AppUpdateState = AppUpdateState.Idle,
    val toastMessage: String? = null
)

class DiagnosticViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CarCheckerApp
    private val otgManager = app.usbOtgManager
    private val btManager = app.bluetoothObdManager
    private val dtcRepo = app.dtcRepository
    private val engine = app.obdEngine
    private val settingsStore = app.settingsStore
    private val historyRepo = app.historyRepository
    private val geminiService = app.geminiService
    private val localAiEngine = app.localAiEngine
    private val updateChecker = app.updateChecker

    private val _uiState = MutableStateFlow(DiagnosticUiState())
    val uiState: StateFlow<DiagnosticUiState> = _uiState.asStateFlow()

    private var activeTransport: ObdTransport? = null
    private var pendingUsbDeviceId: Int? = null
    private var liveJob: Job? = null
    private val liveSensorCache = linkedMapOf<String, LiveSensorReading>()

    init {
        val loadedSettings = settingsStore.load()
        _uiState.update {
            it.copy(
                settings = loadedSettings,
                libraryResults = dtcRepo.search("", null)
            )
        }

        otgManager.register()
        viewModelScope.launch {
            otgManager.adapters.collect { list ->
                _uiState.update { it.copy(usbAdapters = list) }
                // Auto-disconnect if active USB device was unplugged
                if (_uiState.value.connectionMode == ConnectionMode.USB_OTG && list.isEmpty()) {
                    disconnect()
                }
            }
        }

        btManager.refreshStateAndPairedDevices()
        viewModelScope.launch {
            btManager.devices.collect { list ->
                _uiState.update { it.copy(bluetoothDevices = list) }
            }
        }
        viewModelScope.launch {
            btManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(isBluetoothScanning = scanning) }
            }
        }
        viewModelScope.launch {
            btManager.isBluetoothEnabled.collect { enabled ->
                _uiState.update { it.copy(isBluetoothEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            btManager.hasPermissions.collect { granted ->
                _uiState.update { it.copy(hasBluetoothPermission = granted) }
            }
        }

        viewModelScope.launch {
            otgManager.lastPermissionResult.collect { pair ->
                if (pair != null && pendingUsbDeviceId == pair.first) {
                    val (devId, granted) = pair
                    pendingUsbDeviceId = null
                    if (granted) {
                        connectUsbInternal(devId)
                    } else {
                        _uiState.update {
                            it.copy(
                                connectionState = ConnectionState.ERROR,
                                connectionError = if (it.settings.language == AppLanguage.AR) {
                                    "تم رفض إذن الوصول إلى كابل USB OTG."
                                } else {
                                    "USB OTG permission was denied."
                                }
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            val history = historyRepo.loadHistory()
            _uiState.update { state ->
                val activeReport = state.currentReport ?: history.firstOrNull()
                applyReportDerivedState(
                    state = state.copy(
                        scanHistory = history,
                        currentReport = activeReport,
                        liveSensors = if (state.liveSensors.isEmpty() && activeReport != null) {
                            activeReport.sensorSnapshot
                        } else {
                            state.liveSensors
                        }
                    ),
                    autoPickZone = true
                )
            }
        }
    }

    fun refreshUsbDevices() {
        otgManager.refreshDevices()
    }

    fun connectUsb(deviceId: Int? = null) {
        viewModelScope.launch {
            val driver = otgManager.findDriverByDeviceId(deviceId)
            if (driver == null) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        connectionError = if (it.settings.language == AppLanguage.AR) {
                            "لم يتم العثور على وصلة فحص USB OTG. تأكد من تفعيل OTG في إعدادات الهاتف وتوصيل القطعة."
                        } else {
                            "No USB OTG OBD-II adapter detected. Ensure OTG is enabled and adapter is plugged in."
                        }
                    )
                }
                return@launch
            }

            if (!otgManager.hasPermission(driver.device)) {
                pendingUsbDeviceId = driver.device.deviceId
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.REQUESTING_PERMISSION,
                        connectionError = null
                    )
                }
                otgManager.requestPermission(driver.device)
            } else {
                connectUsbInternal(driver.device.deviceId)
            }
        }
    }

    private suspend fun connectUsbInternal(deviceId: Int) {
        stopLiveStreaming()
        activeTransport?.disconnect()

        val driver = otgManager.findDriverByDeviceId(deviceId) ?: return
        val transport = UsbElm327Transport(
            otgManager = otgManager,
            driver = driver,
            preferredBaudRate = _uiState.value.settings.preferredBaudRate
        )

        _uiState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                connectionError = null
            )
        }

        val connRes = transport.connect()
        if (connRes.isFailure) {
            _uiState.update {
                it.copy(
                    connectionMode = ConnectionMode.DISCONNECTED,
                    connectionState = ConnectionState.ERROR,
                    connectionError = connRes.exceptionOrNull()?.localizedMessage ?: "USB Connection Error"
                )
            }
            return
        }

        activeTransport = transport
        _uiState.update {
            it.copy(
                connectionMode = ConnectionMode.USB_OTG,
                connectionState = ConnectionState.CONNECTED,
                activeAdapterLabel = transport.description,
                connectionError = null
            )
        }
        // Auto-start full scan upon connecting
        runFullScan()
    }

    fun requiredBluetoothPermissions(): Array<String> = btManager.requiredPermissions()

    fun refreshBluetoothState() {
        btManager.refreshStateAndPairedDevices()
    }

    fun startBluetoothScan() {
        btManager.startScan()
    }

    fun stopBluetoothScan() {
        btManager.stopScan()
    }

    fun connectBluetooth(deviceInfo: BluetoothAdapterInfo) {
        viewModelScope.launch {
            stopLiveStreaming()
            btManager.stopScan()
            activeTransport?.disconnect()

            val transport = btManager.createTransport(deviceInfo)
            if (transport == null) {
                _uiState.update {
                    it.copy(
                        connectionMode = ConnectionMode.DISCONNECTED,
                        connectionState = ConnectionState.ERROR,
                        connectionError = if (it.settings.language == AppLanguage.AR) {
                            "تعذر فتح قناة الاتصال بالبلوتوث مع ${deviceInfo.name}"
                        } else {
                            "Unable to initialize Bluetooth transport for ${deviceInfo.name}"
                        }
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTING,
                    activeAdapterLabel = deviceInfo.name,
                    connectionError = null
                )
            }

            val connRes = transport.connect()
            if (connRes.isFailure) {
                _uiState.update {
                    it.copy(
                        connectionMode = ConnectionMode.DISCONNECTED,
                        connectionState = ConnectionState.ERROR,
                        connectionError = connRes.exceptionOrNull()?.localizedMessage
                            ?: if (it.settings.language == AppLanguage.AR) {
                                "فشل الاتصال عبر البلوتوث. تأكد من تشغيل السويتش وقربك من القطعة."
                            } else {
                                "Bluetooth connection failed. Check ignition is ON and dongle is powered."
                            }
                    )
                }
                return@launch
            }

            activeTransport = transport
            _uiState.update {
                it.copy(
                    connectionMode = ConnectionMode.BLUETOOTH,
                    connectionState = ConnectionState.CONNECTED,
                    activeAdapterLabel = transport.description,
                    connectionError = null
                )
            }
            runFullScan()
        }
    }

    fun startSimulation(profile: DemoProfile = _uiState.value.settings.demoProfile) {
        viewModelScope.launch {
            stopLiveStreaming()
            activeTransport?.disconnect()

            if (profile != _uiState.value.settings.demoProfile) {
                updateSettings(_uiState.value.settings.copy(demoProfile = profile))
            }

            val sim = SimulatedCarTransport(profile)
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTING,
                    connectionError = null
                )
            }
            sim.connect()
            activeTransport = sim
            _uiState.update {
                it.copy(
                    connectionMode = ConnectionMode.SIMULATION,
                    connectionState = ConnectionState.CONNECTED,
                    activeAdapterLabel = sim.description,
                    connectionError = null
                )
            }
            runFullScan()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopLiveStreaming()
            activeTransport?.disconnect()
            activeTransport = null
            liveSensorCache.clear()
            _uiState.update {
                it.copy(
                    connectionMode = ConnectionMode.DISCONNECTED,
                    connectionState = ConnectionState.DISCONNECTED,
                    activeAdapterLabel = "",
                    connectionError = null,
                    isLiveStreaming = false
                )
            }
        }
    }

    fun runFullScan() {
        val transport = activeTransport ?: return
        viewModelScope.launch {
            val wasStreaming = _uiState.value.isLiveStreaming
            stopLiveStreaming()

            val isSim = _uiState.value.connectionMode == ConnectionMode.SIMULATION
            val unit = _uiState.value.settings.unit

            val result = engine.runFullScan(
                transport = transport,
                unit = unit,
                isSimulated = isSim
            ) { progress ->
                _uiState.update { it.copy(scanProgress = progress) }
            }

            result.onSuccess { report ->
                liveSensorCache.clear()
                report.sensorSnapshot.forEach { liveSensorCache[it.pidHex] = it }
                val updatedHistory = historyRepo.saveReport(report)
                _uiState.update { state ->
                    applyReportDerivedState(
                        state = state.copy(
                            currentReport = report,
                            scanHistory = updatedHistory,
                            liveSensors = report.sensorSnapshot,
                            connectionError = null,
                            aiError = null,
                            aiChatHistory = emptyList()
                        ),
                        autoPickZone = true
                    )
                }
                if (wasStreaming) startLiveStreaming()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        connectionError = err.localizedMessage ?: "Scan failed"
                    )
                }
            }
        }
    }

    fun clearDtcs() {
        val transport = activeTransport ?: return
        viewModelScope.launch {
            val wasStreaming = _uiState.value.isLiveStreaming
            stopLiveStreaming()
            val res = engine.clearDtcs(transport)
            res.onSuccess {
                _uiState.update {
                    it.copy(
                        toastMessage = if (it.settings.language == AppLanguage.AR) {
                            "تم إرسال أمر إلغاء الأعطال وإطفاء لمبة الماكينة — جاري التحقق..."
                        } else {
                            "Faults & Check Engine cleared — re-scanning vehicle..."
                        }
                    )
                }
                delay(450)
                runFullScan()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(toastMessage = err.localizedMessage ?: "Failed to clear DTCs")
                }
                if (wasStreaming) startLiveStreaming()
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            if (_uiState.value.updateState is AppUpdateState.Checking) return@launch
            _uiState.update { it.copy(updateState = AppUpdateState.Checking) }
            val result = updateChecker.checkForUpdates(BuildConfig.VERSION_NAME)
            _uiState.update { state ->
                val isAr = state.settings.language == AppLanguage.AR
                val toast = when (result) {
                    is AppUpdateState.UpToDate -> if (isAr) {
                        "التطبيق محدّث بالفعل (v${result.currentVersion})"
                    } else {
                        "You are running the latest version (v${result.currentVersion})"
                    }
                    is AppUpdateState.UpdateAvailable -> if (isAr) {
                        "يتوفر إصدار جديد: v${result.latestVersion}"
                    } else {
                        "New version available: v${result.latestVersion}"
                    }
                    is AppUpdateState.Error -> if (isAr) {
                        "تعذر التحقق من التحديثات: ${result.message}"
                    } else {
                        "Update check failed: ${result.message}"
                    }
                    else -> null
                }
                state.copy(
                    updateState = result,
                    toastMessage = toast
                )
            }
        }
    }

    fun toggleLiveStreaming() {
        if (_uiState.value.isLiveStreaming) {
            stopLiveStreaming()
        } else {
            startLiveStreaming()
        }
    }

    fun startLiveStreaming() {
        val transport = activeTransport ?: return
        if (liveJob?.isActive == true) return

        _uiState.update { it.copy(isLiveStreaming = true) }
        liveJob = viewModelScope.launch {
            while (isActive && transport.isConnected) {
                val unit = _uiState.value.settings.unit
                val readings = engine.pollSensorsOnce(
                    transport = transport,
                    unit = unit,
                    existing = liveSensorCache,
                    priorityPidsOnly = false
                )
                for (r in readings) {
                    liveSensorCache[r.pidHex] = r
                }
                if (readings.isNotEmpty()) {
                    _uiState.update { state ->
                        applyReportDerivedState(
                            state = state.copy(liveSensors = liveSensorCache.values.toList()),
                            autoPickZone = false
                        )
                    }
                }
                delay(450)
            }
            _uiState.update { it.copy(isLiveStreaming = false) }
        }
    }

    fun stopLiveStreaming() {
        liveJob?.cancel()
        liveJob = null
        _uiState.update { it.copy(isLiveStreaming = false) }
    }

    fun searchLibrary(query: String, systemFilter: DtcSystem? = _uiState.value.librarySystemFilter) {
        _uiState.update {
            it.copy(
                libraryQuery = query,
                librarySystemFilter = systemFilter,
                libraryResults = dtcRepo.search(query, systemFilter)
            )
        }
    }

    fun runAiAnalysis() {
        val report = _uiState.value.currentReport ?: return
        val settings = _uiState.value.settings
        if (settings.geminiApiKey.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isAiAnalyzing = true, aiError = null) }
            val result = geminiService.analyzeScan(
                apiKey = settings.geminiApiKey,
                model = settings.geminiModel,
                report = report,
                language = settings.language
            )
            result.onSuccess { analysisText ->
                val updatedReport = report.copy(aiAnalysisText = analysisText)
                val updatedHistory = historyRepo.saveReport(updatedReport)
                _uiState.update {
                    it.copy(
                        isAiAnalyzing = false,
                        currentReport = updatedReport,
                        scanHistory = updatedHistory
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isAiAnalyzing = false,
                        aiError = err.localizedMessage ?: "AI analysis error"
                    )
                }
            }
        }
    }

    fun selectCarZone(zone: CarSubsystemZone?) {
        _uiState.update { it.copy(focusedZone = zone) }
    }

    fun askLocalAi(
        question: String,
        focusedZone: CarSubsystemZone? = _uiState.value.focusedZone,
        focusedDtc: DtcKnowledgeEntry? = null
    ) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return

        val snap = _uiState.value
        val answer = localAiEngine.answerQuestion(
            question = trimmed,
            report = snap.currentReport,
            liveSensors = snap.liveSensors,
            unit = snap.settings.unit,
            language = snap.settings.language,
            focusedZone = focusedZone,
            focusedDtc = focusedDtc
        )
        val now = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                aiChatHistory = (state.aiChatHistory + listOf(
                    AiChatMessage(id = "u_$now", isUser = true, text = trimmed, timestampMs = now),
                    AiChatMessage(id = "a_${now + 1}", isUser = false, text = answer, timestampMs = now + 1)
                )).takeLast(20)
            )
        }
    }

    fun clearAiChat() {
        _uiState.update { it.copy(aiChatHistory = emptyList()) }
    }

    fun buildFaultAiInsight(knowledge: DtcKnowledgeEntry): String {
        val snap = _uiState.value
        return localAiEngine.buildFaultAiInsight(
            knowledge = knowledge,
            report = snap.currentReport,
            liveSensors = snap.liveSensors,
            unit = snap.settings.unit,
            language = snap.settings.language
        )
    }

    fun selectHistoryReport(report: FullScanReport) {
        stopLiveStreaming()
        _uiState.update { state ->
            val carName = listOfNotNull(report.vehicleInfo.make, report.vehicleInfo.modelYear?.toString())
                .joinToString(" ")
                .ifBlank { report.adapterDescription }
            applyReportDerivedState(
                state = state.copy(
                    currentReport = report,
                    liveSensors = report.sensorSnapshot,
                    aiError = null,
                    aiChatHistory = emptyList(),
                    toastMessage = if (state.settings.language == AppLanguage.AR) {
                        "تم فتح سجل الفحص: $carName"
                    } else {
                        "Opened scan: $carName"
                    }
                ),
                autoPickZone = true
            )
        }
    }

    private fun applyReportDerivedState(
        state: DiagnosticUiState,
        autoPickZone: Boolean
    ): DiagnosticUiState {
        val report = state.currentReport
        val zones = CarZoneMapper.mapZones(report, state.liveSensors)
        val smartAi = report?.let {
            localAiEngine.analyze(it, state.liveSensors, state.settings.unit)
        }
        val nextFocused = if (autoPickZone) {
            zones.values.firstOrNull { it.status == ZoneStatus.CRITICAL }?.zone
                ?: zones.values.firstOrNull { it.status == ZoneStatus.WARNING }?.zone
                ?: if (report != null) CarSubsystemZone.ENGINE else null
        } else {
            state.focusedZone
        }
        return state.copy(
            zoneMap = zones,
            smartAiReport = smartAi,
            focusedZone = nextFocused
        )
    }

    fun deleteHistoryReport(id: String) {
        viewModelScope.launch {
            val updated = historyRepo.deleteReport(id)
            _uiState.update { state ->
                val wasCurrentDeleted = state.currentReport?.id == id
                val nextReport = if (wasCurrentDeleted) updated.firstOrNull() else state.currentReport
                val nextSensors = if (wasCurrentDeleted && state.connectionMode == ConnectionMode.DISCONNECTED) {
                    nextReport?.sensorSnapshot.orEmpty()
                } else {
                    state.liveSensors
                }
                applyReportDerivedState(
                    state = state.copy(
                        scanHistory = updated,
                        currentReport = nextReport,
                        liveSensors = nextSensors,
                        toastMessage = if (state.settings.language == AppLanguage.AR) {
                            "تم حذف سجل الفحص"
                        } else {
                            "Scan record deleted"
                        }
                    ),
                    autoPickZone = wasCurrentDeleted
                )
            }
        }
    }

    fun setLanguage(language: AppLanguage) {
        updateSettings(_uiState.value.settings.copy(language = language))
    }

    fun setUnit(unit: MeasurementUnit) {
        updateSettings(_uiState.value.settings.copy(unit = unit))
    }

    fun setPreferredBaudRate(baud: Int) {
        updateSettings(_uiState.value.settings.copy(preferredBaudRate = baud))
    }

    fun setDemoProfile(profile: DemoProfile) {
        updateSettings(_uiState.value.settings.copy(demoProfile = profile))
        if (_uiState.value.connectionMode == ConnectionMode.SIMULATION) {
            startSimulation(profile)
        }
    }

    fun saveGeminiConfig(apiKey: String, model: String) {
        updateSettings(
            _uiState.value.settings.copy(
                geminiApiKey = apiKey.trim(),
                geminiModel = model.trim().ifEmpty { "gemini-2.5-flash" }
            )
        )
        _uiState.update {
            it.copy(
                toastMessage = if (it.settings.language == AppLanguage.AR) {
                    "تم حفظ إعدادات الذكاء الاصطناعي"
                } else {
                    "Gemini AI settings saved"
                }
            )
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun updateSettings(newSettings: AppSettings) {
        settingsStore.save(newSettings)
        _uiState.update { it.copy(settings = newSettings) }
    }

    override fun onCleared() {
        super.onCleared()
        liveJob?.cancel()
        otgManager.unregister()
        btManager.stopScan()
    }
}
