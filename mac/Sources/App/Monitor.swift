import AppKit
import AVFoundation
import Foundation
import UserNotifications

// ---------------- журнал ----------------

let appVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "?"
let appBuild = Int((Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "0") ?? 0
let env = ProcessInfo.processInfo.environment
let testMode = env["TREVOGA_TEST"] == "1"

private let logURL: URL = {
    let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs")
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir.appendingPathComponent("Trevoga.log")
}()
private let logQ = DispatchQueue(label: "log")
private let logFmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"; return f }()

/** Рядок журналу: ~/Library/Logs/Trevoga.log і stdout (його читає автотест). */
func L(_ s: String) {
    let line = logFmt.string(from: Date()) + " " + s
    print(line)
    fflush(stdout)
    logQ.async {
        if let h = try? FileHandle(forWritingTo: logURL) { h.seekToEndOfFile(); h.write((line + "\n").data(using: .utf8)!); try? h.close() }
        else { try? (line + "\n").write(to: logURL, atomically: true, encoding: .utf8) }
        if let a = try? FileManager.default.attributesOfItem(atPath: logURL.path), let n = a[.size] as? Int, n > 2_000_000 {
            try? FileManager.default.removeItem(at: logURL.deletingPathExtension().appendingPathExtension("old.log"))
            try? FileManager.default.moveItem(at: logURL, to: logURL.deletingPathExtension().appendingPathExtension("old.log"))
        }
    }
}

func nowMs() -> Int { Int(Date().timeIntervalSince1970 * 1000) }

// ---------------- налаштування ----------------

final class Settings: ObservableObject {
    static let shared = Settings()
    let d = UserDefaults.standard
    @Published var region: String { didSet { d.set(region, forKey: "region") } }
    @Published var raion: Int { didSet { d.set(raion, forKey: "raion") } }
    @Published var partial: Bool { didSet { d.set(partial, forKey: "partial") } }
    @Published var quiet: Bool { didSet { d.set(quiet, forKey: "quiet") } }
    @Published var voice: String { didSet { d.set(voice, forKey: "voice") } }
    @Published var mapView: Bool { didSet { d.set(mapView, forKey: "mapView") } }
    @Published var showOnAlert: Bool { didSet { d.set(showOnAlert, forKey: "showOnAlert") } }
    var id: String

    init() {
        region = env["TREVOGA_REGION"] ?? d.string(forKey: "region") ?? "м. Київ"
        raion = env["TREVOGA_RAION"].flatMap { Int($0) } ?? d.integer(forKey: "raion")
        partial = d.bool(forKey: "partial")
        quiet = d.bool(forKey: "quiet")
        voice = d.string(forKey: "voice") ?? "tetiana"
        mapView = d.bool(forKey: "mapView")
        showOnAlert = d.object(forKey: "showOnAlert") as? Bool ?? true
        if let s = d.string(forKey: "id") { id = s } else {
            id = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12))
            d.set(id, forKey: "id")
        }
    }

    var place: String {
        if raion != 0, let r = AlertSource.raionName(raion) { return "\(r) (\(region))" }
        return region
    }
    var placeKey: String { "\(region)|\(raion)|\(partial)" }

    /** Адреса сервера: TREVOGA_SERVER (автотест) → збережена з GitHub → вшита в програму. */
    var server: String {
        if let s = env["TREVOGA_SERVER"], !s.isEmpty { return s }
        if let s = d.string(forKey: "server"), !s.isEmpty { return s }
        if let u = Bundle.main.url(forResource: "config", withExtension: "json"), let t = try? String(contentsOf: u),
           let j = jsonObject(t), let s = j["server"] as? String { return s.trimmingCharacters(in: .whitespaces) }
        return "https://trevoga.seokernel.workers.dev"
    }
}

// ---------------- мережа ----------------

