import SwiftUI

enum Palette {
    static let red = Color(rgb: 0xDC2626), yellow = Color(rgb: 0xD97706), purple = Color(rgb: 0x7C3AED)
    static let green = Color(rgb: 0x16A34A), gray = Color(rgb: 0x6B7280)
}

func agoText(_ ms: Int) -> String {
    let m = (nowMs() - ms) / 60000
    if m < 1 { return "щойно" }
    if m < 60 { return "\(m) хв" }
    return "\(m / 60) год \(m % 60) хв"
}

struct ContentView: View {
    @ObservedObject var m = Monitor.shared
    @ObservedObject var s = Settings.shared
    @ObservedObject var map = MapModel.shared
    @ObservedObject var loc = LocationModel.shared
    @State private var showSettings = false
    @State private var tick = Date()
    let timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("Тривога").font(.system(size: 22))
                    Spacer()
                    Picker("", selection: $s.mapView) {
                        Text("Простий").tag(false)
                        Text("Карта").tag(true)
                    }
                    .pickerStyle(.segmented).labelsHidden().frame(width: 170)
                }
                statusCard
                if s.mapView { mapCard }
                HStack(spacing: 10) {
                    Button { showSettings = true } label: { Label("Налаштування", systemImage: "gearshape").frame(maxWidth: .infinity, minHeight: 30) }
                        .controlSize(.large)
                    Button { Sounds.shared.stop() } label: { Label("Тиша", systemImage: "speaker.slash").frame(maxWidth: .infinity, minHeight: 30) }
                        .controlSize(.large).keyboardShortcut(.escape, modifiers: [])
                        .modifier(Prominent())
                }
                Text(footer).font(.system(size: 11)).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true).monospacedDigit()
                HStack(spacing: 4) {
                    Text("Дані: alerts.in.ua, UkraineAlarm ·").font(.system(size: 11)).foregroundColor(.secondary)
                    Link("Карта повітряних тривог — NEPTUN", destination: URL(string: "https://neptun.in.ua/")!).font(.system(size: 11))
                }
            }
            .padding(18)
        }
        .frame(minWidth: 420, idealWidth: 460, minHeight: 520)
        .sheet(isPresented: $showSettings) { SettingsView(close: { showSettings = false }) }
        .onReceive(timer) { tick = $0 }
        .onAppear { map.activate(s.mapView); loc.activate(s.mapView) }
        .onChange(of: s.mapView) { map.activate($0); loc.activate($0) }
    }

    private var cardColor: Color {
        if m.state == .alert {
            if m.threats.contains("ballistic_missiles") { return Palette.purple }
            return m.level == "yellow" ? Palette.yellow : Palette.red
        }
        return m.state == .clear ? Palette.green : Palette.gray
    }

    private var title: String {
        switch m.state {
        case .alert: return m.threats.contains("ballistic_missiles") ? "БАЛІСТИКА!" : "ПОВІТРЯНА ТРИВОГА"
        case .clear: return "Тривоги немає"
        default: return m.error != nil ? "Немає зв'язку" : "Перевірка…"
        }
    }

    private var detail: String {
        _ = tick
        let hm = DateFormatter(); hm.dateFormat = "HH:mm"
        switch m.state {
        case .alert:
            let lv = m.threats.contains("ballistic_missiles") || m.level == "red" ? "Червоний рівень · " : m.level == "yellow" ? "Жовтий рівень · " : ""
            return lv + (m.since > 0 ? "триває \(agoText(m.since)) · з \(hm.string(from: Date(timeIntervalSince1970: Double(m.since) / 1000)))" : "пройдіть в укриття")
                + (m.disagree ? " · підтверджує 1 з 2 джерел" : "")
        case .clear:
            return m.clearedAt > 0 ? "Відбій о \(hm.string(from: Date(timeIntervalSince1970: Double(m.clearedAt) / 1000)))" : "Спокійно"
        default: return m.error ?? ""
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: s.mapView ? 24 : 30, weight: .heavy)).lineLimit(2)
            Text(s.place).font(.system(size: 15)).lineLimit(1).opacity(0.92)
            Text(detail).font(.system(size: 13)).fixedLines(2).opacity(0.88).monospacedDigit()
            if m.state == .alert && !m.threats.isEmpty && !s.mapView {
                HStack(spacing: 6) {
                    ForEach(m.threats, id: \.self) { t in
                        Text(AlertSource.threatTitle(t)).font(.system(size: 12, weight: .semibold))
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.22)))
                    }
                }.padding(.top, 8)
            }
        }
        .foregroundColor(.white)
        .padding(22)
        .frame(maxWidth: .infinity, minHeight: s.mapView ? 0 : 190, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 24).fill(LinearGradient(colors: [cardColor, cardColor.opacity(0.85)], startPoint: .topLeading, endPoint: .bottomTrailing)))
    }

    private var mapCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            MapView()
            meLine
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    let rel = map.relevant()
                    let counts = Dictionary(grouping: rel, by: { $0.threat.sub }).mapValues { $0.reduce(0) { $0 + $1.threat.count } }
                    let incoming = rel.filter { $0.incoming }.reduce(0) { $0 + $1.threat.count }
                    if map.updated == nil { chip(Palette.gray, "Завантажую цілі…") }
                    else {
                        if map.focus != nil {
                            Text("Ця ділянка").font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 9).padding(.vertical, 4)
                                .background(RoundedRectangle(cornerRadius: 8).fill(Color.accentColor.opacity(0.18)))
                        }
                        if counts.isEmpty { chip(Palette.green, map.focus != nil ? "Поруч цілей немає" : "Цілей на карті немає") }
                    }
                    ForEach(Sub.order, id: \.rawValue) { k in
                        if let n = counts[k] { chip(Color(rgb: k.rgb), "\(k.title): \(n)") }
                    }
                    if incoming > 0 { chip(Color.accentColor, "летять сюди: \(incoming)") }
                }
            }.frame(height: 26)
            Text(mapStatus).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1).monospacedDigit()
            Text("Клік по значку — маршрут і подробиці; у наближенні внизу лише цілі цієї ділянки й ті, що летять у неї. Положення цілей приблизні й можуть запізнюватися; частина цілей може не з'явитися. Порожня карта не означає, що небезпеки немає — орієнтуйтеся на тривогу. Цілі: NEPTUN · міста: GeoNames.")
                .font(.system(size: 11)).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color.primary.opacity(0.05)))
    }

    /** «📍 Ви тут: Одеса» — один рядок завжди (щоб нічого не зсувалося); без дозволу — кнопка. */
    @ViewBuilder private var meLine: some View {
        if let la = loc.lat, let lo = loc.lon {
            let name = map.cityIndex(la, lo, near: false).map { map.cities[$0].name }
                ?? map.cityIndex(la, lo, near: true).map { "біля: " + map.cities[$0].name } ?? s.place
            Text("📍 Ви тут: \(name)").font(.system(size: 12, weight: .semibold)).lineLimit(1)
        } else if loc.allowed {
            Text("📍 Визначаю, де ви…").font(.system(size: 12)).foregroundColor(.secondary).lineLimit(1)
        } else {
            Button(loc.denied ? "📍 Показати себе на карті — дозволити геопозицію в налаштуваннях" : "📍 Показати себе на карті") { loc.ask() }
                .buttonStyle(.link).font(.system(size: 12)).lineLimit(1)
        }
    }

    private func chip(_ c: Color, _ s: String) -> some View {
        HStack(spacing: 5) { Circle().fill(c).frame(width: 8, height: 8); Text(s).font(.system(size: 11)) }
            .padding(.horizontal, 9).padding(.vertical, 4)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.primary.opacity(0.15)))
    }

    private var mapStatus: String {
        guard let u = map.updated else { return map.error.map { "Немає зв'язку з сервером: \($0)" } ?? " " }
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
        return "Цілі оновлено о \(f.string(from: u)) · кожні 15 с" + (map.error.map { " · остання спроба: \($0)" } ?? "")
    }

    private var footer: String {
        var f = ""
        if !m.statusTime.isEmpty { f = "Оновлено о \(m.statusTime) · \(m.source)" + (m.note.isEmpty ? "" : " · \(m.note)") }
        return (f.isEmpty ? "" : f + "\n\n") + "Це додатковий сигнал. Тримайте увімкненим і офіційний застосунок «Повітряна тривога»."
    }
}

