package ua.spokiy.trevoga;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Отримання статусу. Основне джерело — сервер-посередник (адреса в config.json; токен alerts.in.ua
 * зберігається лише на сервері). Якщо сервер недоступний — ubilling.net.ua (лише області).
 */
final class AlertSource {
    static final int UNKNOWN = 0, ALERT = 1, CLEAR = 2;

    static final String CONFIG_URL = "https://raw.githubusercontent.com/repaulshadow/spokiy/main/config.json";

    static final String[] REGIONS = {
        "Автономна Республіка Крим", "Волинська область", "Вінницька область",
        "Дніпропетровська область", "Донецька область", "Житомирська область",
        "Закарпатська область", "Запорізька область", "Івано-Франківська область",
        "м. Київ", "Київська область", "Кіровоградська область", "Луганська область",
        "Львівська область", "Миколаївська область", "Одеська область",
        "Полтавська область", "Рівненська область", "м. Севастополь", "Сумська область",
        "Тернопільська область", "Харківська область", "Херсонська область",
        "Хмельницька область", "Черкаська область", "Чернівецька область",
        "Чернігівська область"
    };

    static final class Result {
        int state = UNKNOWN;
        List<String> threats = new ArrayList<String>();
        long since = 0;          // мс, 0 — невідомо
        String source = "";
        String note = "";
        boolean disagree = false;
        String level = "";       // рівень тривоги: "red" | "yellow" | "" (невідомо)
        int pollS = 0;           // рекомендована сервером частота опитування, с
        int ainState = UNKNOWN, nepState = UNKNOWN, uaState = UNKNOWN;   // що показує кожне джерело
    }

    static int indexOf(String region) {
        for (int i = 0; i < REGIONS.length; i++) if (REGIONS[i].equals(region)) return i;
        return -1;
    }

    static String download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Trevoga-Android/2.0");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("сервер відповів " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---------- адреса сервера ----------

    private static String parseServer(String json) {
        try {
            String s = new JSONObject(json).optString("server", "").trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    static String embeddedServer(Context ctx) {
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.config);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return parseServer(sb.toString());
        } catch (Exception e) {
            return "";
        }
    }

