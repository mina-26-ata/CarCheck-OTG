import Foundation
import Combine

@MainActor
final class DiagnosticStore: ObservableObject {
    @Published var settings: AppSettings
    @Published var connectionMode: ConnectionMode = .disconnected
    @Published var connectionState: ConnectionState = .disconnected
    @Published var activeAdapterLabel: String = ""
    @Published var connectionError: String? = nil

    @Published var bleDevices: [BleDeviceInfo] = []
    @Published var isBleScanning: Bool = false
    @Published var isBluetoothPoweredOn: Bool = false

    @Published var scanProgress: ScanProgress = ScanProgress()
    @Published var currentReport: FullScanReport? = nil
    @Published var scanHistory: [FullScanReport] = []
    @Published var liveSensors: [LiveSensorReading] = []
    @Published var zoneMap: [CarSubsystemZone: CarZoneSummary] = CarZoneMapper.mapZones(report: nil, liveSensors: [])
    @Published var focusedZone: CarSubsystemZone? = nil
    @Published var smartAiReport: SmartAiReport? = nil
    @Published var aiChatHistory: [AiChatMessage] = []

    @Published var isLiveStreaming: Bool = false
    @Published var isResettingMil: Bool = false
    @Published var showNoConnectionDialog: Bool = false
    @Published var toastMessage: String? = nil

    @Published var libraryQuery: String = ""
    @Published var libraryFilter: DtcSystem? = nil
    @Published var libraryResults: [DtcKnowledgeEntry] = []

    @Published var availableUpdate: IosReleaseUpdate? = nil
    @Published var showUpdateDialog: Bool = false
    @Published var exportedPdfUrl: URL? = nil

    let dtcRepo = DtcRepository()
    let bleManager = BleObdManager()
    private let persistence = LocalPersistence()
    private lazy var obdEngine = ObdCommandEngine(dtcRepo: dtcRepo)

    private var activeTransport: ObdTransport? = nil
    private var liveStreamTask: Task<Void, Never>? = nil
    private var liveSensorCache: [String: LiveSensorReading] = [:]
    private var cockpitFastMode: Bool = true
    private var userPausedLive: Bool = false
    private var cancellables = Set<AnyCancellable>()

    var isAr: Bool { settings.language == .ar }
    var isConnected: Bool { connectionState == .connected && connectionMode != .disconnected }

    init() {
        let loaded = persistence.loadSettings()
        self.settings = loaded
        // Load history list only — never display a previous scan on launch when disconnected
        self.scanHistory = persistence.loadHistory()
        self.libraryResults = dtcRepo.search(query: "", systemFilter: nil)

        bleManager.$devices
            .receive(on: RunLoop.main)
            .assign(to: &$bleDevices)
        bleManager.$isScanning
            .receive(on: RunLoop.main)
            .assign(to: &$isBleScanning)
        bleManager.$isBluetoothPoweredOn
            .receive(on: RunLoop.main)
            .assign(to: &$isBluetoothPoweredOn)

        Task {
            await checkForUpdates(silent: true)
        }
    }

    func setActiveTab(_ tab: Int) {
        cockpitFastMode = (tab != 3)
    }

    func setLanguage(_ lang: AppLanguage) {
        settings.language = lang
        persistence.saveSettings(settings)
    }

    func setUnit(_ unit: MeasurementUnit) {
        if settings.unit != unit {
            liveSensorCache.removeAll()
        }
        settings.unit = unit
        persistence.saveSettings(settings)
    }

    func startBleScan() {
        bleManager.startScan()
    }

    func stopBleScan() {
        bleManager.stopScan()
    }

    func connectBle(_ device: BleDeviceInfo) {
        Task {
            stopLiveStreaming()
            userPausedLive = false
            await activeTransport?.disconnect()

            guard let transport = bleManager.createTransport(for: device) else {
                connectionState = .error
                connectionError = isAr ? "تعذر تهيئة اتصال البلوتوث مع \(device.name)" : "Unable to initialize BLE transport for \(device.name)"
                return
            }

            connectionState = .connecting
            activeAdapterLabel = device.name
            connectionError = nil

            do {
                try await transport.connect()
                activeTransport = transport
                connectionMode = .ble
                connectionState = .connected
                activeAdapterLabel = transport.adapterDescription
                runFullScan()
            } catch {
                connectionMode = .disconnected
                connectionState = .error
                connectionError = error.localizedDescription
            }
        }
    }

