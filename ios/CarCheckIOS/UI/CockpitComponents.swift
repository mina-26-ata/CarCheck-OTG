import SwiftUI
import UIKit

enum CockpitPalette {
    static let backgroundDeep = Color(red: 0.024, green: 0.051, blue: 0.094)
    static let background = Color(red: 0.043, green: 0.086, blue: 0.149)
    static let surface = Color(red: 0.067, green: 0.122, blue: 0.200)
    static let surfaceElevated = Color(red: 0.102, green: 0.173, blue: 0.271)
    static let border = Color(red: 0.157, green: 0.255, blue: 0.376)
    static let cyan = Color(red: 0.0, green: 0.898, blue: 1.0)
    static let blue = Color(red: 0.161, green: 0.651, blue: 1.0)
    static let green = Color(red: 0.0, green: 0.902, blue: 0.463)
    static let amber = Color(red: 1.0, green: 0.702, blue: 0.0)
    static let red = Color(red: 1.0, green: 0.239, blue: 0.341)
    static let textPrimary = Color(red: 0.929, green: 0.961, blue: 0.992)
    static let textSecondary = Color(red: 0.596, green: 0.698, blue: 0.812)
}

struct CockpitCard<Content: View>: View {
    var borderColor: Color = CockpitPalette.border
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [CockpitPalette.surface, CockpitPalette.backgroundDeep],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(borderColor, lineWidth: 1)
        )
    }
}

struct StatusPillView: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(color.opacity(0.16))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(color.opacity(0.5), lineWidth: 0.8))
    }
}

/// Permanent top connection status banner shown across all tabs at all times.
struct PermanentConnectionBanner: View {
    let state: ConnectionState
    let mode: ConnectionMode
    let adapterLabel: String
    let isAr: Bool
    let onTapDisconnected: () -> Void

    var body: some View {
        let isConn = state == .connected && mode != .disconnected
        let isWait = state == .connecting
        let accent: Color = isConn ? CockpitPalette.green : (isWait ? CockpitPalette.amber : CockpitPalette.red)

        let iconName: String = {
            if isConn {
                return mode == .wifi ? "wifi" : "dot.radiowaves.left.and.right"
            }
            return isWait ? "arrow.triangle.2.circlepath" : "personalhotspot.slash"
        }()

        let statusText: String = {
            if isConn {
                let via = mode == .wifi ? (isAr ? "عبر الـ Wi-Fi" : "via Wi-Fi") : (isAr ? "عبر البلوتوث BLE" : "via BLE")
                let suffix = adapterLabel.isEmpty ? "" : " • \(adapterLabel)"
                return isAr ? "متصل بالسيارة \(via)\(suffix)" : "Connected \(via)\(suffix)"
            }
            if isWait {
                return isAr ? "جاري الاتصال بالسيارة..." : "Connecting to vehicle..."
            }
            return isAr ? "غير متصل بالسيارة — وصّل البلوتوث BLE أو الـ Wi-Fi" : "Not Connected — Connect BLE 4.0 or Wi-Fi OBD"
        }()

        let pill: String = isConn ? (isAr ? "متصل" : "ONLINE") : (isWait ? (isAr ? "يتصل..." : "WAIT") : (isAr ? "غير متصل" : "OFFLINE"))

        Button {
            if !isConn { onTapDisconnected() }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: iconName)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(accent)
                Text(statusText)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(accent)
                    .lineLimit(1)
                Spacer(minLength: 4)
                StatusPillView(text: pill, color: accent)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(accent.opacity(0.14))
            .overlay(alignment: .bottom) {
                Rectangle().fill(accent.opacity(0.35)).frame(height: 1)
            }
        }
        .buttonStyle(.plain)
    }
}

/// Top 3 real-time circular HUD gauges (RPM, Coolant, Speed / Battery Voltage) with 120ms responsive needle motion.
struct TripleTelemetryGaugesView: View {
    let sensors: [LiveSensorReading]
    let batteryVoltage: Double?
    let unit: MeasurementUnit
    let isAr: Bool
    @State private var showBatteryOnThird = false

