package ua.spokiy.trevoga;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Vibrator;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;

/** Сирена и сигнал отбоя. Синтезируются один раз в WAV и играются через поток будильника. */
final class Sounds {
    private static MediaPlayer player;
    private static final int RATE = 22050;

    static synchronized void stop() {
        try {
            if (player != null) { player.stop(); player.release(); }
        } catch (Exception ignored) { }
        player = null;
    }

    static synchronized void play(Context ctx, boolean alarm) {
        stop();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_ALARM); // слышно даже в беззвучном режиме
            String custom = Prefs.get(ctx).getString(alarm ? Prefs.SOUND_ALARM : Prefs.SOUND_CLEAR, null);
            boolean ok = false;
            if (custom != null) {
                try {
                    mp.setDataSource(ctx, Uri.parse(custom));   // свой звук, выбранный пользователем
                    mp.prepare();
                    ok = true;
                } catch (Exception e) {
                    mp.reset();
                    mp.setAudioStreamType(AudioManager.STREAM_ALARM);
                }
            }
            if (!ok) {
                File f = new File(ctx.getFilesDir(), alarm ? "siren_v2.wav" : "clear_v1.wav");
                if (!f.exists()) writeWav(f, alarm ? siren() : allClear());
                mp.setDataSource(f.getAbsolutePath());
                mp.prepare();
            }
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { stop(); }
            });
            mp.start();
            player = mp;
        } catch (Exception ignored) { }
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (alarm) v.vibrate(new long[]{0, 800, 400, 800, 400, 800, 400, 800}, -1);
                else v.vibrate(new long[]{0, 300, 200, 300}, -1);
            }
        } catch (Exception ignored) { }
    }

    private static void writeWav(File f, short[] s) throws Exception {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)));
        int dataLen = s.length * 2;
        o.writeBytes("RIFF"); le32(o, 36 + dataLen); o.writeBytes("WAVE");
        o.writeBytes("fmt "); le32(o, 16); le16(o, 1); le16(o, 1); le32(o, RATE); le32(o, RATE * 2);
        le16(o, 2); le16(o, 16);
        o.writeBytes("data"); le32(o, dataLen);
        for (short v : s) le16(o, v);
        o.close();
    }

    private static void le32(DataOutputStream o, int v) throws Exception {
        o.write(v & 0xff); o.write((v >> 8) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }

    private static void le16(DataOutputStream o, int v) throws Exception {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
    }

    // Сирена гражданской обороны: раскрутка мотора, протяжный вой вверх-вниз, затухание (~24 сек)
    private static double sirenFreq(double t) {
        final double T = 24, up = 3, down = 3.5, lo = 150, top = 620;
        if (t < up) return lo + (top - lo) * (1 - Math.exp(-3 * t / up)) / (1 - Math.exp(-3));
        double wail = 500 + 120 * Math.cos(2 * Math.PI * (Math.min(t, T - down) - up) / 5.0);
        if (t <= T - down) return wail;
        double k = (t - (T - down)) / down;
        return lo + (wail - lo) * Math.pow(1 - Math.min(1, k), 1.5);
    }

    private static double tanh(double x) { double e = Math.exp(2 * x); return (e - 1) / (e + 1); }

    private static short[] siren() {
        final double T = 24;
        int n = (int) (RATE * T);
        short[] buf = new short[n];
        double[] harm = {1.0, 0.5, 0.35, 0.22, 0.14, 0.08};
        double norm = 0;
        for (double h : harm) norm += h;
        java.util.Random rnd = new java.util.Random(7);
        double phase = 0, sat = tanh(1.8);
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = sirenFreq(t) * (1 + 0.004 * Math.sin(2 * Math.PI * 5.5 * t));
            phase += 2 * Math.PI * f / RATE;
            double v = 0;
            for (int h = 0; h < harm.length; h++) v += harm[h] * Math.sin((h + 1) * phase);
            v = v / norm + 0.03 * (rnd.nextDouble() * 2 - 1);
            v = tanh(1.8 * v) / sat;
            double env = Math.min(1.0, f / 400.0) * Math.min(1.0, t / 0.2) * Math.min(1.0, (n - i) / (RATE * 0.4));
            buf[i] = (short) (v * env * 0.85 * 32767);
        }
        return buf;
    }

    // Отбой: восходящий спокойный сигнал, два раза
    private static short[] allClear() {
        double[] notes = {523.25, 659.25, 783.99, 1046.5};
        double noteLen = 0.45, gap = 0.9;
        int reps = 2;
        double total = reps * (notes.length * noteLen + gap) + 1.2;
        int n = (int) (RATE * total);
        double[] mix = new double[n];
        for (int r = 0; r < reps; r++) {
            for (int j = 0; j < notes.length; j++) {
                double start = r * (notes.length * noteLen + gap) + j * noteLen;
                int s0 = (int) (start * RATE);
                int len = (int) (1.4 * RATE);
                for (int i = 0; i < len && s0 + i < n; i++) {
                    double t = (double) i / RATE;
                    double env = Math.min(1.0, t / 0.01) * Math.exp(-t * 3.2);
                    double v = Math.sin(2 * Math.PI * notes[j] * t) + 0.3 * Math.sin(4 * Math.PI * notes[j] * t);
                    mix[s0 + i] += v * env * 0.35;
                }
            }
        }
        short[] buf = new short[n];
        for (int i = 0; i < n; i++) buf[i] = (short) (Math.max(-1.0, Math.min(1.0, mix[i])) * 32767);
        return buf;
    }
}
