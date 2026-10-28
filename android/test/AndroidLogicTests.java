package ua.spokiy.trevoga;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/** Автотести логіки Android-версії (звичайна Java, без емулятора). Код виходу 0 — усе гаразд. */
public class AndroidLogicTests {
    static int passed, failed;

    static void check(boolean c, String name) {
        if (c) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }
    static void eq(Object e, Object a, String name) {
        boolean ok = e == null ? a == null : e.equals(a);
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name + " — очікувалось «" + e + "», отримано «" + a + "»"); }
    }

    static final String OD = "Одеська область", ODR = "Одеський район", IZM = "Ізмаїльський район";
    static int uid(String raion) {
        for (int i = 0; i < Locations.RAION_NAME.length; i++) if (Locations.RAION_NAME[i].equals(raion)) return Locations.RAION_UID[i];
        throw new RuntimeException("немає району " + raion);
    }
    static String iso(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    /** Корінь у форматі нашого сервера. */
    static class Root {
        char[] air = new char[2000];
        JSONArray alerts = new JSONArray(), nr = new JSONArray(), no = new JSONArray();
        JSONArray ur, uo, uc;
        boolean withAir = true, withNep = true;
        Root() { Arrays.fill(air, 'N'); }
        Root ain(int u, String raion, String level, String since, String... t) throws Exception {
            air[u] = 'A';
            alerts.put(new JSONObject().put("u", u).put("o", u).put("on", OD).put("r", raion).put("s", since).put("l", level).put("t", new JSONArray(Arrays.asList(t))));
            return this;
        }
        Root partialAin(int u) { air[u] = 'P'; return this; }
        Root nep(String obl, String raion, String level, String since, String... t) {
            nr.put(new JSONArray().put(obl).put(raion).put(since).put(level).put(new JSONArray(Arrays.asList(t))));
            return this;
        }
        Root nepObl(String obl, String level) { no.put(new JSONArray().put(obl).put(iso(0)).put(level).put(new JSONArray())); return this; }
        Root ua() { if (ur == null) { ur = new JSONArray(); uo = new JSONArray(); uc = new JSONArray(); } return this; }
        Root ua(String obl, String raion, String level, String since) { ua(); ur.put(new JSONArray().put(obl).put(raion).put(since).put(level).put(new JSONArray())); return this; }
        Root uaObl(String obl, String level, String since) { ua(); uo.put(new JSONArray().put(obl).put(since).put(level).put(new JSONArray())); return this; }
        Root uaCom(String obl, String raion, String name, String level, String since) { ua(); uc.put(new JSONArray().put(obl).put(raion).put(name).put(since).put(level).put(new JSONArray())); return this; }
        JSONObject build() throws Exception {
            JSONObject r = new JSONObject();
            if (withAir) { r.put("air", new String(air)); r.put("alerts", alerts); }
            if (withNep) r.put("neptun", new JSONObject().put("r", nr).put("o", no));
            if (ur != null) r.put("ua", new JSONObject().put("r", ur).put("o", uo).put("c", uc));
            r.put("poll", 10);
            return r;
        }
    }

    static AlertSource.Result m(Root r, String raion, boolean partial) throws Exception {
        return AlertSource.fromRoot(r.build(), OD, raion == null ? 0 : uid(raion), partial);
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        String T0 = iso(t0 - 600000);
        testNorm();
        testMerge(T0);
        testUa(T0, t0);
        testEngine(t0);
        testDecide();
        testStreamParsing();
        System.out.println("Пройдено: " + passed + ", помилок: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void testNorm() {
        eq(AlertSource.norm("Кам'янський район"), AlertSource.norm("Кам’янський район"), "апостроф");
        eq("київ", AlertSource.norm("м. Київ"), "м. Київ");
        eq("крим", AlertSource.norm("АР Крим"), "Крим");
        eq(AlertSource.norm("Шептицький район"), AlertSource.norm("Червоноградський район"), "перейменування Шептицький");
        eq(AlertSource.norm("Самарівський район"), AlertSource.norm("Новомосковський район"), "перейменування Самарівський");
        eq(AlertSource.norm("Берестинський район"), AlertSource.norm("Красноградський район"), "перейменування Берестинський");
        eq(AlertSource.norm("Звягельський район"), AlertSource.norm("Новоград-Волинський район"), "перейменування Звягельський");
        eq(AlertSource.norm("Володимирський район"), AlertSource.norm("Володимир-Волинський район"), "перейменування Володимирський");
        eq(AlertSource.norm("Сіверськодонецький район"), AlertSource.norm("Сєвєродонецький район"), "перейменування Сіверськодонецький");
    }

    static void testMerge(String T0) throws Exception {
        AlertSource.Result r = m(new Root(), ODR, false);
        eq(AlertSource.CLEAR, r.state, "усе тихо");
        eq("джерела збігаються", r.note, "усе тихо: примітка");
        r = m(new Root().nep(OD, ODR, "yellow", T0, "drones"), ODR, false);
        eq(AlertSource.ALERT, r.state, "лише NEPTUN: тривога");
        eq("yellow", r.level, "лише NEPTUN: жовтий");
        check(r.disagree, "лише NEPTUN: розбіжність");
        r = m(new Root().ain(uid(ODR), ODR, "yellow", T0, "drones").nep(OD, ODR, "red", T0, "unspecified_missiles"), ODR, false);
        eq("red", r.level, "рівень — найвищий");
        check(r.threats.contains("drones") && r.threats.contains("unspecified_missiles"), "загрози — об'єднання");
        r = m(new Root().nep(OD, IZM, "red", T0), ODR, false);
        eq(AlertSource.CLEAR, r.state, "сусідній район — тиша");
        r = m(new Root().nep("Миколаївська область", ODR, "red", T0), ODR, false);
        eq(AlertSource.CLEAR, r.state, "район з іншої області — тиша");
        r = m(new Root().nepObl(OD, "red"), ODR, false);
        eq(AlertSource.ALERT, r.state, "тривога по всій області");
        r = m(new Root().partialAin(uid(ODR)), ODR, false);
        eq(AlertSource.CLEAR, r.state, "часткова без галочки");
        r = m(new Root().partialAin(uid(ODR)), ODR, true);
        eq(AlertSource.ALERT, r.state, "часткова з галочкою");
        r = AlertSource.fromRoot(new Root().nep("Львівська область", "Червоноградський район", "red", T0).build(), "Львівська область", uid("Шептицький район"), false);
        eq(AlertSource.ALERT, r.state, "стара назва району в NEPTUN");
        r = m(new Root().ain(uid(ODR), ODR, "red", T0, "drones", "ballistic_missiles"), ODR, false);
        eq("ballistic_missiles", r.threats.get(0), "балістика першою");
    }

    static void testUa(String T0, long now) throws Exception {
        AlertSource.Result r = m(new Root().ua(), ODR, false);
        eq(AlertSource.CLEAR, r.uaState, "UA: тиша");
        check(r.source.contains("UkraineAlarm"), "UA: джерело в переліку");
        r = m(new Root().ua(OD, ODR, "yellow", T0), ODR, false);
        eq(AlertSource.ALERT, r.state, "UA: тривога лише з UkraineAlarm");
        check(r.note.contains("UkraineAlarm"), "UA: розбіжність названо");
        r = m(new Root().uaObl(OD, "red", T0), ODR, false);
        eq(AlertSource.ALERT, r.state, "UA: область");
        r = m(new Root().ua(OD, IZM, "red", T0), ODR, false);
        eq(AlertSource.CLEAR, r.state, "UA: сусідній район");
        r = m(new Root().uaCom(OD, ODR, "Х", "red", T0), ODR, false);
        eq(AlertSource.CLEAR, r.state, "UA: громада без галочки");
        r = m(new Root().uaCom(OD, ODR, "Х", "red", T0), ODR, true);
        eq(AlertSource.ALERT, r.state, "UA: громада з галочкою");
        r = m(new Root().ain(uid(ODR), ODR, "red", T0).nep(OD, ODR, "red", T0).ua(OD, ODR, "red", T0), ODR, false);
        eq("джерела збігаються", r.note, "UA: три джерела збігаються");
        Root late = new Root().ain(uid(ODR), ODR, "red", T0).nep(OD, ODR, "red", T0).ua();
        r = AlertSource.fromRoot(late.build(), OD, uid(ODR), false, now, now + 90000);
        eq(AlertSource.CLEAR, r.state, "UA: офіційний відбій одразу");
        check(r.note.contains("UkraineAlarm"), "UA: примітка відбою");
        r = AlertSource.fromRoot(new Root().nep(OD, ODR, "red", iso(now + 200000)).ua().build(), OD, uid(ODR), false, now, now + 90000);
        eq(AlertSource.ALERT, r.state, "UA: нова тривога NEPTUN після вікна — тривога");
    }

    static void testEngine(long now) throws Exception {
        String T0 = iso(now - 600000);
        int u = uid(ODR);
        Engine e = new Engine();
        eq(null, e.compute(null, OD, u, false, now), "Engine: без даних — null");
        // UA з потоку: тривога
        JSONObject uaAlert = new Root().ua(OD, ODR, "red", T0).build().getJSONObject("ua");
        JSONObject uaClear = new Root().ua().build().getJSONObject("ua");
        e.serverOk(new Root().nep(OD, ODR, "red", T0).ain(u, ODR, "red", T0).ua(OD, ODR, "red", T0).build(), now);
        AlertSource.Result r = e.compute(uaAlert, OD, u, false, now + 1000);
        eq(AlertSource.ALERT, r.state, "Engine: тривога");
        check(r.source.contains("UkraineAlarm наживо"), "Engine: потік позначено");
        // офіційний відбій із потоку, сервер ще показує тривогу (NEPTUN/alerts.in.ua)
        r = e.compute(uaClear, OD, u, false, now + 2000);
        eq(AlertSource.CLEAR, r.state, "Engine: офіційний відбій одразу");
        // потік зник, а остання відповідь сервера СТАРША за дані потоку (там UA ще «тривога») — не беремо
        r = e.compute(null, OD, u, false, now + 3000);
        eq(AlertSource.CLEAR, r.state, "Engine: старі дані сервера не повертають тривогу");
        // свіжіша відповідь сервера з тривогою в NEPTUN (стара тривога, 90 с вікна) — все одно тиша
        e.serverOk(new Root().nep(OD, ODR, "red", T0).ua().build(), now + 4000);
        r = e.compute(null, OD, u, false, now + 4000);
        eq(AlertSource.CLEAR, r.state, "Engine: запізнілий NEPTUN після офіційного відбою — тиша");
        // через 3+ хв вікно закривається: якщо NEPTUN усе ще показує тривогу — тривога
        e.serverOk(new Root().nep(OD, ODR, "red", T0).ua().build(), now + 200000);
        r = e.compute(null, OD, u, false, now + 200000);
        eq(AlertSource.ALERT, r.state, "Engine: після вікна NEPTUN знову враховується");
        // сервер недоступний — примітка
        Engine e2 = new Engine();
        e2.serverFailed("тест");
        r = e2.compute(uaAlert, OD, u, false, now);
        check(r.note.startsWith("сервер недоступний"), "Engine: примітка про сервер");
        eq(AlertSource.ALERT, r.state, "Engine: працює лише потік");
        // дані сервера старші за 90 с не використовуються
        Engine e3 = new Engine();
        e3.serverOk(new Root().nep(OD, ODR, "red", T0).build(), now);
        eq(null, e3.compute(null, OD, u, false, now + 100000), "Engine: застарілий сервер — немає даних");
    }

    static AlertSource.Result res(int state, String level, String... t) {
        AlertSource.Result r = new AlertSource.Result();
        r.state = state; r.level = level; r.threats.addAll(Arrays.asList(t));
        return r;
    }

    static void testDecide() {
        List<String> none = new ArrayList<String>();
        eq(Engine.NONE, Engine.decide(AlertSource.UNKNOWN, none, "", res(AlertSource.ALERT, "red")).kind, "перша перевірка — без звуку");
        eq(Engine.ALARM, Engine.decide(AlertSource.CLEAR, none, "", res(AlertSource.ALERT, "red")).kind, "тривога");
        eq(Engine.CLEAR, Engine.decide(AlertSource.ALERT, none, "red", res(AlertSource.CLEAR, "")).kind, "відбій");
        eq(Engine.ESCALATION, Engine.decide(AlertSource.ALERT, none, "yellow", res(AlertSource.ALERT, "red")).kind, "жовтий → червоний");
        eq(Engine.BALLISTIC, Engine.decide(AlertSource.ALERT, Arrays.asList("drones"), "red", res(AlertSource.ALERT, "red", "drones", "ballistic_missiles")).kind, "балістика");
        eq(Engine.NEW_THREATS, Engine.decide(AlertSource.ALERT, Arrays.asList("drones"), "red", res(AlertSource.ALERT, "red", "drones", "cruise_missiles")).kind, "нова загроза");
        eq(Engine.NONE, Engine.decide(AlertSource.ALERT, Arrays.asList("drones"), "red", res(AlertSource.ALERT, "red", "drones")).kind, "без змін — тиша");
        eq(Engine.NONE, Engine.decide(AlertSource.ALERT, none, "red", res(AlertSource.UNKNOWN, "")).kind, "немає даних — тиша");
        // після обриву (старий ВІДОМИЙ стан — тривога): та сама тривога — без повторної сирени; відбій — звучить
        eq(Engine.NONE, Engine.decide(AlertSource.ALERT, none, "red", res(AlertSource.ALERT, "red")).kind, "після обриву — без повторної сирени");
        eq(Engine.CLEAR, Engine.decide(AlertSource.ALERT, none, "red", res(AlertSource.CLEAR, "")).kind, "після обриву — відбій звучить");
    }

    static void testStreamParsing() throws Exception {
        String msg = "{\"type\":\"alerts\",\"source\":\"ukrainealarm\",\"data\":{\"raions\":[{\"name\":\"" + ODR + "\",\"oblast\":\"" + OD
                + "\",\"since\":\"2026-10-28T10:00:00Z\",\"level\":\"red\",\"reasons\":[\"Балістика\",\"Дрони\"]}],\"oblasts\":[],"
                + "\"communities\":[{\"oblast\":\"" + OD + "\",\"raion\":\"" + ODR + "\",\"name\":\"Х\",\"since\":\"2026-10-28T10:00:00Z\",\"level\":\"yellow\"}]}}";
        StreamClient c = new StreamClient("t", "");
        final int[] n = {0};
        c.listener = new StreamClient.Listener() { public void changed() { n[0]++; } public void lost() { } };
        c.handle(msg);
        c.handle(msg);                                   // те саме — без події
        c.handle("{\"type\":\"heartbeat\"}");
        c.handle("{\"type\":\"snapshot\",\"data\":{\"threats\":[]}}");
        eq(1, n[0], "потік: одна подія на однакові дані");
        JSONObject d = c.data();
        eq(1, d.getJSONArray("r").length(), "потік: район");
        eq(1, d.getJSONArray("c").length(), "потік: громада");
        JSONObject root = new JSONObject().put("ua", d);
        AlertSource.Result r = AlertSource.fromRoot(root, OD, uid(ODR), false);
        eq(AlertSource.ALERT, r.state, "потік: тривога");
        check(r.threats.contains("ballistic_missiles") && r.threats.contains("drones"), "потік: типи загроз з причин");
        byte[] k = new byte[]{1, 2, 3, 4, 5, (byte) 250, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        eq(java.util.Base64.getEncoder().encodeToString(k), StreamClient.base64(k), "base64 ключа WebSocket");
        eq(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2}), StreamClient.base64(new byte[]{1, 2}), "base64 з доповненням");
        eq("wss://x.workers.dev/stream", MonitorService.streamUrl("https://x.workers.dev/"), "адреса потоку");
    }
}
