import UIKit

enum PdfReportGeneratorIOS {
    static func generatePdfFile(report: FullScanReport, smartAi: SmartAiReport?, isAr: Bool) throws -> URL {
        let pageWidth: CGFloat = 595
        let pageHeight: CGFloat = 842
        let marginX: CGFloat = 34
        let marginTop: CGFloat = 32
        let maxContentY: CGFloat = pageHeight - 40
        let contentWidth: CGFloat = pageWidth - marginX * 2

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd  HH:mm"
        let dateStr = formatter.string(from: Date(timeIntervalSince1970: Double(report.timestampMs) / 1000.0))

        formatter.dateFormat = "yyyyMMdd_HHmm"
        let stamp = formatter.string(from: Date(timeIntervalSince1970: Double(report.timestampMs) / 1000.0))
        let safeCar = [report.vehicleInfo.make, report.vehicleInfo.modelYear.map(String.init)]
            .compactMap { $0 }
            .joined(separator: "_")
            .replacingOccurrences(of: " ", with: "_")
        let fileName = safeCar.isEmpty ? "CarCheck_Report_\(stamp).pdf" : "CarCheck_\(safeCar)_\(stamp).pdf"
        let outUrl = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        let navy = UIColor(red: 0.04, green: 0.15, blue: 0.27, alpha: 1)
        let cyan = UIColor(red: 0.0, green: 0.66, blue: 0.80, alpha: 1)
        let green = UIColor(red: 0.06, green: 0.59, blue: 0.35, alpha: 1)
        let amber = UIColor(red: 0.85, green: 0.47, blue: 0.02, alpha: 1)
        let red = UIColor(red: 0.86, green: 0.15, blue: 0.15, alpha: 1)
        let cardBg = UIColor(red: 0.96, green: 0.97, blue: 0.99, alpha: 1)
        let borderCol = UIColor(red: 0.83, green: 0.88, blue: 0.93, alpha: 1)

        func makeParagraphStyle() -> NSMutableParagraphStyle {
            let p = NSMutableParagraphStyle()
            p.alignment = isAr ? .right : .left
            p.baseWritingDirection = isAr ? .rightToLeft : .leftToRight
            p.lineSpacing = 2.5
            return p
        }

        func attr(_ text: String, size: CGFloat, color: UIColor, bold: Bool = false) -> NSAttributedString {
            NSAttributedString(string: text, attributes: [
                .font: bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size),
                .foregroundColor: color,
                .paragraphStyle: makeParagraphStyle()
            ])
        }

        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight))
        try renderer.writePDF(to: outUrl) { ctx in
            var pageNum = 0
            var y: CGFloat = marginTop

            func finishFooter() {
                guard pageNum > 0 else { return }
                let cg = ctx.cgContext
                cg.setStrokeColor(borderCol.cgColor)
                cg.setLineWidth(0.8)
                cg.move(to: CGPoint(x: marginX, y: pageHeight - 30))
                cg.addLine(to: CGPoint(x: pageWidth - marginX, y: pageHeight - 30))
                cg.strokePath()

                let pLabel = isAr ? "CarCheck iOS • تقرير فحص إلكتروني — صفحة \(pageNum)" : "CarCheck iOS • Diagnostic Report — Page \(pageNum)"
                let fAttr = attr(pLabel, size: 8.5, color: .darkGray)
                fAttr.draw(in: CGRect(x: marginX, y: pageHeight - 25, width: contentWidth, height: 16))
            }

            func nextPage() {
                finishFooter()
                ctx.beginPage()
                pageNum += 1
                y = marginTop
            }

            func ensureSpace(_ needed: CGFloat) {
                if y + needed > maxContentY {
                    nextPage()
                }
            }

            func drawSectionHeader(_ title: String, color: UIColor) {
                ensureSpace(34)
                let hAttr = attr(title, size: 12.5, color: navy, bold: true)
                let rect = CGRect(x: marginX + (isAr ? 0 : 8), y: y, width: contentWidth - 8, height: 18)
                hAttr.draw(in: rect)
                let barX = isAr ? (pageWidth - marginX - 3.5) : marginX
                let bar = UIBezierPath(roundedRect: CGRect(x: barX, y: y + 2, width: 3.5, height: 14), cornerRadius: 1.5)
                color.setFill()
                bar.fill()
                y += 22
            }

            func drawTextCard(_ lines: [NSAttributedString], accent: UIColor, bg: UIColor? = nil) {
                let fillBg = bg ?? cardBg
                let pad: CGFloat = 10
                let innerW = contentWidth - pad * 2 - 6
                let heights = lines.map { ceil($0.boundingRect(with: CGSize(width: innerW, height: 2000), options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).height) }
                let totalH = pad * 2 + heights.reduce(0, +) + CGFloat(max(0, lines.count - 1)) * 4
                ensureSpace(totalH + 8)

                let box = CGRect(x: marginX, y: y, width: contentWidth, height: totalH)
                let path = UIBezierPath(roundedRect: box, cornerRadius: 7)
                fillBg.setFill()
                path.fill()
                borderCol.setStroke()
                path.lineWidth = 0.8
                path.stroke()

                let barX = isAr ? (pageWidth - marginX - 4) : marginX
                let bar = UIBezierPath(roundedRect: CGRect(x: barX, y: y + 3, width: 4, height: totalH - 6), cornerRadius: 2)
                accent.setFill()
                bar.fill()

                var curY = y + pad
                let textX = isAr ? (marginX + pad) : (marginX + pad + 4)
                for (i, line) in lines.enumerated() {
                    let h = heights[i]
                    line.draw(in: CGRect(x: textX, y: curY, width: innerW, height: h + 2))
                    curY += h + 4
                }
                y += totalH + 9
            }

            nextPage()

            // Header Banner
            let bannerRect = CGRect(x: marginX, y: y, width: contentWidth, height: 78)
            let bannerPath = UIBezierPath(roundedRect: bannerRect, cornerRadius: 10)
            navy.setFill()
            bannerPath.fill()
            cyan.setFill()
            UIRectFill(CGRect(x: marginX, y: y + 74, width: contentWidth, height: 4))

            let titleStr = isAr ? "تقرير الفحص الشامل للسيارة — CarCheck" : "CarCheck — Vehicle Diagnostic Report"
            let carDisplay = [report.vehicleInfo.make, report.vehicleInfo.modelYear.map(String.init)].compactMap { $0 }.joined(separator: " ")
            let subStr = "\(carDisplay.isEmpty ? report.adapterDescription : carDisplay)  •  \(dateStr)  •  \(report.healthScore)%"
            attr(titleStr, size: 16, color: .white, bold: true).draw(in: CGRect(x: marginX + 14, y: y + 14, width: contentWidth - 28, height: 24))
            attr(subStr, size: 10.5, color: UIColor(white: 0.88, alpha: 1)).draw(in: CGRect(x: marginX + 14, y: y + 42, width: contentWidth - 28, height: 20))
            y += 92

            // Vehicle Summary
            drawSectionHeader(isAr ? "معلومات السيارة وملخص الفحص" : "Vehicle Identity & Summary", color: navy)
            let v = report.vehicleInfo
            let milStr = report.readiness?.milOn == true ? (isAr ? "مضاءة" : "ON") : (isAr ? "مطفأة" : "OFF")
            let vLines: [NSAttributedString] = [
                attr(isAr ? "رقم الشاسيه (VIN): \(v.vin ?? "غير متاح")  |  الموديل: \(v.make ?? "-") \(v.modelYear.map(String.init) ?? "")" : "VIN: \(v.vin ?? "N/A")  |  Vehicle: \(v.make ?? "-") \(v.modelYear.map(String.init) ?? "")", size: 9.8, color: .black, bold: true),
                attr(isAr ? "البروتوكول: \(v.protocolName ?? "-")  |  البطارية: \(v.batteryVoltage.map { String(format: "%.2f V", $0) } ?? "-")" : "Protocol: \(v.protocolName ?? "-")  |  Battery: \(v.batteryVoltage.map { String(format: "%.2f V", $0) } ?? "-")", size: 9.5, color: .darkGray),
                attr(isAr ? "لمبة المحرك: \(milStr)  |  الأعطال المسجلة: \(report.dtcs.count)  |  درجة الصحة: \(report.healthScore)%" : "Check Engine: \(milStr)  |  Faults: \(report.dtcs.count)  |  Health Score: \(report.healthScore)%", size: 9.5, color: .darkGray)
            ]
            drawTextCard(vLines, accent: cyan)

            // Smart AI
            if let ai = smartAi {
                drawSectionHeader(isAr ? "ملخص التحليل الذكي وخطة الإصلاح" : "Smart AI Verdict & Repair Plan", color: cyan)
                var aiLines: [NSAttributedString] = [
                    attr(ai.verdict.label(isAr: isAr), size: 10.5, color: navy, bold: true),
                    attr(isAr ? ai.summaryAr : ai.summaryEn, size: 9.5, color: .black)
                ]
                let steps = isAr ? ai.priorityStepsAr : ai.priorityStepsEn
                for (i, s) in steps.enumerated() {
                    aiLines.append(attr("\(i + 1). \(s)", size: 9.3, color: .darkGray))
                }
                drawTextCard(aiLines, accent: cyan)
            }

            // DTCs
            drawSectionHeader(isAr ? "أكواد الأعطال المكتشفة (\(report.dtcs.count))" : "Detected Trouble Codes (\(report.dtcs.count))", color: report.dtcs.isEmpty ? green : red)
            if report.dtcs.isEmpty {
                drawTextCard([attr(isAr ? "لا توجد أكواد أعطال مسجلة — جميع الأنظمة المفحوصة سليمة." : "No trouble codes detected — all scanned modules are clean.", size: 10, color: green, bold: true)], accent: green)
            } else {
                for d in report.dtcs {
                    var lines: [NSAttributedString] = [
                        attr("\(d.code) (\(d.status.label(isAr: isAr))) — \(isAr ? d.knowledge.titleAr : d.knowledge.titleEn)", size: 10.5, color: red, bold: true),
                        attr(isAr ? d.knowledge.descriptionAr : d.knowledge.descriptionEn, size: 9.3, color: .black)
                    ]
                    let fixes = isAr ? d.knowledge.fixStepsAr : d.knowledge.fixStepsEn
                    for f in fixes.prefix(4) {
                        lines.append(attr("• \(f)", size: 9.0, color: .darkGray))
                    }
                    drawTextCard(lines, accent: red, bg: .white)
                }
            }

            // Observations
            if !report.observations.isEmpty {
                drawSectionHeader(isAr ? "الملاحظات الفنية (\(report.observations.count))" : "Diagnostic Observations (\(report.observations.count))", color: amber)
                for o in report.observations {
                    let col: UIColor = switch o.severity {
                    case .critical: red
                    case .warning: amber
                    case .info: cyan
                    case .good: green
                    }
                    var lines: [NSAttributedString] = [
                        attr("[\(o.severity.label(isAr: isAr))] \(isAr ? o.titleAr : o.titleEn)", size: 10, color: col, bold: true),
                        attr(isAr ? o.summaryAr : o.summaryEn, size: 9.2, color: .black)
                    ]
                    let steps = isAr ? o.resolutionStepsAr : o.resolutionStepsEn
                    for s in steps.prefix(3) {
                        lines.append(attr("• \(s)", size: 9.0, color: .darkGray))
                    }
                    drawTextCard(lines, accent: col)
                }
            }

            // Sensors Snapshot
            if !report.sensorSnapshot.isEmpty {
                drawSectionHeader(isAr ? "قراءات الحساسات (\(report.sensorSnapshot.count))" : "Sensor Telemetry Snapshot (\(report.sensorSnapshot.count))", color: navy)
                let sensorLines = report.sensorSnapshot.map { s in
                    let name = isAr ? s.nameAr : s.nameEn
                    let valStr = String(format: "%.\(s.decimals)f %@", s.value, s.unit)
                    return attr("• \(name) [\(s.pidHex)]: \(valStr)", size: 9.2, color: .black)
                }
                drawTextCard(sensorLines, accent: navy, bg: .white)
            }

            finishFooter()
        }
        return outUrl
    }
}

