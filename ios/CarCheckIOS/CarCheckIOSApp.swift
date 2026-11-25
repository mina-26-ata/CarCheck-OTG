import SwiftUI

@main
struct CarCheckIOSApp: App {
    @StateObject private var store = DiagnosticStore()

    var body: some Scene {
        WindowGroup {
            RootContainerView(store: store)
                .preferredColorScheme(.dark)
                .environment(\.layoutDirection, store.isAr ? .rightToLeft : .leftToRight)
        }
    }
}

struct RootContainerView: View {
    @ObservedObject var store: DiagnosticStore
    @State private var selectedTab: Int = 0
    @State private var showBleSheet = false
    @State private var showWifiSheet = false
    @State private var showHistorySheet = false
    @State private var wifiHostDraft = "192.168.0.10"
    @State private var wifiPortDraft = "35000"

    var body: some View {
        let isAr = store.isAr

        VStack(spacing: 0) {
            // Top App Header
            HStack {
                Circle()
                    .fill(store.isConnected ? CockpitPalette.green : (store.connectionState == .connecting ? CockpitPalette.amber : CockpitPalette.red))
                    .frame(width: 10, height: 10)
                Text(isAr ? "فاحص السيارة" : "CarCheck")
                    .font(.system(size: 18, weight: .heavy))
                    .foregroundStyle(CockpitPalette.textPrimary)
                Spacer()
                Button {
                    store.setLanguage(isAr ? .en : .ar)
                } label: {
                    Text(isAr ? "EN" : "عربي")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(CockpitPalette.cyan)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(CockpitPalette.surfaceElevated)
                        .clipShape(Capsule())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(CockpitPalette.backgroundDeep)

            // Permanent Connection Status Bar across ALL screens
            PermanentConnectionBanner(
                state: store.connectionState,
                mode: store.connectionMode,
                adapterLabel: store.activeAdapterLabel,
                isAr: isAr,
                onTapDisconnected: { selectedTab = 0 }
            )

            // Main 5-Tab Content
            TabView(selection: $selectedTab) {
                DashboardScreenView(
                    store: store,
                    showBleSheet: $showBleSheet,
                    showWifiSheet: $showWifiSheet,
                    showHistorySheet: $showHistorySheet
                )
                .background(CockpitPalette.background)
                .tabItem {
                    Label(isAr ? "الفحص" : "Scan", systemImage: "gauge.with.dots.needle.67percent")
                }
                .tag(0)

                FaultsScreenView(store: store)
                    .background(CockpitPalette.background)
                    .tabItem {
                        Label(isAr ? "الأعطال" : "Faults", systemImage: "wrench.and.screwdriver.fill")
                    }
                    .tag(1)

                ObservationsScreenView(store: store)
                    .background(CockpitPalette.background)
                    .tabItem {
                        Label(isAr ? "الملاحظات" : "Notes", systemImage: "exclamationmark.bubble.fill")
                    }
                    .tag(2)

                LiveSensorsScreenView(store: store)
                    .background(CockpitPalette.background)
                    .tabItem {
                        Label(isAr ? "الحساسات" : "Sensors", systemImage: "waveform.path.ecg")
                    }
                    .tag(3)

                VehicleSettingsScreenView(store: store)
                    .background(CockpitPalette.background)
                    .tabItem {
                        Label(isAr ? "السيارة" : "Vehicle", systemImage: "car.fill")
                    }
                    .tag(4)
            }
            .tint(CockpitPalette.cyan)
        }
        .onChange(of: selectedTab) { newTab in
            store.setActiveTab(newTab)
        }
        // Alert when pressing Scan without an active car connection
        .alert(isAr ? "لا يوجد اتصال بالسيارة" : "Not Connected to Vehicle", isPresented: $store.showNoConnectionDialog) {
            Button(isAr ? "اتصال بلوتوث BLE 4.0" : "Connect BLE 4.0") {
                store.startBleScan()
                showBleSheet = true
            }
            Button(isAr ? "اتصال Wi-Fi OBD" : "Connect Wi-Fi OBD") {
                showWifiSheet = true
            }
            Button(isAr ? "حسناً" : "OK", role: .cancel) {}
        } message: {
            Text(isAr
                ? "لا يمكن بدء الفحص حالياً لعدم وجود اتصال نشط بالسيارة.\nيرجى توصيل قطعة OBD-II عبر البلوتوث BLE 4.0 أو الـ Wi-Fi مع فتح السويتش (ON) أولاً."
                : "Cannot start scan while disconnected. Please connect to your OBD-II BLE 4.0 or Wi-Fi adapter with ignition ON first.")
        }
        // Automatic update notification dialog
        .alert(isAr ? "يتوفر تحديث جديد!" : "New Update Available!", isPresented: $store.showUpdateDialog) {
            if let upd = store.availableUpdate, let url = URL(string: upd.ipaOrReleaseUrl) {
                Link(isAr ? "تنزيل ملف التحديث (.ipa)" : "Download Update (.ipa)", destination: url)
            }
            Button(isAr ? "لاحقاً" : "Later", role: .cancel) {}
        } message: {
            if let upd = store.availableUpdate {
                Text("v\(IosUpdateChecker.currentVersion) → v\(upd.latestVersion)\n\(upd.releaseNotes.prefix(240))")
            }
        }
        // Toast overlay
        .overlay(alignment: .bottom) {
            if let toast = store.toastMessage {
                Text(toast)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(CockpitPalette.surfaceElevated.opacity(0.96))
                    .clipShape(Capsule())
                    .padding(.bottom, 64)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 3.2) {
                            if store.toastMessage == toast { store.toastMessage = nil }
                        }
                    }
            }
        }
        // BLE Scanner Sheet
        .sheet(isPresented: $showBleSheet, onDismiss: { store.stopBleScan() }) {
            NavigationStack {
                List {
                    if !store.isBluetoothPoweredOn {
                        Text(isAr ? "يرجى تشغيل البلوتوث والسماح للتطبيق من إعدادات الآيفون." : "Please turn ON Bluetooth and grant permission in iPhone Settings.")
                            .foregroundStyle(CockpitPalette.amber)
                    } else if store.bleDevices.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(isAr ? "جاري البحث عن قطعة OBD-II BLE 4.0..." : "Scanning for BLE 4.0 OBD-II adapters...")
                        }
                    } else {
                        ForEach(store.bleDevices) { dev in
                            Button {
                                showBleSheet = false
                                store.connectBle(dev)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(dev.name).font(.headline).foregroundStyle(CockpitPalette.textPrimary)
                                        Text("BLE 4.0 • RSSI \(dev.rssi) dBm").font(.caption).foregroundStyle(CockpitPalette.cyan)
                                    }
                                    Spacer()
                                    Text(isAr ? "اتصال" : "Connect")
                                        .font(.caption.bold())
                                        .foregroundStyle(CockpitPalette.green)
                                }
                            }
                        }
                    }
                }
                .navigationTitle(isAr ? "أجهزة البلوتوث BLE 4.0" : "BLE 4.0 OBD Adapters")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(isAr ? "إغلاق" : "Close") { showBleSheet = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
        // Wi-Fi OBD Sheet
        .sheet(isPresented: $showWifiSheet) {
            NavigationStack {
                Form {
                    Section(isAr ? "إعدادات قطعة Wi-Fi OBD-II" : "Wi-Fi OBD-II Endpoint") {
                        TextField("IP (192.168.0.10)", text: $wifiHostDraft)
                            .keyboardType(.numbersAndPunctuation)
                        TextField("Port (35000)", text: $wifiPortDraft)
                            .keyboardType(.numberPad)
                    }
                    Button(isAr ? "اتصال الآن" : "Connect Now") {
                        showWifiSheet = false
                        store.connectWifi(host: wifiHostDraft, port: Int(wifiPortDraft) ?? 35000)
                    }
                }
                .navigationTitle(isAr ? "اتصال Wi-Fi OBD" : "Wi-Fi OBD")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(isAr ? "إلغاء" : "Cancel") { showWifiSheet = false }
                    }
                }
            }
            .presentationDetents([.medium])
        }
        // Scan History Sheet
        .sheet(isPresented: $showHistorySheet) {
            NavigationStack {
                List {
                    if store.scanHistory.isEmpty {
                        Text(isAr ? "لا توجد سجلات فحص محفوظة بعد." : "No saved scan records yet.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(store.scanHistory) { item in
                            HStack {
                                Button {
                                    store.openHistoryReport(item)
                                    showHistorySheet = false
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text([item.vehicleInfo.make, item.vehicleInfo.modelYear.map(String.init)].compactMap { $0 }.joined(separator: " ").isEmpty ? item.adapterDescription : [item.vehicleInfo.make, item.vehicleInfo.modelYear.map(String.init)].compactMap { $0 }.joined(separator: " "))
                                            .font(.headline)
                                        Text("\(item.healthScore)% • \(item.dtcs.count) \(isAr ? "أعطال" : "Faults")")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Button {
                                    showHistorySheet = false
                                    store.exportPdf(for: item)
                                } label: {
                                    Image(systemName: "doc.richtext")
                                        .foregroundStyle(CockpitPalette.green)
                                }
                                .buttonStyle(.borderless)
                            }
                            .swipeActions {
                                Button(role: .destructive) {
                                    store.deleteHistoryReport(item.id)
                                } label: {
                                    Label(isAr ? "حذف" : "Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
                .navigationTitle(isAr ? "سجلات الفحص السابقة" : "Previous Scans")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(isAr ? "إغلاق" : "Close") { showHistorySheet = false }
                    }
                }
            }
        }
        // Export / Save PDF Share Sheet
        .sheet(item: Binding(
            get: { store.exportedPdfUrl.map { IdentifiableUrl(url: $0) } },
            set: { if $0 == nil { store.exportedPdfUrl = nil } }
        )) { wrapper in
            ShareSheetView(items: [wrapper.url])
        }
    }
}

private struct IdentifiableUrl: Identifiable {
    var id: String { url.absoluteString }
    let url: URL
}
