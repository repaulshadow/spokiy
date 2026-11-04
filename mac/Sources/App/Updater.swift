import AppKit
import CryptoKit
import Foundation

/**
 * Оновлення: сервер віддає version.json (розділ «mac»: версія, номер збірки, архів, sha256).
 * Нова версія завантажується, перевіряється за sha256 і ставиться на місце поточної програми, потім перезапуск.
 * Під час тривоги не оновлюємось.
 */
final class Updater: ObservableObject {
    static let shared = Updater()
    @Published var available: String?       // назва нової версії
    @Published var status = ""
    private var info: JObj?
    private var lastCheck = Date.distantPast
    private var busy = false

    func checkIfDue() {
        if testMode || env["TREVOGA_NO_UPDATE"] == "1" { return }
        if Date().timeIntervalSince(lastCheck) < 6 * 3600 { return }
        check(manual: false)
    }

    func check(manual: Bool) {
        lastCheck = Date()
        if manual { status = "Перевіряю…" }
        Net.get(Settings.shared.server + "/version.json?t=\(nowMs())") { res in
            DispatchQueue.main.async {
                switch res {
                case .failure(let e): if manual { self.status = "Не вдалося перевірити: \(e)" }
                case .success(let data):
                    guard let t = String(data: data, encoding: .utf8), let j = jsonObject(t), let m = j["mac"] as? JObj else {
                        if manual { self.status = "На сервері ще немає версії для Mac" }
                        return
                    }
                    let build = (m["build"] as? NSNumber)?.intValue ?? 0
                    if build > appBuild {
                        self.info = m
                        self.available = jstr(m, "version")
                        self.status = "Доступна версія \(jstr(m, "version"))"
                        if !manual && Monitor.shared.state != .alert { self.install() }
                    } else {
                        self.available = nil
                        self.status = manual ? "У вас найновіша версія (\(appVersion))" : ""
                    }
                }
            }
        }
    }

    func install() {
        guard let m = info, !busy else { return }
        if Monitor.shared.state == .alert { status = "Оновлення — після відбою"; return }
        busy = true
        status = "Завантажую оновлення…"
        var url = jstr(m, "url")
        if url.hasPrefix("/") { url = Settings.shared.server + url }
        let want = jstr(m, "sha256").lowercased()
        Net.get(url) { res in
            DispatchQueue.main.async {
                self.busy = false
                switch res {
                case .failure(let e): self.status = "Не вдалося завантажити: \(e)"
                case .success(let data):
                    let got = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                    if !want.isEmpty && got != want { self.status = "Пошкоджений файл оновлення (sha256)"; return }
                    do { try self.replace(with: data) } catch { self.status = "Оновлення не вдалося: \(error)" }
                }
            }
        }
    }

    private func run(_ path: String, _ args: [String]) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        try p.run()
        p.waitUntilExit()
        if p.terminationStatus != 0 { throw AlertError("\(path) завершився з кодом \(p.terminationStatus)") }
    }

    private func replace(with zip: Data) throws {
        let fm = FileManager.default
        let tmp = fm.temporaryDirectory.appendingPathComponent("trevoga-update-\(nowMs())")
        try fm.createDirectory(at: tmp, withIntermediateDirectories: true)
        let z = tmp.appendingPathComponent("Trevoga.zip")
        try zip.write(to: z)
        try run("/usr/bin/ditto", ["-x", "-k", z.path, tmp.path])
        guard let app = try fm.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil).first(where: { $0.pathExtension == "app" }) else {
            throw AlertError("в архіві немає програми")
        }
        let cur = Bundle.main.bundleURL
        L("UPDATE \(appVersion) → \(available ?? "?") (\(cur.path))")
        _ = try fm.replaceItemAt(cur, withItemAt: app, backupItemName: nil, options: [])
        status = "Оновлено — перезапускаю…"
        // перезапуск: нова копія стартує, коли ця завершиться
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "sleep 1; /usr/bin/open \"\(cur.path)\""]
        try p.run()
        NSApp.terminate(nil)
    }
}
