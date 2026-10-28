package ua.spokiy.trevoga;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;

/** WebSocket-клієнт Android-версії проти test/fake_ws.py (ті самі «незручні» сценарії, що й для Windows). */
public class AndroidStreamTest {
    public static void main(String[] a) throws Exception {
        final StreamClient c = new StreamClient("t", a[0]);
        int secs = Integer.parseInt(a[1]);
        final List<String> seq = new ArrayList<String>();
        final long t0 = System.currentTimeMillis();
        c.listener = new StreamClient.Listener() {
            public void changed() {
                JSONArray r = c.data().optJSONArray("r");
                String m = r != null && r.length() > 0 ? r.optJSONArray(0).optString(1) : "-";
                synchronized (seq) { seq.add(m); }
                System.out.printf("+%.1fs подія %s%n", (System.currentTimeMillis() - t0) / 1000.0, m);
            }
            public void lost() { System.out.printf("+%.1fs втрачено%n", (System.currentTimeMillis() - t0) / 1000.0); }
        };
        c.start();
        boolean was = false;
        double staleFrom = -1, longest = 0;
        while (System.currentTimeMillis() - t0 < secs * 1000L) {
            Thread.sleep(250);
            double t = (System.currentTimeMillis() - t0) / 1000.0;
            boolean f = c.fresh();
            if (f != was) System.out.printf("+%.1fs fresh=%s status=%s%n", t, f, c.status);
            if (!f && was) staleFrom = t;
            if (f && !was && staleFrom >= 0) { longest = Math.max(longest, t - staleFrom); staleFrom = -1; }
            was = f;
        }
        int fails = 0;
        String got;
        synchronized (seq) { got = String.join(",", seq); }
        if (!got.equals("A,B,C,D,E,F,G,H")) { fails++; System.out.println("FAIL: послідовність " + got); }
        if (!c.fresh()) { fails++; System.out.println("FAIL: наприкінці потік не свіжий"); }
        if (longest < 3 || longest > 90) { fails++; System.out.println("FAIL: мовчазне з'єднання (без даних " + longest + " с)"); }
        System.out.println(fails == 0 ? "OK: потік Android — усі перевірки пройдено" : "Помилок: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
