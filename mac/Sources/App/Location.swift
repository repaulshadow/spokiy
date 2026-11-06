import AppKit
import CoreLocation

/**
 * Де Mac — лише для крапки «Ви тут» на карті й назви населеного пункту (район не змінює: на комп'ютерах його
 * вибирають вручну). Точки поза Україною не показуємо. Працює, лише поки відкрита карта.
 */
final class LocationModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationModel()
    @Published var lat: Double?
    @Published var lon: Double?
    @Published var status: CLAuthorizationStatus = .notDetermined
    private let mgr = CLLocationManager()
    private var active = false

    override init() {
        super.init()
        mgr.delegate = self
        mgr.desiredAccuracy = kCLLocationAccuracyKilometer
        mgr.distanceFilter = 300
        status = mgr.authorizationStatus
    }

    var denied: Bool { status == .denied || status == .restricted }
    var allowed: Bool { status == .authorizedAlways || status.rawValue == 4 /* whenInUse */ }

    func activate(_ on: Bool) {
        guard on != active else { return }
        active = on
        if !on { mgr.stopUpdatingLocation(); return }
        if !CLLocationManager.locationServicesEnabled() { return }
        if status == .notDetermined { mgr.requestWhenInUseAuthorization() }
        mgr.startUpdatingLocation()
    }

    /** Кнопка «показати себе»: дозвіл ще не питали — спитати; заборонено — відкрити налаштування приватності. */
    func ask() {
        if denied || !CLLocationManager.locationServicesEnabled() {
            if let u = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices") { NSWorkspace.shared.open(u) }
        } else {
            mgr.requestWhenInUseAuthorization()
            mgr.startUpdatingLocation()
        }
    }

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        DispatchQueue.main.async {
            self.status = m.authorizationStatus
            if self.active && self.allowed { m.startUpdatingLocation() }
        }
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let l = locs.last, l.horizontalAccuracy >= 0, l.horizontalAccuracy < 5000 else { return }
        let la = l.coordinate.latitude, lo = l.coordinate.longitude
        // грубо — межі України; точку поза ними не показуємо (VPN/підміна)
        guard la > 44.0, la < 52.5, lo > 22.0, lo < 40.3 else { DispatchQueue.main.async { self.lat = nil; self.lon = nil }; return }
        DispatchQueue.main.async { self.lat = la; self.lon = lo }
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        L("GEO \(error.localizedDescription)")
    }
}

extension MapModel {
    /** Населений пункт, у якому точка (радіус «міста» росте з населенням); near — найближчий у межах 30 км. */
    func cityIndex(_ lat: Double, _ lon: Double, near: Bool) -> Int? {
        var best: Int?
        var bestScore = Double.greatestFiniteMagnitude
        for (i, c) in cities.enumerated() {
            let dlat = (c.lat - lat) * 111, dlon = (c.lon - lon) * 111 * cos(lat * .pi / 180)
            let d = (dlat * dlat + dlon * dlon).squareRoot()
            let r = near ? 30 : 1.5 + 4 * log10(Double(max(1000, c.pop)) / 1000)
            if d > r { continue }
            if d / r < bestScore { bestScore = d / r; best = i }
        }
        return best
    }
}
