import SwiftUI
import CoreBluetooth
import CommonCrypto
import Foundation

// EasyDens ED2 BLE protocol, reverse-engineered from Brew Meister 6.7.0
// (com.antonpaar.homebrew) and verified against a live HCI capture 2026-10-14.
//
//   service      41500000-d278-11eb-b8bc-0242ac130003
//     41500001   density      notify
//     41500002   temperature  notify
//     41500003   rpcRequest   write-no-response
//     41500004   rpcReply     notify
//     41500005   unknown      notify   (undocumented 5th characteristic)
//   RPC frame    0x80 | len | AES-CBC-PKCS7(body, key=ASCII(serial*4), iv=0*16)
//   reply body   [opcode, status(0=ok), payload...]

let ED2_SVC = CBUUID(string: "41500000-D278-11EB-B8BC-0242AC130003")
let CH_DENSITY = CBUUID(string: "41500001-D278-11EB-B8BC-0242AC130003")
let CH_TEMP = CBUUID(string: "41500002-D278-11EB-B8BC-0242AC130003")
let CH_RPC_REQ = CBUUID(string: "41500003-D278-11EB-B8BC-0242AC130003")
let CH_RPC_REP = CBUUID(string: "41500004-D278-11EB-B8BC-0242AC130003")
let CH_FIFTH = CBUUID(string: "41500005-D278-11EB-B8BC-0242AC130003")
let CH_SERIAL = CBUUID(string: "00002A25-0000-1000-8000-00805F9B34FB")

func hexs(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined(separator: " ") }

func aesCBC(_ data: Data, key: Data, decrypt: Bool) -> Data? {
    var out = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
    var moved = 0
    let keyBytes = [UInt8](key)
    let ivBytes = [UInt8](repeating: 0, count: kCCBlockSizeAES128)
    let inBytes = [UInt8](data)
    let st = CCCrypt(decrypt ? CCOperation(kCCDecrypt) : CCOperation(kCCEncrypt),
                     CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                     keyBytes, keyBytes.count, ivBytes,
                     inBytes, inBytes.count, &out, out.count, &moved)
    guard st == kCCSuccess, moved > 0 else { return nil }
    return Data(out[0..<moved])
}

func rpcFrame(serial: String, cmd: Data) -> Data? {
    guard let ct = aesCBC(cmd, key: Data(String(repeating: serial, count: 4).utf8), decrypt: false) else { return nil }
    return Data([0x80, UInt8(ct.count)]) + ct
}

func rpcUnframe(serial: String, frame: Data) -> Data? {
    guard frame.count > 2, let pt = aesCBC(frame.suffix(from: 2),
                                           key: Data(String(repeating: serial, count: 4).utf8), decrypt: true)
    else { return nil }
    return pt
}

func f32le(_ d: Data) -> Float? {
    guard d.count >= 4 else { return nil }
    return d.withUnsafeBytes { $0.load(as: Float32.self) }
}

final class EasyDens: NSObject, ObservableObject {
    static let shared = EasyDens()

    @Published var status = "idle"
    @Published var serial = "—"
    @Published var standbyMin: Int?
    @Published var density: String = "—"
    @Published var temperature: String = "—"
    @Published var lastReply = "—"
    @Published var fifth = "—"
    @Published var busy = false

    private var central: CBCentralManager!
    private let logPath = "/tmp/easydens_ctrl.log"

    func log(_ s: String) {
        NSLog("EasyDensCtrl %@", s)
        if !FileManager.default.fileExists(atPath: logPath) {
            FileManager.default.createFile(atPath: logPath, contents: nil)
        }
        if let fh = FileHandle(forWritingAtPath: logPath) {
            try? fh.seekToEnd()
            try? fh.write(Data((Date().formatted() + " " + s + "\n").utf8))
        }
        status = s
    }
    private var dev: CBPeripheral?
    private var rpcReq: CBCharacteristic?
    private var pending: [Data] = []   // handshake queue

    private func keyBytes() -> Data { Data(String(repeating: serial, count: 4).utf8) }

    func start() {
        central = CBCentralManager(delegate: self, queue: nil)
    }

    private func send(_ bytes: [UInt8]) {
        let cmd = Data(bytes)
        guard let p = dev, let ch = rpcReq, let f = rpcFrame(serial: serial, cmd: cmd) else {
            log("not ready"); return
        }
        log("send \(hexs(Data(cmd))) -> \(hexs(f))")
        p.writeValue(f, for: ch, type: .withoutResponse)
    }

