package ua.spokiy.trevoga;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.JSONObject;

/**
 * Зведення джерел без мережі й без Android (тому перевіряється автотестами на звичайній Java):
 * остання відповідь сервера + свіжий потік UkraineAlarm; швидкий офіційний відбій; не змішуємо свіже зі старим.
 */
final class Engine {
    JSONObject lastRoot;            // остання відповідь /status
    long lastRootAt;                // коли отримана (мс)
    String lastServerError;
    private long uaSeenAt;          // час найсвіжіших даних UkraineAlarm, які вже використано
    private int prevUa = AlertSource.UNKNOWN;
    private long uaClearedAt;       // офіційний відбій (мс), 0 — немає
    private String placeKey = "";

    static final long ROOT_FRESH_MS = 90000, UA_CLEAR_WINDOW_MS = 180000, NEP_LAG_MS = 90000;

    void serverOk(JSONObject root, long now) { lastRoot = root; lastRootAt = now; lastServerError = null; }
    void serverFailed(String err) { lastServerError = err; }

    /** null — даних немає (треба запасне джерело). ua — дані потоку, якщо він свіжий, інакше null. */
    AlertSource.Result compute(JSONObject ua, String region, int raion, boolean partial, long now) throws Exception {
        JSONObject root = new JSONObject();
        boolean rootFresh = lastRoot != null && now - lastRootAt < ROOT_FRESH_MS;
        if (rootFresh) for (Iterator<String> it = lastRoot.keys(); it.hasNext(); ) { String k = it.next(); root.put(k, lastRoot.get(k)); }
        if (ua != null) { root.put("ua", ua); uaSeenAt = now; }
        else if (root.optJSONObject("ua") != null) {
            // потік уже показав новіші дані, ніж ця відповідь сервера, — старі дані сервера про UA не беремо
            if (lastRootAt < uaSeenAt) root.remove("ua"); else uaSeenAt = lastRootAt;
        }
        boolean uaKnown = root.optJSONObject("ua") != null;
        if (!root.has("air") && root.optJSONObject("neptun") == null && !uaKnown) return null;

        String key = region + "|" + raion + "|" + partial;
        if (!key.equals(placeKey)) { placeKey = key; prevUa = AlertSource.UNKNOWN; uaClearedAt = 0; }
        if (uaClearedAt > 0 && now - uaClearedAt > UA_CLEAR_WINDOW_MS) uaClearedAt = 0;

        AlertSource.Result r = merge(root, region, raion, partial);
        if (uaKnown) {
            // офіційний відбій UkraineAlarm — не чекаємо NEPTUN (відстає до ~2 хв) і alerts.in.ua
            if (prevUa == AlertSource.ALERT && r.uaState == AlertSource.CLEAR) { uaClearedAt = now; r = merge(root, region, raion, partial); }
            else if (r.uaState == AlertSource.ALERT) uaClearedAt = 0;
            prevUa = r.uaState;
        }
        r.pollS = rootFresh ? lastRoot.optInt("poll", 0) : 0;
        if (ua != null) r.source = r.source.replace("UkraineAlarm", "UkraineAlarm наживо");
        if (lastServerError != null) r.note = "сервер недоступний (" + lastServerError + ")" + (r.note.length() > 0 ? " · " + r.note : "");
        return r;
    }

    private AlertSource.Result merge(JSONObject root, String region, int raion, boolean partial) throws Exception {
        // NEPTUN запізнюється й на початку тривоги: його «нова» тривога в перші 90 с після офіційного відбою — зазвичай стара
        return AlertSource.fromRoot(root, region, raion, partial, uaClearedAt, uaClearedAt > 0 ? uaClearedAt + NEP_LAG_MS : 0);
    }

    // ---------------- переходи: що озвучити ----------------
    static final int NONE = 0, BALLISTIC = 1, ALARM = 2, ESCALATION = 3, NEW_THREATS = 4, CLEAR = 5;

    static final class Action {
        int kind = NONE;
        List<String> added = new ArrayList<String>();
    }

    /** old — останній ВІДОМИЙ стан (UNKNOWN на першій перевірці). */
    static Action decide(int old, List<String> oldThreats, String oldLevel, AlertSource.Result r) {
        Action a = new Action();
        if (r.state == AlertSource.UNKNOWN || old == AlertSource.UNKNOWN) return a;
        if (r.state == AlertSource.ALERT) for (String t : r.threats) if (!oldThreats.contains(t)) a.added.add(t);
        if (a.added.contains("ballistic_missiles")) a.kind = BALLISTIC;
        else if (old != AlertSource.ALERT && r.state == AlertSource.ALERT) a.kind = ALARM;
        else if (old == AlertSource.ALERT && r.state == AlertSource.ALERT && "yellow".equals(oldLevel) && "red".equals(r.level)) a.kind = ESCALATION;
        else if (old == AlertSource.ALERT && r.state == AlertSource.ALERT && !a.added.isEmpty()) a.kind = NEW_THREATS;
        else if (old == AlertSource.ALERT && r.state == AlertSource.CLEAR) a.kind = CLEAR;
        return a;
    }
}
