package ua.spokiy.trevoga;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Перезапускает слежение после перезагрузки телефона или обновления приложения. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Prefs.get(c).getBoolean(Prefs.ENABLED, true)) {
            try { MonitorService.start(c); } catch (Exception ignored) { }
        }
    }
}
