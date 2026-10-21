package ua.spokiy.trevoga;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Завантаження оновлення завершилося, а вікно програми закрите — пропонуємо встановити через сповіщення. */
public class DownloadReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL = "ua.spokiy.trevoga.INSTALL";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id != Prefs.get(c).getLong(Updater.U_DL, -2)) return;
        if (MainActivity.visible) return;   // вікно відкрите — установку запустить воно саме
        Intent i = new Intent(c, MainActivity.class).setAction(ACTION_INSTALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification n = Prefs.builder(c, Prefs.CH_ALERT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Оновлення завантажено")
                .setContentText("Натисніть, щоб встановити нову версію «Тривоги»")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 8, i, PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(Updater.NOTIF_UPDATE, n);
    }
}
