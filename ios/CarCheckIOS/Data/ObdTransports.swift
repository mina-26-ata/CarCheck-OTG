import Foundation
import CoreBluetooth
import Network

protocol ObdTransport: AnyObject {
    var isConnected: Bool { get }
    var adapterDescription: String { get }
    func connect() async throws
    func sendCommand(_ command: String, timeoutMs: Int) async throws -> String
    func disconnect() async
}

enum ObdTransportError: LocalizedError {
    case bluetoothOff
    case connectionFailed(String)
    case disconnected
    case timeout

    var errorDescription: String? {
        switch self {
        case .bluetoothOff:
            return "البلوتوث مغلق أو غير مصرح به في إعدادات الآيفون."
        case .connectionFailed(let msg):
            return msg
        case .disconnected:
            return "انقطع الاتصال بقطعة الفحص."
        case .timeout:
            return "انتهت مهلة انتظار استجابة قطعة OBD-II."
        }
    }
}

/// Discovers BLE 4.0+ ELM327 OBD-II dongles compatible with iPhone (Vgate, Veepeak, Viecar, ELM327 BLE, etc.)
@MainActor
final class BleObdManager: NSObject, ObservableObject {
    @Published private(set) var devices: [BleDeviceInfo] = []
    @Published private(set) var isScanning: Bool = false
    @Published private(set) var isBluetoothPoweredOn: Bool = false

