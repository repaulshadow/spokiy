package ua.spokiy.trevoga;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Оновлення програми: сервер роздає version.json і Trevoga.apk.
 * Android не дозволяє встановлювати мовчки, тому: сповіщення → завантаження → установник (один тап).
 */
final class Updater {
    static final String ACTION_UPDATE = "ua.spokiy.trevoga.UPDATE";
    static final int NOTIF_UPDATE = 3;
    static final String MIME = "application/vnd.android.package-archive";

    static final String U_CODE = "upd_code", U_NAME = "upd_name", U_URL = "upd_url", U_SHA = "upd_sha",
            U_CHECKED = "upd_checked", U_DL = "upd_dl_id";

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
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Доступна нова версія " + Prefs.get(c).getString(U_NAME, ""))
                .setContentText("Натисніть, щоб оновити програму «Тривога»")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT));
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_UPDATE, b.build());
    }

    static File apkFile(Context c) {
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(dir, "Trevoga-" + Prefs.get(c).getInt(U_CODE, 0) + ".apk");
    }

    /** Починає завантаження APK через системний менеджер завантажень. */
    static long startDownload(Context c) {
        SharedPreferences p = Prefs.get(c);
        File f = apkFile(c);
        if (f.exists()) f.delete();
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(
                Uri.parse(p.getString(U_URL, "") + "?v=" + p.getInt(U_CODE, 0)));
        r.setTitle("Тривога " + p.getString(U_NAME, ""));
        r.setDescription("Оновлення програми");
        r.setMimeType(MIME);
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        r.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, f.getName());
        long id = dm.enqueue(r);
        p.edit().putLong(U_DL, id).apply();
        return id;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Після завантаження: перевірка контрольної суми і запуск установника. null — успіх, інакше текст помилки. */
    static String install(Context c, long id) {
        try {
            File f = apkFile(c);
            if (!f.exists()) return "файл не завантажився";
            String want = Prefs.get(c).getString(U_SHA, "");
            if (want.length() > 0 && !want.equals(sha256(f))) {
                f.delete();
                return "контрольна сума не збігається";
            }
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                uri = dm.getUriForDownloadedFile(id);
            } else {
                uri = Uri.fromFile(f);
            }
            if (uri == null) return "не вдалося відкрити файл";
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(i);
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_UPDATE);
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }
}
