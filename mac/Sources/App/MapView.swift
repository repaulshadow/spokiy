import SwiftUI

extension Color {
    init(rgb: UInt32, _ a: Double = 1) {
        self.init(.sRGB, red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255, opacity: a)
    }
}

/**
 * Карта України: області й райони (тривоги кольором), міста, цілі з курсом і слідом.
 * Жест двома пальцями на трекпаді — масштаб, перетягування — зсув, клік по значку — подробиці.
 */
struct MapView: View {
    @ObservedObject var model = MapModel.shared
    @ObservedObject var monitor = Monitor.shared
    @ObservedObject var settings = Settings.shared
    @Environment(\.colorScheme) var scheme
    @State private var zoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var pinch: CGFloat = 1
    @State private var drag: CGSize = .zero
    @State private var selected: String?

    static let minX = 22.0 * MapGeo.kx, maxX = 40.3 * MapGeo.kx, minY = -52.45, maxY = -44.3

    private struct Xf {
        let s: CGFloat, ox: CGFloat, oy: CGFloat, z: CGFloat, px: CGFloat, py: CGFloat
        func pt(_ p: CGPoint) -> CGPoint {
            CGPoint(x: ((p.x - MapView.minX) * s + ox) * z + px, y: ((p.y - MapView.minY) * s + oy) * z + py)
        }
        func ll(_ lat: Double, _ lon: Double) -> CGPoint { pt(MapGeo.proj(lat, lon)) }
        /** Екран → проєкція (для меж видимої ділянки). */
        func inv(_ q: CGPoint) -> CGPoint {
            CGPoint(x: ((q.x - px) / z - ox) / s + MapView.minX, y: ((q.y - py) / z - oy) / s + MapView.minY)
        }
        var affine: CGAffineTransform {
            CGAffineTransform(translationX: -MapView.minX, y: -MapView.minY).concatenating(CGAffineTransform(scaleX: s, y: s))
                .concatenating(CGAffineTransform(translationX: ox, y: oy)).concatenating(CGAffineTransform(scaleX: z, y: z))
                .concatenating(CGAffineTransform(translationX: px, y: py))
        }
    }

    private func xf(_ size: CGSize) -> Xf {
        let w = MapView.maxX - MapView.minX, h = MapView.maxY - MapView.minY
        let s = min((size.width - 12) / w, (size.height - 12) / h)
        let ox = (size.width - w * s) / 2, oy = (size.height - h * s) / 2
        let z = zoom * pinch
        // масштаб довкола центру вигляду
        let cx = size.width / 2, cy = size.height / 2
        let px = cx - (cx - pan.width) * pinch + drag.width, py = cy - (cy - pan.height) * pinch + drag.height
        return Xf(s: s, ox: ox, oy: oy, z: z, px: px, py: py)
    }

    private var dark: Bool { scheme == .dark }
    private var land: Color { dark ? Color(rgb: 0x2E2A2A) : .white }
    private var sea: Color { dark ? Color(rgb: 0x1E1C1C) : Color(rgb: 0xF1EEEE) }
    private var border: Color { dark ? Color(rgb: 0x8C7A78) : Color(rgb: 0xA08C8A) }
    private var ink: Color { dark ? Color(rgb: 0xF1DEDC) : Color(rgb: 0x231918) }

    /** Рівні тривог: офіційні (потік або сервер), інакше NEPTUN. */
    private func levels() -> (obl: [String: String], raion: [String: String]) {
        var o: [String: String] = [:], r: [String: String] = [:]
        let src = (monitor.stream.fresh() ? monitor.stream.data : nil) ?? (monitor.serverRoot?["ua"] as? JObj) ?? (monitor.serverRoot?["neptun"] as? JObj)
        for e in (src?["o"] as? JArr) ?? [] { if let e = e as? JArr { o[jstr(e, 0)] = jstr(e, 2, "red") } }
        for e in (src?["r"] as? JArr) ?? [] { if let e = e as? JArr { r[jstr(e, 0) + "|" + jstr(e, 1)] = jstr(e, 3, "red") } }
        return (o, r)
    }