enum Net {
    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 10
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "Trevoga-Mac/\(appVersion)", "Accept": "application/json"]
        return URLSession(configuration: c)
    }()

    static func get(_ url: String, _ done: @escaping (Result<Data, Error>) -> Void) {
        guard let u = URL(string: url) else { done(.failure(AlertError("невірна адреса"))); return }
        session.dataTask(with: u) { data, resp, err in
            if let err = err { done(.failure(AlertError(short(err)))); return }
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code != 200 { done(.failure(AlertError("сервер відповів \(code)"))); return }
            done(.success(data ?? Data()))
        }.resume()
    }

    static func short(_ e: Error) -> String {
        let m = (e as NSError).localizedDescription.replacingOccurrences(of: "\n", with: " ")
        return m.count > 60 ? String(m.prefix(60)) + "…" : m
    }
}

/** Звіти про спрацювання на сервер (як у Windows-версії): з них рахується швидкість реакції. */
enum Reporter {
    static func send(_ ev: String, _ fields: [String: String]) {
        var o: [String: String] = ["e": ev, "id": Settings.shared.id, "v": appVersion, "os": "mac"]
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        o["t"] = f.string(from: Date())
        for (k, v) in fields { o[k] = v }
        guard let u = URL(string: Settings.shared.server + "/report"), let body = try? JSONSerialization.data(withJSONObject: o) else { return }
        var req = URLRequest(url: u)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        Net.session.dataTask(with: req).resume()
    }
}

// ---------------- потік сервера (WebSocket) ----------------

/** Потік офіційних тривог UkraineAlarm з нашого сервера. «Свіжий» — якщо сервер подавав ознаки життя протягом 35 с. */
final class StreamClient: NSObject, URLSessionWebSocketDelegate {
    var url = ""
    var onChange: (() -> Void)?
    var onLost: (() -> Void)?
    private(set) var data: JObj?
    private var sig = ""
    private var connected = false, haveData = false
    private var lastMsg = Date.distantPast, openedAt = Date()
    private var task: URLSessionWebSocketTask?
    private var session: URLSession!
    private var gen = 0, fails = 0
    private var watchdog: Timer?
    private(set) var status = ""

    override init() {
        super.init()
        session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: .main)
    }

    private func setStatus(_ s: String) { if s != status { status = s; L("UASTREAM " + s) } }

    func fresh() -> Bool { connected && haveData && data != nil && Date().timeIntervalSince(lastMsg) < 35 }

    func start() {
        guard !url.isEmpty, task == nil else { return }
        connect()
        watchdog = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            guard let s = self, s.connected, Date().timeIntervalSince(s.lastMsg) > 35 else { return }
            s.failed("немає даних 35 с")
        }
    }

    func restart() { task?.cancel(with: .goingAway, reason: nil); task = nil; connected = false; fails = 0; connect() }

    private func connect() {
        gen += 1
        let my = gen
        guard let u = URL(string: url) else { setStatus("невірна адреса"); return }
        var req = URLRequest(url: u)
        req.setValue("Trevoga-Mac/\(appVersion)", forHTTPHeaderField: "User-Agent")
        let t = session.webSocketTask(with: req)
        task = t
        openedAt = Date()
        t.resume()
        receive(t, my)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        guard webSocketTask === task else { return }
        connected = true
        lastMsg = Date()
        setStatus("підключено")
    }

    private func receive(_ t: URLSessionWebSocketTask, _ my: Int) {
        t.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let s = self, my == s.gen else { return }
                switch result {
                case .success(let msg):
                    s.lastMsg = Date()
                    if !s.connected { s.connected = true; s.setStatus("підключено") }
                    if case .string(let text) = msg { s.handle(text) }
                    else if case .data(let d) = msg, let text = String(data: d, encoding: .utf8) { s.handle(text) }
                    s.receive(t, my)
                case .failure(let e):
                    s.failed(Net.short(e))
                }
            }
        }
    }

    private func failed(_ why: String) {
        let was = connected && haveData
        connected = false
        gen += 1
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        setStatus(why)
        if was { onLost?() }
        if Date().timeIntervalSince(openedAt) > 60 { fails = 0 }
        fails += 1
        let delay = Double(min(60, 2 * fails))
        let my = gen
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let s = self, s.gen == my, s.task == nil else { return }
            s.connect()
        }
    }

    func handle(_ text: String) {
        guard let n = StreamParse.alerts(text) else { return }
        let s2 = jsonText(n)
        let changed = data == nil || s2 != sig || !haveData
        data = n
        sig = s2
        haveData = true
        if changed { onChange?() }
    }
}

