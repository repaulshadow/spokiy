package ua.spokiy.trevoga;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.json.JSONObject;

/**
 * Мінімальний WebSocket-клієнт (без сторонніх бібліотек) для потоку нашого сервера: офіційні тривоги
 * UkraineAlarm у форматі NEPTUN («alerts»). Сам перепідключається; «свіжий» — якщо сервер подавав
 * ознаки життя протягом 35 с (heartbeat іде раз на 15 с).
 */
final class StreamClient {
    interface Listener { void changed(); }
    interface Logger { void log(String line); }
    static volatile Logger logger;

    private void setStatus(String st) {
        if (!st.equals(status)) { Logger l = logger; if (l != null) l.log("UASTREAM " + st); }
        status = st;
    }

    volatile String url;
    private final String name;
    private final Object lk = new Object();
    private JSONObject nep;          // останні дані у форматі сервера {r, o, c}
    private String sig = "";
    private boolean connected, haveData;
    private long lastMsg = 0;
    private Thread thread;
    private volatile boolean stop;
    private Socket socket;
    volatile String status = "";
    volatile Listener listener;
    private static final SecureRandom rnd = new SecureRandom();
    static volatile String userAgent = "Trevoga-Android";

    static String base64(byte[] b) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xFF) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xFF) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xFF : 0);
            sb.append(a.charAt(n >> 18 & 63)).append(a.charAt(n >> 12 & 63));
            sb.append(i + 1 < b.length ? a.charAt(n >> 6 & 63) : '=').append(i + 2 < b.length ? a.charAt(n & 63) : '=');
        }
        return sb.toString();
    }

    StreamClient(String name, String url) { this.name = name; this.url = url; }

    boolean fresh() {
        synchronized (lk) {
            return connected && haveData && nep != null && System.currentTimeMillis() - lastMsg < 35000;
        }
    }

    JSONObject data() { synchronized (lk) { return nep; } }

    synchronized void start() {
        if (thread != null || url == null || url.length() == 0) return;
        stop = false;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, name);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void shutdown() {
        stop = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        int fails = 0;
        while (!stop) {
            long t0 = System.currentTimeMillis();
            try { runOnce(); setStatus("закрито сервером"); }
            catch (Exception e) { setStatus(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()); }
            synchronized (lk) { connected = false; }
            if (stop) break;
            if (System.currentTimeMillis() - t0 > 60000) fails = 0;
            fails++;
            try { Thread.sleep(Math.min(60, 2 * fails) * 1000L); } catch (InterruptedException e) { break; }
        }
    }

    private static void readFull(InputStream in, byte[] b, int n) throws IOException {
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r <= 0) throw new IOException("з'єднання закрито");
            o += r;
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 16384) {
            int c = in.read();
            if (c < 0) throw new IOException("з'єднання закрито");
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 1) == '\n' && sb.charAt(n - 2) == '\r' && sb.charAt(n - 3) == '\n' && sb.charAt(n - 4) == '\r')
                return sb.toString();
        }
        throw new IOException("завеликі заголовки");
    }

    private static void send(OutputStream out, int op, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | op);
        if (data.length < 126) f.write(0x80 | data.length);
        else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xFF); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)) & 0xFF); }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
        synchronized (out) { out.write(f.toByteArray()); out.flush(); }
    }

    private void runOnce() throws Exception {
        URI u = new URI(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        Socket s;
        if (tls) {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 15000);
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            try { ss.getClass().getMethod("setHostname", String.class).invoke(ss, host); } catch (Throwable ignored) { }   // SNI (Android)
            ss.setSoTimeout(15000);
            ss.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.getSession()))
                throw new IOException("сертифікат не для " + host);
            s = ss;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 15000);
        }
        socket = s;
        try {
            s.setSoTimeout(15000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] k = new byte[16];
            rnd.nextBytes(k);
            String path = u.getRawPath() == null || u.getRawPath().length() == 0 ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + base64(k) + "\r\nSec-WebSocket-Version: 13\r\n"
                    + "User-Agent: " + userAgent + "\r\n\r\n";
            out.write(req.getBytes("US-ASCII"));
            out.flush();
            String h = readHeaders(in);
            if (!h.matches("(?s)^HTTP/1\\.\\d 101.*")) throw new IOException("сервер: " + h.split("\r")[0]);
            s.setSoTimeout(40000);   // heartbeat — раз на 15 с
            synchronized (lk) { connected = true; haveData = false; lastMsg = System.currentTimeMillis(); }
            setStatus("підключено");

            byte[] hdr = new byte[8];
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            int msgOp = 0;
            while (!stop) {
                readFull(in, hdr, 2);
                boolean fin = (hdr[0] & 0x80) != 0;
                int op = hdr[0] & 0x0F;
                boolean masked = (hdr[1] & 0x80) != 0;
                long len = hdr[1] & 0x7F;
                if (len == 126) { readFull(in, hdr, 2); len = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF); }
                else if (len == 127) { readFull(in, hdr, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (hdr[i] & 0xFF); }
                if (len < 0 || len > 32L * 1024 * 1024) throw new IOException("завелике повідомлення");
                byte[] mk = null;
                if (masked) { mk = new byte[4]; readFull(in, mk, 4); }
                byte[] pl = new byte[(int) len];
                readFull(in, pl, (int) len);
                if (masked) for (int i = 0; i < pl.length; i++) pl[i] ^= mk[i & 3];
                synchronized (lk) { lastMsg = System.currentTimeMillis(); }

                if (op == 8) { try { send(out, 8, new byte[0]); } catch (Exception ignored) { } return; }
                if (op == 9) { send(out, 10, pl); continue; }
                if (op == 10) continue;
                if (op == 1 || op == 2) { msg.reset(); msgOp = op; }
                else if (op != 0) continue;
                msg.write(pl, 0, pl.length);
                if (!fin) continue;
                if (msgOp == 1) handle(new String(msg.toByteArray(), "UTF-8"));
                msg.reset();
            }
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([A-Za-z_]+)\"");

    void handle(String text) {
        String head = text.length() > 200 ? text.substring(0, 200) : text;
        Matcher m = TYPE.matcher(head);
        if (m.find() ? !m.group(1).equals("alerts") : !text.contains("\"raions\"")) return;
        JSONObject n;
        try {
            JSONObject j = new JSONObject(text);
            JSONObject d = j.optJSONObject("data");
            n = AlertSource.streamToNep(d != null ? d : j);
        } catch (Exception e) { return; }
        if (n == null) return;
        String s2 = n.toString();
        boolean changed;
        synchronized (lk) {
            changed = nep == null || !s2.equals(sig) || !haveData;
            nep = n;
            sig = s2;
            haveData = true;
        }
        Listener l = listener;
        if (changed && l != null) try { l.changed(); } catch (Throwable ignored) { }
    }
}
