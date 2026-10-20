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

/** Фоновий сервіс: раз на 15 секунд перевіряє тривогу і грає звук при зміні статусу. */
public class MonitorService extends Service {
    static final int INTERVAL_MS = 15000;
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
        String oldLevel = old == AlertSource.ALERT ? p.getString(Prefs.LEVEL, "") : "";
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
                .putString(Prefs.SOURCE, r.source).putString(Prefs.NOTE, r.note).putBoolean(Prefs.DISAGREE, r.disagree).putString(Prefs.LEVEL, r.level)
                .putLong(Prefs.SINCE, since).putLong(Prefs.CLEARED_AT, clearedAt)
                .remove(Prefs.ERROR).apply();
        String placeName = Prefs.place(this);
        updateStatus(r.state, placeName, false);
        String th = AlertSource.titles(threats);

        if (firstCheck) {
            firstCheck = false;
            return; // при запуску звук не граємо, лише показуємо статус
        }
        boolean quiet = p.getBoolean(Prefs.NOTIFY_ONLY, false);   // «лише сповіщення»: без голосу і сирени
        if (added.contains("ballistic_missiles")) {
            if (!quiet) Sounds.ballistic(this, old != AlertSource.ALERT, threats);   // балістика — окремий терміновий сигнал
            showAlert(this, "БАЛІСТИКА!", placeName + ". Негайно в укриття!" + (th.length() > 0 ? "\n" + th : ""), PURPLE, quiet);
        } else if (old != AlertSource.ALERT && r.state == AlertSource.ALERT) {
            if (!quiet) Sounds.alarm(this, threats);
            boolean y = r.level.equals("yellow");
            showAlert(this, y ? "ПОВІТРЯНА ТРИВОГА — жовтий рівень" : "ПОВІТРЯНА ТРИВОГА!",
                    placeName + ". Пройдіть в укриття!" + (th.length() > 0 ? "\n" + th : ""), y ? YELLOW : RED, quiet);
        } else if (old == AlertSource.ALERT && r.state == AlertSource.ALERT && oldLevel.equals("yellow") && r.level.equals("red")) {
            // Рівень підвищено: жовтий → червоний — повна сирена
            if (!quiet) Sounds.alarm(this, threats);
            showAlert(this, "ЧЕРВОНИЙ РІВЕНЬ ТРИВОГИ!", placeName + ". Негайно в укриття!" + (th.length() > 0 ? "\n" + th : ""), RED, quiet);
        } else if (old == AlertSource.ALERT && r.state == AlertSource.ALERT && !added.isEmpty()) {
            if (!quiet) Sounds.newThreats(this, added);
            showAlert(this, "Нова загроза", placeName + ": " + AlertSource.titles(added), r.level.equals("yellow") ? YELLOW : RED, quiet);
        } else if (old == AlertSource.ALERT && r.state == AlertSource.CLEAR) {
            if (!quiet) Sounds.clear(this);
            showAlert(this, "Відбій тривоги", placeName, GREEN, quiet);
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
        lastStatusColor = state == AlertSource.ALERT
                ? (parseList(Prefs.get(this).getString(Prefs.THREATS, "")).contains("ballistic_missiles") ? PURPLE
                   : Prefs.get(this).getString(Prefs.LEVEL, "").equals("yellow") ? YELLOW : RED)
                : state == AlertSource.CLEAR ? GREEN : GRAY;
        lastStatusTitle = state == AlertSource.ALERT ? "ПОВІТРЯНА ТРИВОГА" : "Тривога: стежу";
        if (state != AlertSource.ALERT) text = error ? "Немає зв'язку" : region;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_STATUS, statusNotification(text));
    }

    static final int RED = 0xFFDC2626, DARK_RED = 0xFF991B1B, GREEN = 0xFF16A34A, GRAY = 0xFF6B7280,
            YELLOW = 0xFFD97706, PURPLE = 0xFF7C3AED;
    private int lastStatusColor = GRAY;
    private String lastStatusTitle = "Тривога: стежу";

    /** Заливка сповіщення кольором (Android 8+; колір фону діє для постійного сповіщення служби). */
    static void colorize(Notification.Builder b, int color) {
        b.setColor(color);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try { Notification.Builder.class.getMethod("setColorized", boolean.class).invoke(b, true); }
            catch (Throwable ignored) { }
        }
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private Notification statusNotification(String text) {
        Notification.Builder b = Prefs.builder(this, Prefs.CH_STATUS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(lastStatusTitle)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)   // без значка в рядку стану, згорнуте внизу шторки
                .setContentIntent(openApp());
        if (lastStatusColor != GRAY && lastStatusColor != GREEN) colorize(b, lastStatusColor);   // жовтий/червоний/фіолетовий   // фарбуємо лише під час тривоги
        return b.build();
    }

    @SuppressWarnings("deprecation")
    static void showAlert(Context ctx, String title, String text, int color, boolean quiet) {
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = Prefs.builder(ctx, Prefs.CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT));
        colorize(b, color);
        if (!quiet) {
            PendingIntent stop = PendingIntent.getService(ctx, 1,
                    new Intent(ctx, MonitorService.class).setAction(ACTION_STOP_SOUND), PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(0, "Вимкнути звук", stop);
        } else {
            // без звуку, лише коротка вібрація, щоб сповіщення помітили
            try {
                android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(new long[]{0, 250, 150, 250}, -1);
            } catch (Exception ignored) { }
        }
        ((NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ALERT, b.build());
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
