import Foundation

// Автотести логіки Mac-версії — ті самі випадки, що й android/test/AndroidLogicTests.java. Код виходу 0 — усе гаразд.

var passed = 0, failed = 0
func check(_ c: Bool, _ name: String) { if c { passed += 1 } else { failed += 1; print("FAIL: \(name)") } }
func eq<T: Equatable>(_ e: T, _ a: T, _ name: String) { if e == a { passed += 1 } else { failed += 1; print("FAIL: \(name) — очікувалось «\(e)», отримано «\(a)»") } }

let OD = "Одеська область", ODR = "Одеський район", IZM = "Ізмаїльський район"
func uid(_ raion: String) -> Int { Locations.raionUid[Locations.raionName.firstIndex(of: raion)!] }
func iso(_ ms: Int) -> String {
    let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC")
    f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

final class Root {
    var air = Array(repeating: Character("N"), count: 2000)
    var alerts: JArr = [], nr: JArr = [], no: JArr = []
    var ur: JArr?, uo: JArr?, uc: JArr?
    @discardableResult func ain(_ u: Int, _ raion: String?, _ level: String, _ since: String, _ t: String...) -> Root {
        air[u] = "A"
        alerts.append(["u": u, "o": u, "on": OD, "r": raion ?? NSNull(), "s": since, "l": level, "t": t] as JObj); return self
    }
    @discardableResult func partialAin(_ u: Int) -> Root { air[u] = "P"; return self }
    @discardableResult func nep(_ obl: String, _ raion: String, _ level: String, _ since: String, _ t: String...) -> Root {
        nr.append([obl, raion, since, level, t] as JArr); return self
    }
    @discardableResult func nepObl(_ obl: String, _ level: String) -> Root { no.append([obl, iso(0), level, [String]()] as JArr); return self }
    @discardableResult func ua() -> Root { if ur == nil { ur = []; uo = []; uc = [] }; return self }
    @discardableResult func ua(_ obl: String, _ raion: String, _ level: String, _ since: String) -> Root { ua(); ur!.append([obl, raion, since, level, [String]()] as JArr); return self }
    @discardableResult func uaObl(_ obl: String, _ level: String, _ since: String) -> Root { ua(); uo!.append([obl, since, level, [String]()] as JArr); return self }
    @discardableResult func uaCom(_ obl: String, _ raion: String, _ name: String, _ level: String, _ since: String) -> Root {
        ua(); uc!.append([obl, raion, name, since, level, [String]()] as JArr); return self
    }
    func build() -> JObj {
        var r: JObj = ["air": String(air), "alerts": alerts, "neptun": ["r": nr, "o": no] as JObj, "poll": 10]
        if let ur = ur { r["ua"] = ["r": ur, "o": uo!, "c": uc!] as JObj }
        // через JSON — щоб типи були такі ж, як у справжній відповіді сервера
        return jsonObject(jsonText(r))!
    }
}

func m(_ r: Root, _ raion: String?, _ partial: Bool) -> AlertResult { try! AlertSource.fromRoot(r.build(), OD, raion == nil ? 0 : uid(raion!), partial) }

let t0 = Int(Date().timeIntervalSince1970 * 1000)
let T0 = iso(t0 - 600_000)

// norm
eq(AlertSource.norm("Кам'янський район"), AlertSource.norm("Кам’янський район"), "апостроф")
eq("київ", AlertSource.norm("м. Київ"), "м. Київ")
eq("крим", AlertSource.norm("АР Крим"), "Крим")
eq(AlertSource.norm("Шептицький район"), AlertSource.norm("Червоноградський район"), "перейменування Шептицький")
eq(AlertSource.norm("Самарівський район"), AlertSource.norm("Новомосковський район"), "перейменування Самарівський")
eq(AlertSource.norm("Звягельський район"), AlertSource.norm("Новоград-Волинський район"), "перейменування Звягельський")

// merge
var r = m(Root(), ODR, false)
eq(AlertState.clear, r.state, "усе тихо")
eq("джерела збігаються", r.note, "усе тихо: примітка")
r = m(Root().nep(OD, ODR, "yellow", T0, "drones"), ODR, false)
eq(AlertState.alert, r.state, "лише NEPTUN: тривога")
eq("yellow", r.level, "лише NEPTUN: жовтий")
check(r.disagree, "лише NEPTUN: розбіжність")
r = m(Root().ain(uid(ODR), ODR, "yellow", T0, "drones").nep(OD, ODR, "red", T0, "unspecified_missiles"), ODR, false)
eq("red", r.level, "рівень — найвищий")
check(r.threats.contains("drones") && r.threats.contains("unspecified_missiles"), "загрози — об'єднання")
r = m(Root().nep(OD, IZM, "red", T0), ODR, false)
eq(AlertState.clear, r.state, "сусідній район — тиша")
r = m(Root().nep("Миколаївська область", ODR, "red", T0), ODR, false)
eq(AlertState.clear, r.state, "район з іншої області — тиша")
r = m(Root().nepObl(OD, "red"), ODR, false)
eq(AlertState.alert, r.state, "тривога по всій області")
r = m(Root().partialAin(uid(ODR)), ODR, false)
eq(AlertState.clear, r.state, "часткова без галочки")
r = m(Root().partialAin(uid(ODR)), ODR, true)
eq(AlertState.alert, r.state, "часткова з галочкою")
r = try! AlertSource.fromRoot(Root().nep("Львівська область", "Червоноградський район", "red", T0).build(), "Львівська область", uid("Шептицький район"), false)
eq(AlertState.alert, r.state, "стара назва району в NEPTUN")
r = m(Root().ain(uid(ODR), ODR, "red", T0, "drones", "ballistic_missiles"), ODR, false)
eq("ballistic_missiles", r.threats.first ?? "", "балістика першою")

// UkraineAlarm
r = m(Root().ua(), ODR, false)
eq(AlertState.clear, r.uaState, "UA: тиша")
check(r.source.contains("UkraineAlarm"), "UA: джерело в переліку")
r = m(Root().ua(OD, ODR, "yellow", T0), ODR, false)
eq(AlertState.alert, r.state, "UA: тривога лише з UkraineAlarm")
check(r.note.contains("UkraineAlarm"), "UA: розбіжність названо")
r = m(Root().uaObl(OD, "red", T0), ODR, false)
eq(AlertState.alert, r.state, "UA: область")
r = m(Root().ua(OD, IZM, "red", T0), ODR, false)
eq(AlertState.clear, r.state, "UA: сусідній район")
r = m(Root().uaCom(OD, ODR, "Х", "red", T0), ODR, false)
eq(AlertState.clear, r.state, "UA: громада без галочки")
r = m(Root().uaCom(OD, ODR, "Х", "red", T0), ODR, true)
eq(AlertState.alert, r.state, "UA: громада з галочкою")
r = m(Root().ain(uid(ODR), ODR, "red", T0).nep(OD, ODR, "red", T0).ua(OD, ODR, "red", T0), ODR, false)
eq("джерела збігаються", r.note, "UA: три джерела збігаються")
r = try! AlertSource.fromRoot(Root().ain(uid(ODR), ODR, "red", T0).nep(OD, ODR, "red", T0).ua().build(), OD, uid(ODR), false, t0, t0 + 90_000)
eq(AlertState.clear, r.state, "UA: офіційний відбій одразу")
check(r.note.contains("UkraineAlarm"), "UA: примітка відбою")
r = try! AlertSource.fromRoot(Root().nep(OD, ODR, "red", iso(t0 + 200_000)).ua().build(), OD, uid(ODR), false, t0, t0 + 90_000)
eq(AlertState.alert, r.state, "UA: нова тривога NEPTUN після вікна — тривога")

// Engine
do {
    let u = uid(ODR)
    let e = Engine()
    check(try! e.compute(nil, OD, u, false, t0) == nil, "Engine: без даних — nil")
    let uaAlert = Root().ua(OD, ODR, "red", T0).build()["ua"] as! JObj
    let uaClear = Root().ua().build()["ua"] as! JObj
    e.serverOk(Root().nep(OD, ODR, "red", T0).ain(u, ODR, "red", T0).ua(OD, ODR, "red", T0).build(), t0)
    var x = try! e.compute(uaAlert, OD, u, false, t0 + 1000)!
    eq(AlertState.alert, x.state, "Engine: тривога")
    check(x.source.contains("UkraineAlarm наживо"), "Engine: потік позначено")
    x = try! e.compute(uaClear, OD, u, false, t0 + 2000)!
    eq(AlertState.clear, x.state, "Engine: офіційний відбій одразу")
    x = try! e.compute(nil, OD, u, false, t0 + 3000)!
    eq(AlertState.clear, x.state, "Engine: старі дані сервера не повертають тривогу")
    e.serverOk(Root().nep(OD, ODR, "red", T0).ua().build(), t0 + 4000)
    x = try! e.compute(nil, OD, u, false, t0 + 4000)!
    eq(AlertState.clear, x.state, "Engine: запізнілий NEPTUN після офіційного відбою — тиша")
    e.serverOk(Root().nep(OD, ODR, "red", T0).ua().build(), t0 + 200_000)
    x = try! e.compute(nil, OD, u, false, t0 + 200_000)!
    eq(AlertState.alert, x.state, "Engine: після вікна NEPTUN знову враховується")
    let e2 = Engine()
    e2.serverFailed("тест")
    x = try! e2.compute(uaAlert, OD, u, false, t0)!
    check(x.note.hasPrefix("сервер недоступний"), "Engine: примітка про сервер")
    eq(AlertState.alert, x.state, "Engine: працює лише потік")
    let e3 = Engine()
    e3.serverOk(Root().nep(OD, ODR, "red", T0).build(), t0)
    check(try! e3.compute(nil, OD, u, false, t0 + 100_000) == nil, "Engine: застарілий сервер — немає даних")
}

// decide
func res(_ s: AlertState, _ level: String, _ t: String...) -> AlertResult { let r = AlertResult(); r.state = s; r.level = level; r.threats = t; return r }
eq(Engine.Kind.none, Engine.decide(.unknown, [], "", res(.alert, "red")).kind, "перша перевірка — без звуку")
eq(Engine.Kind.alarm, Engine.decide(.clear, [], "", res(.alert, "red")).kind, "тривога")
eq(Engine.Kind.clear, Engine.decide(.alert, [], "red", res(.clear, "")).kind, "відбій")
eq(Engine.Kind.escalation, Engine.decide(.alert, [], "yellow", res(.alert, "red")).kind, "жовтий → червоний")
eq(Engine.Kind.ballistic, Engine.decide(.alert, ["drones"], "red", res(.alert, "red", "drones", "ballistic_missiles")).kind, "балістика")
eq(Engine.Kind.newThreats, Engine.decide(.alert, ["drones"], "red", res(.alert, "red", "drones", "cruise_missiles")).kind, "нова загроза")
eq(Engine.Kind.none, Engine.decide(.alert, ["drones"], "red", res(.alert, "red", "drones")).kind, "без змін — тиша")
eq(Engine.Kind.none, Engine.decide(.alert, [], "red", res(.unknown, "")).kind, "немає даних — тиша")
eq(Engine.Kind.none, Engine.decide(.alert, [], "red", res(.alert, "red")).kind, "після обриву — без повторної сирени")

// потік
let msg = "{\"type\":\"alerts\",\"source\":\"ukrainealarm\",\"data\":{\"raions\":[{\"name\":\"\(ODR)\",\"oblast\":\"\(OD)\",\"since\":\"2026-10-28T10:00:00Z\",\"level\":\"red\",\"reasons\":[\"Балістика\",\"Дрони\"]}],\"oblasts\":[],\"communities\":[{\"oblast\":\"\(OD)\",\"raion\":\"\(ODR)\",\"name\":\"Х\",\"since\":\"2026-10-28T10:00:00Z\",\"level\":\"yellow\"}]}}"
let d = StreamParse.alerts(msg)
check(d != nil, "потік: розібрано")
check(StreamParse.alerts("{\"type\":\"heartbeat\"}") == nil, "потік: heartbeat пропущено")
check(StreamParse.alerts("{\"type\":\"snapshot\",\"data\":{\"threats\":[]}}") == nil, "потік: інший тип пропущено")
eq(1, (d?["r"] as? JArr)?.count ?? 0, "потік: район")
eq(1, (d?["c"] as? JArr)?.count ?? 0, "потік: громада")
r = try! AlertSource.fromRoot(["ua": d!], OD, uid(ODR), false)
eq(AlertState.alert, r.state, "потік: тривога")
check(r.threats.contains("ballistic_missiles") && r.threats.contains("drones"), "потік: типи загроз з причин")
eq("wss://x.workers.dev/stream", StreamParse.streamUrl("https://x.workers.dev/"), "адреса потоку")
eq(1_761_645_600_000, AlertSource.parseIso("2025-10-28T10:00:00.123Z"), "розбір ISO-часу")
eq(1_761_645_600_000, AlertSource.parseIso("2025-10-28T10:00:00+00:00"), "розбір ISO-часу зі зсувом")

print("Пройдено: \(passed), помилок: \(failed)")
exit(failed == 0 ? 0 : 1)
