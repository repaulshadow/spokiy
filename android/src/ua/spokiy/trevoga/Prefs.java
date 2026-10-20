package ua.spokiy.trevoga;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

final class Prefs {
    static final String NAME = "trevoga";
    static final String THREATS = "threats", VOICE = "voice", SERVER = "server", CONFIG_AT = "config_at",
            SINCE = "since", CLEARED_AT = "cleared_at", NOTE = "note", DISAGREE = "disagree",
            NOTIFY_ONLY = "notify_only";
    static final String RAION = "raion", SOUND_ALARM = "sound_alarm", SOUND_CLEAR = "sound_clear";
    static final String REGION = "region", PARTIAL = "partial", ENABLED = "enabled",
            STATE = "state", STATUS_TIME = "status_time", ERROR = "error", SOURCE = "source",
            ASKED_BATTERY = "asked_battery";

    static final String CH_STATUS = "status", CH_ALERT = "alert_v1";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String region(Context c) {
        return get(c).getString(REGION, "м. Київ");
    }

    static int raion(Context c) {
        return get(c).getInt(RAION, 0);
    }

    /** "Район (Область)" или просто область. */
    static String place(Context c) {
        String r = raion(c) != 0 ? AlertSource.raionName(raion(c)) : null;
        return r != null ? r + " (" + region(c) + ")" : region(c);
    }

    /** Каналы уведомлений (Android 8+). Создаём через рефлексию — проект собирается против API 23. */
    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Class<?> chCls = Class.forName("android.app.NotificationChannel");
            Object st = chCls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CH_STATUS, "Статус стеження", 2 /* IMPORTANCE_LOW */);
            Object al = chCls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CH_ALERT, "Тривога і відбій", 4 /* IMPORTANCE_HIGH */);
            chCls.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                    .invoke(al, null, null); // звук играет само приложение
            chCls.getMethod("enableVibration", boolean.class).invoke(al, false);
            NotificationManager.class.getMethod("createNotificationChannel", chCls).invoke(nm, st);
            NotificationManager.class.getMethod("createNotificationChannel", chCls).invoke(nm, al);
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return (Notification.Builder) Notification.Builder.class
                        .getConstructor(Context.class, String.class).newInstance(c, channel);
            } catch (Throwable ignored) { }
        }
        return new Notification.Builder(c);
    }
}
