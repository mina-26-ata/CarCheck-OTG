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
    val isResettingMil: Boolean = false,
    val libraryQuery: String = "",
    val librarySystemFilter: DtcSystem? = null,
    val libraryResults: List<DtcKnowledgeEntry> = emptyList(),
    val isAiAnalyzing: Boolean = false,
    val aiError: String? = null,
    val updateState: AppUpdateState = AppUpdateState.Idle,
    val showUpdateDialog: Boolean = false,
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
    @Volatile
    private var cockpitFastStreamMode: Boolean = true
    private var userPausedLiveStream: Boolean = false
    private var lastAutoUpdateCheckMs: Long = 0L
    private var dismissedUpdateVersion: String? = null

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

        // Automatically check for new app releases silently on startup
        checkForUpdatesAutoIfNeeded()
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
        userPausedLiveStream = false
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
            userPausedLiveStream = false
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
            userPausedLiveStream = false
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
            userPausedLiveStream = false
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
                if (!userPausedLiveStream && transport.isConnected) {
                    startLiveStreaming()
                }
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
        if (_uiState.value.isResettingMil || _uiState.value.scanProgress.isScanning) return
        viewModelScope.launch {
            val wasStreaming = _uiState.value.isLiveStreaming
            stopLiveStreaming()
            _uiState.update { it.copy(isResettingMil = true) }
            try {
                val res = engine.forceTurnOffCheckEngine(transport)
                res.onSuccess { outcome ->
                    val isAr = _uiState.value.settings.language == AppLanguage.AR
                    val message = when {
                        outcome.milIsNowOff == true -> if (isAr) {
                            "تم إطفاء لمبة تحذير المحرك بنجاح — جاري إعادة الفحص..."
                        } else {
                            "Check Engine Light turned OFF — re-scanning vehicle..."
                        }
                        outcome.engineRunningBlocked -> if (isAr) {
                            "يرفض الكمبيوتر الإطفاء أثناء عمل المحرك — أطفئ المحرك واترك السويتش مفتوحاً (ON) ثم أعد المحاولة."
                        } else {
                            "ECU refused reset while engine is running — turn engine OFF with ignition ON and retry."
                        }
                        outcome.clearedConfirmed && outcome.milIsNowOff == false -> if (isAr) {
                            "تم إرسال أوامر الإطفاء، لكن اللمبة ما زالت مضاءة لوجود عطل نشط مستمر."
                        } else {
                            "Reset commands accepted, but MIL remains ON due to an active fault condition."
                        }
                        outcome.clearedConfirmed -> if (isAr) {
                            "تم إرسال أمر إطفاء اللمبة ومسح الأعطال — جاري التحقق..."
                        } else {
                            "Reset command sent — verifying via scan..."
                        }
                        else -> if (isAr) {
                            "لم يؤكد الكمبيوتر إطفاء اللمبة. تأكد من فتح السويتش (ON) مع إطفاء المحرك وأعد المحاولة."
                        } else {
                            "ECU did not confirm reset. Ensure ignition is ON with engine OFF and try again."
                        }
                    }
                    _uiState.update { it.copy(isResettingMil = false, toastMessage = message) }
                    if (outcome.clearedConfirmed || outcome.milIsNowOff == true) {
                        delay(450)
                        runFullScan()
                    } else if (wasStreaming) {
                        startLiveStreaming()
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isResettingMil = false,
                            toastMessage = err.localizedMessage ?: "Failed to reset Check Engine Light"
                        )
                    }
                    if (wasStreaming) startLiveStreaming()
                }
            } finally {
                _uiState.update { it.copy(isResettingMil = false) }
            }
        }
    }

    fun checkForUpdatesAutoIfNeeded() {
        val now = System.currentTimeMillis()
        val alreadyCheckedRecently = (now - lastAutoUpdateCheckMs) < 30L * 60L * 1000L
        if (alreadyCheckedRecently && _uiState.value.updateState !is AppUpdateState.Idle) return
        checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (_uiState.value.updateState is AppUpdateState.Checking) return@launch
            lastAutoUpdateCheckMs = System.currentTimeMillis()

            if (!silent) {
                dismissedUpdateVersion = null
                _uiState.update { it.copy(updateState = AppUpdateState.Checking) }
            }

            val result = updateChecker.checkForUpdates(BuildConfig.VERSION_NAME)
            _uiState.update { state ->
                val isAr = state.settings.language == AppLanguage.AR

                if (silent) {
                    when (result) {
                        is AppUpdateState.UpdateAvailable -> state.copy(
                            updateState = result,
                            showUpdateDialog = (dismissedUpdateVersion != result.latestVersion)
                        )
                        is AppUpdateState.UpToDate -> state.copy(
                            updateState = result,
                            showUpdateDialog = false
                        )
                        else -> state // Stay completely quiet when offline or transient error during auto-check
                    }
                } else {
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
                        showUpdateDialog = result is AppUpdateState.UpdateAvailable,
                        toastMessage = toast
                    )
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        val latest = (_uiState.value.updateState as? AppUpdateState.UpdateAvailable)?.latestVersion
        if (latest != null) {
            dismissedUpdateVersion = latest
        }
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun openUpdateDialog() {
        if (_uiState.value.updateState is AppUpdateState.UpdateAvailable) {
            _uiState.update { it.copy(showUpdateDialog = true) }
        }
    }

    fun setActiveTab(tabIndex: Int) {
        cockpitFastStreamMode = (tabIndex != 3)
    }

    fun toggleLiveStreaming() {
        if (_uiState.value.isLiveStreaming) {
            userPausedLiveStream = true
            stopLiveStreaming()
        } else {
            userPausedLiveStream = false
            startLiveStreaming()
        }
    }

    fun startLiveStreaming() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        if (liveJob?.isActive == true) return

        userPausedLiveStream = false
        _uiState.update { it.copy(isLiveStreaming = true) }
        liveJob = viewModelScope.launch {
            var tick = 0
            var consecutiveEmpty = 0
            while (isActive && transport.isConnected) {
                val unit = _uiState.value.settings.unit
                val fastMode = cockpitFastStreamMode
                val readings = if (fastMode) {
                    engine.pollCockpitGaugesOnce(
                        transport = transport,
                        unit = unit,
                        existing = liveSensorCache,
                        includeSlowPids = (tick % 10 == 0)
                    )
                } else {
                    engine.pollSensorsOnce(
                        transport = transport,
                        unit = unit,
                        existing = liveSensorCache,
                        priorityPidsOnly = false
                    )
                }

                if (readings.isNotEmpty()) {
                    consecutiveEmpty = 0
                    for (r in readings) {
                        liveSensorCache[r.pidHex] = r
                    }
                    val snapshot = liveSensorCache.values.toList()
                    if (fastMode) {
                        // Fast lightweight update so the 3 top HUD needles react smoothly to throttle input
                        _uiState.update { it.copy(liveSensors = snapshot) }
                    } else {
                        _uiState.update { state ->
                            applyReportDerivedState(
                                state = state.copy(liveSensors = snapshot),
                                autoPickZone = false
                            )
                        }
                    }
                } else {
                    consecutiveEmpty++
                }

                tick++
                val loopDelayMs = when {
                    consecutiveEmpty >= 3 -> 400L
                    fastMode -> 40L
                    else -> 280L
                }
                delay(loopDelayMs)
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
        if (unit != _uiState.value.settings.unit) {
            liveSensorCache.clear()
        }
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