// ---------------- звук ----------------

/** Голосові фрази й сирена/відбій/балістика (WAV у ресурсах програми), по черзі. */
final class Sounds: NSObject, AVAudioPlayerDelegate {
    static let shared = Sounds()
    private var player: AVAudioPlayer?
    private var queue: [URL] = []
    private var gen = 0
    var playing: Bool { player?.isPlaying ?? false }

    func stop() { gen += 1; queue = []; player?.stop(); player = nil }

    private func res(_ name: String, _ sub: String? = nil) -> URL? { Bundle.main.url(forResource: name, withExtension: "wav", subdirectory: sub) }

    private func voice(_ keys: [String]) -> [URL] {
        let v = Settings.shared.voice
        if v == "off" { return [] }
        return keys.compactMap { res("\(v)_\($0)", "voice") }
    }

    private func play(_ urls: [URL]) {
        stop()
        queue = urls
        next(gen)
    }

    private func next(_ my: Int) {
        guard my == gen, !queue.isEmpty else { if my == gen { player = nil }; return }
        let u = queue.removeFirst()
        do {
            let p = try AVAudioPlayer(contentsOf: u)
            p.delegate = self
            player = p
            if !p.play() { next(my) }
        } catch { next(my) }
    }

    func audioPlayerDidFinishPlaying(_ p: AVAudioPlayer, successfully flag: Bool) {
        let my = gen
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.next(my) }
    }

    func alarm(_ threats: [String]) { play(voice(["alert"] + threats.prefix(4) + ["shelter"]) + [res("siren")].compactMap { $0 }) }
    func ballistic(_ started: Bool, _ threats: [String]) {
        play(voice([started ? "alert" : "attention", "ballistic_missiles"] + threats.filter { $0 != "ballistic_missiles" }.prefix(3) + ["now"])
             + [res("ballistic")].compactMap { $0 })
    }
    func newThreats(_ added: [String]) { play(voice(["attention"] + added)) }
    func clear() { play(voice(["clear"]) + [res("clear")].compactMap { $0 }) }
    func siren() { play([res("siren")].compactMap { $0 }) }
}

// ---------------- сповіщення ----------------

final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()
    private var available = false

    func setup() {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let c = UNUserNotificationCenter.current()
        c.delegate = self
        c.requestAuthorization(options: [.alert, .sound, .badge]) { ok, err in
            self.available = ok
            if let err = err { L("NOTIFY недоступні: \(err.localizedDescription)") }
        }
    }

    func post(_ title: String, _ body: String) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        if !available { legacy(title, body); return }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        if #available(macOS 12.0, *) { c.interruptionLevel = .timeSensitive }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "alert", content: c, trigger: nil))
    }

    /** Запасний шлях, якщо система не дозволила сучасні сповіщення (програма без підпису розробника Apple). */
    @available(macOS, deprecated: 11.0)
    private func legacy(_ title: String, _ body: String) {
        let n = NSUserNotification()
        n.title = title
        n.informativeText = body
        n.identifier = "alert-\(nowMs())"
        NSUserNotificationCenter.default.deliver(n)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent n: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive r: UNNotificationResponse, withCompletionHandler done: @escaping () -> Void) {
        DispatchQueue.main.async { AppDelegate.shared?.showWindow() }
        done()
    }
}

// ---------------- стеження ----------------

final class Monitor: ObservableObject {
    static let shared = Monitor()
    let settings = Settings.shared
    let engine = Engine()
    let stream = StreamClient()

    @Published var state: AlertState = .unknown
    @Published var level = ""
    @Published var threats: [String] = []
    @Published var since = 0
    @Published var clearedAt = 0
    @Published var source = ""
    @Published var note = ""
    @Published var disagree = false
    @Published var error: String?
    @Published var statusTime = ""
    @Published var serverRoot: JObj?

    private var firstCheck = true
    private var lastPlace = ""
    private var timer: Timer?
    private var intervalMs = 15_000
    private var busy = false
    private var activity: NSObjectProtocol?
    private let d = UserDefaults.standard

