import Foundation

// Зведення джерел тривог — та сама логіка, що й в Android-версії (AlertSource.java), перевіряється тими ж тестами.

typealias JObj = [String: Any]
typealias JArr = [Any]

func jstr(_ a: JArr?, _ i: Int, _ def: String = "") -> String {
    guard let a = a, i < a.count else { return def }
    let v = a[i]
    if v is NSNull { return def }
    if let s = v as? String { return s }
    return "\(v)"
}
func jarr(_ a: JArr?, _ i: Int) -> JArr? { guard let a = a, i < a.count else { return nil }; return a[i] as? JArr }
func jstr(_ o: JObj?, _ k: String, _ def: String = "") -> String {
    guard let v = o?[k], !(v is NSNull) else { return def }
    if let s = v as? String { return s }
    return "\(v)"
}
func jsonObject(_ text: String) -> JObj? {
    guard let d = text.data(using: .utf8) else { return nil }
    return (try? JSONSerialization.jsonObject(with: d)) as? JObj
}
func jsonText(_ o: Any) -> String {
    guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { return "" }
    return String(data: d, encoding: .utf8) ?? ""
}

enum AlertState: Int { case unknown = 0, alert = 1, clear = 2 }

final class AlertResult {
    var state: AlertState = .unknown
    var threats: [String] = []
    var since: Int = 0          // мс, 0 — невідомо
    var source = ""
    var note = ""
    var disagree = false
    var level = ""              // "red" | "yellow" | ""
    var pollS = 0
    var ainState: AlertState = .unknown, nepState: AlertState = .unknown, uaState: AlertState = .unknown
}

struct AlertError: Error, CustomStringConvertible {
    let description: String
    init(_ s: String) { description = s }
}

enum AlertSource {
    static let regions = [
        "Автономна Республіка Крим", "Волинська область", "Вінницька область",
        "Дніпропетровська область", "Донецька область", "Житомирська область",
        "Закарпатська область", "Запорізька область", "Івано-Франківська область",
        "м. Київ", "Київська область", "Кіровоградська область", "Луганська область",
        "Львівська область", "Миколаївська область", "Одеська область",
        "Полтавська область", "Рівненська область", "м. Севастополь", "Сумська область",
        "Тернопільська область", "Харківська область", "Херсонська область",
        "Хмельницька область", "Черкаська область", "Чернівецька область",
        "Чернігівська область",
    ]

    static func threatTitle(_ t: String) -> String {
        switch t {
        case "ballistic_missiles": return "Балістика"
        case "cruise_missiles": return "Крилаті ракети"
        case "unspecified_missiles": return "Ракети"
        case "drones": return "Дрони"
        case "guided_aerial_bombs": return "КАБи"
        case "tactic_aircraft_activity": return "Тактична авіація"
        case "strategic_aircraft_activity": return "Стратегічна авіація"
        case "mig31k_departure": return "Зліт МіГ-31К"
        case "artillery_shelling": return "Артобстріл"
        case "urban_fights": return "Вуличні бої"
        case "air_defense": return "Робота ППО"
        case "chemical": return "Хімічна загроза"
        case "nuclear": return "Радіаційна загроза"
        default: return "Інша загроза"
        }
    }

    static func titles(_ types: [String]) -> String { types.map(threatTitle).joined(separator: " · ") }

    static func raionName(_ uid: Int) -> String? {
        if let i = Locations.raionUid.firstIndex(of: uid) { return Locations.raionName[i] }
        return nil
    }

    static func raions(of region: String) -> [(uid: Int, name: String)] {
        let obl = Locations.oblastUid(region)
        var out: [(Int, String)] = []
        for i in 0..<Locations.raionUid.count where Locations.raionOblast[i] == obl { out.append((Locations.raionUid[i], Locations.raionName[i])) }
        return out
    }

    static let order = ["ballistic_missiles", "cruise_missiles", "unspecified_missiles", "mig31k_departure", "guided_aerial_bombs", "drones",
                        "strategic_aircraft_activity", "tactic_aircraft_activity", "air_defense", "artillery_shelling", "urban_fights", "chemical", "nuclear"]

    private static func toInt(_ o: Any?) -> Int {
        guard let o = o, !(o is NSNull) else { return -1 }
        if let n = o as? NSNumber { return n.intValue }
        if let s = o as? String, let d = Double(s) { return Int(d) }
        return -1
    }