    var body: some View {
        let map = Dictionary(uniqueKeysWithValues: sensors.map { ($0.pidHex, $0) })
        let rpm = map["0C"]?.value ?? 0
        let coolant = map["05"]?.value
        let speed = map["0D"]?.value ?? 0
        let voltage = map["42"]?.value ?? batteryVoltage

        let isImp = unit == .imperial
        let coolMin: Double = isImp ? 100 : 40
        let coolMax: Double = isImp ? 265 : 130
        let coolVal = coolant ?? (isImp ? 104 : 40)
        let coolText = coolant.map { "\(Int($0))" } ?? "--"
        let coolAlert = coolant != nil && ((!isImp && coolant! >= 105) || (isImp && coolant! >= 221))

        HStack(spacing: 8) {
            CircularDialGauge(
                title: isAr ? "المحرك" : "Engine",
                valueText: "\(Int(max(0, rpm)))",
                unitText: "RPM",
                value: rpm,
                minVal: 0,
                maxVal: 8000,
                tint: rpm > 6200 ? CockpitPalette.red : CockpitPalette.green
            )

            CircularDialGauge(
                title: isAr ? "الحرارة" : "Coolant",
                valueText: coolText,
                unitText: isImp ? "°F" : "°C",
                value: coolVal,
                minVal: coolMin,
                maxVal: coolMax,
                tint: coolAlert ? CockpitPalette.red : CockpitPalette.cyan
            )

            Button {
                showBatteryOnThird.toggle()
            } label: {
                if !showBatteryOnThird {
                    CircularDialGauge(
                        title: isAr ? "السرعة" : "Speed",
                        valueText: "\(Int(max(0, speed)))",
                        unitText: isImp ? "mph" : "km/h",
                        value: speed,
                        minVal: 0,
                        maxVal: isImp ? 140 : 220,
                        tint: CockpitPalette.blue
                    )
                } else {
                    let v = voltage ?? 12.0
                    let bad = voltage != nil && (v < 12.1 || v > 15.0)
                    CircularDialGauge(
                        title: isAr ? "البطارية" : "Battery",
                        valueText: voltage.map { String(format: "%.1f", $0) } ?? "--",
                        unitText: "V",
                        value: v,
                        minVal: 10,
                        maxVal: 16,
                        tint: bad ? CockpitPalette.amber : CockpitPalette.green
                    )
                }
            }
            .buttonStyle(.plain)
        }
    }
}

struct CircularDialGauge: View {
    let title: String
    let valueText: String
    let unitText: String
    let value: Double
    let minVal: Double
    let maxVal: Double
    let tint: Color

