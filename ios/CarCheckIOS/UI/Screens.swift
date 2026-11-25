import SwiftUI

struct DashboardScreenView: View {
    @ObservedObject var store: DiagnosticStore
    @Binding var showBleSheet: Bool
    @Binding var showWifiSheet: Bool
    @Binding var showHistorySheet: Bool
    @State private var showClearMilConfirm = false

    var body: some View {
        let isAr = store.isAr
        let report = store.currentReport
        let activeSensors = store.liveSensors.isEmpty ? (report?.sensorSnapshot ?? []) : store.liveSensors
        let sensorMap = Dictionary(uniqueKeysWithValues: activeSensors.map { ($0.pidHex, $0) })
        let rpm = sensorMap["0C"]?.value
        let coolant = sensorMap["05"]?.value
        let voltage = sensorMap["42"]?.value ?? report?.vehicleInfo.batteryVoltage
        let tempUnit = store.settings.unit == .imperial ? "°F" : "°C"

        ScrollView {
            VStack(spacing: 12) {
                // 1. Top Cockpit Card: Live Bar + 3 Circular Gauges + Readout Ratings + PDF & History
                CockpitCard(borderColor: CockpitPalette.cyan.opacity(0.55)) {
                    if store.isConnected {
                        HStack {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(store.isLiveStreaming ? CockpitPalette.green : CockpitPalette.amber)
                                    .frame(width: 8, height: 8)
                                Text(store.isLiveStreaming ? (isAr ? "بث مباشر للمؤشرات" : "LIVE GAUGES") : (isAr ? "البث المباشر متوقف" : "Live Paused"))
                                    .font(.system(size: 11.5, weight: .bold))
                                    .foregroundStyle(store.isLiveStreaming ? CockpitPalette.green : CockpitPalette.amber)
                            }
                            Spacer()
                            Button {
                                store.toggleLiveStreaming()
                            } label: {
                                Label(
                                    store.isLiveStreaming ? (isAr ? "إيقاف مؤقت" : "Pause") : (isAr ? "تشغيل البث" : "Go Live"),
                                    systemImage: store.isLiveStreaming ? "pause.fill" : "play.fill"
                                )
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(CockpitPalette.textPrimary)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(CockpitPalette.surfaceElevated)
                                .clipShape(Capsule())
                            }
                        }
                    }

                    TripleTelemetryGaugesView(
                        sensors: activeSensors,
                        batteryVoltage: report?.vehicleInfo.batteryVoltage,
                        unit: store.settings.unit,
                        isAr: isAr
                    )

                    Divider().overlay(CockpitPalette.border)

                    VStack(spacing: 6) {
                        TelemetryRowView(
                            label: isAr ? "دورات المحرك" : "Engine RPM",
                            value: rpm.map { "\(Int($0))" } ?? "--",
                            rating: TelemetryEvaluator.rateRpm(rpm),
                            isAr: isAr,
                            faultAlert: nil
                        )
                        TelemetryRowView(
                            label: isAr ? "حرارة التبريد" : "Coolant Temp",
                            value: coolant.map { "\(Int($0))\(tempUnit)" } ?? "--",
                            rating: TelemetryEvaluator.rateCoolant(coolant, unit: store.settings.unit),
                            isAr: isAr,
                            faultAlert: nil
                        )
                        TelemetryRowView(
                            label: isAr ? "جهد البطارية" : "Battery",
                            value: voltage.map { String(format: "%.1fV", $0) } ?? "--",
                            rating: TelemetryEvaluator.rateBatteryVoltage(voltage),
                            isAr: isAr,
                            faultAlert: nil
                        )
                        TelemetryRowView(
                            label: isAr ? "الأعطال" : "Faults",
                            value: report.map { "\($0.dtcs.count)" } ?? "--",
                            rating: nil,
                            isAr: isAr,
                            faultAlert: report.map { !$0.dtcs.isEmpty }
                        )
                    }

                    if let report {
                        Button {
                            store.exportPdf(for: report)
                        } label: {
                            Label(isAr ? "تحميل التقرير PDF" : "Download PDF Report", systemImage: "doc.richtext.fill")
                                .font(.system(size: 14, weight: .heavy))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(CockpitPalette.green)
                                .foregroundStyle(CockpitPalette.backgroundDeep)
                                .clipShape(RoundedRectangle(cornerRadius: 11))
                        }
                    }

                    Button {
                        showHistorySheet = true
                    } label: {
                        HStack {
                            Image(systemName: "clock.arrow.circlepath")
                            Text(isAr ? "سجلات الفحص السابقة (\(store.scanHistory.count))" : "Previous Scan Records (\(store.scanHistory.count))")
                                .font(.system(size: 13, weight: .bold))
                            Spacer()
                            Image(systemName: isAr ? "chevron.left" : "chevron.right")
                        }
                        .foregroundStyle(CockpitPalette.cyan)
                        .padding(10)
                        .background(CockpitPalette.surfaceElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 11))
                    }
                }

                // 2. Interactive X-Ray Vehicle Map
                CarSchematicView(
                    zoneMap: store.zoneMap,
                    selectedZone: store.focusedZone,
                    bodyStyle: VinDecoder.detectBodyStyle(vin: report?.vehicleInfo.vin, make: report?.vehicleInfo.make),
                    vehicleName: [report?.vehicleInfo.make, report?.vehicleInfo.modelYear.map(String.init)].compactMap { $0 }.joined(separator: " "),
                    isScanning: store.scanProgress.isScanning,
                    isAr: isAr,
                    onSelectZone: { store.focusedZone = $0 }
                )

                // 3. Connection & Scan Actions Card
                CockpitCard(borderColor: store.isConnected ? CockpitPalette.green.opacity(0.6) : CockpitPalette.red.opacity(0.55)) {
                    if store.scanProgress.isScanning {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(store.scanProgress.stepIndex)/\(store.scanProgress.totalSteps): \(isAr ? store.scanProgress.stepLabelAr : store.scanProgress.stepLabelEn)")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(CockpitPalette.cyan)
                            ProgressView(value: Double(store.scanProgress.stepIndex), total: Double(max(1, store.scanProgress.totalSteps)))
                                .tint(CockpitPalette.cyan)
                        }
                    }

                    // Scan button is ALWAYS visible — alerts user immediately if disconnected!
                    Button {
                        store.runFullScan()
                    } label: {
                        Label(
                            store.scanProgress.isScanning ? (isAr ? "جاري الفحص الشامل..." : "Scanning...") : (isAr ? "بدء الفحص الشامل للسيارة" : "Start Full Vehicle Scan"),
                            systemImage: "gauge.with.dots.needle.67percent"
                        )
                        .font(.system(size: 14.5, weight: .heavy))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(CockpitPalette.cyan)
                        .foregroundStyle(CockpitPalette.backgroundDeep)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .disabled(store.scanProgress.isScanning || store.isResettingMil)

                    if !store.isConnected {
                        Button {
                            store.startBleScan()
                            showBleSheet = true
                        } label: {
                            Label(isAr ? "اتصال بلوتوث (ELM327 BLE 4.0)" : "Connect Bluetooth (BLE 4.0)", systemImage: "dot.radiowaves.left.and.right")
                                .font(.system(size: 13.5, weight: .bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 11)
                                .foregroundStyle(CockpitPalette.green)
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(CockpitPalette.green.opacity(0.7), lineWidth: 1.2))
                        }

                        Button {
                            showWifiSheet = true
                        } label: {
                            Label(isAr ? "اتصال عبر Wi-Fi OBD-II" : "Connect Wi-Fi OBD-II", systemImage: "wifi")
                                .font(.system(size: 13.5, weight: .bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 11)
                                .foregroundStyle(CockpitPalette.cyan)
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(CockpitPalette.cyan.opacity(0.6), lineWidth: 1.2))
                        }
                    } else {
                        Button {
                            showClearMilConfirm = true
                        } label: {
                            Label(
                                store.isResettingMil ? (isAr ? "جاري إطفاء لمبة المحرك..." : "Resetting MIL...") : (isAr ? "إجبار إطفاء لمبة المحرك ومسح الأعطال" : "Force Check Engine Off & Clear Faults"),
                                systemImage: "exclamationmark.triangle.fill"
                            )
                            .font(.system(size: 13.5, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .foregroundStyle(CockpitPalette.red)
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(CockpitPalette.red.opacity(0.75), lineWidth: 1.2))
                        }
                        .disabled(store.isResettingMil || store.scanProgress.isScanning)

                        Button {
                            store.disconnect()
                        } label: {
                            Text(isAr ? "قطع الاتصال بالسيارة" : "Disconnect")
                                .font(.system(size: 13, weight: .semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 9)
                                .foregroundStyle(CockpitPalette.textSecondary)
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(CockpitPalette.border, lineWidth: 1))
                        }
                    }

                    if let err = store.connectionError, !err.isEmpty {
                        Text(err)
                            .font(.system(size: 12.5, weight: .semibold))
                            .foregroundStyle(CockpitPalette.red)
                            .padding(9)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(CockpitPalette.red.opacity(0.14))
                            .clipShape(RoundedRectangle(cornerRadius: 9))
                    }
                }

                // 4. Smart AI Verdict Summary
                if let report, let ai = store.smartAiReport {
                    CockpitCard(borderColor: CockpitPalette.cyan.opacity(0.5)) {
                        HStack {
                            Label(isAr ? "المساعد الذكي لتحليل الأعطال" : "Smart AI Diagnostic Verdict", systemImage: "sparkles")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(CockpitPalette.cyan)
                            Spacer()
                            StatusPillView(text: "\(report.healthScore)%", color: report.healthScore >= 80 ? CockpitPalette.green : CockpitPalette.amber)
                        }
                        Text(ai.verdict.label(isAr: isAr))
                            .font(.system(size: 13.5, weight: .bold))
                            .foregroundStyle(CockpitPalette.amber)
                        Text(isAr ? ai.summaryAr : ai.summaryEn)
                            .font(.system(size: 13))
                            .foregroundStyle(CockpitPalette.textPrimary)
                    }
                }
            }
            .padding(14)
        }
        .alert(isAr ? "إجبار إطفاء لمبة تحذير المحرك" : "Force Check Engine Light Off", isPresented: $showClearMilConfirm) {
            Button(isAr ? "نعم، إطفاء اللمبة الآن" : "Yes, Turn Off", role: .destructive) {
                store.forceClearCheckEngine()
            }
            Button(isAr ? "إلغاء" : "Cancel", role: .cancel) {}
        } message: {
            Text(isAr
                ? "سيتم إرسال أوامر مسح الأعطال وإطفاء لمبة المحرك عبر جميع وحدات التحكم المتاحة ثم إعادة الفحص.\nلأفضل نتيجة: افتح السويتش (ON) مع إطفاء المحرك."
                : "Sends multi-protocol DTC clear & MIL reset across control modules. Best with ignition ON and engine OFF.")
        }
    }
}