    func connectWifi(host: String, port: Int) {
        Task {
            stopLiveStreaming()
            userPausedLive = false
            await activeTransport?.disconnect()

            settings.wifiHost = host
            settings.wifiPort = port
            persistence.saveSettings(settings)

            let transport = WifiElm327Transport(host: host, port: port)
            connectionState = .connecting
            activeAdapterLabel = "Wi-Fi \(host):\(port)"
            connectionError = nil

            do {
                try await transport.connect()
                activeTransport = transport
                connectionMode = .wifi
                connectionState = .connected
                activeAdapterLabel = transport.adapterDescription
                runFullScan()
            } catch {
                connectionMode = .disconnected
                connectionState = .error
                connectionError = isAr
                    ? "تعذر الاتصال بقطعة الـ Wi-Fi (\(host):\(port)). تأكد من الاتصال بشبكة القطعة وفتح السويتش."
                    : "Failed to connect to Wi-Fi OBD (\(host):\(port)). Ensure iPhone is joined to the adapter Wi-Fi and ignition is ON."
            }
        }
    }

    func disconnect() {
        Task {
            stopLiveStreaming()
            userPausedLive = false
            await activeTransport?.disconnect()
            activeTransport = nil
            liveSensorCache.removeAll()
            connectionMode = .disconnected
            connectionState = .disconnected
            activeAdapterLabel = ""
            connectionError = nil
            currentReport = nil
            liveSensors = []
            smartAiReport = nil
            focusedZone = nil
            zoneMap = CarZoneMapper.mapZones(report: nil, liveSensors: [])
            aiChatHistory = []
        }
    }

    func runFullScan() {
        guard let transport = activeTransport, transport.isConnected, connectionState == .connected else {
            showNoConnectionDialog = true
            toastMessage = isAr
                ? "لا يوجد اتصال بالسيارة — وصّل البلوتوث BLE أو الـ Wi-Fi أولاً"
                : "Not connected to vehicle — connect via BLE or Wi-Fi first"
            return
        }

        Task {
            stopLiveStreaming()
            connectionError = nil
            do {
                let report = try await obdEngine.runFullScan(transport: transport, unit: settings.unit) { [weak self] prog in
                    await MainActor.run {
                        self?.scanProgress = prog
                    }
                }
                liveSensorCache.removeAll()
                for s in report.sensorSnapshot {
                    liveSensorCache[s.pidHex] = s
                }
                scanHistory = persistence.saveReport(report)
                applyReport(report, sensors: report.sensorSnapshot, autoPickZone: true)

                if !userPausedLive && transport.isConnected {
                    startLiveStreaming()
                }
            } catch {
                scanProgress = ScanProgress(isScanning: false)
                connectionError = error.localizedDescription
            }
        }
    }

    func forceClearCheckEngine() {
        guard let transport = activeTransport, transport.isConnected, !isResettingMil, !scanProgress.isScanning else {
            showNoConnectionDialog = true
            return
        }
        Task {
            let wasLive = isLiveStreaming
            stopLiveStreaming()
            isResettingMil = true
            let res = await obdEngine.forceTurnOffCheckEngine(transport)
            isResettingMil = false

            if res.milIsNowOff == true {
                toastMessage = isAr ? "تم إطفاء لمبة تحذير المحرك بنجاح — جاري إعادة الفحص..." : "Check Engine Light turned OFF — re-scanning..."
            } else if res.engineRunningBlocked {
                toastMessage = isAr ? "يرفض الكمبيوتر الإطفاء أثناء دوران المحرك — أطفئ المحرك واترك السويتش مفتوحاً (ON) ثم أعد المحاولة." : "Turn engine OFF with ignition ON and retry."
            } else if res.clearedConfirmed {
                toastMessage = isAr ? "تم إرسال أوامر إطفاء اللمبة ومسح الأعطال — جاري التحقق..." : "Reset commands accepted — verifying..."
            } else {
                toastMessage = isAr ? "لم يؤكد الكمبيوتر إطفاء اللمبة. تأكد من فتح السويتش (ON) مع إطفاء المحرك." : "ECU did not confirm reset. Ensure ignition ON and engine OFF."
            }

            if res.clearedConfirmed || res.milIsNowOff == true {
                try? await Task.sleep(nanoseconds: 450_000_000)
                runFullScan()
            } else if wasLive {
                startLiveStreaming()
            }
        }
    }

