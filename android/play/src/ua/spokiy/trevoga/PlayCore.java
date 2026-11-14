package ua.spokiy.trevoga;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Версія для Google Play: та сама логіка зведення джерел (Engine), але без постійної служби.
 * Дані приходять: push (зріз UkraineAlarm по області — одразу), відкриття програми й запасне опитування (/status).
 * Порівнюємо з останнім відомим станом із налаштувань — процес між push може бути зупинений системою.
 */
final class PlayCore {
    private static final Engine engine = new Engine();
    static final String PLACE = "play_place";

    static void checkAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() { try { apply(app, null, false); } catch (Throwable ignored) { } }
        }).start();
    }

    /**
     * ua — зріз із push (null — запитати сервер). fromPush — прийшло push-повідомлення високого пріоритету:
     * лише тоді (або коли програма відкрита) Android дозволяє запустити звук.
     */
    static synchronized void apply(Context ctx, JSONObject ua, boolean fromPush) {
        SharedPreferences p = Prefs.get(ctx);
        if (!p.getBoolean(Prefs.ENABLED, true)) return;
        String region = Prefs.region(ctx);
        int raion = Prefs.raion(ctx);
        boolean partial = p.getBoolean(Prefs.PARTIAL, false);
        String place = region + "|" + raion + "|" + partial;
        boolean first = !place.equals(p.getString(PLACE, null)) || !p.contains(Prefs.KNOWN_STATE);
        if (first) p.edit().putString(PLACE, place).remove(Prefs.THREATS).remove(Prefs.SINCE).remove(Prefs.CLEARED_AT)
                .remove(Prefs.KNOWN_STATE).remove(Prefs.KNOWN_THREATS).remove(Prefs.KNOWN_LEVEL).apply();
        long now = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        if (ua == null) {
            String server = AlertSource.server(ctx);
            try { engine.serverOk(AlertSource.fetchRoot(server), now); }
            catch (Exception e) { engine.serverFailed(AlertSource.shortErr(e)); }
        }
        AlertSource.Result r;
        try {
            r = engine.compute(ua, region, raion, partial, now);
            if (r == null) { p.edit().putString(Prefs.ERROR, engine.lastServerError != null ? engine.lastServerError : "немає даних").apply(); return; }
        } catch (Exception e) {
            p.edit().putString(Prefs.ERROR, e.getMessage()).apply();
            return;
        }
        if (ua != null) r.source = r.source.replace("UkraineAlarm наживо", "UkraineAlarm (push)");

        int old = first ? AlertSource.UNKNOWN : p.getInt(Prefs.KNOWN_STATE, AlertSource.UNKNOWN);
        List<String> oldThreats = MonitorService.parseList(p.getString(Prefs.KNOWN_THREATS, ""));
        String oldLevel = old == AlertSource.ALERT ? p.getString(Prefs.KNOWN_LEVEL, "") : "";
        List<String> threats = r.threats;
        long since = p.getLong(Prefs.SINCE, 0), clearedAt = p.getLong(Prefs.CLEARED_AT, 0);
        if (r.state == AlertSource.ALERT) {
            if (r.since > 0) since = r.since; else if (old != AlertSource.ALERT) since = first ? 0 : now;
            clearedAt = 0;
        } else if (r.state == AlertSource.CLEAR) {
            since = 0;
            if (old == AlertSource.ALERT) clearedAt = now;
        }
        String th = MonitorService.join(threats);
        if (r.state != p.getInt(Prefs.STATE, -1) || !r.level.equals(p.getString(Prefs.LEVEL, "")) || !th.equals(p.getString(Prefs.THREATS, "")))
            MonitorService.L("STATE " + (r.state == AlertSource.ALERT ? "Alert" : r.state == AlertSource.CLEAR ? "Clear" : "Unknown") + " level=" + r.level
                    + " threats=" + th + " src=" + r.source + (r.note.length() > 0 ? " note=" + r.note : ""));
        p.edit().putInt(Prefs.STATE, r.state).putString(Prefs.STATUS_TIME, time).putString(Prefs.THREATS, th)
                .putString(Prefs.SOURCE, r.source).putString(Prefs.NOTE, r.note).putBoolean(Prefs.DISAGREE, r.disagree).putString(Prefs.LEVEL, r.level)
                .putLong(Prefs.SINCE, since).putLong(Prefs.CLEARED_AT, clearedAt).remove(Prefs.ERROR).apply();
        if (r.state == AlertSource.UNKNOWN) return;
        p.edit().putInt(Prefs.KNOWN_STATE, r.state).putString(Prefs.KNOWN_THREATS, th).putString(Prefs.KNOWN_LEVEL, r.level).apply();
        if (first) return;   // перше визначення — без звуку

        Engine.Action act = Engine.decide(old, oldThreats, oldLevel, r);
        if (act.kind == Engine.NONE) return;
        boolean notifyOnly = p.getBoolean(Prefs.NOTIFY_ONLY, false);
        // звук — лише з push або коли програма на екрані (інакше Android не дозволить службу); тоді — вібрація й сповіщення
        boolean canSound = !notifyOnly && (fromPush || MainActivity.visible);
        boolean sound = canSound && PlaySoundService.play(ctx, act.kind, old != AlertSource.ALERT, threats, act.added);
        boolean quiet = !sound;
        MonitorService.L("SOUND " + new String[]{"", "ballistic", "alarm", "escalation", "newthreats", "clear"}[act.kind]
                + (notifyOnly ? " (лише сповіщення)" : quiet ? " (без звуку: не push)" : ""));
        String placeName = Prefs.place(ctx), tt = AlertSource.titles(threats);
        String more = tt.length() > 0 ? "\n" + tt : "";
        boolean y = r.level.equals("yellow");
        if (act.kind == Engine.BALLISTIC)
            MonitorService.showSeparate(ctx, "БАЛІСТИКА!", placeName + ". Негайно в укриття!" + more, MonitorService.PURPLE, quiet);
        else if (act.kind == Engine.ALARM)
            MonitorService.showSeparate(ctx, y ? "ПОВІТРЯНА ТРИВОГА — жовтий рівень" : "ПОВІТРЯНА ТРИВОГА!", placeName + ". Пройдіть в укриття!" + more,
                    y ? MonitorService.YELLOW : MonitorService.RED, quiet);
        else if (act.kind == Engine.ESCALATION)
            MonitorService.showSeparate(ctx, "ЧЕРВОНИЙ РІВЕНЬ ТРИВОГИ!", placeName + ". Негайно в укриття!" + more, MonitorService.RED, quiet);
        else if (act.kind == Engine.NEW_THREATS)
            MonitorService.showSeparate(ctx, "Нова загроза", placeName + ": " + AlertSource.titles(act.added), y ? MonitorService.YELLOW : MonitorService.RED, quiet);
        else if (act.kind == Engine.CLEAR)
            MonitorService.showSeparate(ctx, "Відбій тривоги", placeName, MonitorService.GREEN, quiet);
    }
}