private struct TelemetryRowView: View {
    let label: String
    let value: String
    let rating: TelemetryRating?
    let isAr: Bool
    let faultAlert: Bool?

    var body: some View {
        HStack {
            Text("\(label):")
                .font(.system(size: 12.5))
                .foregroundStyle(CockpitPalette.textSecondary)
            Spacer()
            Text(value)
                .font(.system(size: 13.5, weight: .heavy))
                .foregroundStyle(faultAlert == true ? CockpitPalette.red : CockpitPalette.textPrimary)

            if let faultAlert {
                Image(systemName: faultAlert ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(faultAlert ? CockpitPalette.red : CockpitPalette.green)
            } else if let rating {
                let col = ratingColor(rating.level)
                Text(isAr ? rating.labelAr : rating.labelEn)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(col)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(col.opacity(0.16))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
    }

    private func ratingColor(_ level: TelemetryRatingLevel) -> Color {
        switch level {
        case .excellent: return CockpitPalette.green
        case .good: return CockpitPalette.cyan
        case .fair: return CockpitPalette.amber
        case .poor: return CockpitPalette.red
        case .idle: return CockpitPalette.textSecondary
        }
    }
}

struct FaultsScreenView: View {
    @ObservedObject var store: DiagnosticStore
    @State private var showLibrary = false

    var body: some View {
        let isAr = store.isAr
        let dtcs = store.currentReport?.dtcs ?? []

        ScrollView {
            VStack(spacing: 12) {
                Picker("", selection: $showLibrary) {
                    Text(isAr ? "أعطال السيارة (\(dtcs.count))" : "Vehicle Faults (\(dtcs.count))").tag(false)
                    Text(isAr ? "موسوعة الأكواد" : "DTC Library").tag(true)
                }
                .pickerStyle(.segmented)

                if !showLibrary {
                    if dtcs.isEmpty {
                        CockpitCard {
                            Text(isAr ? "لا توجد أكواد أعطال مسجلة حالياً." : "No trouble codes currently recorded.")
                                .foregroundStyle(CockpitPalette.textSecondary)
                        }
                    } else {
                        ForEach(dtcs) { dtc in
                            DtcCardView(code: dtc.code, statusText: dtc.status.label(isAr: isAr), ecu: isAr ? dtc.ecuNameAr : dtc.ecuNameEn, entry: dtc.knowledge, isAr: isAr)
                        }
                    }
                } else {
                    TextField(isAr ? "ابحث بكود العطل (مثل P0171)..." : "Search DTC (e.g. P0171)...", text: Binding(
                        get: { store.libraryQuery },
                        set: { store.searchLibrary(query: $0, filter: store.libraryFilter) }
                    ))
                    .textFieldStyle(.roundedBorder)

                    ForEach(store.libraryResults) { entry in
                        DtcCardView(code: entry.code, statusText: entry.system.label(isAr: isAr), ecu: nil, entry: entry, isAr: isAr)
                    }
                }
            }
            .padding(14)
        }
    }
}

private struct DtcCardView: View {
    let code: String
    let statusText: String
    let ecu: String?
    let entry: DtcKnowledgeEntry
    let isAr: Bool

    var body: some View {
        CockpitCard(borderColor: CockpitPalette.red.opacity(0.5)) {
            HStack {
                Text(code)
                    .font(.system(size: 18, weight: .heavy, design: .monospaced))
                    .foregroundStyle(CockpitPalette.red)
                Spacer()
                StatusPillView(text: statusText, color: CockpitPalette.amber)
                StatusPillView(text: entry.severity.label(isAr: isAr), color: CockpitPalette.red)
            }
            Text(isAr ? entry.titleAr : entry.titleEn)
                .font(.system(size: 14.5, weight: .bold))
                .foregroundStyle(CockpitPalette.textPrimary)
            if let ecu {
                Text(ecu)
                    .font(.system(size: 12))
                    .foregroundStyle(CockpitPalette.cyan)
            }
            Text(isAr ? entry.descriptionAr : entry.descriptionEn)
                .font(.system(size: 13))
                .foregroundStyle(CockpitPalette.textSecondary)

            let fixes = isAr ? entry.fixStepsAr : entry.fixStepsEn
            if !fixes.isEmpty {
                Divider().overlay(CockpitPalette.border)
                Text(isAr ? "خطوات الفحص والإصلاح:" : "Diagnostic & Repair Steps:")
                    .font(.system(size: 12.5, weight: .bold))
                    .foregroundStyle(CockpitPalette.green)
                ForEach(Array(fixes.enumerated()), id: \.offset) { idx, step in
                    Text("\(idx + 1). \(step)")
                        .font(.system(size: 12.5))
                        .foregroundStyle(CockpitPalette.textPrimary)
                }
            }
        }
    }
}

struct ObservationsScreenView: View {
    @ObservedObject var store: DiagnosticStore
    @State private var questionDraft = ""

    var body: some View {
        let isAr = store.isAr
        let obs = store.currentReport?.observations ?? []

        ScrollView {
            VStack(spacing: 12) {
                CockpitCard(borderColor: CockpitPalette.cyan.opacity(0.55)) {
                    Text(isAr ? "اسأل المساعد الذكي عن أي عطل أو عرض" : "Ask Smart AI Diagnostic Assistant")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(CockpitPalette.cyan)

                    HStack {
                        TextField(isAr ? "مثال: P0171 أو هل القيادة آمنة؟" : "e.g., P0171 or Is it safe to drive?", text: $questionDraft)
                            .textFieldStyle(.roundedBorder)
                        Button(isAr ? "إرسال" : "Ask") {
                            store.askAi(questionDraft)
                            questionDraft = ""
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(CockpitPalette.cyan)
                    }

                    ForEach(store.aiChatHistory) { msg in
                        Text(msg.text)
                            .font(.system(size: 13))
                            .foregroundStyle(msg.isUser ? CockpitPalette.cyan : CockpitPalette.textPrimary)
                            .padding(9)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(CockpitPalette.backgroundDeep)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                }

                if obs.isEmpty {
                    CockpitCard {
                        Text(isAr ? "اتصل بالسيارة وشغّل الفحص الشامل لعرض الملاحظات الفنية." : "Connect and run Full Scan to view diagnostic observations.")
                            .foregroundStyle(CockpitPalette.textSecondary)
                    }
                } else {
                    ForEach(obs) { item in
                        let col: Color = switch item.severity {
                        case .critical: CockpitPalette.red
                        case .warning: CockpitPalette.amber
                        case .info: CockpitPalette.cyan
                        case .good: CockpitPalette.green
                        }
                        CockpitCard(borderColor: col.opacity(0.55)) {
                            HStack {
                                Text(isAr ? item.titleAr : item.titleEn)
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(CockpitPalette.textPrimary)
                                Spacer()
                                StatusPillView(text: item.severity.label(isAr: isAr), color: col)
                            }
                            Text(isAr ? item.summaryAr : item.summaryEn)
                                .font(.system(size: 13))
                                .foregroundStyle(CockpitPalette.textSecondary)
                            let steps = isAr ? item.resolutionStepsAr : item.resolutionStepsEn
                            ForEach(Array(steps.enumerated()), id: \.offset) { _, s in
                                Text("• \(s)")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(CockpitPalette.green)
                            }
                        }
                    }
                }
            }
            .padding(14)
        }
    }
}

struct LiveSensorsScreenView: View {
    @ObservedObject var store: DiagnosticStore

    var body: some View {
        let isAr = store.isAr
        ScrollView {
            VStack(spacing: 12) {
                if !store.isConnected {
                    CockpitCard {
                        Text(isAr ? "اتصل بالسيارة عبر البلوتوث BLE أو الـ Wi-Fi لعرض قراءات الحساسات المباشرة." : "Connect via BLE 4.0 or Wi-Fi OBD-II to stream live sensors.")
                            .foregroundStyle(CockpitPalette.textSecondary)
                    }
                } else {
                    Button {
                        store.toggleLiveStreaming()
                    } label: {
                        Label(
                            store.isLiveStreaming ? (isAr ? "إيقاف البث الحي" : "Stop Live Stream") : (isAr ? "تشغيل البث الحي" : "Start Live Stream"),
                            systemImage: store.isLiveStreaming ? "stop.fill" : "play.fill"
                        )
                        .font(.system(size: 14, weight: .bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(store.isLiveStreaming ? CockpitPalette.amber : CockpitPalette.green)
                        .foregroundStyle(CockpitPalette.backgroundDeep)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }

                    ForEach(store.liveSensors) { s in
                        CockpitCard(borderColor: s.isOutOfRange ? CockpitPalette.amber : CockpitPalette.border) {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(isAr ? s.nameAr : s.nameEn)
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(CockpitPalette.textPrimary)
                                    Text("PID \(s.pidHex) • \(isAr ? "أدنى" : "Min"): \(String(format: "%.\(s.decimals)f", s.minRecorded)) • \(isAr ? "أقصى" : "Max"): \(String(format: "%.\(s.decimals)f", s.maxRecorded))")
                                        .font(.system(size: 11.5))
                                        .foregroundStyle(CockpitPalette.textSecondary)
                                }
                                Spacer()
                                Text("\(String(format: "%.\(s.decimals)f", s.value)) \(s.unit)")
                                    .font(.system(size: 16, weight: .heavy, design: .rounded))
                                    .foregroundStyle(CockpitPalette.cyan)
                            }
                        }
                    }
                }
            }
            .padding(14)
        }
    }
}

struct VehicleSettingsScreenView: View {
    @ObservedObject var store: DiagnosticStore

    var body: some View {
        let isAr = store.isAr
        let v = store.currentReport?.vehicleInfo

        ScrollView {
            VStack(spacing: 12) {
                CockpitCard {
                    Text(isAr ? "معلومات السيارة" : "Vehicle Identity")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(CockpitPalette.cyan)
                    if let v {
                        Text("VIN: \(v.vin ?? "-")")
                            .foregroundStyle(CockpitPalette.textPrimary)
                        Text("\(isAr ? "الشركة / الموديل" : "Make / Year"): \(v.make ?? "-") \(v.modelYear.map(String.init) ?? "")")
                            .foregroundStyle(CockpitPalette.textPrimary)
                        Text("\(isAr ? "البروتوكول" : "Protocol"): \(v.protocolName ?? "-")")
                            .foregroundStyle(CockpitPalette.textSecondary)
                    } else {
                        Text(isAr ? "لا توجد بيانات سيارة معروضة حالياً — اتصل بالسيارة وابدأ الفحص." : "No active vehicle scan — connect to your car and run Full Scan.")
                            .foregroundStyle(CockpitPalette.textSecondary)
                    }
                }

                CockpitCard {
                    Text(isAr ? "الإعدادات" : "Settings")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(CockpitPalette.cyan)

                    Picker(isAr ? "اللغة" : "Language", selection: Binding(
                        get: { store.settings.language },
                        set: { store.setLanguage($0) }
                    )) {
                        Text("العربية").tag(AppLanguage.ar)
                        Text("English").tag(AppLanguage.en)
                    }
                    .pickerStyle(.segmented)

                    Picker(isAr ? "الوحدات" : "Units", selection: Binding(
                        get: { store.settings.unit },
                        set: { store.setUnit($0) }
                    )) {
                        Text(isAr ? "متري (°C / km/h)" : "Metric (°C / km/h)").tag(MeasurementUnit.metric)
                        Text(isAr ? "إمبراطوري (°F / mph)" : "Imperial (°F / mph)").tag(MeasurementUnit.imperial)
                    }
                    .pickerStyle(.segmented)

                    Button {
                        Task { await store.checkForUpdates(silent: false) }
                    } label: {
                        Label(isAr ? "التحقق من التحديثات (v\(IosUpdateChecker.currentVersion))" : "Check for Updates (v\(IosUpdateChecker.currentVersion))", systemImage: "arrow.down.circle.fill")
                            .font(.system(size: 13.5, weight: .bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .foregroundStyle(CockpitPalette.cyan)
                            .overlay(RoundedRectangle(cornerRadius: 11).stroke(CockpitPalette.cyan.opacity(0.6), lineWidth: 1))
                    }
                }
            }
            .padding(14)
        }
    }
}
