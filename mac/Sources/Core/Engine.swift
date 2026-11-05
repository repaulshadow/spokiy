import Foundation

/**
 * Зведення джерел (як Engine.java): остання відповідь сервера + свіжий потік UkraineAlarm;
 * швидкий офіційний відбій; не змішуємо свіже зі старим.
 */
final class Engine {
    var lastRoot: JObj?
    var lastRootAt = 0
    var lastServerError: String?
    private var uaSeenAt = 0
    private var uaLast: JObj?
    private var prevUa: AlertState = .unknown
    private var uaClearedAt = 0
    private var placeKey = ""

    static let rootFreshMs = 90_000, uaClearWindowMs = 180_000, nepLagMs = 90_000

    func serverOk(_ root: JObj, _ now: Int) { lastRoot = root; lastRootAt = now; lastServerError = nil }
    func serverFailed(_ err: String) { lastServerError = err }

    /** nil — даних немає (треба запасне джерело). ua — дані потоку, якщо він свіжий, інакше nil. */
    func compute(_ ua: JObj?, _ region: String, _ raion: Int, _ partial: Bool, _ now: Int) throws -> AlertResult? {
        var root: JObj = [:]
        let rootFresh = lastRoot != nil && now - lastRootAt < Engine.rootFreshMs
        if rootFresh, let lr = lastRoot { root = lr }
        if let ua = ua { root["ua"] = ua; uaSeenAt = now; uaLast = ua }
        else if lastRootAt < uaSeenAt {
            // потік обірвався, а його дані новіші за відповідь сервера: беремо останні дані потоку (до 90 с) —
            // інакше лишились би лише інші джерела, і міг би прозвучати хибний відбій, а потім сирена знову
            if let l = uaLast, now - uaSeenAt < Engine.rootFreshMs { root["ua"] = l } else { root.removeValue(forKey: "ua") }
        } else if root["ua"] is JObj { uaSeenAt = lastRootAt }
        let uaKnown = root["ua"] is JObj
        if root["air"] == nil && !(root["neptun"] is JObj) && !uaKnown { return nil }

        let key = "\(region)|\(raion)|\(partial)"
        if key != placeKey { placeKey = key; prevUa = .unknown; uaClearedAt = 0 }
        if uaClearedAt > 0 && now - uaClearedAt > Engine.uaClearWindowMs { uaClearedAt = 0 }

        var r = try merge(root, region, raion, partial)
        if uaKnown {
            // офіційний відбій UkraineAlarm — не чекаємо NEPTUN (відстає до ~2 хв) і alerts.in.ua
            if prevUa == .alert && r.uaState == .clear { uaClearedAt = now; r = try merge(root, region, raion, partial) }
            else if r.uaState == .alert { uaClearedAt = 0 }
            prevUa = r.uaState
        }
        r.pollS = rootFresh ? ((lastRoot?["poll"] as? NSNumber)?.intValue ?? 0) : 0
        if ua != nil { r.source = r.source.replacingOccurrences(of: "UkraineAlarm", with: "UkraineAlarm наживо") }
        if let e = lastServerError { r.note = "сервер недоступний (\(e))" + (r.note.isEmpty ? "" : " · " + r.note) }
        return r
    }

    private func merge(_ root: JObj, _ region: String, _ raion: Int, _ partial: Bool) throws -> AlertResult {
        // NEPTUN запізнюється: його «нова» тривога в перші 90 с після офіційного відбою — зазвичай стара
        try AlertSource.fromRoot(root, region, raion, partial, uaClearedAt, uaClearedAt > 0 ? uaClearedAt + Engine.nepLagMs : 0)
    }

    // ---------------- переходи: що озвучити ----------------
    enum Kind: Int { case none = 0, ballistic, alarm, escalation, newThreats, clear }
    struct Action { var kind: Kind = .none; var added: [String] = [] }

    /** old — останній ВІДОМИЙ стан (unknown на першій перевірці). */
    static func decide(_ old: AlertState, _ oldThreats: [String], _ oldLevel: String, _ r: AlertResult) -> Action {
        var a = Action()
        if r.state == .unknown || old == .unknown { return a }
        if r.state == .alert { for t in r.threats where !oldThreats.contains(t) { a.added.append(t) } }
        if a.added.contains("ballistic_missiles") { a.kind = .ballistic }
        else if old != .alert && r.state == .alert { a.kind = .alarm }
        else if old == .alert && r.state == .alert && oldLevel == "yellow" && r.level == "red" { a.kind = .escalation }
        else if old == .alert && r.state == .alert && !a.added.isEmpty { a.kind = .newThreats }
        else if old == .alert && r.state == .clear { a.kind = .clear }
        return a
    }
}

/** Повідомлення потоку сервера (WebSocket): лише «alerts» у форматі NEPTUN. */
enum StreamParse {
    static func alerts(_ text: String) -> JObj? {
        let head = String(text.prefix(200))
        if let m = head.range(of: #""type"\s*:\s*"([A-Za-z_]+)""#, options: .regularExpression) {
            if !head[m].contains("\"alerts\"") { return nil }
        } else if !text.contains("\"raions\"") { return nil }
        guard let j = jsonObject(text) else { return nil }
        return AlertSource.streamToNep((j["data"] as? JObj) ?? j)
    }

    static func streamUrl(_ server: String) -> String {
        var s = server.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        return s.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://") + "/stream"
    }
}