struct Prominent: ViewModifier {
    func body(content: Content) -> some View { content.buttonStyle(.borderedProminent) }
}

extension Text {
    func fixedLines(_ n: Int) -> some View {
        // фіксована висота — картка не «стрибає», коли текст коротший/довший
        self.lineLimit(n).frame(minHeight: CGFloat(n) * 17, alignment: .topLeading)
    }
}

struct SettingsView: View {
    @ObservedObject var s = Settings.shared
    @ObservedObject var up = Updater.shared
    @ObservedObject var login = LoginItem.shared
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Налаштування").font(.system(size: 20, weight: .semibold))
            GroupBox(label: Text("Місце")) {
                VStack(alignment: .leading, spacing: 8) {
                    Picker("Область", selection: Binding(get: { s.region }, set: { v in
                        if v != s.region { s.region = v; s.raion = 0; Monitor.shared.placeChanged() }
                    })) { ForEach(AlertSource.regions, id: \.self) { Text($0).tag($0) } }
                    Picker("Район", selection: Binding(get: { s.raion }, set: { v in
                        if v != s.raion { s.raion = v; Monitor.shared.placeChanged() }
                    })) {
                        Text("Вся область").tag(0)
                        ForEach(AlertSource.raions(of: s.region).map { RaionItem(id: $0.uid, name: $0.name) }) { Text($0.name).tag($0.id) }
                    }
                    Toggle("Сповіщати й про тривогу в окремих громадах", isOn: Binding(get: { s.partial }, set: { s.partial = $0; Monitor.shared.placeChanged() }))
                }.padding(6)
            }
            GroupBox(label: Text("Сповіщення")) {
                VStack(alignment: .leading, spacing: 8) {
                    Toggle("Лише сповіщення — без голосу і сирени", isOn: $s.quiet)
                    Picker("Голос перед сиреною", selection: $s.voice) {
                        Text("Жіночий (Тетяна)").tag("tetiana")
                        Text("Чоловічий (Микита)").tag("mykyta")
                        Text("Без голосу, лише сирена").tag("off")
                    }.disabled(s.quiet)
                    Toggle("Показувати вікно при тривозі", isOn: $s.showOnAlert)
                    Toggle("Запускати разом із Mac", isOn: Binding(get: { login.enabled }, set: { login.set($0) }))
                    HStack {
                        Button("Тест тривоги") {
                            if !s.quiet { Sounds.shared.alarm(Monitor.shared.threats.isEmpty ? ["drones"] : Monitor.shared.threats) }
                            Notifier.shared.post("ПОВІТРЯНА ТРИВОГА! (тест)", "\(s.place). Пройдіть в укриття!")
                        }
                        Button("Тест відбою") {
                            if !s.quiet { Sounds.shared.clear() }
                            Notifier.shared.post("Відбій тривоги (тест)", s.place)
                        }
                        Button("Тиша") { Sounds.shared.stop() }
                    }
                    Text("Звук іде через динаміки Mac: перевірте, що гучність не на нулі. Сповіщення можна налаштувати в «Системні параметри → Сповіщення → Тривога».")
                        .font(.system(size: 11)).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
                }.padding(6)
            }
            GroupBox(label: Text("Оновлення")) {
                HStack {
                    Text("Встановлено: \(appVersion)" + (up.status.isEmpty ? "" : " · \(up.status)")).font(.system(size: 12)).lineLimit(2)
                    Spacer()
                    if up.available != nil { Button("Оновити") { up.install() } }
                    else { Button("Перевірити") { up.check(manual: true) } }
                }.padding(6)
            }
            HStack {
                Text("Журнал: ~/Library/Logs/Trevoga.log").font(.system(size: 11)).foregroundColor(.secondary)
                Spacer()
                Button("Готово", action: close).keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 460)
    }
}

struct RaionItem: Identifiable { let id: Int; let name: String }
