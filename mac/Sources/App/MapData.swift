import Foundation
import CoreGraphics

/** Межі областей і районів (geo.json, координати ×1e4) — у проєкції карти: x = довгота·cos 48.5°, y = −широта. */
final class MapGeo {
    struct Area { let name: String; let uid: Int; let oblast: Int; let path: CGPath }
    static let kx = cos(48.5 * .pi / 180)
    var oblasts: [Area] = [], raions: [Area] = []

    static func proj(_ lat: Double, _ lon: Double) -> CGPoint { CGPoint(x: lon * kx, y: -lat) }

    static func load() -> MapGeo? {
        guard let u = Bundle.main.url(forResource: "geo", withExtension: "json"), let d = try? Data(contentsOf: u),
              let j = (try? JSONSerialization.jsonObject(with: d)) as? JObj else { return nil }
        let g = MapGeo()
        g.oblasts = read(j["oblasts"] as? JArr, oblast: true)
        g.raions = read(j["raions"] as? JArr, oblast: false)
        return g
    }

    private static func read(_ a: JArr?, oblast: Bool) -> [Area] {
        var out: [Area] = []
        for x in a ?? [] {
            guard let o = x as? JObj else { continue }
            let p = CGMutablePath()
            for r in (o["g"] as? JArr) ?? [] {
                guard let ring = r as? [NSNumber], ring.count >= 6 else { continue }
                var i = 0
                while i + 1 < ring.count {
                    let pt = CGPoint(x: ring[i].doubleValue / 1e4 * kx, y: -ring[i + 1].doubleValue / 1e4)
                    if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                    i += 2
                }
                p.closeSubpath()
            }
            let uid = (o["u"] as? NSNumber)?.intValue ?? 0
            out.append(Area(name: jstr(o, "n"), uid: uid, oblast: oblast ? uid : ((o["o"] as? NSNumber)?.intValue ?? 0), path: p))
        }
        return out
    }
}

/** Міста (cities.json, GeoNames): назва, координати, населення, статус (2 — столиця, 1 — обласний центр). */
struct MapCity { let name: String; let lat: Double; let lon: Double; let pop: Int; let cap: Int }

enum MapCities {
    static func load() -> [MapCity] {
        guard let u = Bundle.main.url(forResource: "cities", withExtension: "json"), let d = try? Data(contentsOf: u),
              let a = (try? JSONSerialization.jsonObject(with: d)) as? JArr else { return [] }
        return a.compactMap { x in
            guard let x = x as? JArr, x.count >= 5 else { return nil }
            return MapCity(name: jstr(x, 0), lat: ((x[1] as? NSNumber)?.doubleValue ?? 0) / 1e4, lon: ((x[2] as? NSNumber)?.doubleValue ?? 0) / 1e4,
                           pop: (x[3] as? NSNumber)?.intValue ?? 0, cap: (x[4] as? NSNumber)?.intValue ?? 0)
        }
    }
}

/** Ціль на карті (з /threats сервера, дані NEPTUN; положення приблизні). */
struct Threat: Identifiable, Equatable {
    let id: String, type: String, title: String, place: String, region: String, quality: String, at: String
    let lat: Double, lon: Double, heading: Int, uncKm: Double, count: Int
    let trail: [(Double, Double)]
    static func == (a: Threat, b: Threat) -> Bool { a.id == b.id && a.lat == b.lat && a.lon == b.lon && a.count == b.count }

    enum Kind: Int, CaseIterable { case ballistic, missile, kab, uav, fpv, other }
    var kind: Kind {
        let s = (type + " " + title).lowercased()
        if s.contains("ballist") || s.contains("баліст") || s.contains("іскандер") { return .ballistic }
        if s.contains("missile") || s.contains("ракет") || s.contains("cruise") || s.contains("крилат") || s.contains("калібр") { return .missile }
        if s.contains("kab") || s.contains("каб") || s.contains("бомб") { return .kab }
        if s.contains("fpv") { return .fpv }
        if s.contains("uav") || s.contains("бпла") || s.contains("дрон") || s.contains("shahed") || s.contains("шахед") || s.contains("recon") { return .uav }
        return .other
    }

