import AppKit
import ServiceManagement
import SwiftUI

/** Запуск разом із Mac: SMAppService (macOS 13+) або LaunchAgent (macOS 12). */
final class LoginItem: ObservableObject {
    static let shared = LoginItem()
    @Published var enabled = false
    private var agent: URL { FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/LaunchAgents/ua.spokiy.trevoga.plist") }

    init() { enabled = current() }

    private func current() -> Bool {
        if #available(macOS 13.0, *) { return SMAppService.mainApp.status == .enabled }
        return FileManager.default.fileExists(atPath: agent.path)
    }

    func set(_ on: Bool) {
        if #available(macOS 13.0, *) {
            do { if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() } }
            catch { L("LOGIN \(error.localizedDescription)") }
        } else if on {
            let plist: [String: Any] = ["Label": "ua.spokiy.trevoga", "ProgramArguments": ["/usr/bin/open", "-g", Bundle.main.bundlePath], "RunAtLoad": true]
            try? FileManager.default.createDirectory(at: agent.deletingLastPathComponent(), withIntermediateDirectories: true)
            (plist as NSDictionary).write(to: agent, atomically: true)
        } else {
            try? FileManager.default.removeItem(at: agent)
        }
        enabled = current()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    static weak var shared: AppDelegate?
    private var window: NSWindow!
    private var item: NSStatusItem!

    func applicationDidFinishLaunching(_ n: Notification) {
        AppDelegate.shared = self
        setupMenu()
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 760), styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "Тривога"
        window.contentView = NSHostingView(rootView: ContentView())
        window.isReleasedWhenClosed = false
        if !window.setFrameUsingName("main") { window.center() }
        window.setFrameAutosaveName("main")
        window.delegate = self

        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.image = NSImage(named: "MenuIcon")
        item.button?.image?.isTemplate = true
        item.button?.imagePosition = .imageLeading
        let menu = NSMenu()
        menu.addItem(withTitle: "Відкрити «Тривогу»", action: #selector(openWindow), keyEquivalent: "o").target = self
        menu.addItem(withTitle: "Тиша (вимкнути звук)", action: #selector(hush), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Вийти", action: #selector(quit), keyEquivalent: "q").target = self
        item.menu = menu

        Notifier.shared.setup()
        Monitor.shared.start()
        updateStatusItem()
        if !(env["TREVOGA_HIDDEN"] == "1") { showWindow() }
    }

    private func setupMenu() {
        let main = NSMenu()
        let app = NSMenuItem()
        main.addItem(app)
        let m = NSMenu()
        m.addItem(withTitle: "Про «Тривогу»", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        m.addItem(.separator())
        m.addItem(withTitle: "Сховати", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        m.addItem(withTitle: "Вийти", action: #selector(quit), keyEquivalent: "q").target = self
        app.submenu = m
        let edit = NSMenuItem()
        main.addItem(edit)
        let e = NSMenu(title: "Правка")
        e.addItem(withTitle: "Копіювати", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        e.addItem(withTitle: "Виділити все", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.submenu = e
        let win = NSMenuItem()
        main.addItem(win)
        let w = NSMenu(title: "Вікно")
        w.addItem(withTitle: "Закрити", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        w.addItem(withTitle: "Згорнути", action: #selector(NSWindow.miniaturize(_:)), keyEquivalent: "m")
        win.submenu = w
        NSApp.mainMenu = main
    }

    /** Значок у рядку меню: колір і короткий текст — щоб тривогу було видно, навіть коли вікно закрите. */
    func updateStatusItem() {
        guard let b = item?.button else { return }
        let m = Monitor.shared
        let (txt, col): (String, NSColor?) = {
            switch m.state {
            case .alert:
                if m.threats.contains("ballistic_missiles") { return (" БАЛІСТИКА", NSColor(red: 0.49, green: 0.23, blue: 0.93, alpha: 1)) }
                return (m.level == "yellow" ? " Тривога" : " ТРИВОГА", m.level == "yellow" ? NSColor(red: 0.85, green: 0.47, blue: 0.02, alpha: 1) : .systemRed)
            case .clear: return ("", nil)
            default: return (" ?", nil)
            }
        }()
        // значок: сирена — тривога, перекреслена — тривоги немає
        let img = NSImage(named: m.state == .clear ? "MenuIconOff" : "MenuIcon")
        img?.isTemplate = true
        b.image = img
        b.alphaValue = m.state == .unknown ? 0.5 : 1
        if let col = col {
            b.attributedTitle = NSAttributedString(string: txt, attributes: [.foregroundColor: col, .font: NSFont.systemFont(ofSize: 12, weight: .bold)])
            b.contentTintColor = col
        } else {
            b.attributedTitle = NSAttributedString(string: txt)
            b.contentTintColor = nil
        }
        b.toolTip = "Тривога: " + Settings.shared.place
    }

    @objc func openWindow() { showWindow() }
    @objc func hush() { Sounds.shared.stop() }
    @objc func quit() { NSApp.terminate(nil) }

    func showWindow() {
        NSApp.setActivationPolicy(.regular)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    // закриття вікна не зупиняє стеження: програма лишається в рядку меню
    func windowWillClose(_ n: Notification) {
        NSApp.setActivationPolicy(.accessory)
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool { true }

    func applicationShouldTerminateAfterLastWindowClosed(_ s: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ s: NSApplication, hasVisibleWindows: Bool) -> Bool { showWindow(); return true }

    func applicationShouldTerminate(_ s: NSApplication) -> NSApplication.TerminateReply {
        L("STOP")
        Reporter.send("stop", ["reason": "quit", "place": Settings.shared.placeKey])
        return .terminateNow
    }
}