final class LocalPersistence {
    private let historyKey = "carcheck_ios_scan_history_v1"
    private let settingsKey = "carcheck_ios_settings_v1"

    func loadSettings() -> AppSettings {
        guard let data = UserDefaults.standard.data(forKey: settingsKey),
              let s = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            return AppSettings()
        }
        return s
    }

    func saveSettings(_ s: AppSettings) {
        if let data = try? JSONEncoder().encode(s) {
            UserDefaults.standard.set(data, forKey: settingsKey)
        }
    }

    func loadHistory() -> [FullScanReport] {
        guard let data = UserDefaults.standard.data(forKey: historyKey),
              let items = try? JSONDecoder().decode([FullScanReport].self, from: data) else {
            return []
        }
        return items.sorted { $0.timestampMs > $1.timestampMs }
    }

    func saveReport(_ report: FullScanReport) -> [FullScanReport] {
        var current = loadHistory().filter { $0.id != report.id }
        current.insert(report, at: 0)
        let trimmed = Array(current.prefix(25))
        if let data = try? JSONEncoder().encode(trimmed) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
        return trimmed
    }

    func deleteReport(id: String) -> [FullScanReport] {
        let updated = loadHistory().filter { $0.id != id }
        if let data = try? JSONEncoder().encode(updated) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
        return updated
    }
}