    static func parse(_ j: JObj?) -> [Threat] {
        ((j?["items"] as? JArr) ?? []).compactMap { x in
            guard let o = x as? JObj, let la = (o["la"] as? NSNumber)?.doubleValue, let lo = (o["lo"] as? NSNumber)?.doubleValue else { return nil }
            var tr: [(Double, Double)] = []
            let t = (o["tr"] as? [NSNumber]) ?? []
            var i = 0
            while i + 1 < t.count { tr.append((t[i].doubleValue, t[i + 1].doubleValue)); i += 2 }
            return Threat(id: jstr(o, "id"), type: jstr(o, "t"), title: jstr(o, "n"), place: jstr(o, "p"), region: jstr(o, "r"),
                          quality: jstr(o, "q"), at: jstr(o, "at"), lat: la, lon: lo,
                          heading: (o["h"] as? NSNumber)?.intValue ?? -1, uncKm: (o["u"] as? NSNumber)?.doubleValue ?? 0,
                          count: max(1, (o["k"] as? NSNumber)?.intValue ?? 1), trail: tr)
        }
    }
}

extension Threat {
    /** Орієнтовна дальність прогнозу курсу (км). */
    var reachKm: Double {
        switch kind { case .ballistic: return 300; case .missile: return 400; case .kab: return 60; case .uav: return 120; case .fpv: return 20; case .other: return 100 }
    }
    /** Точка на відстані km за курсом (наближено). */
    func ahead(_ km: Double) -> (Double, Double) {
        let a = Double(heading) * .pi / 180
        return (lat + km * cos(a) / 111, lon + km * sin(a) / (111 * cos(lat * .pi / 180)))
    }
}

extension Threat.Kind {
    var title: String {
        switch self { case .ballistic: return "Балістика"; case .missile: return "Ракети"; case .kab: return "КАБи"
        case .uav: return "БпЛА"; case .fpv: return "FPV"; case .other: return "Інше" }
    }
    var rgb: UInt32 {
        switch self { case .ballistic: return 0x7B1FA2; case .missile: return 0xD32F2F; case .kab: return 0xEF6C00
        case .uav: return 0x37474F; case .fpv: return 0x6D4C41; case .other: return 0x1565C0 }
    }
}

/** Дані карти: межі, міста, цілі (оновлюються кожні 15 с, лише поки карта відкрита). */
final class MapModel: ObservableObject {
    static let shared = MapModel()
    @Published var geo: MapGeo?
    @Published var cities: [MapCity] = []
    @Published var threats: [Threat] = []
    @Published var updated: Date?
    @Published var error: String?
    /** Видима ділянка карти (у проєкції) — nil, якщо видно всю країну. */
    @Published var focus: CGRect?
    private var timer: Timer?

    /** Цілі для підсумку: на всій карті — усі; у наближенні — на ділянці й ті, що летять у неї (incoming). */
    func relevant() -> [(threat: Threat, incoming: Bool)] {
        guard let f = focus else { return threats.map { ($0, false) } }
        let big = f.insetBy(dx: -f.width * 0.05, dy: -f.height * 0.05)
        var out: [(Threat, Bool)] = []
        for t in threats {
            if big.contains(MapGeo.proj(t.lat, t.lon)) { out.append((t, false)); continue }
            guard t.heading >= 0 else { continue }
            for k in 1...24 {
                let q = t.ahead(t.reachKm * Double(k) / 24)
                if f.contains(MapGeo.proj(q.0, q.1)) { out.append((t, true)); break }
            }
        }
        return out
    }
    private var loading = false

    func activate(_ on: Bool) {
        timer?.invalidate(); timer = nil
        guard on else { return }
        if geo == nil {
            DispatchQueue.global(qos: .userInitiated).async {
                let g = MapGeo.load(), c = MapCities.load()
                DispatchQueue.main.async { self.geo = g; self.cities = c }
            }
        }
        load()
        timer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in self?.load() }
    }

    func load() {
        if loading { return }
        loading = true
        Net.get(Settings.shared.server + "/threats") { res in
            DispatchQueue.main.async {
                self.loading = false
                switch res {
                case .success(let d):
                    self.threats = Threat.parse(String(data: d, encoding: .utf8).flatMap(jsonObject))
                    self.updated = Date(); self.error = nil
                case .failure(let e): self.error = "\(e)"
                }
            }
        }
    }
}