    private static func charAt(_ str: [Character], _ uid: Int) throws -> Character {
        if uid < 0 || uid >= str.count || str[uid] == " " { throw AlertError("неочікувана відповідь сервера") }
        return Character(str[uid].uppercased())
    }

    private static let isoFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f
    }()

    static func parseIso(_ s: String) -> Int {
        var x = s.replacingOccurrences(of: "Z", with: "")
        if let dot = x.firstIndex(of: ".") { x = String(x[..<dot]) }
        if let plus = x.range(of: "+", options: .backwards), x.distance(from: x.startIndex, to: plus.lowerBound) > 10 { x = String(x[..<plus.lowerBound]) }
        guard let d = isoFmt.date(from: x) else { return 0 }
        return Int(d.timeIntervalSince1970 * 1000)
    }

    private static let renamed: [String: String] = [
        "володимир-волинський район": "володимирський район",
        "новомосковський район": "самарівський район",
        "новоград-волинський район": "звягельський район",
        "сєвєродонецький район": "сіверськодонецький район",
        "червоноградський район": "шептицький район",
        "красноградський район": "берестинський район",
    ]

    /** Нормалізація назв для порівняння з NEPTUN: апостроф, «м.», Крим, перейменування. */
    static func norm(_ x: String?) -> String {
        guard let x = x else { return "" }
        var t = x.trimmingCharacters(in: .whitespaces).lowercased()
            .replacingOccurrences(of: "\u{2019}", with: "'").replacingOccurrences(of: "\u{02BC}", with: "'").replacingOccurrences(of: "`", with: "'")
        if t.hasPrefix("м. ") { t = String(t.dropFirst(3)) }
        if t == "ар крим" || t == "автономна республіка крим" { t = "крим" }
        return renamed[t] ?? t
    }

    /** «reasons» (напр. «Дронова загроза (жовтий рівень)») → типи загроз як у alerts.in.ua. */
    static func reasonTypes(_ reasons: JArr?) -> [String] {
        let txt = (reasons ?? []).map { ($0 as? String) ?? "" }.joined(separator: " ").lowercased()
        var t: [String] = []
        if txt.contains("баліст") { t.append("ballistic_missiles") }
        if txt.contains("крилат") { t.append("cruise_missiles") }
        if txt.contains("ракет") && t.isEmpty { t.append("unspecified_missiles") }
        if txt.contains("міг") || txt.contains("mig") { t.append("mig31k_departure") }
        if txt.contains("каб") || txt.contains("авіабомб") || txt.contains("керован") { t.append("guided_aerial_bombs") }
        if txt.contains("дрон") || txt.contains("бпла") || txt.contains("шахед") || txt.contains("безпілот") { t.append("drones") }
        if txt.contains("артобстріл") || txt.contains("артилер") { t.append("artillery_shelling") }
        return t
    }

    /** Повідомлення потоку (формат NEPTUN: raions/oblasts/communities) → формат сервера {r, o, c}. */
    static func streamToNep(_ data: JObj?) -> JObj? {
        guard let data = data else { return nil }
        let rs = data["raions"] as? [Any], os = data["oblasts"] as? [Any], cs = data["communities"] as? [Any]
        if rs == nil && os == nil { return nil }
        var r: JArr = [], o: JArr = [], c: JArr = []
        for x in rs ?? [] { guard let x = x as? JObj else { continue }
            r.append([jstr(x, "oblast"), jstr(x, "name"), jstr(x, "since"), jstr(x, "level").lowercased(), reasonTypes(x["reasons"] as? JArr)]) }
        for x in os ?? [] { guard let x = x as? JObj else { continue }
            o.append([jstr(x, "name"), jstr(x, "since"), jstr(x, "level").lowercased(), reasonTypes(x["reasons"] as? JArr)]) }
        for x in cs ?? [] { guard let x = x as? JObj else { continue }
            c.append([jstr(x, "oblast"), jstr(x, "raion"), jstr(x, "name"), jstr(x, "since"), jstr(x, "level").lowercased(), [String]()]) }
        return ["r": r, "o": o, "c": c]
    }

    private static func earliest(_ cur: Int, _ t: Int) -> Int { t > 0 && (cur == 0 || t < cur) ? t : cur }
    private static func sev(_ lv: String) -> Int { lv == "red" ? 2 : lv == "yellow" ? 1 : 0 }
    private static func maxLevel(_ a: String, _ b: String) -> String { sev(b) > sev(a) ? b : a }
    private static func addTypes(_ dst: inout [String], _ t: JArr?) {
        for x in t ?? [] { if let s = x as? String, !s.isEmpty, !dst.contains(s) { dst.append(s) } }
    }

    struct SrcEval { var state: AlertState = .clear; var level = ""; var threats: [String] = []; var since = 0 }

    /** Джерело у форматі NEPTUN: o — області, r — райони, c — громади (часткова тривога). */
    static func evalNepLike(_ nep: JObj, _ oblN: String, _ raionN: String, _ obl: Int, _ raion: Int, _ partial: Bool) -> SrcEval {
        var res = SrcEval()
        var oblWide = false, raionHit = false, partialHit = false
        var alertedInObl = 0
        for e in (nep["o"] as? JArr) ?? [] {
            guard let e = e as? JArr, norm(jstr(e, 0)) == oblN else { continue }
            oblWide = true
            res.since = earliest(res.since, parseIso(jstr(e, 1)))
            res.level = maxLevel(res.level, jstr(e, 2))
            addTypes(&res.threats, jarr(e, 3))
        }
        for e in (nep["r"] as? JArr) ?? [] {
            guard let e = e as? JArr, norm(jstr(e, 0)) == oblN else { continue }
            alertedInObl += 1
            let mine = raion != 0 && norm(jstr(e, 1)) == raionN
            if mine { raionHit = true }
            if mine || raion == 0 {
                res.since = earliest(res.since, parseIso(jstr(e, 2)))
                res.level = maxLevel(res.level, jstr(e, 3))
                addTypes(&res.threats, jarr(e, 4))
            }
        }
        if partial {
            for e in (nep["c"] as? JArr) ?? [] {
                guard let e = e as? JArr, norm(jstr(e, 0)) == oblN else { continue }
                if raion != 0 && norm(jstr(e, 1)) != raionN { continue }
                partialHit = true
                res.since = earliest(res.since, parseIso(jstr(e, 3)))
                res.level = maxLevel(res.level, jstr(e, 4))
            }
        }
        let raionsInObl = Locations.raionOblast.filter { $0 == obl }.count
        let allRaions = raionsInObl > 0 && alertedInObl >= raionsInObl
        if raion != 0 { res.state = (oblWide || raionHit || partialHit) ? .alert : .clear }
        else { res.state = (oblWide || allRaions || (partial && (alertedInObl > 0 || partialHit))) ? .alert : .clear }
        return res
    }

    /**
     * ignoreAinBefore / ignoreNepBefore (мс): офіційне джерело щойно дало відбій — тривоги alerts.in.ua / NEPTUN,
     * що почалися не пізніше цього часу, вважаємо застарілими.
     */
    static func fromRoot(_ root: JObj, _ region: String, _ raion: Int, _ partial: Bool, _ ignoreAinBefore: Int = 0, _ ignoreNepBefore: Int = 0) throws -> AlertResult {
        let obl = Locations.oblastUid(region)
        if obl < 0 { throw AlertError("невідома область") }
        let air: String? = root["air"] as? String
        let nep = root["neptun"] as? JObj
        let uaD = root["ua"] as? JObj
        if air == nil && nep == nil && uaD == nil { throw AlertError("сервер без даних") }

        let raionName = raion != 0 ? self.raionName(raion) : nil
        let oblN = norm(region), raionN = norm(raionName)
        let r = AlertResult()
        var ain: AlertState = .unknown, nepState: AlertState = .unknown
        var ainSince = 0
        var ainLevel = ""
        var ainThreats: [String] = []
        var ainPartial = false

        if let air = air {
            do {
                let chars = Array(air)
                let o = try charAt(chars, obl)
                let c = raion != 0 ? try charAt(chars, raion) : o
                if o == "A" || c == "A" { ain = .alert }
                else if c == "P" { ain = partial ? .alert : .clear; ainPartial = true }
                else if c == "N" { ain = .clear }
                for x in (root["alerts"] as? JArr) ?? [] {
                    guard let a = x as? JObj else { continue }
                    let u = toInt(a["u"]), ao = toInt(a["o"])
                    let on = a["on"] as? String, rn = a["r"] as? String
                    let inObl = u == obl || ao == obl || (on != nil && norm(on) == oblN)
                    if !inObl { continue }
                    if raion != 0 && u != obl && u != raion && (rn == nil || norm(rn) != raionN) { continue }
                    if let s = a["s"] as? String { ainSince = earliest(ainSince, parseIso(s)) }
                    if let l = a["l"] as? String { ainLevel = maxLevel(ainLevel, l) }
                    addTypes(&ainThreats, a["t"] as? JArr)
                }
            } catch { ain = .unknown }
        }

        let nepE = nep.map { evalNepLike($0, oblN, raionN, obl, raion, partial) }
        let uaE = uaD.map { evalNepLike($0, oblN, raionN, obl, raion, partial) }
        let uaState = uaE?.state ?? .unknown
        if let n = nepE { nepState = n.state }
        var ainIgnored = false, nepIgnored = false
        if ain == .alert && ignoreAinBefore > 0 && (ainSince == 0 || ainSince <= ignoreAinBefore) { ain = .clear; ainPartial = false; ainIgnored = true }
        if let n = nepE, n.state == .alert, ignoreNepBefore > 0, n.since == 0 || n.since <= ignoreNepBefore { nepState = .clear; nepIgnored = true }

        if ain == .unknown && nepState == .unknown && uaState == .unknown { throw AlertError("неочікувана відповідь сервера") }

        r.state = (ain == .alert || nepState == .alert || uaState == .alert) ? .alert : .clear
        if r.state == .alert {
            var lv = ""
            if ain == .alert || ainPartial { lv = maxLevel(lv, ainLevel) }
            if nepState == .alert, let n = nepE { lv = maxLevel(lv, n.level) }
            if uaState == .alert, let u = uaE { lv = maxLevel(lv, u.level) }
            r.level = lv.isEmpty ? "red" : lv
            for t in ainThreats where !r.threats.contains(t) { r.threats.append(t) }
            if nepState == .alert, let n = nepE { for t in n.threats where !r.threats.contains(t) { r.threats.append(t) } }
            if uaState == .alert, let u = uaE { for t in u.threats where !r.threats.contains(t) { r.threats.append(t) } }
            if ain == .alert || ainPartial { r.since = earliest(r.since, ainSince) }
            if nepState == .alert, let n = nepE { r.since = earliest(r.since, n.since) }
            if uaState == .alert, let u = uaE { r.since = earliest(r.since, u.since) }
        }
        var names: [String] = [], alerting: [String] = []
        var known = 0, alerts = 0
        if ain != .unknown { names.append("alerts.in.ua"); known += 1; if ain == .alert { alerts += 1; alerting.append("alerts.in.ua") } }
        if nepState != .unknown { names.append("NEPTUN"); known += 1; if nepState == .alert { alerts += 1; alerting.append("NEPTUN") } }
        if uaState != .unknown { names.append("UkraineAlarm"); known += 1; if uaState == .alert { alerts += 1; alerting.append("UkraineAlarm") } }
        r.source = names.joined(separator: " + ")
        r.ainState = ain
        r.nepState = nepE?.state ?? .unknown
        r.uaState = uaState
        if (ainIgnored || nepIgnored) && r.state == .clear {
            r.note = nepIgnored ? "відбій за UkraineAlarm (інші джерела ще оновлюються)" : "відбій за NEPTUN (alerts.in.ua ще оновлюється)"
        } else if known >= 2 {
            if alerts == 0 || alerts == known { r.note = "джерела збігаються" }
            else if ainPartial && alerts == known - 1 && ain != .alert {
                r.note = "alerts.in.ua: тривога в частині " + (raion != 0 ? "району" : "області") + ", інші: у всьому"
            } else {
                r.note = "джерела розходяться: тривогу показує " + (alerting.count == 1 ? "лише " : "") + alerting.joined(separator: " і ")
                r.disagree = true
            }
        }
        if r.state != .alert { r.threats = []; r.since = 0 }
        r.threats = r.threats.enumerated().sorted { a, b in
            let ia = order.firstIndex(of: a.element) ?? 99, ib = order.firstIndex(of: b.element) ?? 99
            return ia != ib ? ia < ib : a.offset < b.offset   // стабільно, як у Java
        }.map { $0.element }
        return r
    }
}
