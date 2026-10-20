package ua.spokiy.trevoga;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Фоновий сервіс: раз на 20 секунд перевіряє тривогу і грає звук при зміні статусу. */
public class MonitorService extends Service {
    static final int INTERVAL_MS = 20000;
    static final int NOTIF_STATUS = 1, NOTIF_ALERT = 2;
    static final String ACTION_CHECK = "ua.spokiy.trevoga.CHECK";
    static final String ACTION_STOP_SOUND = "ua.spokiy.trevoga.STOP_SOUND";

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private String lastPlace = null;
    private boolean firstCheck = true;

    static void start(Context c) {
        c.startService(new Intent(c, MonitorService.class));
    }

    static void checkNow(Context c) {
        c.startService(new Intent(c, MonitorService.class).setAction(ACTION_CHECK));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.createChannels(this);
        startForeground(NOTIF_STATUS, statusNotification("Перевірка…"));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trevoga:monitor");
        wakeLock.acquire();
        thread = new HandlerThread("trevoga-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(loop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.get(this).getBoolean(Prefs.ENABLED, true)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP_SOUND.equals(intent.getAction())) {
            Sounds.stop();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ALERT);
        } else if (intent != null && ACTION_CHECK.equals(intent.getAction()) && handler != null) {
            handler.removeCallbacks(loop);
            handler.post(loop);
        }
        return START_STICKY;
    }

    private final Runnable loop = new Runnable() {
        public void run() {
            try { checkOnce(); } catch (Throwable ignored) { }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void refreshConfigIfDue() {
        SharedPreferences p = Prefs.get(this);
        long last = p.getLong(Prefs.CONFIG_AT, 0);
        if (System.currentTimeMillis() - last < 6 * 3600 * 1000L) return;
        p.edit().putLong(Prefs.CONFIG_AT, System.currentTimeMillis()).apply();
        String s = AlertSource.fetchServer();
        if (s != null) p.edit().putString(Prefs.SERVER, s).apply();
    }

    private void checkOnce() {
        refreshConfigIfDue();
        SharedPreferences p = Prefs.get(this);
        String region = Prefs.region(this);
        int raion = Prefs.raion(this);
        boolean partial = p.getBoolean(Prefs.PARTIAL, false);
        String place = region + "|" + raion + "|" + partial;
        if (!place.equals(lastPlace)) {
            lastPlace = place;
            firstCheck = true;
            p.edit().remove(Prefs.THREATS).remove(Prefs.SINCE).remove(Prefs.CLEARED_AT).apply();
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        AlertSource.Result r;
        try {
            r = AlertSource.check(AlertSource.server(this), region, raion, partial);
        } catch (Exception e) {
            p.edit().putString(Prefs.ERROR, e.getMessage()).apply();
            updateStatus(p.getInt(Prefs.STATE, AlertSource.UNKNOWN), Prefs.place(this), true);
            return;
        }
        if (!place.equals(Prefs.region(this) + "|" + Prefs.raion(this) + "|" + p.getBoolean(Prefs.PARTIAL, false)))
            return; // місце змінили під час запиту

        int old = firstCheck ? AlertSource.UNKNOWN : p.getInt(Prefs.STATE, AlertSource.UNKNOWN);
        List<String> oldThreats = parseList(p.getString(Prefs.THREATS, ""));
        List<String> threats = r.threats;
        List<String> added = new ArrayList<String>();
        if (r.state == AlertSource.ALERT) for (String t : threats) if (!oldThreats.contains(t)) added.add(t);

        long since = p.getLong(Prefs.SINCE, 0);
        long clearedAt = p.getLong(Prefs.CLEARED_AT, 0);
        long now = System.currentTimeMillis();
        if (r.state == AlertSource.ALERT) {
            if (r.since > 0) since = r.since;
            else if (old != AlertSource.ALERT) since = firstCheck ? 0 : now;
            clearedAt = 0;
        } else {
            since = 0;
            if (old == AlertSource.ALERT) clearedAt = now;
        }

        p.edit().putInt(Prefs.STATE, r.state).putString(Prefs.STATUS_TIME, time).putString(Prefs.THREATS, join(threats))
                .putString(Prefs.SOURCE, r.source).putString(Prefs.NOTE, r.note)
                .putLong(Prefs.SINCE, since).putLong(Prefs.CLEARED_AT, clearedAt)
                .remove(Prefs.ERROR).apply();
        String placeName = Prefs.place(this);
        updateStatus(r.state, placeName, false);
        String th = AlertSource.titles(threats);

        if (firstCheck) {
            firstCheck = false;
            return; // при запуску звук не граємо, лише показуємо статус
        }
        if (added.contains("ballistic_missiles")) {
            Sounds.ballistic(this, old != AlertSource.ALERT, threats);      // балістика — окремий терміновий сигнал
            alertNotification("БАЛІСТИКА!", placeName + ". Негайно в укриття!");
        } else if (old != AlertSource.ALERT && r.state == AlertSource.ALERT) {
            Sounds.alarm(this, threats);
            alertNotification("ПОВІТРЯНА ТРИВОГА!", placeName + ". Пройдіть в укриття!" + (th.length() > 0 ? "\n" + th : ""));
        } else if (old == AlertSource.ALERT && r.state == AlertSource.ALERT && !added.isEmpty()) {
            Sounds.newThreats(this, added);
            alertNotification("Нова загроза", placeName + ": " + AlertSource.titles(added));
        } else if (old == AlertSource.ALERT && r.state == AlertSource.CLEAR) {
            Sounds.clear(this);
            alertNotification("Відбій тривоги", placeName);
        }
    }

    static List<String> parseList(String s) {
        List<String> l = new ArrayList<String>();
        if (s != null && s.length() > 0) l.addAll(Arrays.asList(s.split(",")));
        return l;
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) { if (sb.length() > 0) sb.append(','); sb.append(x); }
        return sb.toString();
    }

    private void updateStatus(int state, String region, boolean error) {
        String th = AlertSource.titles(parseList(Prefs.get(this).getString(Prefs.THREATS, "")));
        String text = state == AlertSource.ALERT ? "ТРИВОГА — " + region + (th.length() > 0 ? " · " + th : "")
                : state == AlertSource.CLEAR ? "Тривоги немає — " + region
                : "Немає даних — " + region;
        if (error) text += " (немає зв'язку)";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_STATUS, statusNotification(text));
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private Notification statusNotification(String text) {
        return Prefs.builder(this, Prefs.CH_STATUS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Тривога: стежу")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setContentIntent(openApp())
                .build();
    }

    @SuppressWarnings("deprecation")
    private void alertNotification(String title, String text) {
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction(ACTION_STOP_SOUND), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Prefs.builder(this, Prefs.CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .addAction(0, "Вимкнути звук", stop)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ALERT, n);
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quit();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Sounds.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
