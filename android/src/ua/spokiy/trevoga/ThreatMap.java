package ua.spokiy.trevoga;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Карта України: області й райони (межі з res/raw/geo.json), тривоги кольором, цілі (дрони, ракети, КАБи)
 * з напрямком і слідом. Масштаб — двома пальцями, зсув — пальцем, подвійний дотик — наблизити, дотик до цілі — подробиці.
 */
public class ThreatMap extends View {
    static final class Threat {
        String id, type, title, place, region, quality, conf, at;
        double lat, lon;
        int heading = -1, count = 1;
        double uncKm;
        double[] trail = new double[0];
        boolean incoming;   // поза видимою ділянкою, але курс веде в неї
    }

    interface OnSelect { void selected(Threat t); }
    interface OnView { void changed(); }

    /** Міста (res/raw/cities.json, GeoNames): назва, координати, населення, статус (2 — столиця, 1 — обласний центр). */
    static final class Cities {
        String[] name; float[] lat, lon; int[] pop; byte[] cap;
        static Cities parse(String json) throws Exception {
            JSONArray a = new JSONArray(json);
            Cities c = new Cities();
            int n = a.length();
            c.name = new String[n]; c.lat = new float[n]; c.lon = new float[n]; c.pop = new int[n]; c.cap = new byte[n];
            for (int i = 0; i < n; i++) {
                JSONArray x = a.getJSONArray(i);
                c.name[i] = x.getString(0); c.lat[i] = x.getInt(1) / 1e4f; c.lon[i] = x.getInt(2) / 1e4f;
                c.pop[i] = x.getInt(3); c.cap[i] = (byte) x.getInt(4);
            }
            return c;
        }
        /** Населений пункт, у якому (або біля якого) точка: радіус «міста» росте з населенням. -1 — нічого поруч. */
        int locate(double lat, double lon, boolean near) {
            int best = -1;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < name.length; i++) {
                double d = GeoFilter.distM(lat, lon, lat(i), lon(i)) / 1000;
                double r = 1.5 + 4 * Math.log10(Math.max(1000, pop[i]) / 1000.0);
                if (near) r = 30;
                if (d > r) continue;
                double score = d / r;
                if (score < bestScore) { bestScore = score; best = i; }
            }
            return best;
        }
        private double lat(int i) { return lat[i]; }
        private double lon(int i) { return lon[i]; }
    }

    // проєкція: x = довгота·cos(48.5°), y = −широта (у «градусах»; масштаб і зсув — матрицею)
    private static final double KX = Math.cos(Math.toRadians(48.5));
    private static final float MINX = (float) (22.0 * KX), MAXX = (float) (40.3 * KX), MINY = -52.45f, MAXY = -44.3f;

    private GeoLookup geo;
    private final List<Path> oblastPaths = new ArrayList<Path>(), raionPaths = new ArrayList<Path>();
    private final List<GeoLookup.Area> oblastAreas = new ArrayList<GeoLookup.Area>(), raionAreas = new ArrayList<GeoLookup.Area>();
    private List<Threat> threats = new ArrayList<Threat>();
    private final Map<String, String> oblastLevel = new HashMap<String, String>();   // назва → рівень (red/yellow/…)
    private final Map<String, String> raionLevel = new HashMap<String, String>();    // «область|район» → рівень
    private String myRegion = "";
    private int myRaion = 0;
    private Threat selected;
    private Cities cities;
    private double meLat = Double.NaN, meLon = Double.NaN;
    private int meCity = -1;
    private final List<RectF> placed = new ArrayList<RectF>();
    private final Paint cityText = new Paint(Paint.ANTI_ALIAS_FLAG), cityHalo = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnSelect onSelect;
    private OnView onView;

    private final Matrix base = new Matrix();     // вписати Україну у вигляд
    private float zoom = 1f, panX = 0f, panY = 0f;
    private final Matrix m = new Matrix();
    private final float density;
    private final Paint land = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG),
            thin = new Paint(Paint.ANTI_ALIAS_FLAG), mine = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            icon = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG), trail = new Paint(Paint.ANTI_ALIAS_FLAG),
            course = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int colLand, colBorder, colText, colMine, colSea;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    public ThreatMap(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        land.setStyle(Paint.Style.FILL);
        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        thin.setStyle(Paint.Style.STROKE);
        mine.setStyle(Paint.Style.STROKE);
        trail.setStyle(Paint.Style.STROKE);
        trail.setStrokeCap(Paint.Cap.ROUND);
        trail.setStrokeJoin(Paint.Join.ROUND);
        course.setStyle(Paint.Style.STROKE);
        course.setStrokeCap(Paint.Cap.ROUND);
        halo.setStyle(Paint.Style.FILL);
        text.setTextSize(11 * density);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        cityText.setTextSize(11 * density);
        cityHalo.setTextSize(11 * density);
        cityHalo.setStyle(Paint.Style.STROKE);
        cityHalo.setStrokeWidth(3 * density);
        cityHalo.setStrokeJoin(Paint.Join.ROUND);
        setColors(0xFFFCEAE7, 0xFF857371, 0xFF231918, 0xFFB3261E, 0xFFF6F2F1);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                panX -= dx; panY -= dy; clamp(); invalidate(); viewChanged(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 6f) { zoom = 1f; panX = 0; panY = 0; invalidate(); viewChanged(); } else zoomAt(2.2f, e.getX(), e.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { pick(e.getX(), e.getY()); return true; }
        });
        setContentDescription("Карта цілей");
    }

    void setColors(int landC, int borderC, int textC, int mineC, int seaC) {
        colLand = landC; colBorder = borderC; colText = textC; colMine = mineC; colSea = seaC;
        invalidate();
    }

    void setOnSelect(OnSelect s) { onSelect = s; }
    void setOnView(OnView v) { onView = v; }

    private void viewChanged() { if (onView != null) onView.changed(); }

    /** Карту наближено — тоді внизу показуємо лише цілі цієї ділянки. */
    boolean zoomed() { return zoom > 1.3f; }

    /** Орієнтовна дальність прогнозу курсу (км): куди ціль може долетіти найближчим часом. */
    static double reachKm(Threat t) {
        switch (kind(t)) {
            case 0: return 300;   // балістика
            case 1: return 400;   // крилаті ракети ~30 хв
            case 2: return 60;    // КАБ
            case 3: return 120;   // БпЛА ~1 год
            case 4: return 20;    // FPV
            default: return 100;
        }
    }

    /** Точка на відстані km від цілі за її курсом (наближено, для малих відстаней). */
    static double[] ahead(Threat t, double km) {
        double a = Math.toRadians(t.heading);
        double lat = t.lat + km * Math.cos(a) / 111.0;
        double lon = t.lon + km * Math.sin(a) / (111.0 * Math.cos(Math.toRadians(t.lat)));
        return new double[]{lat, lon};
    }

    /**
     * Цілі для підсумку внизу: на всій карті — усі; у наближенні — ті, що в межах видимої ділянки,
     * і ті, що поза нею, але летять у неї (за курсом у межах дальності прогнозу).
     */
    List<Threat> relevant() {
        if (!zoomed() || getWidth() == 0) { for (Threat t : threats) t.incoming = false; return threats; }
        matrix();
        float w = getWidth(), h = getHeight(), mx = w * 0.05f, my = h * 0.05f;
        List<Threat> out = new ArrayList<Threat>();
        for (Threat t : threats) {
            t.incoming = false;
            float[] p = screen(t.lat, t.lon);
            if (p[0] >= -mx && p[0] <= w + mx && p[1] >= -my && p[1] <= h + my) { out.add(t); continue; }
            if (t.heading < 0) continue;
            double reach = reachKm(t);
            for (int k = 1; k <= 24; k++) {
                double[] q = ahead(t, reach * k / 24.0);
                float[] s2 = screen(q[0], q[1]);
                if (s2[0] >= 0 && s2[0] <= w && s2[1] >= 0 && s2[1] <= h) { t.incoming = true; out.add(t); break; }
            }
        }
        return out;
    }

    /** Межі (розбір ~0,6 МБ — у фоновому потоці, потім setGeo у потоці інтерфейсу). */
    void setGeo(GeoLookup g) {
        geo = g;
        oblastPaths.clear(); raionPaths.clear(); oblastAreas.clear(); raionAreas.clear();
        for (GeoLookup.Area a : g.oblasts) { oblastPaths.add(path(a)); oblastAreas.add(a); }
        for (GeoLookup.Area a : g.raions) { raionPaths.add(path(a)); raionAreas.add(a); }
        invalidate();
    }

    boolean hasGeo() { return geo != null; }

    void setCities(Cities c) { cities = c; if (!Double.isNaN(meLat)) setMe(meLat, meLon); invalidate(); }

    /** Де користувач (для крапки на карті й підпису його населеного пункту); NaN — невідомо. */
    void setMe(double lat, double lon) {
        meLat = lat; meLon = lon;
        meCity = cities != null && !Double.isNaN(lat) ? cities.locate(lat, lon, false) : -1;
        invalidate();
    }

    /** Назва населеного пункту користувача: «Одеса» або «біля Таїрове»; null — невідомо. */
    String meCityName() {
        if (cities == null || Double.isNaN(meLat)) return null;
        if (meCity >= 0) return cities.name[meCity];
        int n = cities.locate(meLat, meLon, true);
        return n >= 0 ? "біля: " + cities.name[n] : null;
    }

    private static Path path(GeoLookup.Area a) {
        Path p = new Path();
        p.setFillType(Path.FillType.EVEN_ODD);
        for (int[] r : a.rings) {
            for (int i = 0; i + 1 < r.length; i += 2) {
                float x = (float) (r[i] / 1e4 * KX), y = (float) (-r[i + 1] / 1e4);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            p.close();
        }
        return p;
    }

    void setPlace(String region, int raion) { myRegion = region == null ? "" : region; myRaion = raion; invalidate(); }

    /** Тривоги з /status сервера: спершу офіційні (UkraineAlarm), інакше NEPTUN. */
    void setAlerts(JSONObject status) {
        oblastLevel.clear(); raionLevel.clear();
        if (status != null) {
            JSONObject src = status.optJSONObject("ua");
            if (src == null) src = status.optJSONObject("neptun");
            if (src != null) {
                JSONArray o = src.optJSONArray("o"), r = src.optJSONArray("r");
                for (int i = 0; o != null && i < o.length(); i++) {
                    JSONArray x = o.optJSONArray(i);
                    if (x != null) oblastLevel.put(x.optString(0), x.optString(2, "red"));
                }
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONArray x = r.optJSONArray(i);
                    if (x != null) raionLevel.put(x.optString(0) + "|" + x.optString(1), x.optString(3, "red"));
                }
            }
        }
        invalidate();
    }

    static List<Threat> parseThreats(JSONObject j) {
        List<Threat> out = new ArrayList<Threat>();
        JSONArray a = j == null ? null : j.optJSONArray("items");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.isNull("la") || o.isNull("lo")) continue;
            Threat t = new Threat();
            t.id = o.optString("id"); t.type = o.optString("t"); t.title = o.optString("n");
            t.lat = o.optDouble("la"); t.lon = o.optDouble("lo");
            t.heading = o.isNull("h") ? -1 : o.optInt("h", -1);
            t.uncKm = o.optDouble("u", 0); if (Double.isNaN(t.uncKm)) t.uncKm = 0;
            t.quality = o.optString("q"); t.conf = o.optString("c"); t.count = Math.max(1, o.optInt("k", 1));
            t.at = o.optString("at"); t.place = o.optString("p"); t.region = o.optString("r");
            JSONArray tr = o.optJSONArray("tr");
            if (tr != null) { t.trail = new double[tr.length()]; for (int k = 0; k < tr.length(); k++) t.trail[k] = tr.optDouble(k); }
            out.add(t);
        }
        return out;
    }

    void setThreats(List<Threat> list) {
        threats = list;
        if (selected != null) {
            Threat keep = null;
            for (Threat t : list) if (t.id.equals(selected.id)) keep = t;
            selected = keep;
            if (onSelect != null) onSelect.selected(selected);
        }
        invalidate();
        viewChanged();
    }

    // ---------- вигляд ----------

    static final int C_BALLISTIC = 0xFF7B1FA2, C_MISSILE = 0xFFD32F2F, C_KAB = 0xFFEF6C00, C_UAV = 0xFF37474F,
            C_FPV = 0xFF6D4C41, C_AIR = 0xFF1565C0;

    static int kind(Threat t) {
        String s = (t.type + " " + t.title).toLowerCase();
        if (s.contains("ballist") || s.contains("баліст") || s.contains("iskander") || s.contains("іскандер")) return 0;
        if (s.contains("missile") || s.contains("ракет") || s.contains("cruise") || s.contains("крилат") || s.contains("x-") || s.contains("калібр")) return 1;
        if (s.contains("kab") || s.contains("каб") || s.contains("бомб")) return 2;
        if (s.contains("fpv")) return 4;
        if (s.contains("uav") || s.contains("бпла") || s.contains("дрон") || s.contains("shahed") || s.contains("шахед") || s.contains("герань")) return 3;
        return 5;
    }

    static int color(Threat t) {
        switch (kind(t)) {
            case 0: return C_BALLISTIC;
            case 1: return C_MISSILE;
            case 2: return C_KAB;
            case 3: return C_UAV;
            case 4: return C_FPV;
            default: return C_AIR;
        }
    }

    static String kindTitle(int k) {
        switch (k) {
            case 0: return "Балістика";
            case 1: return "Ракети";
            case 2: return "КАБи";
            case 3: return "БпЛА";
            case 4: return "FPV";
            default: return "Інше";
        }
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        setMeasuredDimension(w, (int) (w * 0.72f));   // пропорції України
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        RectF src = new RectF(MINX, MINY, MAXX, MAXY), dst = new RectF(6 * density, 6 * density, w - 6 * density, h - 6 * density);
        base.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        clamp();
    }

    private void zoomAt(float f, float fx, float fy) {
        float nz = Math.max(1f, Math.min(14f, zoom * f));
        f = nz / zoom;
        panX = fx - (fx - panX) * f;
        panY = fy - (fy - panY) * f;
        zoom = nz;
        clamp();
        invalidate();
        viewChanged();
    }

    private void clamp() {
        float w = getWidth(), h = getHeight();
        float minX = w - w * zoom, minY = h - h * zoom;
        panX = Math.min(0, Math.max(minX, panX));
        panY = Math.min(0, Math.max(minY, panY));
    }

    private void matrix() {
        m.set(base);
        m.postScale(zoom, zoom);
        m.postTranslate(panX, panY);
    }

    private final float[] pt = new float[2];
    private float[] screen(double lat, double lon) {
        pt[0] = (float) (lon * KX); pt[1] = (float) -lat;
        m.mapPoints(pt);
        return pt;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    private void pick(float x, float y) {
        matrix();
        Threat best = null;
        float bd = 28 * density;
        for (Threat t : threats) {
            float[] p = screen(t.lat, t.lon);
            float d = (float) Math.hypot(p[0] - x, p[1] - y);
            if (d < bd) { bd = d; best = t; }
        }
        selected = best;
        if (onSelect != null) onSelect.selected(best);
        invalidate();
    }

    private static int levelColor(String lv) {
        if (lv == null) return 0;
        if (lv.contains("yellow")) return 0x66F9A825;
        if (lv.contains("purple") || lv.contains("ballist")) return 0x667B1FA2;
        return 0x59E53935;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(colSea);
        if (geo == null) {
            text.setColor(colText);
            c.drawText("Завантажую карту…", getWidth() / 2f, getHeight() / 2f, text);
            return;
        }
        matrix();
        float s = 1f;
        float[] v = new float[9];
        m.getValues(v);
        s = v[Matrix.MSCALE_X];   // пікселів на «градус»
        c.save();
        c.concat(m);
        land.setColor(colLand);
        for (Path p : oblastPaths) c.drawPath(p, land);
        // тривоги: область цілком або окремі райони
        for (int i = 0; i < oblastAreas.size(); i++) {
            int col = levelColor(oblastLevel.get(oblastAreas.get(i).name));
            if (col != 0) { fill.setColor(col); c.drawPath(oblastPaths.get(i), fill); }
        }
        for (int i = 0; i < raionAreas.size(); i++) {
            GeoLookup.Area a = raionAreas.get(i);
            String on = oblastName(a.oblast);
            if (on == null || oblastLevel.containsKey(on)) continue;
            int col = levelColor(raionLevel.get(on + "|" + a.name));
            if (col != 0) { fill.setColor(col); c.drawPath(raionPaths.get(i), fill); }
        }
        if (zoom >= 2.5f) {
            thin.setColor((colBorder & 0x00FFFFFF) | 0x55000000);
            thin.setStrokeWidth(0.6f * density / s);
            for (Path p : raionPaths) c.drawPath(p, thin);
        }
        border.setColor(colBorder);
        border.setStrokeWidth(0.9f * density / s);
        for (Path p : oblastPaths) c.drawPath(p, border);
        // своє місце
        mine.setColor(colMine);
        mine.setStrokeWidth(2.2f * density / s);
        for (int i = 0; i < raionAreas.size(); i++)
            if (myRaion != 0 && raionAreas.get(i).uid == myRaion) c.drawPath(raionPaths.get(i), mine);
        if (myRaion == 0)
            for (int i = 0; i < oblastAreas.size(); i++)
                if (oblastAreas.get(i).name.equals(myRegion)) c.drawPath(oblastPaths.get(i), mine);
        c.restore();

        drawCities(c, s);
        drawMe(c);

        // цілі — у пікселях екрана (розмір значків не залежить від масштабу)
        float pxPerKm = s / 111f;
        if (selected != null) drawRoute(c, selected);   // маршрут — лише для вибраної цілі, щоб не захаращувати карту
        for (Threat t : threats) {
            float[] p = screen(t.lat, t.lon);
            float x = p[0], y = p[1];
            int col = color(t);
            float r = (float) (t.uncKm * pxPerKm);
            if (r > 10 * density && r < 3 * Math.max(getWidth(), getHeight())) {
                halo.setColor((col & 0x00FFFFFF) | 0x1F000000);
                c.drawCircle(x, y, r, halo);
            }
            if (t.heading >= 0) {
                course.setColor((col & 0x00FFFFFF) | 0xB0000000);
                course.setStrokeWidth(1.6f * density);
                course.setPathEffect(new DashPathEffect(new float[]{4 * density, 3 * density}, 0));
                double a = Math.toRadians(t.heading);
                float len = 26 * density;
                c.drawLine(x, y, x + (float) Math.sin(a) * len, y - (float) Math.cos(a) * len, course);
                course.setPathEffect(null);
            }
            drawIcon(c, t, x, y, col, t == selected);
        }
    }

    /** Мінімальне населення для підпису за масштабом (обласні центри — завжди). */
    private int popMin() {
        if (zoom < 1.8f) return Integer.MAX_VALUE;
        if (zoom < 3f) return 150000;
        if (zoom < 5f) return 40000;
        if (zoom < 8f) return 12000;
        if (zoom < 11f) return 4000;
        return 1500;
    }

    private void drawCities(Canvas c, float s) {
        if (cities == null) return;
        placed.clear();
        int pm = popMin();
        float w = getWidth(), h = getHeight();
        cityHalo.setColor(colLand);
        // спершу своє місто (жирним, кольором акценту), далі столиця, обласні центри, решта за населенням
        if (meCity >= 0) label(c, meCity, true, w, h);
        for (int i = 0; i < cities.name.length; i++) {
            if (i == meCity) continue;
            if (cities.cap[i] == 0 && cities.pop[i] < pm) continue;
            label(c, i, false, w, h);
        }
    }

    private void label(Canvas c, int i, boolean mineCity, float w, float h) {
        float[] p = screen(cities.lat[i], cities.lon[i]);
        float x = p[0], y = p[1];
        if (x < -40 * density || y < -20 * density || x > w + 40 * density || y > h + 20 * density) return;
        boolean big = cities.cap[i] > 0 || mineCity;
        float ts = (cities.cap[i] == 2 ? 13 : big ? 11.5f : 10.5f) * density;
        cityText.setTextSize(ts);
        cityHalo.setTextSize(ts);
        cityText.setFakeBoldText(big);
        cityHalo.setFakeBoldText(big);
        String n = cities.name[i];
        float tw = cityText.measureText(n);
        float r = (big ? 3.2f : 2.4f) * density;
        float lx = x + r + 3 * density, ly = y + ts * 0.35f;
        RectF box = new RectF(x - r, ly - ts, lx + tw, ly + ts * 0.3f);
        if (lx + tw > w - 2 * density) { lx = x - r - 3 * density - tw; box.set(lx, ly - ts, x + r, ly + ts * 0.3f); }
        if (!mineCity) for (RectF o : placed) if (RectF.intersects(o, box)) return;
        placed.add(box);
        int col = mineCity ? colMine : colText;
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(colLand);
        c.drawCircle(x, y, r + 1.2f * density, dot);
        dot.setColor(col);
        c.drawCircle(x, y, r, dot);
        cityText.setColor(col);
        c.drawText(n, lx, ly, cityHalo);
        c.drawText(n, lx, ly, cityText);
    }

    private void drawMe(Canvas c) {
        if (Double.isNaN(meLat)) return;
        float[] p = screen(meLat, meLon);
        float x = p[0], y = p[1];
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0x331A73E8);
        c.drawCircle(x, y, 13 * density, dot);
        dot.setColor(Color.WHITE);
        c.drawCircle(x, y, 7 * density, dot);
        dot.setColor(0xFF1A73E8);
        c.drawCircle(x, y, 5 * density, dot);
    }

    /** Маршрут вибраної цілі: пройдений шлях (суцільна лінія) і прогноз за курсом (пунктир, слабший). */
    private void drawRoute(Canvas c, Threat t) {
        drawTrail(c, t);
        if (t.heading < 0) return;
        float[] q0 = screen(t.lat, t.lon);
        float x0 = q0[0], y0 = q0[1];
        double[] e = ahead(t, reachKm(t));
        float[] q1 = screen(e[0], e[1]);
        course.setColor((color(t) & 0x00FFFFFF) | 0x99000000);
        course.setStrokeWidth(2f * density);
        course.setPathEffect(new DashPathEffect(new float[]{7 * density, 5 * density}, 0));
        c.drawLine(x0, y0, q1[0], q1[1], course);
        course.setPathEffect(null);
    }

    private void drawTrail(Canvas c, Threat t) {
        if (t.trail.length < 2) return;
        Path p = new Path();
        for (int i = 0; i + 1 < t.trail.length; i += 2) {
            float[] q = screen(t.trail[i], t.trail[i + 1]);
            if (i == 0) p.moveTo(q[0], q[1]); else p.lineTo(q[0], q[1]);
        }
        float[] q = screen(t.lat, t.lon);
        p.lineTo(q[0], q[1]);
        trail.setColor((color(t) & 0x00FFFFFF) | 0xCC000000);
        trail.setStrokeWidth(3f * density);
        c.drawPath(p, trail);
    }

    private void drawIcon(Canvas c, Threat t, float x, float y, int col, boolean sel) {
        float r = 9 * density;
        icon.setStyle(Paint.Style.FILL);
        if (sel) { icon.setColor((col & 0x00FFFFFF) | 0x40000000); c.drawCircle(x, y, r * 1.9f, icon); }
        icon.setColor(Color.WHITE);
        c.drawCircle(x, y, r + 1.6f * density, icon);
        icon.setColor(col);
        c.drawCircle(x, y, r, icon);
        // гліф: літак-«дельта» для дронів, стріла для ракет, ромб для КАБ, крапка для решти — повернутий за курсом
        c.save();
        c.translate(x, y);
        if (t.heading >= 0) c.rotate(t.heading);
        icon.setColor(Color.WHITE);
        Path g = new Path();
        int k = kind(t);
        float u = r * 0.62f;
        if (k == 3) { g.moveTo(0, -u); g.lineTo(u, u * 0.7f); g.lineTo(0, u * 0.25f); g.lineTo(-u, u * 0.7f); g.close(); }
        else if (k == 0 || k == 1) { g.moveTo(0, -u * 1.1f); g.lineTo(u * 0.45f, -u * 0.2f); g.lineTo(u * 0.25f, -u * 0.2f); g.lineTo(u * 0.25f, u);
            g.lineTo(-u * 0.25f, u); g.lineTo(-u * 0.25f, -u * 0.2f); g.lineTo(-u * 0.45f, -u * 0.2f); g.close(); }
        else if (k == 2) { g.moveTo(0, -u); g.lineTo(u * 0.7f, 0); g.lineTo(0, u); g.lineTo(-u * 0.7f, 0); g.close(); }
        else g.addCircle(0, 0, u * 0.45f, Path.Direction.CW);
        c.drawPath(g, icon);
        c.restore();
        if (t.count > 1) {
            float bx = x + r * 0.9f, by = y - r * 0.9f;
            icon.setColor(Color.WHITE);
            c.drawCircle(bx, by, 7 * density, icon);
            icon.setColor(col);
            c.drawCircle(bx, by, 6 * density, icon);
            text.setColor(Color.WHITE);
            text.setTextSize(9 * density);
            c.drawText(String.valueOf(t.count), bx, by + 3.2f * density, text);
            text.setTextSize(11 * density);
        }
    }

    private String oblastName(int uid) {
        for (GeoLookup.Area a : oblastAreas) if (a.uid == uid) return a.name;
        return null;
    }
}