    fileprivate var central: CBCentralManager!
    private var peripheralsById: [UUID: CBPeripheral] = [:]
    fileprivate var activeTransportDelegate: BleElm327Transport?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func startScan() {
        guard central.state == .poweredOn else {
            isBluetoothPoweredOn = false
            return
        }
        devices.removeAll()
        isScanning = true
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScan() {
        if isScanning {
            central.stopScan()
            isScanning = false
        }
    }

    func createTransport(for device: BleDeviceInfo) -> BleElm327Transport? {
        guard let peripheral = peripheralsById[device.id] else { return nil }
        stopScan()
        let transport = BleElm327Transport(manager: self, peripheral: peripheral, name: device.name)
        activeTransportDelegate = transport
        return transport
    }
}

extension BleObdManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let powered = central.state == .poweredOn
        Task { @MainActor in
            self.isBluetoothPoweredOn = powered
            if !powered {
                self.isScanning = false
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let rawName = (advName ?? peripheral.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rawName.isEmpty else { return }

        let id = peripheral.identifier
        let rssiVal = RSSI.intValue
        Task { @MainActor in
            self.peripheralsById[id] = peripheral
            let info = BleDeviceInfo(id: id, name: rawName, rssi: rssiVal)
            if let idx = self.devices.firstIndex(where: { $0.id == id }) {
                self.devices[idx] = info
            } else {
                self.devices.append(info)
                self.devices.sort { a, b in
                    let aLikely = Self.isLikelyObdName(a.name)
                    let bLikely = Self.isLikelyObdName(b.name)
                    if aLikely != bLikely { return aLikely }
                    return a.rssi > b.rssi
                }
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.activeTransportDelegate?.onCentralConnected()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let msg = error?.localizedDescription ?? "تعذر الاتصال بقطعة البلوتوث"
        Task { @MainActor in
            self.activeTransportDelegate?.onCentralFailed(msg)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.activeTransportDelegate?.onCentralDisconnected()
        }
    }

    private static func isLikelyObdName(_ name: String) -> Bool {
        let u = name.uppercased()
        let hints = ["OBD", "ELM", "IOS-VLINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "VIECAR", "CARISTA", "KONNWEI", "LELINK", "SCAN"]
        return hints.contains { u.contains($0) }
    }
}

final class BleElm327Transport: NSObject, ObdTransport, CBPeripheralDelegate {
    private weak var manager: BleObdManager?
    private let peripheral: CBPeripheral
    private let deviceName: String

    private var writeChar: CBCharacteristic?
    private var notifyChar: CBCharacteristic?
    private var writeType: CBCharacteristicWriteType = .withResponse
    private(set) var isConnected: Bool = false

    private var connectCont: CheckedContinuation<Void, Error>?
    private var responseBuffer: String = ""
    private var promptWaiter: CheckedContinuation<String, Error>?
    private let bufferLock = NSLock()

    // Known ELM327 BLE UART GATT UUID profiles across iOS adapters
    private static let knownProfiles: [(svc: String, write: String, notify: String)] = [
        ("FFE0", "FFE1", "FFE1"),
        ("FFF0", "FFF2", "FFF1"),
        ("FFF0", "FFF1", "FFF2"),
        ("18F0", "2AF1", "2AF0"),
        ("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", "6E400002-B5A3-F393-E0A9-E50E24DCCA9E", "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    ]

    var adapterDescription: String { "BLE 4.0 • \(deviceName)" }

    init(manager: BleObdManager, peripheral: CBPeripheral, name: String) {
        self.manager = manager
        self.peripheral = peripheral
        self.deviceName = name
        super.init()
        self.peripheral.delegate = self
    }

    func connect() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.connectCont = cont
            Task { @MainActor in
                guard let mgr = self.manager, mgr.isBluetoothPoweredOn else {
                    self.finishConnect(with: .failure(ObdTransportError.bluetoothOff))
                    return
                }
                mgr.central.connect(self.peripheral, options: nil)
            }
        }
    }

    private func finishConnect(with result: Result<Void, Error>) {
        guard let c = connectCont else { return }
        connectCont = nil
        switch result {
        case .success: c.resume()
        case .failure(let err): c.resume(throwing: err)
        }
    }

    @MainActor func onCentralConnected() {
        peripheral.discoverServices(nil)
    }

    @MainActor func onCentralFailed(_ message: String) {
        isConnected = false
        finishConnect(with: .failure(ObdTransportError.connectionFailed(message)))
    }

    @MainActor func onCentralDisconnected() {
        isConnected = false
        finishConnect(with: .failure(ObdTransportError.disconnected))
        resumePrompt(with: .failure(ObdTransportError.disconnected))
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services, !services.isEmpty else {
            finishConnect(with: .failure(ObdTransportError.connectionFailed("لم يتم العثور على خدمات GATT في قطعة البلوتوث")))
            return
        }
        for svc in services {
            peripheral.discoverCharacteristics(nil, for: svc)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard !isConnected else { return }
        let allServices = peripheral.services ?? []
        // Try matching known ELM327 profiles first
        for prof in Self.knownProfiles {
            if let svc = allServices.first(where: { $0.uuid.uuidString.uppercased() == prof.svc }),
               let chars = svc.characteristics,
               let w = chars.first(where: { $0.uuid.uuidString.uppercased() == prof.write }),
               let n = chars.first(where: { $0.uuid.uuidString.uppercased() == prof.notify }) {
                bindEndpoints(write: w, notify: n)
                return
            }
        }
        // Generic fallback: any characteristic pair supporting write + notify
        if let chars = service.characteristics {
            let writable = chars.first { $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse) }
            let notifiable = chars.first { $0.properties.contains(.notify) || $0.properties.contains(.indicate) }
            if let w = writable, let n = notifiable {
                bindEndpoints(write: w, notify: n)
            }
        }
    }

    private func bindEndpoints(write: CBCharacteristic, notify: CBCharacteristic) {
        self.writeChar = write
        self.notifyChar = notify
        self.writeType = write.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        peripheral.setNotifyValue(true, for: notify)
        self.isConnected = true
        finishConnect(with: .success(()))
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value, !data.isEmpty,
              let chunk = String(data: data, encoding: .ascii) else { return }
        bufferLock.lock()
        responseBuffer.append(chunk)
        let hasPrompt = responseBuffer.contains(">")
        let snapshot = hasPrompt ? responseBuffer : nil
        if hasPrompt { responseBuffer = "" }
        bufferLock.unlock()

        if let snap = snapshot {
            resumePrompt(with: .success(snap))
        }
    }

    private func resumePrompt(with result: Result<String, Error>) {
        bufferLock.lock()
        let waiter = promptWaiter
        promptWaiter = nil
        bufferLock.unlock()
        guard let waiter else { return }
        switch result {
        case .success(let s): waiter.resume(returning: s)
        case .failure(let e): waiter.resume(throwing: e)
        }
    }

    func sendCommand(_ command: String, timeoutMs: Int) async throws -> String {
        guard isConnected, let wChar = writeChar else {
            throw ObdTransportError.disconnected
        }

        bufferLock.lock()
        responseBuffer = ""
        bufferLock.unlock()

        let payload = Array("\(command.trimmingCharacters(in: .whitespacesAndNewlines))\r".utf8)
        let chunkSize = 20
        var offset = 0
        while offset < payload.count {
            let end = min(offset + chunkSize, payload.count)
            let chunkData = Data(payload[offset..<end])
            peripheral.writeValue(chunkData, for: wChar, type: writeType)
            offset = end
        }

        let raw = try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
                    self.bufferLock.lock()
                    if self.responseBuffer.contains(">") {
                        let ready = self.responseBuffer
                        self.responseBuffer = ""
                        self.bufferLock.unlock()
                        cont.resume(returning: ready)
                    } else {
                        self.promptWaiter = cont
                        self.bufferLock.unlock()
                    }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(max(timeoutMs, 200)) * 1_000_000)
                throw ObdTransportError.timeout
            }
            do {
                let first = try await group.next() ?? ""
                group.cancelAll()
                return first
            } catch {
                group.cancelAll()
                self.bufferLock.lock()
                let partial = self.responseBuffer
                self.responseBuffer = ""
                let w = self.promptWaiter
                self.promptWaiter = nil
                self.bufferLock.unlock()
                w?.resume(returning: partial)
                if !partial.isEmpty { return partial }
                throw error
            }
        }
        return Self.cleanElmResponse(raw, sent: command)
    }

    func disconnect() async {
        isConnected = false
        await MainActor.run {
            manager?.central.cancelPeripheralConnection(peripheral)
        }
    }

    fileprivate static func cleanElmResponse(_ raw: String, sent: String) -> String {
        let cmdUpper = sent.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return raw
            .replacingOccurrences(of: ">", with: "")
            .replacingOccurrences(of: "\r", with: "\n")
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0.uppercased() != cmdUpper }
            .joined(separator: "\n")
    }
}

/// Connects to Wi-Fi ELM327 OBD-II adapters on iPhone (typically 192.168.0.10:35000)
final class WifiElm327Transport: ObdTransport {
    private let host: String
    private let port: UInt16
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "com.carchecker.ios.wifiobd")
    private(set) var isConnected: Bool = false