    private func levelColor(_ lv: String?) -> Color? {
        guard let lv = lv else { return nil }
        if lv.contains("yellow") { return Color(rgb: 0xF9A825, 0.42) }
        return Color(rgb: 0xE53935, 0.36)
    }

    private func popMin(_ z: CGFloat) -> Int {
        z < 1.8 ? Int.max : z < 3 ? 150_000 : z < 5 ? 40_000 : z < 8 ? 12_000 : z < 11 ? 4_000 : 1_500
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                Canvas { ctx, size in draw(&ctx, size) }
                    .gesture(dragGesture(geo.size))
                    .simultaneousGesture(MagnificationGesture()
                        .onChanged { v in pinch = max(1 / zoom, min(14 / zoom, v)) }
                        .onEnded { _ in commitPinch(geo.size) })
                VStack {
                    HStack {
                        Spacer()
                        VStack(spacing: 6) {
                            mapButton("plus") { zoomBy(1.6, geo.size) }
                            mapButton("minus") { zoomBy(1 / 1.6, geo.size) }
                            mapButton("arrow.counterclockwise") { zoom = 1; pan = .zero; publish(geo.size) }
                        }
                    }
                    Spacer()
                }.padding(8)
                if let t = model.threats.first(where: { $0.id == selected }) {
                    ThreatInfo(t: t).padding(8).onTapGesture { selected = nil }
                }
            }
        }
        .aspectRatio(1.39, contentMode: .fit)
        .background(sea)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func mapButton(_ icon: String, _ act: @escaping () -> Void) -> some View {
        Button(action: act) { Image(systemName: icon).frame(width: 26, height: 26) }
            .buttonStyle(.plain)
            .background(Circle().fill(Color(nsColor: .windowBackgroundColor).opacity(0.92)))
            .overlay(Circle().stroke(border.opacity(0.5)))
    }

    private func zoomBy(_ f: CGFloat, _ size: CGSize) {
        let nz = max(1, min(14, zoom * f))
        let k = nz / zoom
        let cx = size.width / 2, cy = size.height / 2
        pan = CGSize(width: cx - (cx - pan.width) * k, height: cy - (cy - pan.height) * k)
        zoom = nz
        clamp(size)
        publish(size)
    }

    /** Повідомити видиму ділянку — підсумок під картою рахує лише її цілі. */
    private func publish(_ size: CGSize) {
        if zoom * pinch <= 1.3 { if model.focus != nil { model.focus = nil }; return }
        let x = xf(size)
        let a = x.inv(.zero), b = x.inv(CGPoint(x: size.width, y: size.height))
        model.focus = CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(b.x - a.x), height: abs(b.y - a.y))
    }

    private func commitPinch(_ size: CGSize) {
        let cx = size.width / 2, cy = size.height / 2
        pan = CGSize(width: cx - (cx - pan.width) * pinch, height: cy - (cy - pan.height) * pinch)
        zoom = max(1, min(14, zoom * pinch))
        pinch = 1
        clamp(size)
        publish(size)
    }

    private func clamp(_ size: CGSize) {
        pan.width = min(0, max(size.width - size.width * zoom, pan.width))
        pan.height = min(0, max(size.height - size.height * zoom, pan.height))
    }

    private func dragGesture(_ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in if hypot(v.translation.width, v.translation.height) > 4 { drag = v.translation } }
            .onEnded { v in
                if hypot(v.translation.width, v.translation.height) <= 4 { tap(v.location, size) }
                else { pan.width += v.translation.width; pan.height += v.translation.height; clamp(size) }
                drag = .zero
                publish(size)
            }
    }

    private func tap(_ p: CGPoint, _ size: CGSize) {
        let x = xf(size)
        var best: Threat?
        var bd: CGFloat = 16
        for t in model.threats {
            let q = x.ll(t.lat, t.lon)
            let dd = hypot(q.x - p.x, q.y - p.y)
            if dd < bd { bd = dd; best = t }
        }
        selected = best?.id
    }

    // ---------- малювання ----------

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        guard let g = model.geo else {
            ctx.draw(Text("Завантажую карту…").foregroundColor(ink), at: CGPoint(x: size.width / 2, y: size.height / 2))
            return
        }
        let x = xf(size)
        let tf = x.affine
        func path(_ cg: CGPath) -> Path { Path(cg).applying(tf) }
        let lv = levels()
        for a in g.oblasts { ctx.fill(path(a.path), with: .color(land), style: FillStyle(eoFill: true)) }
        for a in g.oblasts { if let c = levelColor(lv.obl[a.name]) { ctx.fill(path(a.path), with: .color(c), style: FillStyle(eoFill: true)) } }
        var oblName: [Int: String] = [:]
        for a in g.oblasts { oblName[a.uid] = a.name }
        for a in g.raions {
            guard let on = oblName[a.oblast], lv.obl[on] == nil, let c = levelColor(lv.raion[on + "|" + a.name]) else { continue }
            ctx.fill(path(a.path), with: .color(c), style: FillStyle(eoFill: true))
        }
        if x.z >= 2.5 { for a in g.raions { ctx.stroke(path(a.path), with: .color(border.opacity(0.35)), lineWidth: 0.6) } }
        for a in g.oblasts { ctx.stroke(path(a.path), with: .color(border), lineWidth: 0.9) }
        // своє місце
        let accent = Color.accentColor
        if settings.raion != 0 { for a in g.raions where a.uid == settings.raion { ctx.stroke(path(a.path), with: .color(accent), lineWidth: 2.2) } }
        else { for a in g.oblasts where a.name == settings.region { ctx.stroke(path(a.path), with: .color(accent), lineWidth: 2.2) } }

        drawCities(&ctx, size, x)

        // цілі
        let pxPerKm = x.s * x.z / 111
        // маршрут — лише для вибраної цілі: пройдений шлях і прогноз за курсом (пунктир)
        if let t = model.threats.first(where: { $0.id == selected }) {
            if !t.trail.isEmpty {
                var p = Path()
                for (i, q) in t.trail.enumerated() { let s = x.ll(q.0, q.1); if i == 0 { p.move(to: s) } else { p.addLine(to: s) } }
                p.addLine(to: x.ll(t.lat, t.lon))
                ctx.stroke(p, with: .color(Color(rgb: t.sub.rgb, 0.8)), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            }
            if t.heading >= 0 {
                let e = t.ahead(t.reachKm)
                var p = Path(); p.move(to: x.ll(t.lat, t.lon)); p.addLine(to: x.ll(e.0, e.1))
                ctx.stroke(p, with: .color(Color(rgb: t.sub.rgb, 0.6)), style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [7, 5]))
            }
        }
        for t in model.threats {
            let c = x.ll(t.lat, t.lon)
            let col = Color(rgb: t.sub.rgb)
            let r = CGFloat(t.uncKm) * pxPerKm
            if r > 10 && r < 3 * max(size.width, size.height) {
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(Color(rgb: t.sub.rgb, 0.12)))
            }
            if t.heading >= 0 {
                let a = Double(t.heading) * .pi / 180
                var p = Path(); p.move(to: c); p.addLine(to: CGPoint(x: c.x + sin(a) * 26, y: c.y - cos(a) * 26))
                ctx.stroke(p, with: .color(Color(rgb: t.sub.rgb, 0.7)), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, dash: [4, 3]))
            }
            icon(&ctx, t, c, col)
        }
    }

    private func icon(_ ctx: inout GraphicsContext, _ t: Threat, _ c: CGPoint, _ col: Color) {
        let r: CGFloat = 11
        if t.id == selected { ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * 1.8, y: c.y - r * 1.8, width: r * 3.6, height: r * 3.6)), with: .color(col.opacity(0.25))) }
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r - 1.6, y: c.y - r - 1.6, width: 2 * r + 3.2, height: 2 * r + 3.2)), with: .color(.white))
        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(col))
        // силует (вид згори), повернутий за курсом; FPV не повертаємо
        let sb = t.sub
        let sc = r * 2 * 0.8 / 24
        var m = CGAffineTransform(translationX: -12, y: -12).concatenating(CGAffineTransform(scaleX: sc, y: sc))
        if t.heading >= 0 && sb != .fpv { m = m.concatenating(CGAffineTransform(rotationAngle: CGFloat(t.heading) * .pi / 180)) }
        m = m.concatenating(CGAffineTransform(translationX: c.x, y: c.y))
        ctx.fill(Glyphs.parsed[sb.glyph].applying(m), with: .color(.white))
        if sb == .fpv {
            for (rx, ry, rr) in Glyphs.fpvRings {
                let cx = c.x + (rx - 12) * sc, cy = c.y + (ry - 12) * sc, q = rr * sc
                ctx.stroke(Path(ellipseIn: CGRect(x: cx - q, y: cy - q, width: 2 * q, height: 2 * q)), with: .color(.white), lineWidth: 1.3 * sc)
            }
        }
        if t.count > 1 {
            let b = CGPoint(x: c.x + r * 0.85, y: c.y - r * 0.85)
            ctx.fill(Path(ellipseIn: CGRect(x: b.x - 7, y: b.y - 7, width: 14, height: 14)), with: .color(.white))
            ctx.fill(Path(ellipseIn: CGRect(x: b.x - 6, y: b.y - 6, width: 12, height: 12)), with: .color(col))
            ctx.draw(Text("\(t.count)").font(.system(size: 8.5, weight: .bold)).foregroundColor(.white), at: b)
        }
    }

    private func drawCities(_ ctx: inout GraphicsContext, _ size: CGSize, _ x: Xf) {
        let pm = popMin(x.z)
        var placed: [CGRect] = []
        for c in model.cities {
            if c.cap == 0 && c.pop < pm { continue }
            let p = x.ll(c.lat, c.lon)
            if p.x < -40 || p.y < -20 || p.x > size.width + 40 || p.y > size.height + 20 { continue }
            let big = c.cap > 0
            let font = Font.system(size: c.cap == 2 ? 12.5 : big ? 11 : 10, weight: big ? .semibold : .regular)
            let txt = ctx.resolve(Text(c.name).font(font).foregroundColor(ink))
            let ts = txt.measure(in: CGSize(width: 400, height: 40))
            let r: CGFloat = big ? 3 : 2.3
            var lx = p.x + r + 3
            if lx + ts.width > size.width - 2 { lx = p.x - r - 3 - ts.width }
            let box = CGRect(x: min(lx, p.x - r), y: p.y - ts.height / 2, width: ts.width + r + 3, height: ts.height)
            if placed.contains(where: { $0.intersects(box) }) { continue }
            placed.append(box)
            ctx.fill(Path(ellipseIn: CGRect(x: p.x - r - 1.2, y: p.y - r - 1.2, width: 2 * r + 2.4, height: 2 * r + 2.4)), with: .color(land))
            ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)), with: .color(ink))
            // ореол під підписом — читається поверх кордонів і заливки
            let halo = ctx.resolve(Text(c.name).font(font).foregroundColor(land))
            for (dx, dy) in [(-1.0, 0.0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (-1, 1), (1, -1)] {
                ctx.draw(halo, at: CGPoint(x: lx + dx, y: p.y + dy), anchor: .leading)
            }
            ctx.draw(txt, at: CGPoint(x: lx, y: p.y), anchor: .leading)
        }
    }
}

struct ThreatInfo: View {
    let t: Threat
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(([.uav, .missile, .other].contains(t.sub) ? (t.title.isEmpty ? t.sub.one : t.title) : t.sub.one) + (t.count > 1 ? " ×\(t.count)" : "") + " — " + [t.place, t.region].filter { !$0.isEmpty }.joined(separator: ", "))
                .font(.system(size: 12, weight: .semibold)).lineLimit(1)
            Text(details).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(nsColor: .windowBackgroundColor).opacity(0.95)))
    }
    var details: String {
        var s: [String] = []
        if t.heading >= 0 {
            let d = ["на північ", "на північний схід", "на схід", "на південний схід", "на південь", "на південний захід", "на захід", "на північний захід"]
            s.append("Курс: \(d[((t.heading + 22) % 360) / 45]) (\(t.heading)°)")
        }
        if t.uncKm > 0 { s.append("точність ±\(Int(t.uncKm.rounded())) км") }
        if t.quality == "approx" { s.append("наближено") }
        return s.isEmpty ? "Положення приблизне" : s.joined(separator: " · ")
    }
}
