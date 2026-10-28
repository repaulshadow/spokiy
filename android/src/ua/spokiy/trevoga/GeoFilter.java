package ua.spokiy.trevoga;

import java.util.ArrayList;
import java.util.List;

/**
 * Фільтр геопозиції для автовизначення району. РЕБ може глушити GPS або підміняти його (точка «стрибає»
 * у море, в іншу країну, на сотні кілометрів). Приймаємо лише правдоподібні точки:
 *  1) не підроблені програмою (mock), з розумною точністю;
 *  2) в Україні (поза межами — типова підміна);
 *  3) GPS не розходиться з мережею (Wi-Fi/вишки — їх РЕБ не глушить) більш ніж на 5 км;
 *  4) фізично досяжні з останньої прийнятої точки: не швидше ~180 км/год з урахуванням похибок;
 *     справжню поїздку без відміток (телефон був вимкнений) приймаємо, якщо нове місце стабільне ≥15 хв і є мережева точка;
 *  5) район змінюємо без «дребезгу» на межі: одразу — лише якщо точка всередині з запасом більшим за похибку,
 *     інакше — після двох узгоджених точок із проміжком ≥30 с.
 * Без Android — перевіряється автотестами на звичайній Java.
 */
final class GeoFilter {
    static final class Fix {
        double lat, lon;
        float acc;          // м
        long time;          // мс
        boolean gps;        // false — мережа (Wi-Fi/вишки) або інше
        boolean mock;
        Fix(double lat, double lon, float acc, long time, boolean gps, boolean mock) {
            this.lat = lat; this.lon = lon; this.acc = acc; this.time = time; this.gps = gps; this.mock = mock;
        }
    }

    static final class Decision {
        boolean accepted;
        boolean changed;            // змінився район
        String reason = "";         // чому відкинуто / що сталося
        GeoLookup.Place place;      // поточний район після рішення
    }

    static final double VMAX = 50.0;              // м/с ≈ 180 км/год
    static final float MAX_ACC = 3000;            // м
    static final double GPS_NET_MAX = 5000;       // м
    static final long NET_RECENT = 3 * 60000L;
    static final long CLUSTER_MS = 15 * 60000L;
    static final double CLUSTER_R = 3000;         // м
    static final int CLUSTER_N = 5;
    static final long CANDIDATE_GAP = 30000L;

    private final GeoLookup geo;
    GeoLookup.Place current;
    private Fix lastAccepted, lastNet;
    private GeoLookup.Place candidate;
    private int candidateCount;
    private long candidateSince;
    private final List<Fix> far = new ArrayList<Fix>();   // відкинуті як «стрибок», але, можливо, справжня поїздка

    GeoFilter(GeoLookup geo) { this.geo = geo; }

    /** Відновити стан після перезапуску (останній надійний район). */
    void restore(GeoLookup.Place p, Fix last) { current = p; lastAccepted = last; }

    Fix lastAccepted() { return lastAccepted; }

    static double distM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    Decision offer(Fix f) {
        Decision d = new Decision();
        d.place = current;
        if (f.mock) return reject(d, "підроблене місце (mock)");
        if (Float.isNaN(f.acc) || f.acc <= 0) f.acc = 500;   // точність не вказано — вважаємо обережно ±500 м
        if (f.acc > MAX_ACC) return reject(d, "низька точність (" + Math.round(f.acc) + " м)");
        if (lastAccepted != null && f.time < lastAccepted.time - 1000) return reject(d, "застаріла точка");
        GeoLookup.Place p = geo.find(f.lat, f.lon);
        if (p == null) return reject(d, "точка поза Україною — ймовірно РЕБ або підміна GPS");
        if (!f.gps) lastNet = f;
        if (f.gps && lastNet != null && Math.abs(f.time - lastNet.time) < NET_RECENT
                && distM(f.lat, f.lon, lastNet.lat, lastNet.lon) - f.acc - lastNet.acc > GPS_NET_MAX)
            return reject(d, "GPS розходиться з мережею на " + Math.round(distM(f.lat, f.lon, lastNet.lat, lastNet.lon) / 1000) + " км — ймовірно РЕБ");

        if (lastAccepted != null) {
            double dt = Math.max(1, (f.time - lastAccepted.time) / 1000.0);
            double dist = distM(f.lat, f.lon, lastAccepted.lat, lastAccepted.lon);
            double allowed = VMAX * dt + f.acc + lastAccepted.acc + 1000;
            if (dist > allowed) {
                // можливо, справжнє переміщення без відміток: нове місце стабільне досить довго і підтверджене мережею
                far.add(f);
                while (!far.isEmpty() && f.time - far.get(0).time > 2 * CLUSTER_MS) far.remove(0);
                if (!stableCluster(f)) return reject(d, "неможливий стрибок на " + Math.round(dist / 1000) + " км за " + Math.round(dt) + " с");
                d.reason = "нове місце підтверджене протягом 15 хв";
            }
        }
        far.clear();
        lastAccepted = f;
        d.accepted = true;

        if (current != null && p.key().equals(current.key())) { candidate = null; candidateCount = 0; return d; }
        boolean clearlyInside = p.borderM > Math.max(f.acc, 100);
        if (current == null || clearlyInside) return change(d, p, current == null ? "перше визначення" : "впевнено в новому районі");
        if (candidate != null && candidate.key().equals(p.key())) {
            candidateCount++;
            if (candidateCount >= 2 && f.time - candidateSince >= CANDIDATE_GAP) return change(d, p, "підтверджено двома точками");
        } else {
            candidate = p;
            candidateCount = 1;
            candidateSince = f.time;
        }
        if (d.reason.length() == 0) d.reason = "біля межі — чекаю підтвердження";
        return d;
    }

    private boolean stableCluster(Fix f) {
        int n = 0;
        boolean net = false;
        long first = Long.MAX_VALUE;
        for (Fix x : far) {
            if (distM(x.lat, x.lon, f.lat, f.lon) > CLUSTER_R) continue;
            n++;
            if (!x.gps) net = true;
            first = Math.min(first, x.time);
        }
        return n >= CLUSTER_N && net && f.time - first >= CLUSTER_MS;
    }

    private Decision change(Decision d, GeoLookup.Place p, String why) {
        current = p;
        candidate = null;
        candidateCount = 0;
        d.place = p;
        d.changed = true;
        d.reason = why;
        return d;
    }

    private static Decision reject(Decision d, String why) {
        d.accepted = false;
        d.reason = why;
        return d;
    }
}