    var adapterDescription: String { "Wi-Fi OBD (\(host):\(port))" }

    init(host: String, port: Int) {
        self.host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        self.port = UInt16(clamping: port)
    }

    func connect() async throws {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw ObdTransportError.connectionFailed("رقم منفذ Wi-Fi غير صالح")
        }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        self.connection = conn

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            final class OnceBox: @unchecked Sendable { var done = false }
            let box = OnceBox()
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if !box.done {
                        box.done = true
                        self.isConnected = true
                        cont.resume()
                    }
                case .failed(let err):
                    if !box.done {
                        box.done = true
                        self.isConnected = false
                        cont.resume(throwing: ObdTransportError.connectionFailed(err.localizedDescription))
                    }
                case .cancelled:
                    if !box.done {
                        box.done = true
                        self.isConnected = false
                        cont.resume(throwing: ObdTransportError.disconnected)
                    }
                default:
                    break
                }
            }
            conn.start(queue: queue)
        }
    }

    func sendCommand(_ command: String, timeoutMs: Int) async throws -> String {
        guard isConnected, let conn = connection else {
            throw ObdTransportError.disconnected
        }
        let data = Data("\(command.trimmingCharacters(in: .whitespacesAndNewlines))\r".utf8)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { err in
                if let err { cont.resume(throwing: err) } else { cont.resume() }
            })
        }

        var accumulated = ""
        let deadline = Date().addingTimeInterval(Double(max(timeoutMs, 250)) / 1000.0)
        while Date() < deadline {
            let chunk: String = try await withCheckedThrowingContinuation { cont in
                conn.receive(minimumIncompleteLength: 1, maximumLength: 1024) { content, _, isComplete, err in
                    if let err {
                        cont.resume(throwing: err)
                    } else if let content, let str = String(data: content, encoding: .ascii) {
                        cont.resume(returning: str)
                    } else if isComplete {
                        cont.resume(throwing: ObdTransportError.disconnected)
                    } else {
                        cont.resume(returning: "")
                    }
                }
            }
            accumulated.append(chunk)
            if accumulated.contains(">") { break }
        }
        return BleElm327Transport.cleanElmResponse(accumulated, sent: command)
    }

    func disconnect() async {
        isConnected = false
        connection?.cancel()
        connection = nil
    }
}
