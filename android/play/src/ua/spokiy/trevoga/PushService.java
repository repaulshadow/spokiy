package ua.spokiy.trevoga;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.Map;

/** Push від нашого сервера: зміна тривог в області. Дані — зріз UkraineAlarm по області або «poke» (запитати сервер). */
public class PushService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage msg) {
        try {
            Map<String, String> d = msg.getData();
            String o = d.get("o");
            if (o == null || !Prefs.get(this).getBoolean(Prefs.ENABLED, true)) return;
            if (!AlertSource.norm(o).equals(AlertSource.norm(Prefs.region(this)))) {   // стара підписка — виправити
                PlayPush.sync(this);
                return;
            }
            MonitorService.L("PUSH " + o + (d.containsKey("poke") ? " (poke)" : "") + " ts=" + d.get("ts"));
            String body = d.get("d");
            PlayCore.apply(getApplicationContext(), body != null ? new JSONObject(body) : null, true);
        } catch (Throwable e) {
            MonitorService.L("PUSH помилка: " + e);
        }
    }

    @Override
    public void onNewToken(String token) {
        Prefs.get(this).edit().remove(PlayPush.TOPIC).apply();   // новий токен — підписатися знову
        PlayPush.sync(this);
    }
}
