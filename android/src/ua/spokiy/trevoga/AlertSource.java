package ua.spokiy.trevoga;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Получение статуса тревоги. Логика та же, что в Windows-версии. */
final class AlertSource {
    static final int UNKNOWN = 0, ALERT = 1, CLEAR = 2;

    // Порядок областей в строке alerts.in.ua /v1/iot/active_air_raid_alerts_by_oblast.json
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

    static int indexOf(String region) {
        for (int i = 0; i < REGIONS.length; i++) if (REGIONS[i].equals(region)) return i;
        return -1;
    }

    static String lastSource = "";

    static String download(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Trevoga-Android/1.0");
        c.setRequestProperty("Accept", "application/json");
        if (token != null && token.length() > 0) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new Exception("неверный токен");
            if (code == 429) throw new Exception("слишком частые запросы");
            if (code != 200) throw new Exception("сервер ответил " + code);
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
        return "Невідома загроза";
    }

    static String titles(List<String> types) {
        StringBuilder sb = new StringBuilder();
        for (String t : types) { if (sb.length() > 0) sb.append(" · "); sb.append(threatTitle(t)); }
        return sb.toString();
    }

    private static int toInt(Object o) {
        if (o == null || o == JSONObject.NULL) return -1;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return -1; }
    }

    private static final List<String> ORDER = Arrays.asList("ballistic_missiles", "cruise_missiles",
            "unspecified_missiles", "mig31k_departure", "guided_aerial_bombs", "drones",
            "strategic_aircraft_activity", "tactic_aircraft_activity", "air_defense",
            "artillery_shelling", "urban_fights", "chemical", "nuclear");

    /** Типы угроз по выбранной области/району (только с токеном), по важности. */
    static List<String> getThreats(String region, int raion, String token) throws Exception {
        int obl = Locations.oblastUid(region);
        String raionName = raion != 0 ? raionName(raion) : null;
        JSONObject root = new JSONObject(download("https://api.alerts.in.ua/v1/alerts/active.json", token.trim()));
        JSONArray arr = root.optJSONArray("alerts");
        final List<String> found = new ArrayList<String>();
        if (arr == null) return found;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            int locUid = toInt(a.opt("location_uid"));
            int locObl = toInt(a.opt("location_oblast_uid"));
            if (locObl < 0 && region.equals(a.optString("location_oblast"))) locObl = obl;
            if (locObl != obl && locUid != obl) continue;
            if (raion != 0 && locUid != obl && locUid != raion) {
                String r = a.optString("location_raion", null);
                if (r == null || !r.equals(raionName)) continue;
            }
            String type = a.optString("alert_type", "air_raid");
            if (!type.equals("air_raid") && !found.contains(type)) found.add(type);
            JSONArray th = a.optJSONArray("threats");
            if (th != null) for (int j = 0; j < th.length(); j++) {
                JSONObject t = th.optJSONObject(j);
                String tt = t != null ? t.optString("threat_type", null) : null;
                if (tt != null && !found.contains(tt)) found.add(tt);
            }
        }
        Collections.sort(found, new Comparator<String>() {
            public int compare(String x, String y) {
                int ix = ORDER.indexOf(x), iy = ORDER.indexOf(y);
                if (ix < 0) ix = 99;
                if (iy < 0) iy = 99;
                return ix - iy;
            }
        });
        return found;
    }

    static String raionName(int uid) {
        for (int i = 0; i < Locations.RAION_UID.length; i++) if (Locations.RAION_UID[i] == uid) return Locations.RAION_NAME[i];
        return null;
    }

    private static char charAt(String str, int uid) throws Exception {
        if (uid < 0 || uid >= str.length() || str.charAt(uid) == ' ') throw new Exception("неожиданный ответ сервера");
        return Character.toUpperCase(str.charAt(uid));
    }

    /** raion — uid района alerts.in.ua, 0 = вся область. */
    static int check(String region, int raion, String token, boolean partial) throws Exception {
        if (token != null && token.trim().length() > 0) {
            lastSource = "alerts.in.ua";
            int obl = Locations.oblastUid(region);
            if (obl < 0) throw new Exception("неизвестная область");
            String str = download("https://api.alerts.in.ua/v1/iot/active_air_raid_alerts.json", token.trim()).trim();
            if (str.startsWith("\"")) str = str.substring(1);
            if (str.endsWith("\"")) str = str.substring(0, str.length() - 1);
            char o = charAt(str, obl);
            if (o == 'A') return ALERT;                       // тревога по всей области
            char c = raion != 0 ? charAt(str, raion) : o;
            if (c == 'A') return ALERT;
            if (c == 'P') return partial ? ALERT : CLEAR;
            if (c == 'N') return CLEAR;
            throw new Exception("неожиданный ответ сервера");
        }
        lastSource = raion != 0 ? "ubilling.net.ua · район без токена недоступен, следим за всей областью" : "ubilling.net.ua";
        if (region.equals("Автономна Республіка Крим"))
            throw new Exception("для Крыма нужен токен alerts.in.ua");
        String name = region.equals("м. Севастополь") ? "Севастополь" : region;
        String body = decodeJsonEscapes(download("https://ubilling.net.ua/aerialalerts/", null));
        Matcher m = Pattern.compile("\"" + Pattern.quote(name)
                + "\"\\s*:\\s*\\{[^}]*?\"alertnow\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!m.find()) throw new Exception("область не найдена в ответе сервера");
        return m.group(1).equalsIgnoreCase("true") ? ALERT : CLEAR;
    }
}
