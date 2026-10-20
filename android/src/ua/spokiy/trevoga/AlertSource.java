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
    }

    static int indexOf(String region) {
        for (int i = 0; i < REGIONS.length; i++) if (REGIONS[i].equals(region)) return i;
        return -1;
    }

    static String download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
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

    /** raion — uid району alerts.in.ua, 0 = вся область. */
    static Result check(String server, String region, int raion, boolean partial) throws Exception {
        String serverError = null;
        if (server != null && server.length() > 0) {
            try { return fromServer(server, region, raion, partial); }
            catch (Exception e) { serverError = e.getMessage(); }
        }
        Result r = fromUbilling(region);
        if (raion != 0) {
            // Обрано район: дані по всій області сигналу не дають — лише для довідки
            boolean oblAlert = r.state == ALERT;
            r.state = UNKNOWN;
            r.threats.clear();
            r.since = 0;
            r.note = (serverError != null || (server != null && server.length() > 0) ? "сервер недоступний" : "сервер ще не налаштовано")
                    + " · в області зараз " + (oblAlert ? "тривога" : "тиша");
            return r;
        }
        if (serverError != null) r.note = "сервер недоступний";
        return r;
    }

    static Result fromServer(String server, String region, int raion, boolean partial) throws Exception {
        int obl = Locations.oblastUid(region);
        if (obl < 0) throw new Exception("невідома область");
        JSONObject root = new JSONObject(download(server + "/status"));
        String air = root.optString("air", null);
        if (air == null) throw new Exception("неочікувана відповідь сервера");

        Result r = new Result();
        r.source = "сервер";
        char o = charAt(air, obl);
        char c = raion != 0 ? charAt(air, raion) : o;
        if (o == 'A' || c == 'A') r.state = ALERT;
        else if (c == 'P') r.state = partial ? ALERT : CLEAR;
        else if (c == 'N') r.state = CLEAR;
        else throw new Exception("неочікувана відповідь сервера");

        String raionName = raion != 0 ? raionName(raion) : null;
        JSONArray arr = root.optJSONArray("alerts");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            int u = toInt(a.opt("u"));
            int ao = toInt(a.opt("o"));
            if (ao != obl && u != obl) continue;
            if (raion != 0 && u != obl && u != raion) {
                String rn = a.isNull("r") ? null : a.optString("r", null);
                if (rn == null || !rn.equals(raionName)) continue;
            }
            if (raion == 0 && u != obl && !(partial || o == 'A')) continue;   // лише частина області
            if (!a.isNull("s")) {
                long st = parseIso(a.optString("s", ""));
                if (st > 0 && (r.since == 0 || st < r.since)) r.since = st;
            }
            JSONArray t = a.optJSONArray("t");
            if (t != null) for (int j = 0; j < t.length(); j++) {
                String ts = t.optString(j, "");
                if (ts.length() > 0 && !r.threats.contains(ts)) r.threats.add(ts);
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