    func toggleLiveStreaming() {
        if isLiveStreaming {
            userPausedLive = true
            stopLiveStreaming()
        } else {
            userPausedLive = false
            startLiveStreaming()
        }
    }

    func startLiveStreaming() {
        guard let transport = activeTransport, transport.isConnected else { return }
        guard liveStreamTask == nil else { return }

        isLiveStreaming = true
        liveStreamTask = Task { [weak self] in
            guard let self else { return }
            var tick = 0
            var emptyCount = 0
            while !Task.isCancelled && transport.isConnected {
                let fast = self.cockpitFastMode
                let unit = self.settings.unit
                let readings: [LiveSensorReading]
                if fast {
                    readings = await self.obdEngine.pollCockpitGaugesOnce(
                        transport: transport,
                        unit: unit,
                        existing: self.liveSensorCache,
                        includeSlowPids: (tick % 10 == 0)
                    )
                } else {
                    readings = await self.obdEngine.pollSensorsOnce(
                        transport: transport,
                        unit: unit,
                        existing: self.liveSensorCache
                    )
                }

                if !readings.isEmpty {
                    emptyCount = 0
                    for r in readings { self.liveSensorCache[r.pidHex] = r }
                    self.liveSensors = Array(self.liveSensorCache.values)
                } else {
                    emptyCount += 1
                }

                tick += 1
                let delayMs: UInt64 = emptyCount >= 3 ? 400 : (fast ? 45 : 280)
                try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
            }
            self.isLiveStreaming = false
            self.liveStreamTask = nil
        }
    }

    func stopLiveStreaming() {
        liveStreamTask?.cancel()
        liveStreamTask = nil
        isLiveStreaming = false
    }

    func openHistoryReport(_ report: FullScanReport) {
        stopLiveStreaming()
        applyReport(report, sensors: report.sensorSnapshot, autoPickZone: true)
    }

    func deleteHistoryReport(_ id: String) {
        scanHistory = persistence.deleteReport(id: id)
        if currentReport?.id == id {
            currentReport = nil
            liveSensors = []
            smartAiReport = nil
            zoneMap = CarZoneMapper.mapZones(report: nil, liveSensors: [])
        }
    }

    func searchLibrary(query: String, filter: DtcSystem?) {
        libraryQuery = query
        libraryFilter = filter
        libraryResults = dtcRepo.search(query: query, systemFilter: filter)
    }

    func askAi(_ question: String) {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        aiChatHistory.append(AiChatMessage(isUser: true, text: q))
        let ans = LocalAiDiagnosticEngine.answerQuestion(q, report: currentReport, dtcRepo: dtcRepo, isAr: isAr)
        aiChatHistory.append(AiChatMessage(isUser: false, text: ans))
    }

    func exportPdf(for report: FullScanReport) {
        let ai = (report.id == currentReport?.id) ? smartAiReport : LocalAiDiagnosticEngine.analyze(report: report)
        do {
            exportedPdfUrl = try PdfReportGeneratorIOS.generatePdfFile(report: report, smartAi: ai, isAr: isAr)
        } catch {
            toastMessage = isAr ? "تعذر إنشاء ملف PDF" : "Failed to generate PDF"
        }
    }

    func checkForUpdates(silent: Bool) async {
        if let upd = await IosUpdateChecker.checkLatest() {
            availableUpdate = upd
            showUpdateDialog = true
        } else if !silent {
            toastMessage = isAr ? "التطبيق محدّث بالفعل (v\(IosUpdateChecker.currentVersion))" : "App is up to date (v\(IosUpdateChecker.currentVersion))"
        }
    }

    private func applyReport(_ report: FullScanReport, sensors: [LiveSensorReading], autoPickZone: Bool) {
        currentReport = report
        liveSensors = sensors
        let zMap = CarZoneMapper.mapZones(report: report, liveSensors: sensors)
        zoneMap = zMap
        smartAiReport = LocalAiDiagnosticEngine.analyze(report: report)
        if autoPickZone {
            focusedZone = zMap.values.first(where: { $0.status == .critical })?.zone
                ?? zMap.values.first(where: { $0.status == .warning })?.zone
                ?? .engine
        }
    }
}
