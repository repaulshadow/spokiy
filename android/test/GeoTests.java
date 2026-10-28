package ua.spokiy.trevoga;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Автотести автовизначення району: межі (справжня карта) і фільтр геопозиції (РЕБ, підміна, поїздки). */
public class GeoTests {
    static int passed, failed;
    static void check(boolean c, String n) { if (c) passed++; else { failed++; System.out.println("FAIL: " + n); } }
    static void eq(Object e, Object a, String n) {
        boolean ok = e == null ? a == null : e.equals(a);
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + n + " — очікувалось «" + e + "», отримано «" + a + "»"); }
    }
    static int uid(String raion) {
        for (int i = 0; i < Locations.RAION_NAME.length; i++) if (Locations.RAION_NAME[i].equals(raion)) return Locations.RAION_UID[i];
        throw new RuntimeException(raion);
    }

    public static void main(String[] a) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(a.length > 0 ? a[0] : "res/raw/geo.json")), StandardCharsets.UTF_8);
        long t = System.nanoTime();
        GeoLookup g = GeoLookup.parse(json);
        System.out.println("geo.json розібрано за " + (System.nanoTime() - t) / 1000000 + " мс");
        realMap(g);
        filter(g);
        synthetic();
        System.out.println("Пройдено: " + passed + ", помилок: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void at(GeoLookup g, double lat, double lon, String region, String raion, String name) {
        GeoLookup.Place p = g.find(lat, lon);
        if (region == null) { eq(null, p == null ? null : p.title(), name); return; }
        if (p == null) { failed++; System.out.println("FAIL: " + name + " — точку не знайдено"); return; }
        eq(region, p.region, name + ": область");
        eq(raion == null ? 0 : uid(raion), p.raion, name + ": район");
    }

    static void realMap(GeoLookup g) {
        eq(27, g.oblasts.size(), "областей у карті");
        check(g.raions.size() >= 126, "районів у карті");
        at(g, 46.4825, 30.7233, "Одеська область", "Одеський район", "Одеса, центр");
        at(g, 46.3800, 30.7300, "Одеська область", "Одеський район", "Одеса, Аркадія");
        at(g, 45.3516, 28.8365, "Одеська область", "Ізмаїльський район", "Ізмаїл");
        at(g, 46.1900, 30.3400, "Одеська область", "Білгород-Дністровський район", "Білгород-Дністровський");
        at(g, 50.4501, 30.5234, "м. Київ", null, "Київ, Майдан");
        at(g, 50.5110, 30.7900, "Київська область", "Броварський район", "Бровари");
        at(g, 49.8397, 24.0297, "Львівська область", "Львівський район", "Львів");
        at(g, 49.9935, 36.2304, "Харківська область", "Харківський район", "Харків");
        at(g, 48.4647, 35.0462, "Дніпропетровська область", "Дніпровський район", "Дніпро");
        at(g, 44.9521, 34.1024, "Автономна Республіка Крим", null, "Сімферополь (Крим — лише область)");
        at(g, 44.6166, 33.5254, "м. Севастополь", null, "Севастополь");
        at(g, 55.7558, 37.6173, null, null, "Москва — поза Україною");
        at(g, 45.0000, 31.5000, null, null, "Чорне море — поза Україною");
        at(g, 47.0105, 28.8638, null, null, "Кишинів — поза Україною");
        at(g, 52.2297, 21.0122, null, null, "Варшава — поза Україною");
        GeoLookup.Place p = g.find(46.4825, 30.7233);
        check(p != null && p.borderM > 1000, "Одеса, центр — далеко від межі району");
    }

    static GeoFilter.Fix fx(double lat, double lon, float acc, long t, boolean gps) { return new GeoFilter.Fix(lat, lon, acc, t, gps, false); }

    static void filter(GeoLookup g) {
        long T = 1_800_000_000_000L, M = 60000;
        GeoFilter f = new GeoFilter(g);
        GeoFilter.Decision d = f.offer(fx(46.4825, 30.7233, 30, T, true));
        check(d.accepted && d.changed, "перша точка — Одеський район");
        eq(uid("Одеський район"), f.current.raion, "поточний район — Одеський");

        d = f.offer(new GeoFilter.Fix(46.48, 30.72, 10, T + M, true, true));
        check(!d.accepted && d.reason.contains("mock"), "mock відкинуто");
        d = f.offer(fx(46.48, 30.72, 5000, T + M, false));
        check(!d.accepted && d.reason.contains("точність"), "низька точність відкинута");
        d = f.offer(fx(55.7558, 37.6173, 10, T + 2 * M, true));
        check(!d.accepted && d.reason.contains("поза Україною"), "підміна GPS у Москву відкинута");
        d = f.offer(fx(45.0, 31.5, 10, T + 2 * M, true));
        check(!d.accepted, "точка в морі відкинута");
        d = f.offer(fx(50.4501, 30.5234, 10, T + 3 * M, true));
        check(!d.accepted && d.reason.contains("стрибок"), "стрибок Одеса → Київ за хвилину відкинуто");
        eq(uid("Одеський район"), f.current.raion, "після стрибків район той самий");

        // мережа в Одесі, а GPS «показує» Ізмаїл — РЕБ
        d = f.offer(fx(46.4700, 30.7100, 400, T + 4 * M, false));
        check(d.accepted, "мережева точка прийнята");
        d = f.offer(fx(45.3516, 28.8365, 8, T + 4 * M + 20000, true));
        check(!d.accepted && (d.reason.contains("мереж") || d.reason.contains("стрибок")), "GPS розходиться з мережею — відкинуто");

        // поїздка трасою Одеса → Білгород-Дністровський: ~60 км за ~55 хв, точки щохвилини
        double[][] route = {{46.43, 30.70}, {46.38, 30.66}, {46.33, 30.60}, {46.28, 30.52}, {46.24, 30.45}, {46.20, 30.36}, {46.19, 30.34}};
        long tt = T + 10 * M;
        boolean reached = false;
        for (int i = 0; i < route.length; i++) {
            for (int k = 0; k < 8; k++) {
                double frac = k / 8.0;
                double[] a = route[Math.max(0, i - 1)], b = route[i];
                tt += M;
                d = f.offer(fx(a[0] + (b[0] - a[0]) * frac, a[1] + (b[1] - a[1]) * frac, 20, tt, true));
                check(d.accepted, "поїздка: точка " + i + "/" + k + " прийнята (" + d.reason + ")");
            }
        }
        for (int k = 0; k < 3; k++) { tt += M; f.offer(fx(46.19, 30.34, 20, tt, true)); }
        reached = f.current.raion == uid("Білгород-Дністровський район");
        check(reached, "після поїздки — Білгород-Дністровський район");

        // телефон вимкнули, приїхали в Київ (через 1 год, ~430 км — швидше 180 км/год): спершу відкидаємо,
        // потім 15+ хв стабільно в Києві й є мережева точка — приймаємо
        tt += 60 * M;
        d = f.offer(fx(50.45, 30.52, 20, tt, true));
        check(!d.accepted, "довга поїздка: перша точка в Києві — ще не віримо");
        for (int k = 1; k <= 16; k++) {
            tt += M;
            d = f.offer(fx(50.45 + 0.001 * (k % 3), 30.52, 25, tt, k % 4 != 0));
        }
        check(d.accepted, "довга поїздка: стабільно 15+ хв і є мережа — прийнято");
        eq("м. Київ", f.current.region, "довга поїздка: тепер м. Київ");

        // підміна, що «стоїть» далеко довго, але лише GPS (без мережі) — не приймаємо
        GeoFilter f2 = new GeoFilter(g);
        f2.offer(fx(46.4825, 30.7233, 30, T, false));
        long t2 = T;
        for (int k = 0; k < 30; k++) { t2 += M; d = f2.offer(fx(49.8397, 24.0297, 5, t2, true)); }
        check(!d.accepted, "стабільна підміна лише GPS (без мережі) 30 хв — не прийнята");
        eq(uid("Одеський район"), f2.current.raion, "підміна: район лишився Одеський");
    }

    /** Штучна карта: два райони-квадрати поруч, межа по довготі 30.0 — перевірка «дребезгу» на межі. */
    static void synthetic() throws Exception {
        int a = uid("Одеський район"), b = uid("Березівський район");
        String json = "{\"oblasts\":[{\"n\":\"Одеська область\",\"u\":18,\"b\":[290000,460000,310000,470000],\"g\":[[290000,460000,310000,460000,310000,470000,290000,470000]]}],"
                + "\"raions\":[{\"n\":\"A\",\"u\":" + a + ",\"o\":18,\"b\":[290000,460000,300000,470000],\"g\":[[290000,460000,300000,460000,300000,470000,290000,470000]]},"
                + "{\"n\":\"B\",\"u\":" + b + ",\"o\":18,\"b\":[300000,460000,310000,470000],\"g\":[[300000,460000,310000,460000,310000,470000,300000,470000]]}]}";
        GeoLookup g = GeoLookup.parse(json);
        GeoFilter f = new GeoFilter(g);
        long T = 1_800_000_000_000L;
        f.offer(fx(46.5, 29.995, 20, T, true));
        eq(a, f.current.raion, "межа: старт у районі A (за ~400 м від межі)");
        // 50 м за межею, точність 200 м — одна точка не перемикає
        GeoFilter.Decision d = f.offer(fx(46.5, 30.0006, 200, T + 10000, true));
        check(d.accepted && !d.changed, "межа: одна непевна точка не перемикає");
        d = f.offer(fx(46.5, 29.9996, 200, T + 20000, true));
        eq(a, f.current.raion, "межа: повернувся — район A");
        d = f.offer(fx(46.5, 30.0006, 200, T + 30000, true));
        d = f.offer(fx(46.5, 30.0007, 200, T + 70000, true));
        check(d.changed && f.current.raion == b, "межа: дві узгоджені точки з проміжком — перемкнуло на B");
        // впевнено всередині (5 км від межі) — одразу
        GeoFilter f2 = new GeoFilter(g);
        f2.offer(fx(46.5, 29.9, 20, T, true));
        d = f2.offer(fx(46.5, 30.07, 20, T + 600000, true));
        check(d.changed && f2.current.raion == b, "межа: впевнено в новому районі — одразу");
    }
}
