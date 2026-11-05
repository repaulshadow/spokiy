package ua.spokiy.trevoga;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;


/**
 * Оновлення програми: сервер роздає version.json і Trevoga.apk.
 * Нова версія → сповіщення → завантаження в браузері → системний установник.
 */
final class Updater {
    static final String ACTION_UPDATE = "ua.spokiy.trevoga.UPDATE";
    static final int NOTIF_UPDATE = 3;

    static final String U_CODE = "upd_code", U_NAME = "upd_name", U_URL = "upd_url", U_SHA = "upd_sha",
            U_CHECKED = "upd_checked";

    static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Перевіряє version.json. true — є новіша версія (дані збережено в налаштуваннях). */
    static boolean check(Context c) throws Exception {
        String server = AlertSource.server(c);
        if (server == null || server.length() == 0) return false;
        JSONObject j = new JSONObject(AlertSource.download(server + "/version.json?t=" + System.currentTimeMillis()));
        JSONObject a = j.optJSONObject("android");
        SharedPreferences p = Prefs.get(c);
        p.edit().putLong(U_CHECKED, System.currentTimeMillis()).apply();
        if (a == null) return false;
        int code = a.optInt("code", 0);
        if (code <= currentCode(c)) {
            p.edit().remove(U_CODE).apply();
            return false;
        }
        String url = a.optString("url", "");
        if (url.startsWith("/")) url = server + url;
        p.edit().putInt(U_CODE, code).putString(U_NAME, a.optString("name", String.valueOf(code)))
                .putString(U_URL, url).putString(U_SHA, a.optString("sha256", "").toLowerCase()).apply();
        return true;
    }

    /** Фонова перевірка (з сервісу) не частіше ніж раз на 6 годин; при новій версії — сповіщення. */
    static void checkIfDue(Context c) {
        SharedPreferences p = Prefs.get(c);
        if (System.currentTimeMillis() - p.getLong(U_CHECKED, 0) < 6 * 3600 * 1000L) return;
        try {
            if (check(c)) notifyAvailable(c);
        } catch (Exception ignored) { }
    }

    static boolean available(Context c) {
        return Prefs.get(c).getInt(U_CODE, 0) > currentCode(c);
    }

    @SuppressWarnings("deprecation")
    static void notifyAvailable(Context c) {
        Intent i = new Intent(c, MainActivity.class).setAction(ACTION_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = Prefs.builder(c, Prefs.CH_ALERT)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Доступна нова версія " + Prefs.get(c).getString(U_NAME, ""))
                .setContentText("Натисніть, щоб оновити програму «Тривога»")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 7, i, Prefs.PI));
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_UPDATE, b.build());
    }

    /**
     * Завантаження нової версії — через браузер (як звичайне встановлення з сайту): програма сама нічого
     * не встановлює і не просить дозволу «встановлювати програми» — так Play Protect не вважає її «завантажувачем».
     * Android перевіряє, що оновлення підписане тим самим ключем.
     */
    static void openDownload(Context c) {
        String url = Prefs.get(c).getString(U_URL, "");
        if (url.length() == 0) url = AlertSource.server(c) + "/download/Trevoga.apk";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url + "?v=" + Prefs.get(c).getInt(U_CODE, 0)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_UPDATE);
    }
}
