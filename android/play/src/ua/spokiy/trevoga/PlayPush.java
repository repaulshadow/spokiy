package ua.spokiy.trevoga;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;

import java.security.MessageDigest;

/** Підписка на push своєї області: тема FCM = «o_» + 12 hex SHA-1 від нормалізованої назви (як на сервері, push.js). */
final class PlayPush {
    static final String TOPIC = "push_topic";   // тема, на яку вже підписано
    private static volatile String pending;

    static String topic(String region) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(AlertSource.norm(region).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("o_");
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    /** Привести підписку у відповідність до налаштувань: увімкнено → тема області; вимкнено → без підписки. */
    static synchronized void sync(Context c) {
        final SharedPreferences p = Prefs.get(c);
        // тестова збірка (сервер-імітація) слухає окремі теми «…_t» — справжні користувачі тестових push не отримують
        String tp = topic(Prefs.region(c));
        if (tp != null && AlertSource.embeddedFixed(c)) tp += "_t";
        final String want = p.getBoolean(Prefs.ENABLED, true) ? tp : null;
        final String have = p.getString(TOPIC, null);
        if (want == null ? have == null : want.equals(have)) return;
        if (want != null && want.equals(pending)) return;   // запит уже надіслано — чекаємо відповіді
        try {
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            if (have != null) fm.unsubscribeFromTopic(have);
            if (want == null) { p.edit().remove(TOPIC).apply(); MonitorService.L("PUSH відписка"); return; }
            pending = want;
            fm.subscribeToTopic(want).addOnCompleteListener(new OnCompleteListener<Void>() {
                public void onComplete(Task<Void> t) {
                    pending = null;
                    if (t.isSuccessful()) { p.edit().putString(TOPIC, want).apply(); MonitorService.L("PUSH підписка " + want); }
                    else { p.edit().remove(TOPIC).apply(); MonitorService.L("PUSH підписка не вдалася: " + t.getException()); }
                }
            });
        } catch (Throwable e) {
            MonitorService.L("PUSH недоступний: " + e);
        }
    }
}
