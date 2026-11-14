package ua.spokiy.trevoga;

import android.content.Context;
import android.content.Intent;

final class PlayHooks implements Hooks {
    public void start(Context c) {
        PlayPush.sync(c);
        PollJob.schedule(c);
        PlayCore.checkAsync(c);
    }

    public void checkNow(Context c) {
        PlayPush.sync(c);          // місце могли змінити — інша тема
        PlayCore.checkAsync(c);
    }

    public void stop(Context c) {
        PlayPush.sync(c);          // ENABLED уже false — відписка
        PollJob.cancel(c);
        Sounds.stop();
    }

    public Intent stopSoundIntent(Context c) {
        return new Intent(c, PlaySoundService.class).setAction(PlaySoundService.ACTION_STOP);
    }

    public void debugPush(final Context c, final String json) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try { PlayCore.apply(app, new org.json.JSONObject(json), true); }
                catch (Exception e) { MonitorService.L("PUSH помилка: " + e); }
            }
        }).start();
    }
}
