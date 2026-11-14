package ua.spokiy.trevoga;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * Коротка служба лише на час звучання голосу й сирени (до ~40 с): без неї Android обірве звук, щойно обробка push
 * завершиться. Сама зупиняється, коли звук скінчився або натиснули «Вимкнути звук».
 */
public class PlaySoundService extends Service {
    static final String ACTION_STOP = "ua.spokiy.trevoga.PLAY_STOP_SOUND";
    static final int NOTIF = 3;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long startedAt;

    /** kind — як у Engine (BALLISTIC, ALARM, ESCALATION, NEW_THREATS, CLEAR). false — службу запустити не вдалося. */
    static boolean play(Context c, int kind, boolean alertStarted, List<String> threats, List<String> added) {
        try {
            Intent i = new Intent(c, PlaySoundService.class).putExtra("kind", kind).putExtra("started", alertStarted)
                    .putExtra("threats", MonitorService.join(threats)).putExtra("added", MonitorService.join(added));
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Throwable e) {
            MonitorService.L("SOUND служба недоступна: " + e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Notification.Builder b = Prefs.builder(this, Prefs.CH_STATUS)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle("Звучить сигнал тривоги").setOngoing(true)
                .addAction(0, "Вимкнути звук", PendingIntent.getService(this, 2, new Intent(this, PlaySoundService.class).setAction(ACTION_STOP), Prefs.PI));
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, b.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF, b.build());
        } catch (Throwable e) {
            MonitorService.L("SOUND без служби: " + e.getClass().getSimpleName());
        }
        if (i == null || ACTION_STOP.equals(i.getAction())) { Sounds.stop(); finish(); return START_NOT_STICKY; }
        int kind = i.getIntExtra("kind", Engine.NONE);
        List<String> threats = MonitorService.parseList(i.getStringExtra("threats")), added = MonitorService.parseList(i.getStringExtra("added"));
        if (kind == Engine.BALLISTIC) Sounds.ballistic(this, i.getBooleanExtra("started", true), threats);
        else if (kind == Engine.ALARM || kind == Engine.ESCALATION) Sounds.alarm(this, threats);
        else if (kind == Engine.NEW_THREATS) Sounds.newThreats(this, added);
        else if (kind == Engine.CLEAR) Sounds.clear(this);
        startedAt = System.currentTimeMillis();
        h.removeCallbacks(watch);
        h.postDelayed(watch, 1500);
        return START_NOT_STICKY;
    }

    private final Runnable watch = new Runnable() {
        public void run() {
            if (!Sounds.playing() || System.currentTimeMillis() - startedAt > 90000) { finish(); return; }
            h.postDelayed(this, 1000);
        }
    };

    private void finish() {
        h.removeCallbacks(watch);
        try { stopForeground(true); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override
    public void onDestroy() { h.removeCallbacks(watch); super.onDestroy(); }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
