package ua.spokiy.trevoga;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Район за координатами — офлайн, за межами з карти NEPTUN (res/raw/geo.json, координати ×1e4, ~100 м).
 * Без Android, тому перевіряється автотестами на звичайній Java.
 */
final class GeoLookup {
    static final class Area {
        String name;
        int uid;        // для району: uid району (0 — немає у списку програми, напр. Крим); для області: uid області
        int oblast;     // uid області
        int[] bbox;     // minX, minY, maxX, maxY (×1e4; X — довгота)
        int[][] rings;  // x0,y0,x1,y1,…
    }

    static final class Place {
        String region;      // назва області як у програмі
        int raion;          // uid району, 0 — уся область
        double borderM;     // відстань до межі (м): наскільки «впевнено» точка всередині
        String key() { return region + "|" + raion; }
        String title() {
            String r = raion != 0 ? AlertSource.raionName(raion) : null;
            return r != null ? r + " (" + region + ")" : region;
        }
    }

    final List<Area> oblasts = new ArrayList<Area>(), raions = new ArrayList<Area>();

    static GeoLookup parse(String json) throws Exception {
        JSONObject j = new JSONObject(json);
        GeoLookup g = new GeoLookup();
        read(j.getJSONArray("oblasts"), g.oblasts, true);
        read(j.getJSONArray("raions"), g.raions, false);
        return g;
    }

    private static void read(JSONArray a, List<Area> out, boolean oblast) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Area ar = new Area();
            ar.name = o.optString("n", "");
            ar.uid = o.optInt("u", 0);
            ar.oblast = oblast ? ar.uid : o.optInt("o", 0);
            JSONArray b = o.optJSONArray("b");
            ar.bbox = new int[]{b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3)};
            JSONArray g = o.optJSONArray("g");
            ar.rings = new int[g.length()][];
            for (int k = 0; k < g.length(); k++) {
                JSONArray r = g.optJSONArray(k);
                int[] pts = new int[r.length()];
                for (int m = 0; m < pts.length; m++) pts[m] = r.optInt(m);
                ar.rings[k] = pts;
            }
            out.add(ar);
        }
    }

    /** Точка всередині (правило парності — враховує й «дірки», як м. Київ усередині області). */
    static boolean contains(Area a, double x, double y) {
        if (x < a.bbox[0] || x > a.bbox[2] || y < a.bbox[1] || y > a.bbox[3]) return false;
        boolean in = false;
        for (int[] r : a.rings) {
            int n = r.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
            }
        }
        return in;
    }

    /** Відстань до межі області/району, м (наближено: рівнокутна проєкція в околі точки). */
    static double borderDistanceM(Area a, double lat, double lon) {
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)) / 1e4, ky = 110540.0 / 1e4;
        double px = lon * 1e4, py = lat * 1e4, best = Double.MAX_VALUE;
        for (int[] r : a.rings) {
            int n = r.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double ax = (r[2 * j] - px) * kx, ay = (r[2 * j + 1] - py) * ky;
                double bx = (r[2 * i] - px) * kx, by = (r[2 * i + 1] - py) * ky;
                double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
                double t = len > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len)) : 0;
                double cx = ax + t * dx, cy = ay + t * dy;
                best = Math.min(best, Math.sqrt(cx * cx + cy * cy));
            }
        }
        return best;
    }

    static String oblastName(int uid) {
        for (int i = 0; i < Locations.OBLAST_UID.length; i++) if (Locations.OBLAST_UID[i] == uid) return Locations.OBLAST_NAME[i];
        return null;
    }

    /** null — точка поза Україною (або в морі). */
    Place find(double lat, double lon) {
        double x = lon * 1e4, y = lat * 1e4;
        // 1) міста-області (м. Київ, м. Севастополь) — всередині інших областей
        for (Area o : oblasts) {
            String n = oblastName(o.uid);
            if (n != null && n.startsWith("м. ") && contains(o, x, y)) return place(n, 0, o, lat, lon);
        }
        // 2) район
        for (Area r : raions) {
            if (!contains(r, x, y)) continue;
            String n = oblastName(r.oblast);
            if (n == null) continue;
            return place(n, r.uid, r, lat, lon);
        }
        // 3) лише область
        for (Area o : oblasts) {
            if (!contains(o, x, y)) continue;
            String n = oblastName(o.uid);
            if (n != null) return place(n, 0, o, lat, lon);
        }
        return null;
    }

    private static Place place(String region, int raion, Area a, double lat, double lon) {
        Place p = new Place();
        p.region = region;
        p.raion = raion;
        p.borderM = borderDistanceM(a, lat, lon);
        return p;
    }
}