    func cmdMeasure() { busy = true; send([2]) }
    func cmdInfo() { busy = true; send([1, 0]) }
    func cmdStandby() { busy = true; send([16]) }
    func cmdQ15() { busy = true; send([15]) }
    func cmdQ17() { busy = true; send([17]) }
}

extension EasyDens: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn:
            log("scanning for EasyDens…")
            c.scanForPeripherals(withServices: [ED2_SVC])
        case .unauthorized:
            log("Bluetooth permission denied — allow this app in System Settings → Privacy → Bluetooth")
        default:
            log("bluetooth state: \(c.state.rawValue)")
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        dev = p
        p.delegate = self
        c.stopScan()
        log("connecting \(p.name ?? "?") \(p.identifier)")
        c.connect(p)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        log("connected — discovering")
        p.discoverServices(nil)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        log("disconnected: \(error?.localizedDescription ?? "clean")")
        dev = nil
    }
}

extension EasyDens: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices e: Error?) {
        p.services?.forEach { p.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        for ch in s.characteristics ?? [] {
            switch ch.uuid {
            case CH_RPC_REQ: rpcReq = ch
            case CH_RPC_REP, CH_DENSITY, CH_TEMP, CH_FIFTH:
                p.setNotifyValue(true, for: ch)
            case CH_SERIAL: p.readValue(for: ch)
            default: break
            }
        }
        if rpcReq != nil, serial != "—" { handshake() }
    }

    func handshake() {
        send([15])
        send([16])
        send([1, 0])
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let d = ch.value else { return }
        switch ch.uuid {
        case CH_SERIAL:
            serial = String(data: d, encoding: .utf8) ?? "—"
            log("serial \(serial)")
            if rpcReq != nil { handshake() }
        case CH_DENSITY:
            log("notify density \(hexs(d))")
            if let pt = aesCBC(d, key: keyBytes(), decrypt: true), let v = f32le(pt) {
                density = String(format: "%.5f g/cm³", v)
            } else { density = "raw \(hexs(d))" }
        case CH_TEMP:
            log("notify temperature \(hexs(d))")
            if let pt = aesCBC(d, key: keyBytes(), decrypt: true), let v = f32le(pt) {
                temperature = String(format: "%.2f °C", v)
            } else { temperature = "raw \(hexs(d))" }
        case CH_FIFTH:
            log("notify fifth \(hexs(d))")
            fifth = f32le(d).map { String(format: "%.3f V", $0) } ?? hexs(d)
        case CH_RPC_REP:
            log("notify rpcReply \(hexs(d))")
            guard let pt = rpcUnframe(serial: serial, frame: d) else {
                lastReply = "reply decrypt FAIL \(hexs(d))"; busy = false; return
            }
            lastReply = "op=\(pt.first ?? 255) status=\(pt.count > 1 ? pt[1] : 255) payload=\(hexs(pt.dropFirst(2)))"
            if pt.first == 16, pt.count >= 4 {
                standbyMin = Int(pt[2]) | Int(pt[3]) << 8
            }
            busy = false
        default: break
        }
    }
}

struct Row: View {
    let label: String, value: String
    var body: some View {
        HStack { Text(label).foregroundStyle(.secondary); Spacer(); Text(value).monospaced().textSelection(.enabled) }
    }
}

struct ContentView: View {
    @ObservedObject var ed = EasyDens.shared
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("EasyDens Control").font(.title2.bold())
            Row(label: "status", value: ed.status)
            Row(label: "serial (key)", value: ed.serial)
            Row(label: "standby", value: ed.standbyMin.map { "\($0) min" } ?? "—")
            Row(label: "density", value: ed.density)
            Row(label: "temperature", value: ed.temperature)
            Row(label: "char 41500005", value: ed.fifth)
            Row(label: "last reply", value: ed.lastReply)
            HStack {
                Button("Scan/Connect") { ed.start() }
                Button("Measure") { ed.cmdMeasure() }.disabled(ed.busy)
                Button("Info") { ed.cmdInfo() }
                Button("Standby") { ed.cmdStandby() }
                Button("Q15") { ed.cmdQ15() }
                Button("Q17") { ed.cmdQ17() }
            }
            Text("protocol: Brew Meister 6.7.0 REA/JADX · verified vs live capture 2026-10-14")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(16)
        .onAppear { EasyDens.shared.start() }
    }
}

@main
struct EasyDensApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
