import XCTest
@testable import CarCheckIOS

final class MockObdTransport: ObdTransport {
    var isConnected: Bool = true
    var adapterDescription: String = "Mock BLE OBD"
    var sentCommands: [String] = []
    private let responder: (String) -> String

    init(responder: @escaping (String) -> String) {
        self.responder = responder
    }

    func connect() async throws {}
    func disconnect() async { isConnected = false }

    func sendCommand(_ command: String, timeoutMs: Int) async throws -> String {
        let clean = command.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        sentCommands.append(clean)
        return responder(clean)
    }
}

final class CarCheckIOSTests: XCTestCase {

    func testVinDecoderAndBodyStyle() {
        let kia = VinDecoder.decode("KNDPN81ABM7123456")
        XCTAssertEqual(kia.make, "Kia")
        XCTAssertEqual(kia.year, 2021)
        XCTAssertEqual(VinDecoder.detectBodyStyle(vin: "KNDPN81ABM7123456", make: kia.make), .suv)

        let fordPickup = VinDecoder.decode("1FTFW1E50NFA12345")
        XCTAssertEqual(fordPickup.make, "Ford")
        XCTAssertEqual(fordPickup.year, 2022)
        XCTAssertEqual(VinDecoder.detectBodyStyle(vin: "1FTFW1E50NFA12345", make: fordPickup.make), .pickup)
    }

    func testDtcDatabaseAndParser() {
        let repo = DtcRepository()
        let entry = repo.lookup("P0171")
        XCTAssertEqual(entry.code, "P0171")
        XCTAssertFalse(entry.titleAr.isEmpty)

        let dtcs = ObdResponseParser.parseModeDtcResponse("7E8 06 43 02 01 71 03 00", responseServiceByte: 0x43, expectCanHeaders: true)
        XCTAssertEqual(dtcs.map(\.code), ["P0171", "P0300"])
    }

    func testFastCockpitPollerAndCloneFallback() async {
        var rpms = [760, 2900, 4800]
        let mock = MockObdTransport { cmd in
            switch cmd {
            case "010C1": return "?" // Simulate cheap BLE clone rejecting suffix
            case "010C":
                let r = rpms.removeFirst() * 4
                return String(format: "41 0C %02X %02X", (r >> 8) & 0xFF, r & 0xFF)
            case "010D", "010D1": return "41 0D 3C" // 60 km/h
            case "0105": return "41 05 82" // 90C
            case "0142": return "NO DATA"
            case "ATRV": return "14.1V"
            default: return "NO DATA"
            }
        }

        let engine = ObdCommandEngine(dtcRepo: DtcRepository())
        var cache: [String: LiveSensorReading] = [:]

        let tick1 = await engine.pollCockpitGaugesOnce(transport: mock, unit: .metric, existing: cache, includeSlowPids: true)
        for item in tick1 { cache[item.pidHex] = item }
        XCTAssertEqual(cache["0C"]?.value ?? 0, 760, accuracy: 1)
        XCTAssertEqual(cache["0D"]?.value ?? 0, 60, accuracy: 1)
        XCTAssertEqual(cache["05"]?.value ?? 0, 90, accuracy: 1)
        XCTAssertEqual(cache["42"]?.value ?? 0, 14.1, accuracy: 0.1)

        mock.sentCommands.removeAll()
        let tick2 = await engine.pollCockpitGaugesOnce(transport: mock, unit: .metric, existing: cache, includeSlowPids: false)
        for item in tick2 { cache[item.pidHex] = item }
        XCTAssertEqual(cache["0C"]?.value ?? 0, 2900, accuracy: 1)
        XCTAssertEqual(mock.sentCommands, ["010C", "010D"])
    }

    func testForceMilResetUdsSequence() async {
        var cleared = false
        let mock = MockObdTransport { cmd in
            switch cmd {
            case "10 03": return "50 03"
            case "14 FF FF FF":
                cleared = true
                return "54"
            case "0101":
                return cleared ? "41 01 00 07 65 00" : "41 01 81 07 65 00"
            default:
                return "OK"
            }
        }

        let engine = ObdCommandEngine(dtcRepo: DtcRepository())
        let res = await engine.forceTurnOffCheckEngine(mock)
        XCTAssertTrue(res.clearedConfirmed)
        XCTAssertEqual(res.milIsNowOff, true)
    }

    func testArabicPdfReportGeneration() throws {
        let report = FullScanReport(
            id: "test-1",
            timestampMs: 1_732_500_000_000,
            adapterDescription: "BLE 4.0 • IOS-Vlink",
            healthScore: 92,
            vehicleInfo: VehicleInfo(vin: "KNDPN81ABM7123456", make: "Kia", country: "كوريا الجنوبية", modelYear: 2021, protocolName: "ISO 15765-4 CAN", batteryVoltage: 14.2),
            dtcs: [],
            freezeFrame: [],
            readiness: ReadinessSummary(milOn: false, ecuReportedDtcCount: 0, isDiesel: false),
            sensorSnapshot: [],
            observations: []
        )
        let ai = LocalAiDiagnosticEngine.analyze(report: report)
        let pdfUrl = try PdfReportGeneratorIOS.generatePdfFile(report: report, smartAi: ai, isAr: true)
        let data = try Data(contentsOf: pdfUrl)
        XCTAssertGreaterThan(data.count, 1000)
        let header = String(data: data.prefix(5), encoding: .ascii)
        XCTAssertEqual(header, "%PDF-")
    }
}