    private var fraction: Double {
        min(1.0, max(0.0, (value - minVal) / max(1.0, maxVal - minVal)))
    }

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                let side = min(geo.size.width, geo.size.height)
                let strokeW = max(5.5, side * 0.095)
                ZStack {
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [tint.opacity(0.16), CockpitPalette.surface, CockpitPalette.backgroundDeep],
                                center: .center,
                                startRadius: 4,
                                endRadius: side * 0.5
                            )
                        )
                    Circle()
                        .trim(from: 0, to: 0.75)
                        .stroke(CockpitPalette.surfaceElevated, style: StrokeStyle(lineWidth: strokeW, lineCap: .round))
                        .rotationEffect(.degrees(135))

                    Circle()
                        .trim(from: 0, to: 0.75 * fraction)
                        .stroke(tint, style: StrokeStyle(lineWidth: strokeW, lineCap: .round))
                        .rotationEffect(.degrees(135))
                        .animation(.easeOut(duration: 0.12), value: fraction)

                    // Needle pointer
                    Canvas { context, size in
                        let center = CGPoint(x: size.width / 2, y: size.height / 2)
                        let angleDeg = 135.0 + 270.0 * fraction
                        let rad = angleDeg * .pi / 180.0
                        let len = (min(size.width, size.height) / 2) * 0.68
                        let tip = CGPoint(x: center.x + cos(rad) * len, y: center.y + sin(rad) * len)
                        var path = Path()
                        path.move(to: center)
                        path.addLine(to: tip)
                        context.stroke(path, with: .color(tint), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                    }
                    .animation(.easeOut(duration: 0.12), value: fraction)

                    VStack(spacing: 1) {
                        Text(valueText)
                            .font(.system(size: side < 100 ? 15 : 17, weight: .heavy, design: .rounded))
                            .foregroundStyle(CockpitPalette.textPrimary)
                        Text(unitText)
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(CockpitPalette.textSecondary)
                    }
                    .offset(y: side * 0.14)
                }
            }
            .aspectRatio(1, contentMode: .fit)

            Text(title)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(CockpitPalette.textPrimary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Interactive X-Ray Vehicle Chassis Visualizer with clickable subsystem hotspots.
struct CarSchematicView: View {
    let zoneMap: [CarSubsystemZone: CarZoneSummary]
    let selectedZone: CarSubsystemZone?
    let bodyStyle: CarBodyStyle
    let vehicleName: String?
    let isScanning: Bool
    let isAr: Bool
    let onSelectZone: (CarSubsystemZone) -> Void

    private let hotspots: [(CarSubsystemZone, CGFloat, CGFloat)] = [
        (.cooling, 0.18, 0.50),
        (.battery, 0.25, 0.28),
        (.engine, 0.29, 0.52),
        (.fuelAir, 0.36, 0.72),
        (.transmission, 0.47, 0.50),
        (.bodyNetwork, 0.58, 0.34),
        (.brakesChassis, 0.73, 0.26),
        (.exhaust, 0.84, 0.64)
    ]

    var body: some View {
        CockpitCard(borderColor: CockpitPalette.cyan.opacity(0.45)) {
            HStack {
                Label(isAr ? "خريطة السيارة التفاعلية (X-Ray)" : "Interactive X-Ray Vehicle Map", systemImage: "car.top.door.front.left.and.front.right.open")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(CockpitPalette.cyan)
                Spacer()
                if isScanning {
                    ProgressView().tint(CockpitPalette.cyan).scaleEffect(0.8)
                }
            }

            GeometryReader { geo in
                let w = geo.size.width
                let h = geo.size.height
                ZStack {
                    // X-Ray top-down futuristic chassis wireframe
                    Canvas { ctx, size in
                        let rect = CGRect(x: size.width * 0.10, y: size.height * 0.16, width: size.width * 0.80, height: size.height * 0.68)
                        let radius: CGFloat = bodyStyle == .suv ? 22 : 30
                        let bodyPath = Path(roundedRect: rect, cornerRadius: radius)
                        ctx.fill(bodyPath, with: .color(CockpitPalette.cyan.opacity(0.06)))
                        ctx.stroke(bodyPath, with: .color(CockpitPalette.cyan.opacity(0.55)), lineWidth: 1.8)

                        let cabin = CGRect(x: size.width * 0.36, y: size.height * 0.24, width: size.width * 0.36, height: size.height * 0.52)
                        ctx.stroke(Path(roundedRect: cabin, cornerRadius: 14), with: .color(CockpitPalette.blue.opacity(0.45)), lineWidth: 1.2)
                    }

                    ForEach(hotspots, id: \.0.id) { (zone, fx, fy) in
                        let summary = zoneMap[zone]
                        let col = zoneColor(summary?.status ?? .unscanned)
                        let isSel = selectedZone == zone
                        Button {
                            onSelectZone(zone)
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(col.opacity(isSel ? 0.38 : 0.20))
                                    .frame(width: isSel ? 30 : 22, height: isSel ? 30 : 22)
                                Circle()
                                    .fill(col)
                                    .frame(width: 11, height: 11)
                                    .overlay(Circle().stroke(Color.white, lineWidth: isSel ? 2 : 1))
                            }
                        }
                        .position(x: w * fx, y: h * fy)
                    }
                }
            }
            .frame(height: 155)

            if let name = vehicleName, !name.isEmpty {
                Text(name)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(CockpitPalette.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            if let sel = selectedZone, let sum = zoneMap[sel] {
                let col = zoneColor(sum.status)
                HStack {
                    Text(sel.title(isAr: isAr))
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(col)
                    Spacer()
                    let count = sum.faultCodes.count + sum.observations.count
                    Text(count == 0 ? (isAr ? "سليم" : "Normal") : (isAr ? "\(count) تنبيه/عطل" : "\(count) Issue(s)"))
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(col)
                }
                .padding(9)
                .background(CockpitPalette.backgroundDeep)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    private func zoneColor(_ status: ZoneStatus) -> Color {
        switch status {
        case .healthy: return CockpitPalette.green
        case .warning: return CockpitPalette.amber
        case .critical: return CockpitPalette.red
        case .unscanned: return CockpitPalette.cyan.opacity(0.65)
        }
    }
}

struct ShareSheetView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
