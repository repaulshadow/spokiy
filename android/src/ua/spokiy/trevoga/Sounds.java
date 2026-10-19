package ua.spokiy.trevoga;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
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
            File f = new File(ctx.getFilesDir(), alarm ? "siren_v1.wav" : "clear_v1.wav");
            if (!f.exists()) writeWav(f, alarm ? siren() : allClear());
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_ALARM); // слышно даже в беззвучном режиме
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
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

    // Сирена: тон плавно поднимается и опускается, 4 цикла (~16 сек)
    private static short[] siren() {
        double cycle = 4.0;
        int n = (int) (RATE * cycle * 4);
        short[] buf = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double pos = (t % cycle) / cycle;
            double k = pos < 0.5 ? pos * 2 : (1 - pos) * 2;
            k = 0.5 - 0.5 * Math.cos(Math.PI * k);
            double f = 450 + 650 * k;
            phase += 2 * Math.PI * f / RATE;
            double v = 0.62 * Math.sin(phase) + 0.25 * Math.sin(2 * phase) + 0.12 * Math.sin(3 * phase);
            double env = Math.min(1.0, t / 0.3) * Math.min(1.0, (n - i) / (RATE * 0.5));
            buf[i] = (short) (v * env * 0.8 * 32767);
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