    func start() {
        L("START v\(appVersion) (\(appBuild)) server=\(settings.server) place=\(settings.placeKey)")
        // App Nap не повинен «присипляти» стеження
        activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiatedAllowingIdleSystemSleep, .latencyCritical], reason: "Стеження за повітряною тривогою")
        state = AlertState(rawValue: d.integer(forKey: "state")) ?? .unknown
        stream.url = StreamParse.streamUrl(settings.server)
        stream.onChange = { [weak self] in self?.apply() }
        stream.onLost = { [weak self] in self?.checkNow() }
        stream.start()
        Reporter.send("start", ["place": placeKeyLong()])
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            L("WAKE")
            self?.stream.restart()
            self?.checkNow()
        }
        Timer.scheduledTimer(withTimeInterval: testMode ? 10 : 1800, repeats: true) { [weak self] _ in
            guard let s = self else { return }
            Reporter.send("alive", ["place": s.placeKeyLong(), "uastream": s.stream.fresh() ? "1" : "0", "st": "\(s.state)", "srv_ok": s.engine.lastServerError == nil ? "1" : "0"])
        }
        refreshConfigIfDue()
        checkNow()
    }

    private func placeKeyLong() -> String { "\(settings.region)|\(settings.raion)|\(settings.raion != 0 ? AlertSource.raionName(settings.raion) ?? "" : "")" }

    func schedule() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: Double(intervalMs) / 1000, repeats: false) { [weak self] _ in self?.checkNow() }
    }

    /** Місце змінили в налаштуваннях. */
    func placeChanged() {
        firstCheck = true
        for k in ["knownState", "knownThreats", "knownLevel", "since", "clearedAt"] { d.removeObject(forKey: k) }
        since = 0; clearedAt = 0
        state = .unknown
        checkNow()
    }

    func checkNow() {
        if busy { return }
        busy = true
        let server = settings.server
        Net.get(server + "/status") { [weak self] res in
            DispatchQueue.main.async {
                guard let s = self else { return }
                s.busy = false
                switch res {
                case .success(let data):
                    if let t = String(data: data, encoding: .utf8), let j = jsonObject(t), j["air"] != nil || j["neptun"] != nil || j["ua"] != nil {
                        s.engine.serverOk(j, nowMs())
                        s.serverRoot = j
                    } else { s.engine.serverFailed("неочікувана відповідь сервера") }
                case .failure(let e): s.engine.serverFailed("\(e)")
                }
                s.apply()
                s.schedule()
                Updater.shared.checkIfDue()
            }
        }
    }

    /** Зведення (сервер + потік) і реакція: звук, сповіщення. */
    func apply() {
        let region = settings.region, raion = settings.raion, partial = settings.partial
        let place = settings.placeKey
        if place != lastPlace { lastPlace = place; firstCheck = true }
        let tf = DateFormatter(); tf.dateFormat = "HH:mm:ss"
        let r: AlertResult
        do {
            guard let x = try engine.compute(stream.fresh() ? stream.data : nil, region, raion, partial, nowMs()) else {
                error = engine.lastServerError ?? "немає даних"
                return
            }
            r = x
            if r.pollS >= 5 && r.pollS <= 120 { intervalMs = r.pollS * 1000 }
        } catch {
            self.error = "\(error)"
            return
        }

        let old: AlertState = firstCheck ? .unknown : (AlertState(rawValue: d.integer(forKey: "knownState")) ?? .unknown)
        let oldThreats = (d.string(forKey: "knownThreats") ?? "").split(separator: ",").map(String.init)
        let oldLevel = old == .alert ? (d.string(forKey: "knownLevel") ?? "") : ""
        var since = d.integer(forKey: "since"), clearedAt = d.integer(forKey: "clearedAt")
        let now = nowMs()
        if r.state == .alert {
            if r.since > 0 { since = r.since } else if old != .alert { since = firstCheck ? 0 : now }
            clearedAt = 0
        } else if r.state == .clear {
            since = 0
            if old == .alert { clearedAt = now }
        }
        if r.state != state || r.level != level || r.threats != threats {
            L("STATE \(r.state == .alert ? "Alert" : r.state == .clear ? "Clear" : "Unknown") level=\(r.level) threats=\(r.threats.joined(separator: ",")) src=\(r.source)" + (r.note.isEmpty ? "" : " note=\(r.note)"))
            Reporter.send("state", ["st": r.state == .alert ? "Alert" : r.state == .clear ? "Clear" : "Unknown", "old": old == .alert ? "Alert" : old == .clear ? "Clear" : "Unknown",
                                    "lv": r.level, "th": r.threats.joined(separator: ","), "src": r.source, "note": r.note, "place": placeKeyLong(),
                                    "since": r.state == .alert && r.since > 0 ? ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: Double(r.since) / 1000)) : "",
                                    "first": firstCheck ? "1" : "0"])
        }
        state = r.state; level = r.level; threats = r.threats; source = r.source; note = r.note; disagree = r.disagree
        self.since = since; self.clearedAt = clearedAt; error = nil
        statusTime = tf.string(from: Date())
        d.set(r.state.rawValue, forKey: "state"); d.set(since, forKey: "since"); d.set(clearedAt, forKey: "clearedAt")
        AppDelegate.shared?.updateStatusItem()
        if r.state == .unknown { return }
        d.set(r.state.rawValue, forKey: "knownState"); d.set(r.threats.joined(separator: ","), forKey: "knownThreats"); d.set(r.level, forKey: "knownLevel")
        if firstCheck { firstCheck = false; return }   // при запуску звук не граємо, лише показуємо статус

        let act = Engine.decide(old, oldThreats, oldLevel, r)
        if act.kind == .none { return }
        let quiet = settings.quiet
        let names = ["", "ballistic", "alarm", "escalation", "newthreats", "clear"]
        L("SOUND \(names[act.kind.rawValue])" + (quiet ? " (лише сповіщення)" : ""))
        Reporter.send("sound", ["kind": names[act.kind.rawValue], "place": placeKeyLong()])
        let th = AlertSource.titles(r.threats)
        let pl = settings.place
        switch act.kind {
        case .ballistic:
            if !quiet { Sounds.shared.ballistic(old != .alert, r.threats) }
            Notifier.shared.post("БАЛІСТИКА!", "\(pl). Негайно в укриття!" + (th.isEmpty ? "" : "\n" + th))
        case .alarm:
            if !quiet { Sounds.shared.alarm(r.threats) }
            Notifier.shared.post(r.level == "yellow" ? "ПОВІТРЯНА ТРИВОГА — жовтий рівень" : "ПОВІТРЯНА ТРИВОГА!", "\(pl). Пройдіть в укриття!" + (th.isEmpty ? "" : "\n" + th))
        case .escalation:
            if !quiet { Sounds.shared.alarm(r.threats) }
            Notifier.shared.post("ЧЕРВОНИЙ РІВЕНЬ ТРИВОГИ!", "\(pl). Негайно в укриття!" + (th.isEmpty ? "" : "\n" + th))
        case .newThreats:
            if !quiet { Sounds.shared.newThreats(act.added) }
            Notifier.shared.post("Нова загроза", "\(pl): " + AlertSource.titles(act.added))
        case .clear:
            if !quiet { Sounds.shared.clear() }
            Notifier.shared.post("Відбій тривоги", pl)
        case .none: break
        }
        if act.kind != .clear && act.kind != .newThreats && settings.showOnAlert { AppDelegate.shared?.showWindow() }
    }

    private func refreshConfigIfDue() {
        if env["TREVOGA_SERVER"] != nil { return }
        let last = d.double(forKey: "configAt")
        if Date().timeIntervalSince1970 - last < 6 * 3600 { return }
        d.set(Date().timeIntervalSince1970, forKey: "configAt")
        Net.get("https://raw.githubusercontent.com/repaulshadow/spokiy/main/config.json?t=\(nowMs())") { res in
            guard case .success(let data) = res, let t = String(data: data, encoding: .utf8), let j = jsonObject(t),
                  let s = (j["server"] as? String)?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return }
            DispatchQueue.main.async {
                if s != self.settings.server {
                    self.d.set(s, forKey: "server")
                    self.stream.url = StreamParse.streamUrl(s)
                    self.stream.restart()
                }
            }
        }
    }
}