    /** Вбудований config.json (у збірці для автотестів: "fixed", "auto_place", "geo_fast"). */
    static JSONObject embeddedConfig(Context ctx) {
        try {
            InputStream in = ctx.getResources().openRawResource(R.raw.config);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** "fixed": true — адресу сервера не оновлюємо з GitHub (збірка для автотестів). */
    static boolean embeddedFixed(Context ctx) { return embeddedConfig(ctx).optBoolean("fixed", false); }

    /** null — не вдалося завантажити (наприклад, репозиторій закритий). */
    static String fetchServer() {
        try {
            String s = parseServer(download(CONFIG_URL + "?t=" + System.currentTimeMillis()));
            return s.length() > 0 ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String server(Context ctx) {
        String s = Prefs.get(ctx).getString(Prefs.SERVER, "");
        return s.length() > 0 ? s : embeddedServer(ctx);
    }

    // ---------- назви ----------

    static String threatTitle(String t) {
        if (t.equals("ballistic_missiles")) return "Балістика";
        if (t.equals("cruise_missiles")) return "Крилаті ракети";
        if (t.equals("unspecified_missiles")) return "Ракети";
        if (t.equals("drones")) return "Дрони";
        if (t.equals("guided_aerial_bombs")) return "КАБи";
        if (t.equals("tactic_aircraft_activity")) return "Тактична авіація";
        if (t.equals("strategic_aircraft_activity")) return "Стратегічна авіація";
        if (t.equals("mig31k_departure")) return "Зліт МіГ-31К";
        if (t.equals("air_defense")) return "Робота ППО";
        if (t.equals("artillery_shelling")) return "Артобстріл";
        if (t.equals("urban_fights")) return "Вуличні бої";
        if (t.equals("chemical")) return "Хімічна загроза";
        if (t.equals("nuclear")) return "Радіаційна загроза";
        return "Інша загроза";
    }

    static String titles(List<String> types) {
        StringBuilder sb = new StringBuilder();
        for (String t : types) { if (sb.length() > 0) sb.append(" · "); sb.append(threatTitle(t)); }
        return sb.toString();
    }

    static String raionName(int uid) {
        for (int i = 0; i < Locations.RAION_UID.length; i++) if (Locations.RAION_UID[i] == uid) return Locations.RAION_NAME[i];
        return null;
    }

    private static final List<String> ORDER = Arrays.asList("ballistic_missiles", "cruise_missiles",
            "unspecified_missiles", "mig31k_departure", "guided_aerial_bombs", "drones",
            "strategic_aircraft_activity", "tactic_aircraft_activity", "air_defense",
            "artillery_shelling", "urban_fights", "chemical", "nuclear");

    private static int toInt(Object o) {
        if (o == null || o == JSONObject.NULL) return -1;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return -1; }
    }

    private static char charAt(String str, int uid) throws Exception {
        if (uid < 0 || uid >= str.length() || str.charAt(uid) == ' ') throw new Exception("неочікувана відповідь сервера");
        return Character.toUpperCase(str.charAt(uid));
    }

    private static long parseIso(String s) {
        try {
            String x = s.replace("Z", "");
            int dot = x.indexOf('.');
            if (dot > 0) x = x.substring(0, dot);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(x).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- перевірка ----------

    /** Відповідь сервера /status (усі джерела); виняток — сервер недоступний. */
    static JSONObject fetchRoot(String server) throws Exception {
        JSONObject root = new JSONObject(download(server + "/status"));
        if (!root.has("air") && !root.has("neptun") && !root.has("ua")) throw new Exception("неочікувана відповідь сервера");
        return root;
    }

    /** raion — uid району alerts.in.ua, 0 = вся область. */
    static Result check(String server, String region, int raion, boolean partial) throws Exception {
        String serverError = null;
        if (server != null && server.length() > 0) {
            try { return fromServer(server, region, raion, partial); }
            catch (Exception e) { serverError = shortErr(e); }
        }
        return fallback(region, raion, partial, serverError);
    }

    /** Запасні джерела, коли немає ні сервера, ні потоку. */
    static Result fallback(String region, int raion, boolean partial, String serverError) throws Exception {
        // Запасний шлях 1: NEPTUN напряму (дані по районах, без ключа)
        try {
            Result n = fromNeptunDirect(region, raion, partial);
            n.note = (serverError != null ? "сервер недоступний (" + serverError + ")" : "сервер ще не налаштовано")
                    + (n.note.length() > 0 ? " · " + n.note : "");
            return n;
        } catch (Exception ignored) { }
        // Запасний шлях 2: ubilling.net.ua (лише області)
        Result r = fromUbilling(region);
        if (raion != 0) {
            // Обрано район: дані по всій області сигналу не дають — лише для довідки
            boolean oblAlert = r.state == ALERT;
            r.state = UNKNOWN;
            r.threats.clear();
            r.since = 0;
            r.note = (serverError != null ? "сервер і NEPTUN недоступні" : "NEPTUN недоступний")
                    + " · в області зараз " + (oblAlert ? "тривога" : "тиша");
            return r;
        }
        if (serverError != null) r.note = "сервер недоступний (" + serverError + ")";
        return r;
    }

    static String shortErr(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    static String NEPTUN_BASE = "https://neptun.in.ua";

    /** NEPTUN напряму: перетворюємо їхню відповідь у формат нашого сервера і розбираємо тим самим кодом. */
    static Result fromNeptunDirect(String region, int raion, boolean partial) throws Exception {
        JSONObject j = new JSONObject(download(NEPTUN_BASE + "/api/v1/alerts"));
        JSONArray rs = j.optJSONArray("raions"), os = j.optJSONArray("oblasts");
        if (rs == null && os == null) throw new Exception("NEPTUN: неочікувана відповідь");
        JSONArray r = new JSONArray(), o = new JSONArray();
        if (rs != null) for (int i = 0; i < rs.length(); i++) {
            JSONObject x = rs.optJSONObject(i);
            if (x == null) continue;
            JSONArray e = new JSONArray();
            e.put(x.optString("oblast", "")); e.put(x.optString("name", "")); e.put(x.optString("since", ""));
            e.put(x.optString("level", "").toLowerCase(Locale.ROOT));
            r.put(e);
        }
        if (os != null) for (int i = 0; i < os.length(); i++) {
            JSONObject x = os.optJSONObject(i);
            if (x == null) continue;
            JSONArray e = new JSONArray();
            e.put(x.optString("name", "")); e.put(x.optString("since", ""));
            e.put(x.optString("level", "").toLowerCase(Locale.ROOT));
            o.put(e);
        }
        JSONObject nep = new JSONObject();
        nep.put("r", r);
        nep.put("o", o);
        JSONObject root = new JSONObject();
        root.put("neptun", nep);
        Result res = fromRoot(root, region, raion, partial);
        res.source = "NEPTUN напряму";
        return res;
    }

    /** Нормалізація назв для порівняння з NEPTUN: апостроф, «м.», Крим. */
    static String norm(String x) {
        if (x == null) return "";
        String t = x.trim().toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u02BC', '\'').replace('`', '\'');
        if (t.startsWith("м. ")) t = t.substring(3);
        if (t.equals("ар крим") || t.equals("автономна республіка крим")) t = "крим";
        String alias = RENAMED.get(t);
        return alias != null ? alias : t;
    }

    /** Перейменовані райони: NEPTUN (карта) подекуди вживає старі назви — зводимо до нових. */
    private static final java.util.Map<String, String> RENAMED = new java.util.HashMap<String, String>();
    static {
        RENAMED.put("володимир-волинський район", "володимирський район");
        RENAMED.put("новомосковський район", "самарівський район");
        RENAMED.put("новоград-волинський район", "звягельський район");
        RENAMED.put("сєвєродонецький район", "сіверськодонецький район");
        RENAMED.put("червоноградський район", "шептицький район");
        RENAMED.put("красноградський район", "берестинський район");
    }

    /** «reasons» (напр. «Дронова загроза (жовтий рівень)») → типи загроз як у alerts.in.ua. */
    static JSONArray reasonTypes(JSONArray reasons) {
        StringBuilder sb = new StringBuilder();
        if (reasons != null) for (int i = 0; i < reasons.length(); i++) sb.append(' ').append(reasons.optString(i, ""));
        String txt = sb.toString().toLowerCase(Locale.ROOT);
        JSONArray t = new JSONArray();
        if (txt.contains("баліст")) t.put("ballistic_missiles");
        if (txt.contains("крилат")) t.put("cruise_missiles");
        if (txt.contains("ракет") && t.length() == 0) t.put("unspecified_missiles");
        if (txt.contains("міг") || txt.contains("mig")) t.put("mig31k_departure");
        if (txt.contains("каб") || txt.contains("авіабомб") || txt.contains("керован")) t.put("guided_aerial_bombs");
        if (txt.contains("дрон") || txt.contains("бпла") || txt.contains("шахед") || txt.contains("безпілот")) t.put("drones");
        if (txt.contains("артобстріл") || txt.contains("артилер")) t.put("artillery_shelling");
        return t;
    }

    /** Повідомлення потоку (формат NEPTUN: raions/oblasts/communities) → формат сервера {r, o, c}. null — не той формат. */
    static JSONObject streamToNep(JSONObject data) {
        if (data == null) return null;
        JSONArray rs = data.optJSONArray("raions"), os = data.optJSONArray("oblasts"), cs = data.optJSONArray("communities");
        if (rs == null && os == null) return null;
        try {
            JSONArray r = new JSONArray(), o = new JSONArray(), c = new JSONArray();
            if (rs != null) for (int i = 0; i < rs.length(); i++) {
                JSONObject x = rs.optJSONObject(i);
                if (x == null) continue;
                r.put(new JSONArray().put(x.optString("oblast", "")).put(x.optString("name", "")).put(x.optString("since", ""))
                        .put(x.optString("level", "").toLowerCase(Locale.ROOT)).put(reasonTypes(x.optJSONArray("reasons"))));
            }
            if (os != null) for (int i = 0; i < os.length(); i++) {
                JSONObject x = os.optJSONObject(i);
                if (x == null) continue;
                o.put(new JSONArray().put(x.optString("name", "")).put(x.optString("since", ""))
                        .put(x.optString("level", "").toLowerCase(Locale.ROOT)).put(reasonTypes(x.optJSONArray("reasons"))));
            }
            if (cs != null) for (int i = 0; i < cs.length(); i++) {
                JSONObject x = cs.optJSONObject(i);
                if (x == null) continue;
                c.put(new JSONArray().put(x.optString("oblast", "")).put(x.optString("raion", "")).put(x.optString("name", ""))
                        .put(x.optString("since", "")).put(x.optString("level", "").toLowerCase(Locale.ROOT)).put(new JSONArray()));
            }
            return new JSONObject().put("r", r).put("o", o).put("c", c);
        } catch (Exception e) {
            return null;
        }
    }

    private static long earliest(long cur, long t) {
        return t > 0 && (cur == 0 || t < cur) ? t : cur;
    }

    static Result fromServer(String server, String region, int raion, boolean partial) throws Exception {
        JSONObject root = new JSONObject(download(server + "/status"));
        Result r = fromRoot(root, region, raion, partial);
        r.pollS = root.optInt("poll", 0);
        return r;
    }

    private static int sev(String lv) { return "red".equals(lv) ? 2 : "yellow".equals(lv) ? 1 : 0; }
    private static String maxLevel(String a, String b) { return sev(b) > sev(a) ? b : a; }

    private static void addTypes(List<String> dst, JSONArray t) {
        if (t == null) return;
        for (int j = 0; j < t.length(); j++) {
            String ts = t.optString(j, "");
            if (ts.length() > 0 && !dst.contains(ts)) dst.add(ts);
        }
    }

    static Result fromRoot(JSONObject root, String region, int raion, boolean partial) throws Exception {
        return fromRoot(root, region, raion, partial, 0, 0);
    }

    static final class SrcEval {
        int state = CLEAR;
        String level = "";
        List<String> threats = new ArrayList<String>();
        long since = 0;
    }

    /** Джерело у форматі NEPTUN: o — області, r — райони, c — громади (часткова тривога). */
    static SrcEval evalNepLike(JSONObject nep, String oblN, String raionN, int obl, int raion, boolean partial) {
        SrcEval res = new SrcEval();
        boolean oblWide = false, raionHit = false, partialHit = false;
        int alertedInObl = 0;
        JSONArray os = nep.optJSONArray("o");
        if (os != null) for (int i = 0; i < os.length(); i++) {
            JSONArray e = os.optJSONArray(i);
            if (e == null || !norm(e.optString(0, "")).equals(oblN)) continue;
            oblWide = true;
            res.since = earliest(res.since, parseIso(e.optString(1, "")));
            res.level = maxLevel(res.level, e.optString(2, ""));
            addTypes(res.threats, e.optJSONArray(3));
        }
        JSONArray rs = nep.optJSONArray("r");
        if (rs != null) for (int i = 0; i < rs.length(); i++) {
            JSONArray e = rs.optJSONArray(i);
            if (e == null || !norm(e.optString(0, "")).equals(oblN)) continue;
            alertedInObl++;
            boolean mine = raion != 0 && norm(e.optString(1, "")).equals(raionN);
            if (mine) raionHit = true;
            if (mine || raion == 0) {
                res.since = earliest(res.since, parseIso(e.optString(2, "")));
                res.level = maxLevel(res.level, e.optString(3, ""));
                addTypes(res.threats, e.optJSONArray(4));
            }
        }
        JSONArray cs = partial ? nep.optJSONArray("c") : null;
        if (cs != null) for (int i = 0; i < cs.length(); i++) {
            JSONArray e = cs.optJSONArray(i);
            if (e == null || !norm(e.optString(0, "")).equals(oblN)) continue;
            if (raion != 0 && !norm(e.optString(1, "")).equals(raionN)) continue;
            partialHit = true;
            res.since = earliest(res.since, parseIso(e.optString(3, "")));
            res.level = maxLevel(res.level, e.optString(4, ""));
        }
        int raionsInObl = 0;
        for (int i = 0; i < Locations.RAION_OBLAST.length; i++) if (Locations.RAION_OBLAST[i] == obl) raionsInObl++;
        boolean allRaions = raionsInObl > 0 && alertedInObl >= raionsInObl;
        if (raion != 0) res.state = (oblWide || raionHit || partialHit) ? ALERT : CLEAR;
        else res.state = (oblWide || allRaions || (partial && (alertedInObl > 0 || partialHit))) ? ALERT : CLEAR;
        return res;
    }

    /**
     * ignoreAinBefore / ignoreNepBefore (мс): офіційне джерело щойно дало відбій — тривоги alerts.in.ua / NEPTUN,
     * що почалися не пізніше цього часу, вважаємо застарілими.
     */
    static Result fromRoot(JSONObject root, String region, int raion, boolean partial, long ignoreAinBefore, long ignoreNepBefore) throws Exception {
        int obl = Locations.oblastUid(region);
        if (obl < 0) throw new Exception("невідома область");
        String air = root.isNull("air") ? null : root.optString("air", null);
        JSONObject nep = root.optJSONObject("neptun");
        if (air == null && nep == null && root.optJSONObject("ua") == null) throw new Exception("сервер без даних");

        String raionName = raion != 0 ? raionName(raion) : null;
        String oblN = norm(region), raionN = norm(raionName);
        Result r = new Result();
        int ain = UNKNOWN, nepState = UNKNOWN;
        long ainSince = 0;
        String ainLevel = "";
        List<String> ainThreats = new ArrayList<String>();
        boolean ainPartial = false;   // alerts.in.ua: тривога лише в частині району/області

        // --- Джерело 1: alerts.in.ua
        if (air != null) {
            try {
                char o = charAt(air, obl);
                char c = raion != 0 ? charAt(air, raion) : o;
                if (o == 'A' || c == 'A') ain = ALERT;
                else if (c == 'P') { ain = partial ? ALERT : CLEAR; ainPartial = true; }
                else if (c == 'N') ain = CLEAR;
                JSONArray arr = root.optJSONArray("alerts");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) continue;
                    int u = toInt(a.opt("u"));
                    int ao = toInt(a.opt("o"));
                    String on = a.isNull("on") ? null : a.optString("on", null);
                    String rn = a.isNull("r") ? null : a.optString("r", null);
                    // у районних тривог alerts.in.ua код області буває некоректним — звіряємо ще й за назвою
                    boolean inObl = u == obl || ao == obl || (on != null && norm(on).equals(oblN));
                    if (!inObl) continue;
                    if (raion != 0 && u != obl && u != raion && (rn == null || !norm(rn).equals(raionN))) continue;
                    if (!a.isNull("s")) ainSince = earliest(ainSince, parseIso(a.optString("s", "")));
                    if (!a.isNull("l")) ainLevel = maxLevel(ainLevel, a.optString("l", ""));
                    addTypes(ainThreats, a.optJSONArray("t"));
                }
            } catch (Exception e) {
                ain = UNKNOWN;
            }
        }

        // --- Джерело 2: NEPTUN; джерело 3: UkraineAlarm (офіційне, через наш сервер) — однаковий формат
        SrcEval nepE = nep != null ? evalNepLike(nep, oblN, raionN, obl, raion, partial) : null;
        JSONObject uaD = root.optJSONObject("ua");
        SrcEval uaE = uaD != null ? evalNepLike(uaD, oblN, raionN, obl, raion, partial) : null;
        int uaState = uaE != null ? uaE.state : UNKNOWN;
        if (nepE != null) nepState = nepE.state;
        // UkraineAlarm щойно дав відбій — тривоги, що почалися раніше, вважаємо застарілими (NEPTUN відстає до ~2 хв)
        boolean ainIgnored = false, nepIgnored = false;
        if (ain == ALERT && ignoreAinBefore > 0 && (ainSince == 0 || ainSince <= ignoreAinBefore)) {
            ain = CLEAR; ainPartial = false; ainIgnored = true;
        }
        if (nepE != null && nepE.state == ALERT && ignoreNepBefore > 0 && (nepE.since == 0 || nepE.since <= ignoreNepBefore)) {
            nepState = CLEAR; nepIgnored = true;
        }

        if (ain == UNKNOWN && nepState == UNKNOWN && uaState == UNKNOWN) throw new Exception("неочікувана відповідь сервера");

        // --- Зведення: тривога, якщо її показує хоча б одне джерело
        r.state = (ain == ALERT || nepState == ALERT || uaState == ALERT) ? ALERT : CLEAR;
        if (r.state == ALERT) {
            // Рівень — найвищий серед джерел, що бачать тривогу; якщо невідомий — червоний
            String lv = "";
            if (ain == ALERT || ainPartial) lv = maxLevel(lv, ainLevel);
            if (nepState == ALERT) lv = maxLevel(lv, nepE.level);
            if (uaState == ALERT) lv = maxLevel(lv, uaE.level);
            r.level = lv.length() > 0 ? lv : "red";
            for (String t : ainThreats) if (!r.threats.contains(t)) r.threats.add(t);
            if (nepState == ALERT) for (String t : nepE.threats) if (!r.threats.contains(t)) r.threats.add(t);
            if (uaState == ALERT) for (String t : uaE.threats) if (!r.threats.contains(t)) r.threats.add(t);
            if (ain == ALERT || ainPartial) r.since = earliest(r.since, ainSince);
            if (nepState == ALERT) r.since = earliest(r.since, nepE.since);
            if (uaState == ALERT) r.since = earliest(r.since, uaE.since);
        }
        List<String> names = new ArrayList<String>(), alerting = new ArrayList<String>();
        int known = 0, alerts = 0;
        if (ain != UNKNOWN) { names.add("alerts.in.ua"); known++; if (ain == ALERT) { alerts++; alerting.add("alerts.in.ua"); } }
        if (nepState != UNKNOWN) { names.add("NEPTUN"); known++; if (nepState == ALERT) { alerts++; alerting.add("NEPTUN"); } }
        if (uaState != UNKNOWN) { names.add("UkraineAlarm"); known++; if (uaState == ALERT) { alerts++; alerting.add("UkraineAlarm"); } }
        StringBuilder src = new StringBuilder();
        for (String n : names) { if (src.length() > 0) src.append(" + "); src.append(n); }
        r.source = src.toString();
        r.ainState = ain;
        r.nepState = nepE != null ? nepE.state : UNKNOWN;
        r.uaState = uaState;
        if ((ainIgnored || nepIgnored) && r.state == CLEAR) {
            r.note = nepIgnored ? "відбій за UkraineAlarm (інші джерела ще оновлюються)" : "відбій за NEPTUN (alerts.in.ua ще оновлюється)";
        } else if (known >= 2) {
            if (alerts == 0 || alerts == known) r.note = "джерела збігаються";
            else if (ainPartial && alerts == known - 1 && ain != ALERT)
                r.note = "alerts.in.ua: тривога в частині " + (raion != 0 ? "району" : "області") + ", інші: у всьому";
            else {
                StringBuilder w = new StringBuilder();
                for (String n : alerting) { if (w.length() > 0) w.append(" і "); w.append(n); }
                r.note = "джерела розходяться: тривогу показує " + (alerting.size() == 1 ? "лише " : "") + w;
                r.disagree = true;
            }
        }

        if (r.state != ALERT) { r.threats.clear(); r.since = 0; }
        Collections.sort(r.threats, new Comparator<String>() {
            public int compare(String x, String y) {
                int ix = ORDER.indexOf(x), iy = ORDER.indexOf(y);
                if (ix < 0) ix = 99;
                if (iy < 0) iy = 99;
                return ix - iy;
            }
        });
        return r;
    }

    static String decodeJsonEscapes(String s) {
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            char ch = (char) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static Result fromUbilling(String region) throws Exception {
        if (region.equals("Автономна Республіка Крим")) throw new Exception("для Криму потрібен сервер");
        String name = region.equals("м. Севастополь") ? "Севастополь" : region;
        String body = decodeJsonEscapes(download("https://ubilling.net.ua/aerialalerts/"));
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\{([^}]*)\\}", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!m.find()) throw new Exception("область не знайдено у відповіді");
        String block = m.group(1);
        Matcher a = Pattern.compile("\"alertnow\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(block);
        if (!a.find()) throw new Exception("неочікувана відповідь");
        Result r = new Result();
        r.source = "ubilling.net.ua";
        r.state = a.group(1).equalsIgnoreCase("true") ? ALERT : CLEAR;
        Matcher ch = Pattern.compile("\"changed\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
        if (r.state == ALERT && ch.find()) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("Europe/Kiev"));
                r.since = f.parse(ch.group(1)).getTime();
            } catch (Exception ignored) { }
        }
        return r;
    }
}