struct IosReleaseUpdate: Equatable {
    let latestVersion: String
    let releaseNotes: String
    let ipaOrReleaseUrl: String
}

enum IosUpdateChecker {
    static let currentVersion = "1.7.0"
    private static let apiUrl = "https://api.github.com/repos/mina-26-ata/CarCheck-OTG/releases/latest"

    static func checkLatest() async -> IosReleaseUpdate? {
        guard let url = URL(string: apiUrl) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 9)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("CarCheck-iOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")

        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = (json["tag_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return nil
        }

        let latestNorm = tag.replacingOccurrences(of: "v", with: "").replacingOccurrences(of: "V", with: "")
        guard isNewer(latestNorm, than: currentVersion) else { return nil }

        let notes = (json["body"] as? String) ?? ""
        let htmlUrl = (json["html_url"] as? String) ?? "https://github.com/mina-26-ata/CarCheck-OTG/releases"
        var downloadUrl = htmlUrl
        if let assets = json["assets"] as? [[String: Any]] {
            if let ipa = assets.first(where: { (($0["name"] as? String) ?? "").lowercased().hasSuffix(".ipa") }),
               let dl = ipa["browser_download_url"] as? String {
                downloadUrl = dl
            }
        }
        return IosReleaseUpdate(latestVersion: latestNorm, releaseNotes: notes, ipaOrReleaseUrl: downloadUrl)
    }

    static func isNewer(_ cand: String, than curr: String) -> Bool {
        let cParts = cand.split(separator: ".").map { Int($0.filter(\.isNumber)) ?? 0 }
        let kParts = curr.split(separator: ".").map { Int($0.filter(\.isNumber)) ?? 0 }
        let count = max(cParts.count, kParts.count)
        for i in 0..<count {
            let a = i < cParts.count ? cParts[i] : 0
            let b = i < kParts.count ? kParts[i] : 0
            if a != b { return a > b }
        }
        return false
    }
}
