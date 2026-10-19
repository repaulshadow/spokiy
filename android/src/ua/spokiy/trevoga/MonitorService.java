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
import java.util.Date;
import java.util.Locale;

/** Фоновый сервис: раз в 20 секунд проверяет тревогу и играет звук при смене статуса. */
public class MonitorService extends Service {
    static final int INTERVAL_MS = 20000;
    static final int NOTIF_STATUS = 1, NOTIF_ALERT = 2;
    static final String ACTION_CHECK = "ua.spokiy.trevoga.CHECK";
    static final String ACTION_STOP_SOUND = "ua.spokiy.trevoga.STOP_SOUND";

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private String lastRegion = null;
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
        startForeground(NOTIF_STATUS, statusNotification("Проверка…"));
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

    private void checkOnce() {
        SharedPreferences p = Prefs.get(this);
        String region = Prefs.region(this);
        String token = p.getString(Prefs.TOKEN, "");
        boolean partial = p.getBoolean(Prefs.PARTIAL, false);
        if (!region.equals(lastRegion)) {
            lastRegion = region;
            firstCheck = true;
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        int result;
        try {
            result = AlertSource.check(region, token, partial);
        } catch (Exception e) {
            p.edit().putString(Prefs.ERROR, "Нет связи (" + time + "): " + e.getMessage()).apply();
            updateStatus(p.getInt(Prefs.STATE, AlertSource.UNKNOWN), region, true);
            return;
        }

        int old = firstCheck ? AlertSource.UNKNOWN : p.getInt(Prefs.STATE, AlertSource.UNKNOWN);
        p.edit().putInt(Prefs.STATE, result).putString(Prefs.STATUS_TIME, time)
                .putString(Prefs.SOURCE, AlertSource.lastSource).remove(Prefs.ERROR).apply();
        updateStatus(result, region, false);

        if (firstCheck) {
            firstCheck = false;
            return; // при запуске звук не играем, только показываем статус
        }
        if (old == AlertSource.CLEAR && result == AlertSource.ALERT) {
            Sounds.play(this, true);
            alertNotification("ПОВІТРЯНА ТРИВОГА!", region + ". Пройдіть в укриття!");
        } else if (old == AlertSource.ALERT && result == AlertSource.CLEAR) {
            Sounds.play(this, false);
            alertNotification("Відбій тривоги", region);
        }
    }

    private void updateStatus(int state, String region, boolean error) {
        String text = state == AlertSource.ALERT ? "ТРИВОГА — " + region
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
                .setContentTitle("Тривога: слежение включено")
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
