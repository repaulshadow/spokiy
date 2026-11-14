package ua.spokiy.trevoga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    // Кольори
    // Material 3: поверхні й текст (на Android 12+ — системні кольори з шпалер, див. initPalette)
    static int BG = 0xFFFFF8F7, CARD = 0xFFFCEAE7, TEXT = 0xFF231918, MUTED = 0xFF534341, LINE = 0xFFD8C2BF;
    static int PRIMARY = 0xFFB3261E, ON_PRIMARY = 0xFFFFFFFF, TONAL = 0xFFFFDAD6, ON_TONAL = 0xFF410002;
    static boolean night;
    static final int FILLED = 0, TONAL_BTN = 1, OUTLINED = 2;
    static final int RED = 0xFFDC2626, DARK_RED = 0xFF991B1B, GREEN = 0xFF16A34A, GRAY = 0xFF6B7280;

    private SharedPreferences prefs;
    private LinearLayout mapCard;
    private ThreatMap map;
    private TextView mapInfo, mapSummary, mapMe;
    private LinearLayout legend;
    private final List<TextView> segs = new ArrayList<TextView>();
    private static GeoLookup geoCache;
    private static ThreatMap.Cities citiesCache;
    private volatile boolean mapLoading;
    private long mapAt;
    private String mapErr;
    private LinearLayout home, settings;
    private ScrollView scroll;
    private LinearLayout statusCard;
    private TextView title, place, detail, footer, soundInfo;
    private FlowLayout chips;
    private Spinner raionSpinner, regionSpinner;
    private Switch autoSwitch;
    private TextView autoInfo;
    static final int REQ_LOCATION = 41, REQ_NOTIF = 42;
    private Switch enabledSwitch;
    private final List<Integer> raionUids = new ArrayList<Integer>();
    private final Handler ticker = new Handler();
    private static final int PICK_ALARM = 11, PICK_CLEAR = 12;
    static boolean visible = false;
    private TextView versionInfo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        Prefs.createChannels(this);
        initPalette();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(root);
        this.scroll = scroll;

        // Два екрани: головний (статус) і налаштування (усе інше, разом із тестами)
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setVisibility(View.GONE);
        root.addView(home);
        root.addView(settings);

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(64));
        View back = iconButton(R.drawable.ic_back, "Назад", new View.OnClickListener() {
            public void onClick(View v) { showSettings(false); }
        });
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView st = text("Налаштування", 22, TEXT, false);
        st.setPadding(dp(8), 0, 0, 0);
        head.addView(st);
        settings.addView(head);

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(dp(64));
        TextView appTitle = text("Тривога", 22, TEXT, false);
        appTitle.setPadding(dp(4), 0, 0, 0);
        top.addView(appTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(segmented(new String[]{"Простий", "Карта"}, prefs.getBoolean(Prefs.MAP_VIEW, false) ? 1 : 0));
        home.addView(top);

        // ---------- картка статусу ----------
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(24), dp(24), dp(24), dp(24));
        statusCard.setMinimumHeight(dp(200));
        title = text("", 30, Color.WHITE, true);
        place = text("", 16, 0xE6FFFFFF, false);
        place.setPadding(0, dp(6), 0, 0);
        detail = text("", 14, 0xD9FFFFFF, false);
        detail.setPadding(0, dp(2), 0, 0);
        // фіксовані рядки — щоб картка не «стрибала», коли текст довший/коротший
        title.setMaxLines(2);
        place.setSingleLine(true);
        place.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tnum(detail);
        chips = new FlowLayout(this, dp(8));
        chips.setPadding(0, dp(16), 0, 0);
        statusCard.addView(title);
        statusCard.addView(place);
        statusCard.addView(detail);
        statusCard.addView(chips);
        home.addView(statusCard, matchWrap(0));
        buildMapCard();
        home.addView(mapCard, matchWrap(dp(12)));

        // ---------- стеження ----------
        LinearLayout watch = card(null);
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Стежити за тривогою");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setTextColor(TEXT);
        enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean on) {
                prefs.edit().putBoolean(Prefs.ENABLED, on).apply();
                if (on) MonitorService.start(MainActivity.this);
                else {
                    if (Flavor.hooks != null) Flavor.hooks.stop(MainActivity.this);
                    else stopService(new Intent(MainActivity.this, MonitorService.class));
                    prefs.edit().putInt(Prefs.STATE, AlertSource.UNKNOWN).apply();
                }
                refresh();
            }
        });
        watch.addView(enabledSwitch);
        settings.addView(watch, matchWrap(dp(8)));

        // ---------- місце ----------
        LinearLayout where = card("Місце");
        where.addView(fieldLabel("Область"));
        final Spinner spinner = new Spinner(this);
        regionSpinner = spinner;
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, AlertSource.REGIONS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);
        int idx = AlertSource.indexOf(Prefs.region(this));
        spinner.setSelection(idx >= 0 ? idx : 9);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String r = AlertSource.REGIONS[pos];
                if (!r.equals(Prefs.region(MainActivity.this))) {
                    prefs.edit().putString(Prefs.REGION, r).putInt(Prefs.RAION, 0)
                            .putInt(Prefs.STATE, AlertSource.UNKNOWN).remove(Prefs.STATUS_TIME).apply();
                    fillRaions();
                    if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.checkNow(MainActivity.this);
                }
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        where.addView(field(spinner), matchWrap(dp(4)));
        where.addView(fieldLabel("Район"));
        raionSpinner = new Spinner(this);
        raionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                int uid = raionUids.get(pos);
                if (uid != Prefs.raion(MainActivity.this)) {
                    prefs.edit().putInt(Prefs.RAION, uid).putInt(Prefs.STATE, AlertSource.UNKNOWN)
                            .remove(Prefs.STATUS_TIME).apply();
                    if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.checkNow(MainActivity.this);
                }
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        where.addView(field(raionSpinner), matchWrap(dp(4)));
        fillRaions();
        Switch partial = new Switch(this);
        partial.setText("Сповіщати й про тривогу в окремих громадах");
        partial.setTextColor(TEXT);
        partial.setTextSize(15);
        partial.setPadding(0, dp(12), 0, 0);
        partial.setChecked(prefs.getBoolean(Prefs.PARTIAL, false));
        partial.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) {
                prefs.edit().putBoolean(Prefs.PARTIAL, v).apply();
                if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.checkNow(MainActivity.this);
            }
        });
        where.addView(partial);
        // автовизначення району за геопозицією (з перевіркою правдоподібності точок — РЕБ, підміна GPS)
        autoSwitch = new Switch(this);
        autoSwitch.setText("Визначати район автоматично (геопозиція)");
        autoSwitch.setTextColor(TEXT);
        autoSwitch.setTextSize(15);
        autoSwitch.setPadding(0, dp(12), 0, 0);
        autoSwitch.setChecked(prefs.getBoolean(Prefs.AUTO_PLACE, false));
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) {
                prefs.edit().putBoolean(Prefs.AUTO_PLACE, v).apply();
                if (v) askLocation();
                updateAutoUi();
                if (prefs.getBoolean(Prefs.ENABLED, true)) {
                    MonitorService.start(MainActivity.this);   // тип служби «геопозиція» — поки вікно відкрите
                    MonitorService.checkNow(MainActivity.this);
                }
            }
        });
        where.addView(autoSwitch);
        autoInfo = text("", 12, MUTED, false);
        where.addView(autoInfo);
        if (Flavor.PLAY) {   // у версії з Google Play немає постійної служби — район вибирають вручну
            autoSwitch.setVisibility(View.GONE);
            autoInfo.setVisibility(View.GONE);
        }
        updateAutoUi();
        settings.addView(where, matchWrap(dp(12)));

        // ---------- сповіщення ----------
        LinearLayout notif = card("Сповіщення");
        final Switch quiet = new Switch(this);
        quiet.setText("Лише сповіщення — без голосу і сирени");
        quiet.setTextColor(TEXT);
        quiet.setTextSize(15);
        quiet.setPadding(0, dp(10), 0, dp(4));
        quiet.setChecked(prefs.getBoolean(Prefs.NOTIFY_ONLY, false));
        notif.addView(quiet);
        TextView quietHint = text("Сповіщення буде зафарбоване кольором тривоги (червоний) або відбою (зелений).", 12, MUTED, false);
        notif.addView(quietHint);

        final TextView voiceLabel = fieldLabel("Голос перед сиреною");
        notif.addView(voiceLabel);
        final Spinner voice = new Spinner(this);
        final String[] voiceIds = {"tetiana", "mykyta", "off"};
        ArrayAdapter<String> va = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"Жіночий (Тетяна)", "Чоловічий (Микита)", "Без голосу, лише сирена"});
        va.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voice.setAdapter(va);
        String cv = prefs.getString(Prefs.VOICE, "tetiana");
        voice.setSelection(cv.equals("mykyta") ? 1 : cv.equals("off") ? 2 : 0);
        voice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(Prefs.VOICE, voiceIds[pos]).apply();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        View voiceBox = field(voice);
        notif.addView(voiceBox, matchWrap(dp(4)));
        final View[] soundViews = {voiceLabel, voice, voiceBox};
        quiet.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) {
                prefs.edit().putBoolean(Prefs.NOTIFY_ONLY, v).apply();
                if (v) Sounds.stop();
                for (View x : soundViews) { x.setEnabled(!v); x.setAlpha(v ? 0.6f : 1f); }
            }
        });
        for (View x : soundViews) { x.setEnabled(!quiet.isChecked()); x.setAlpha(quiet.isChecked() ? 0.6f : 1f); }

        notif.addView(fieldLabel("Свої звуки"));
        LinearLayout row2 = row();
        row2.addView(button("Сирена", false, new View.OnClickListener() {
            public void onClick(View v) { pickSound(PICK_ALARM); }
        }), weight(0));
        row2.addView(button("Відбій", false, new View.OnClickListener() {
            public void onClick(View v) { pickSound(PICK_CLEAR); }
        }), weight(dp(8)));
        row2.addView(button("Скинути", false, new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().remove(Prefs.SOUND_ALARM).remove(Prefs.SOUND_CLEAR).apply();
                refresh();
            }
        }), weight(dp(8)));
        notif.addView(row2);
        soundInfo = text("", 13, MUTED, false);
        soundInfo.setPadding(0, dp(6), 0, 0);
        notif.addView(soundInfo);

        View batt = button("Дозволити роботу у фоні", 0, OUTLINED, new View.OnClickListener() {
            public void onClick(View v) { askBattery(true); }
        });
        LinearLayout.LayoutParams bl = matchWrap(dp(12));
        notif.addView(batt, bl);
        if (Flavor.PLAY) batt.setVisibility(View.GONE);   // push доходить і без винятків для батареї
        if (Build.VERSION.SDK_INT >= 26 && !Flavor.PLAY) {
            // Android вимагає постійне сповіщення для роботи у фоні; його можна сховати в налаштуваннях каналу
            View hide = button("Сховати постійне сповіщення", 0, OUTLINED, new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        startActivity(new Intent("android.settings.CHANNEL_NOTIFICATION_SETTINGS")
                                .putExtra("android.provider.extra.APP_PACKAGE", getPackageName())
                                .putExtra("android.provider.extra.CHANNEL_ID", Prefs.CH_STATUS));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                                    .putExtra("android.provider.extra.APP_PACKAGE", getPackageName()));
                        } catch (Exception ignored) { }
                    }
                }
            });
            notif.addView(hide, matchWrap(dp(8)));
            notif.addView(text("Вимкніть «Фонова робота» — стеження продовжить працювати, а сповіщення прийдуть лише при тривозі й відбої.", 12, MUTED, false));
        }
        settings.addView(notif, matchWrap(dp(12)));

        // ---------- кнопки ----------
        LinearLayout row = row();
        row.addView(button("Тест тривоги", false, new View.OnClickListener() {
            public void onClick(View v) {
                List<String> t = MonitorService.parseList(prefs.getString(Prefs.THREATS, ""));
                if (t.isEmpty()) t.add("drones");
                boolean q = prefs.getBoolean(Prefs.NOTIFY_ONLY, false);
                if (!q) Sounds.alarm(MainActivity.this, t);
                MonitorService.showAlert(MainActivity.this, "ПОВІТРЯНА ТРИВОГА! (тест)",
                        Prefs.place(MainActivity.this) + ". Пройдіть в укриття!\n" + AlertSource.titles(t), MonitorService.RED, q);
            }
        }), weight(0));
        row.addView(button("Тест відбою", false, new View.OnClickListener() {
            public void onClick(View v) {
                boolean q = prefs.getBoolean(Prefs.NOTIFY_ONLY, false);
                if (!q) Sounds.clear(MainActivity.this);
                MonitorService.showAlert(MainActivity.this, "Відбій тривоги (тест)", Prefs.place(MainActivity.this), MonitorService.GREEN, q);
            }
        }), weight(dp(8)));
        // ---------- оновлення ----------
        LinearLayout upd = card("Оновлення");
        versionInfo = text("", 14, TEXT, false);
        versionInfo.setPadding(0, dp(8), 0, dp(4));
        upd.addView(versionInfo);
        upd.addView(button("Перевірити оновлення", 0, OUTLINED, new View.OnClickListener() {
            public void onClick(View v) { checkUpdateNow(); }
        }), matchWrap(dp(8)));
        if (!Flavor.PLAY) settings.addView(upd, matchWrap(dp(12)));   // у Google Play оновлює магазин

        LinearLayout tests = card("Перевірка звуку і сповіщень");
        tests.addView(row, matchWrap(dp(10)));
        settings.addView(tests, matchWrap(dp(12)));

        // ---------- головний екран: кнопки ----------
        LinearLayout homeRow = row();
        homeRow.addView(button("Налаштування", R.drawable.ic_settings, TONAL_BTN, new View.OnClickListener() {
            public void onClick(View v) { showSettings(true); }
        }), new LinearLayout.LayoutParams(0, dp(52), 2f));
        View hush = button("Тиша", R.drawable.ic_volume_off, FILLED, new View.OnClickListener() {
            public void onClick(View v) { Sounds.stop(); }
        });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(52), 1f);
        hl.leftMargin = dp(8);
        homeRow.addView(hush, hl);
        home.addView(homeRow, matchWrap(dp(16)));

        footer = text("", 12, MUTED, false);
        tnum(footer);
        footer.setPadding(dp(4), dp(14), dp(4), 0);
        home.addView(footer);

        // Обов'язкове посилання на NEPTUN (умова використання їхніх даних)
        TextView credit = text("", 12, MUTED, false);
        credit.setPadding(dp(4), dp(10), dp(4), 0);
        credit.setText(android.text.Html.fromHtml("Дані: alerts.in.ua · <a href=\"https://neptun.in.ua/\">Карта повітряних тривог — NEPTUN</a>"));
        credit.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        credit.setLinkTextColor(PRIMARY);
        home.addView(credit);

        // Android 15: вікно на весь екран — відступи під рядок стану й панель навігації
        final int padTop = root.getPaddingTop(), padBottom = root.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @SuppressWarnings("deprecation")
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets ins) {
                root.setPadding(root.getPaddingLeft(), padTop + ins.getSystemWindowInsetTop(), root.getPaddingRight(), padBottom + ins.getSystemWindowInsetBottom());
                return ins;
            }
        });
        setContentView(scroll);
        applyMode();
        handleIntent(getIntent());

        if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.start(this);
        // Android 13+: дозвіл на сповіщення (без нього не буде видно тривоги в шторці)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        else askBattery(false);
    }

    // ---------- допоміжне для інтерфейсу ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private TextView fieldLabel(String s) {
        TextView t = text(s, 13, MUTED, false);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    /** Material 3 «filled card»: тонований фон поверхні, великі заокруглення, без рамки й тіні. */
    private LinearLayout card(String caption) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(20));
        c.setBackground(bg);
        if (caption != null) {
            TextView t = text(caption, 14, PRIMARY, true);
            t.setLetterSpacing(0.01f);
            c.addView(t);
        }
        return c;
    }

    /** Список у рамці (Material «outlined field»). */
    private View field(Spinner sp) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), MUTED);
        f.setBackground(g);
        f.setPadding(dp(4), dp(2), dp(4), dp(2));
        f.addView(sp, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return f;
    }

    private View iconButton(int icon, String desc, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        if (Build.VERSION.SDK_INT >= 21) iv.setImageTintList(ColorStateList.valueOf(TEXT));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setContentDescription(desc);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        iv.setBackground(new RippleDrawable(ColorStateList.valueOf((TEXT & 0x00FFFFFF) | 0x26000000), null, mask));
        iv.setClickable(true);
        iv.setOnClickListener(l);
        return iv;
    }

    private void initPalette() {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            BG = 0xFF1A1110; CARD = 0xFF2A201F; TEXT = 0xFFF1DEDC; MUTED = 0xFFD8C2BF; LINE = 0xFF534341;
            PRIMARY = 0xFFFFB4AB; ON_PRIMARY = 0xFF690005; TONAL = 0xFF5D3F3B; ON_TONAL = 0xFFFFDAD6;
        } else {
            BG = 0xFFFFF8F7; CARD = 0xFFFCEAE7; TEXT = 0xFF231918; MUTED = 0xFF534341; LINE = 0xFFD8C2BF;
            PRIMARY = 0xFFB3261E; ON_PRIMARY = 0xFFFFFFFF; TONAL = 0xFFFFDAD6; ON_TONAL = 0xFF410002;
        }
        if (Build.VERSION.SDK_INT >= 31) {   // Material You: кольори системи (з шпалер)
            PRIMARY = sys(night ? "system_accent1_200" : "system_accent1_600", PRIMARY);
            ON_PRIMARY = sys(night ? "system_accent1_800" : "system_accent1_0", ON_PRIMARY);
            TONAL = sys(night ? "system_accent2_700" : "system_accent2_100", TONAL);
            ON_TONAL = sys(night ? "system_accent2_100" : "system_accent2_900", ON_TONAL);
            BG = sys(night ? "system_neutral1_900" : "system_neutral1_10", BG);
            CARD = sys(night ? "system_neutral1_800" : "system_neutral1_50", CARD);
            TEXT = sys(night ? "system_neutral1_100" : "system_neutral1_900", TEXT);
            MUTED = sys(night ? "system_neutral2_200" : "system_neutral2_700", MUTED);
            LINE = sys(night ? "system_neutral2_700" : "system_neutral2_200", LINE);
        }
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        int flags = 0;
        if (!night && Build.VERSION.SDK_INT >= 23) flags |= 0x00002000;   // SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (!night && Build.VERSION.SDK_INT >= 27) flags |= 0x00000010;   // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30 && w.getInsetsController() != null) {   // Android 11+: темні значки на світлому тлі
            int light = night ? 0 : (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            w.getInsetsController().setSystemBarsAppearance(light, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    @SuppressWarnings("deprecation")
    private int sys(String name, int def) {
        try {
            int id = getResources().getIdentifier(name, "color", "android");
            return id == 0 ? def : getResources().getColor(id);
        } catch (Exception e) { return def; }
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams weight(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.leftMargin = left;
        return lp;
    }

    private View button(String s, boolean dark, View.OnClickListener l) {
        return button(s, 0, dark ? FILLED : TONAL_BTN, l);
    }

    /** Кнопка Material 3: FILLED (основна дія), TONAL_BTN (другорядна), OUTLINED (рідкісна). Форма — «пігулка». */
    private View button(String s, int icon, int kind, View.OnClickListener l) {
        int fg = kind == FILLED ? ON_PRIMARY : kind == TONAL_BTN ? ON_TONAL : PRIMARY;
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12), dp(10), dp(12), dp(10));
        btn.setMinimumHeight(dp(48));
        if (icon != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setImageTintList(ColorStateList.valueOf(fg));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(18), dp(18));
            ip.rightMargin = dp(8);
            btn.addView(iv, ip);
        }
        TextView t = text(s, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(false);
        btn.addView(t);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(kind == FILLED ? PRIMARY : kind == TONAL_BTN ? TONAL : Color.TRANSPARENT);
        if (kind == OUTLINED) shape.setStroke(dp(1), MUTED);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(24));
        mask.setColor(Color.WHITE);
        Drawable bg = new RippleDrawable(ColorStateList.valueOf((fg & 0x00FFFFFF) | 0x33000000), shape, mask);
        btn.setBackground(bg);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setContentDescription(s);
        btn.setOnClickListener(l);
        return btn;
    }

    private TextView chip(String s, boolean hot) {
        TextView t = text(s, 13, hot ? MonitorService.PURPLE : Color.WHITE, true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(hot ? Color.WHITE : 0x38FFFFFF);
        g.setCornerRadius(dp(8));
        t.setBackground(g);
        return t;
    }

    private void paintCard(int color) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, darken(color, 0.12f)});
        g.setCornerRadius(dp(28));
        statusCard.setBackground(g);
    }

    private static int lighten(int c, float k) {
        return Color.rgb((int) (Color.red(c) + (255 - Color.red(c)) * k), (int) (Color.green(c) + (255 - Color.green(c)) * k),
                (int) (Color.blue(c) + (255 - Color.blue(c)) * k));
    }

    private static int darken(int c, float k) {
        return Color.rgb((int) (Color.red(c) * (1 - k)), (int) (Color.green(c) * (1 - k)), (int) (Color.blue(c) * (1 - k)));
    }

    private static String note(SharedPreferences p) {
        return p.getString(Prefs.NOTE, "");
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "щойно";
        if (m < 60) return m + " хв";
        return (m / 60) + " год " + (m % 60) + " хв";
    }

    // ---------- логіка ----------

    // ---------- оновлення ----------

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra("map") && mapCard != null) {   // вигляд «Простий/Карта» ззовні (ярлик, автотест)
            prefs.edit().putBoolean(Prefs.MAP_VIEW, i.getBooleanExtra("map", false)).apply();
            applyMode();
        }
        // лише тестова збірка (сервер-імітація): імітація push для автотесту версії Google Play
        if (Flavor.hooks != null && i.hasExtra("push") && AlertSource.embeddedFixed(this)) Flavor.hooks.debugPush(this, i.getStringExtra("push"));
        if (i.getAction() == null) return;
        if (Updater.ACTION_UPDATE.equals(i.getAction()) && !Flavor.PLAY) askUpdate();
    }

    private void updateVersionInfo() {
        if (versionInfo == null) return;
        String t = "Встановлено: " + Updater.currentName(this);
        if (Updater.available(this)) t += " · доступна " + prefs.getString(Updater.U_NAME, "");
        versionInfo.setText(t);
    }

    private void checkUpdateNow() {
        versionInfo.setText("Перевіряю…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                boolean avail = false;
                try { avail = Updater.check(MainActivity.this); } catch (Exception e) { err = e.getMessage(); }
                final String fErr = err;
                final boolean fAvail = avail;
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateVersionInfo();
                        if (fErr != null) toastDialog("Не вдалося перевірити оновлення: " + fErr);
                        else if (fAvail) askUpdate();
                        else toastDialog("У вас найновіша версія (" + Updater.currentName(MainActivity.this) + ").");
                    }
                });
            }
        }).start();
    }

    private void toastDialog(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void askUpdate() {
        if (!Updater.available(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Нова версія " + prefs.getString(Updater.U_NAME, ""))
                .setMessage("Файл оновлення завантажиться в браузері. Коли завантажиться — відкрийте його і натисніть «Оновити» "
                        + "(уперше Android попросить дозволити браузеру встановлення). Налаштування збережуться.")
                .setPositiveButton("Завантажити", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try { Updater.openDownload(MainActivity.this); }
                        catch (Exception e) { toastDialog("Не вдалося відкрити браузер: " + e.getMessage()); }
                    }
                })
                .setNegativeButton("Пізніше", null)
                .show();
    }

    private void showSettings(boolean on) {
        settings.setVisibility(on ? View.VISIBLE : View.GONE);
        home.setVisibility(on ? View.GONE : View.VISIBLE);
        scroll.scrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (settings.getVisibility() == View.VISIBLE) showSettings(false);
        else super.onBackPressed();
    }

    private void fillRaions() {
        raionUids.clear();
        List<String> names = new ArrayList<String>();
        raionUids.add(0);
        names.add("Вся область");
        int obl = Locations.oblastUid(Prefs.region(this));
        int sel = 0, cur = Prefs.raion(this);
        for (int i = 0; i < Locations.RAION_UID.length; i++) {
            if (Locations.RAION_OBLAST[i] != obl) continue;
            raionUids.add(Locations.RAION_UID[i]);
            names.add(Locations.RAION_NAME[i]);
            if (Locations.RAION_UID[i] == cur) sel = names.size() - 1;
        }
        if (sel == 0 && cur != 0 && !Prefs.autoActive(this)) prefs.edit().putInt(Prefs.RAION, 0).apply();
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        raionSpinner.setAdapter(ad);
        raionSpinner.setSelection(sel);
    }

    private void pickSound(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try { startActivityForResult(i, code); }
        catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("audio/*")
                        .addCategory(Intent.CATEGORY_OPENABLE), code);
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        prefs.edit().putString(code == PICK_ALARM ? Prefs.SOUND_ALARM : Prefs.SOUND_CLEAR, uri.toString()).apply();
        Sounds.play(this, code == PICK_ALARM);
        refresh();
    }

    private void askBattery(boolean force) {
        if (Build.VERSION.SDK_INT < 23 || Flavor.PLAY) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (force) new AlertDialog.Builder(this)
                    .setMessage("Економію батареї для програми вже вимкнено — вона може працювати у фоні.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (!force && prefs.getBoolean(Prefs.ASKED_BATTERY, false)) return;
        prefs.edit().putBoolean(Prefs.ASKED_BATTERY, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Робота у фоні")
                .setMessage("Щоб телефон не «присипляв» стеження за тривогою, зніміть обмеження батареї: "
                        + "у налаштуваннях програми відкрийте «Батарея» і виберіть «Без обмежень» (або «Не оптимізувати»).")
                .setPositiveButton("Відкрити налаштування", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            try { startActivity(new Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")); }
                            catch (Exception ignored) { }
                        }
                    }
                })
                .setNegativeButton("Пізніше", null)
                .show();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            ticker.postDelayed(this, 30000);   // оновлюємо «триває N хв»
        }
    };

    private boolean hasLocation() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askLocation() {
        if (hasLocation() || android.os.Build.VERSION.SDK_INT < 23) return;
        requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_NOTIF) { askBattery(false); return; }
        if (req != REQ_LOCATION) return;
        updateAutoUi();
        if (mapMe != null) updateMe();
        if (prefs.getBoolean(Prefs.ENABLED, true)) { MonitorService.start(this); MonitorService.checkNow(this); }
    }

    private void updateAutoUi() {
        if (autoSwitch == null) return;
        boolean auto = prefs.getBoolean(Prefs.AUTO_PLACE, false);
        if (autoSwitch.isChecked() != auto) autoSwitch.setChecked(auto);
        boolean active = Prefs.autoActive(this);
        regionSpinner.setEnabled(!auto);
        raionSpinner.setEnabled(!auto);
        for (View sp : new View[]{regionSpinner, raionSpinner})
            if (sp.getParent() instanceof View) ((View) sp.getParent()).setAlpha(auto ? 0.6f : 1f);
        if (active) {
            int i = AlertSource.indexOf(Prefs.region(this));
            if (i >= 0 && regionSpinner.getSelectedItemPosition() != i) { regionSpinner.setSelection(i); fillRaions(); }
            int ri = raionUids.indexOf(Prefs.raion(this));
            if (ri >= 0 && raionSpinner.getSelectedItemPosition() != ri) raionSpinner.setSelection(ri);
        }
        String info;
        if (!auto) info = "Район обирається вручну. Автовизначення: мережа (Wi-Fi/вишки) і GPS; неправдоподібні точки"
                + " (РЕБ, підміна, «стрибки» в іншу країну) відкидаються, район лишається останній надійний.";
        else if (!hasLocation()) info = "Немає дозволу на геопозицію. Дозвольте його («Під час використання застосунку»).";
        else info = prefs.getString(Prefs.AUTO_INFO, "Визначаю місце… Поки що — обране вручну.");
        autoInfo.setText(info);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        updateVersionInfo();
        prefs.registerOnSharedPreferenceChangeListener(this);
        ticker.post(tick);
        ticker.post(mapTick);
    }

    @Override
    protected void onPause() {
        visible = false;
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        ticker.removeCallbacks(tick);
        ticker.removeCallbacks(mapTick);
        super.onPause();
    }

    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        runOnUiThread(new Runnable() { public void run() { refresh(); } });
    }

    // ---------- карта цілей ----------

    /** Перемикач «Простий / Карта» (Material segmented button). */
    private View segmented(final String[] labels, int sel) {
        LinearLayout box = row();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), MUTED);
        box.setBackground(g);
        box.setPadding(dp(1), dp(1), dp(1), dp(1));
        segs.clear();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = text(labels[i], 14, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), 0, dp(12), 0);
            t.setMinHeight(dp(38));
            // ширина з запасом під «✓», щоб перемикач не змінював розмір
            t.setMinWidth((int) (t.getPaint().measureText("✓ " + labels[i]) + dp(24) + 1));
            t.setContentDescription(labels[i]);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.edit().putBoolean(Prefs.MAP_VIEW, idx == 1).apply();
                    applyMode();
                }
            });
            segs.add(t);
            box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        }
        return box;
    }

    private void applyMode() {
        boolean on = prefs.getBoolean(Prefs.MAP_VIEW, false);
        for (int i = 0; i < segs.size(); i++) {
            boolean s = (i == 1) == on;
            TextView t = segs.get(i);
            GradientDrawable g = new GradientDrawable();
            float r = dp(19);
            g.setCornerRadii(i == 0 ? new float[]{r, r, 0, 0, 0, 0, r, r} : new float[]{0, 0, r, r, r, r, 0, 0});
            g.setColor(s ? TONAL : Color.TRANSPARENT);
            t.setBackground(g);
            t.setTextColor(s ? ON_TONAL : TEXT);
            t.setText((s ? "✓ " : "") + (i == 0 ? "Простий" : "Карта"));
        }
        mapCard.setVisibility(on ? View.VISIBLE : View.GONE);
        statusCard.setMinimumHeight(on ? 0 : dp(200));
        chips.setVisibility(chips.getChildCount() > 0 && !on ? View.VISIBLE : View.GONE);
        title.setTextSize(on ? 24 : 30);
        if (on) { loadGeo(); ticker.removeCallbacks(mapTick); ticker.post(mapTick); }
    }

    private void buildMapCard() {
        mapCard = card(null);
        mapCard.setPadding(dp(8), dp(8), dp(8), dp(12));
        map = new ThreatMap(this);
        map.setColors(night ? 0xFF3A2E2D : 0xFFFFFFFF, night ? 0xFF8C7A78 : 0xFFA08C8A, TEXT, PRIMARY, CARD);
        // подробиці про ціль — поверх карти внизу, не зсуваючи решту екрана
        FrameLayout frame = new FrameLayout(this);
        frame.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mapInfo = text("", 13, TEXT, false);
        mapInfo.setMaxLines(3);
        mapInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mapInfo.setPadding(dp(12), dp(8), dp(12), dp(8));
        tnum(mapInfo);
        GradientDrawable ib = new GradientDrawable();
        ib.setCornerRadius(dp(12));
        ib.setColor((CARD & 0x00FFFFFF) | 0xF2000000);
        ib.setStroke(dp(1), LINE);
        mapInfo.setBackground(ib);
        mapInfo.setVisibility(View.GONE);
        mapInfo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { mapInfo.setVisibility(View.GONE); }
        });
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ip.setMargins(dp(6), 0, dp(6), dp(6));
        frame.addView(mapInfo, ip);
        mapCard.addView(frame);
        map.setOnSelect(new ThreatMap.OnSelect() {
            public void selected(ThreatMap.Threat t) { showThreat(t); }
        });
        map.setOnView(new ThreatMap.OnView() {
            public void changed() { fillLegend(); }
        });
        // своє місце — один рядок завжди (щоб нічого не зсувалося)
        mapMe = text("", 13, TEXT, true);
        mapMe.setSingleLine(true);
        mapMe.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mapMe.setPadding(dp(8), dp(10), dp(8), 0);
        mapMe.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (!hasLocation()) askLocation(); }
        });
        mapCard.addView(mapMe);
        // легенда — одна прокручувана смуга фіксованої висоти
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        legend = row();
        legend.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(legend);
        hs.setPadding(dp(8), dp(8), dp(8), 0);
        mapCard.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        addLegend(legendChip(MUTED, "Завантажую цілі…"));
        mapSummary = text("", 12, MUTED, false);
        mapSummary.setSingleLine(true);
        mapSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mapSummary.setPadding(dp(8), dp(8), dp(8), 0);
        tnum(mapSummary);
        mapCard.addView(mapSummary);
        TextView warn = text("Двома пальцями — масштаб; торкніться значка — маршрут і подробиці; у наближенні внизу лише цілі цієї ділянки й ті, що летять у неї. Положення цілей приблизні й можуть "
                + "запізнюватися; частина цілей може не з'явитися. Порожня карта не означає, що небезпеки немає — "
                + "орієнтуйтеся на тривогу. Цілі: NEPTUN · міста: GeoNames.", 12, MUTED, false);
        warn.setPadding(dp(8), dp(6), dp(8), 0);
        mapCard.addView(warn);
        updateMe();
        updateMapSummary();
    }

    private void tnum(TextView t) {
        if (Build.VERSION.SDK_INT >= 21) t.setFontFeatureSettings("tnum");   // цифри однакової ширини — час не «тремтить»
    }

    /** Де користувач: остання надійна точка автовизначення, інакше остання відома геопозиція (якщо є дозвіл). */
    @SuppressWarnings("MissingPermission")
    private double[] myLocation() {
        long now = System.currentTimeMillis();
        String fix = prefs.getString(Prefs.AUTO_FIX, null);
        if (fix != null) {
            try {
                String[] f = fix.split(",");
                if (now - Long.parseLong(f[3]) < 6 * 3600000L) return new double[]{Double.parseDouble(f[0]), Double.parseDouble(f[1])};
            } catch (Exception ignored) { }
        }
        if (!hasLocation()) return null;
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            android.location.Location best = null;
            for (String pr : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(pr);
                if (l == null || (Build.VERSION.SDK_INT >= 18 && l.isFromMockProvider())) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            if (best == null || now - best.getTime() > 6 * 3600000L) return null;
            if (geoCache != null && geoCache.find(best.getLatitude(), best.getLongitude()) == null) return null;   // поза Україною — підміна/РЕБ
            return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Exception e) { return null; }
    }

    private void updateMe() {
        double[] me = myLocation();
        if (me == null) map.setMe(Double.NaN, Double.NaN); else map.setMe(me[0], me[1]);
        String city = map.meCityName();
        if (me == null) mapMe.setText(hasLocation() ? "📍 Місце поки невідоме — " + Prefs.place(this) : "📍 Показати себе на карті — дозволити геопозицію");
        else mapMe.setText("📍 Ви тут: " + (city != null ? city : Prefs.place(this)));
        mapMe.setTextColor(me == null && !hasLocation() ? PRIMARY : TEXT);
    }

    private TextView legendChip(int color, String s) {
        TextView t = text("●  " + s, 12, TEXT, false);
        android.text.SpannableString sp = new android.text.SpannableString(t.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(sp);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), LINE);
        t.setBackground(g);
        return t;
    }

    private void showThreat(ThreatMap.Threat t) {
        if (t == null) { mapInfo.setVisibility(View.GONE); return; }
        mapInfo.setVisibility(View.VISIBLE);
        StringBuilder b = new StringBuilder();
        int sb = ThreatMap.sub(t);
        b.append(sb == ThreatMap.S_UAV || sb == ThreatMap.S_MISSILE || sb == ThreatMap.S_OTHER ? (t.title.length() > 0 ? t.title : ThreatMap.SUB_ONE[sb]) : ThreatMap.SUB_ONE[sb]);
        if (t.count > 1) b.append(" ×").append(t.count);
        b.append(" — ").append(t.place.length() > 0 ? t.place : "").append(t.region.length() > 0 ? (t.place.length() > 0 ? ", " : "") + t.region : "");
        if (t.heading >= 0) b.append("\nКурс: ").append(compass(t.heading)).append(" (").append(t.heading).append("°)");
        if (t.uncKm > 0) b.append(t.heading >= 0 ? " · " : "\n").append("точність ±").append(Math.round(t.uncKm)).append(" км");
        String ago = isoAgo(t.at);
        if (ago != null) b.append(" · ").append(ago);
        if ("approx".equals(t.quality)) b.append(" · наближено");
        mapInfo.setText(b.toString());
    }

    private static String compass(int h) {
        String[] d = {"на північ", "на північний схід", "на схід", "на південний схід", "на південь", "на південний захід", "на захід", "на північний захід"};
        return d[((h + 22) % 360) / 45];
    }

    private static String isoAgo(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long t = f.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).getTime();
            return "оновлено " + ago(t) + (ago(t).equals("щойно") ? "" : " тому");
        } catch (Exception e) { return null; }
    }

    private String raw(int id) throws Exception {
        java.io.InputStream in = getResources().openRawResource(id);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private void loadGeo() {
        if (map.hasGeo()) return;
        if (geoCache != null && citiesCache != null) { map.setGeo(geoCache); map.setCities(citiesCache); updateMe(); return; }
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.InputStream in = getResources().openRawResource(R.raw.geo);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    final GeoLookup g = GeoLookup.parse(new String(bo.toByteArray(), "UTF-8"));
                    geoCache = g;
                    final ThreatMap.Cities c = ThreatMap.Cities.parse(raw(R.raw.cities));
                    citiesCache = c;
                    runOnUiThread(new Runnable() { public void run() { map.setGeo(g); map.setCities(c); updateMe(); } });
                } catch (Exception ignored) { }
            }
        }).start();
    }

    private final Runnable mapTick = new Runnable() {
        public void run() {
            if (!prefs.getBoolean(Prefs.MAP_VIEW, false)) return;
            loadMapData();
            ticker.postDelayed(this, 15000);
        }
    };

    private void loadMapData() {
        if (mapLoading) return;
        mapLoading = true;
        final String srv = AlertSource.server(this);
        new Thread(new Runnable() {
            public void run() {
                List<ThreatMap.Threat> th = null;
                org.json.JSONObject st = null;
                String err = null;
                try { th = ThreatMap.parseThreats(new org.json.JSONObject(AlertSource.download(srv + "/threats"))); }
                catch (Exception e) { err = e.getMessage(); }
                try { st = new org.json.JSONObject(AlertSource.download(srv + "/status")); } catch (Exception ignored) { }
                final List<ThreatMap.Threat> fth = th;
                final org.json.JSONObject fst = st;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        mapLoading = false;
                        updateMe();
                        if (fst != null) map.setAlerts(fst);
                        if (fth != null) { mapAt = System.currentTimeMillis(); mapErr = null; map.setThreats(fth); fillLegend(); }
                        else mapErr = ferr;
                        updateMapSummary();
                    }
                });
            }
        }).start();
    }

    private String legendSig = "";

    /** Підсумок під картою: на всій карті — усі цілі; у наближенні — лише цієї ділянки й ті, що летять у неї. */
    private void fillLegend() {
        if (!map.hasGeo() && mapAt == 0) return;
        boolean zoomed = map.zoomed();
        List<ThreatMap.Threat> th = map.relevant();
        int[] n = new int[ThreatMap.SUBS];
        int incoming = 0;
        for (ThreatMap.Threat t : th) { n[ThreatMap.sub(t)] += t.count; if (t.incoming) incoming += t.count; }
        StringBuilder sig = new StringBuilder(zoomed ? "z" : "a");
        for (int x : n) sig.append(',').append(x);
        sig.append(',').append(incoming).append(mapAt == 0 ? "l" : "");
        if (sig.toString().equals(legendSig)) return;   // без змін — не перебудовуємо (жест іде плавно)
        legendSig = sig.toString();
        legend.removeAllViews();
        if (mapAt == 0) { addLegend(legendChip(MUTED, "Завантажую цілі…")); return; }
        if (zoomed) addLegend(scopeChip());
        for (int k : ThreatMap.SUB_ORDER)
            if (n[k] > 0) addLegend(legendChip(ThreatMap.SUB_COLOR[k], ThreatMap.SUB_TITLE[k] + ": " + n[k]));
        if (incoming > 0) addLegend(legendChip(PRIMARY, "летять сюди: " + incoming));
        if (th.isEmpty()) addLegend(legendChip(GREEN, zoomed ? "Поруч цілей немає" : "Цілей на карті немає"));
    }

    /** Позначка «лише ця ділянка» — для певності, що підсумок не по всій країні. */
    private TextView scopeChip() {
        TextView t = text("Ця ділянка", 12, ON_TONAL, true);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(TONAL);
        t.setBackground(g);
        return t;
    }

    private void addLegend(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (legend.getChildCount() > 0) lp.leftMargin = dp(6);
        legend.addView(v, lp);
    }

    private void updateMapSummary() {
        String s;
        if (mapAt == 0) s = mapErr != null ? "Немає зв'язку з сервером: " + mapErr : "Завантажую цілі…";
        else {
            s = "Оновлено о " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(mapAt)) + " · кожні 15 с";
            if (mapErr != null) s += " · остання спроба: " + mapErr;
        }
        mapSummary.setText(s);
    }

    private void refresh() {
        boolean on = prefs.getBoolean(Prefs.ENABLED, true);
        int state = prefs.getInt(Prefs.STATE, AlertSource.UNKNOWN);
        List<String> threats = MonitorService.parseList(prefs.getString(Prefs.THREATS, ""));
        String err = prefs.getString(Prefs.ERROR, null);
        long since = prefs.getLong(Prefs.SINCE, 0), cleared = prefs.getLong(Prefs.CLEARED_AT, 0);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

        place.setText(Prefs.place(this));
        if (map != null) map.setPlace(Prefs.region(this), Prefs.raion(this));
        chips.removeAllViews();
        if (!on) {
            title.setText("Стеження вимкнено");
            detail.setText("Увімкніть, щоб отримувати сигнали");
            paintCard(GRAY);
        } else if (state == AlertSource.ALERT) {
            boolean ballistic = threats.contains("ballistic_missiles");
            String lv = prefs.getString(Prefs.LEVEL, "");
            boolean yellow = lv.equals("yellow") && !ballistic;
            title.setText(ballistic ? "БАЛІСТИКА!" : "ПОВІТРЯНА ТРИВОГА");
            String lvText = ballistic || lv.equals("red") ? "Червоний рівень · " : yellow ? "Жовтий рівень · " : "";
            detail.setText(lvText + (since > 0 ? "триває " + ago(since) + " · з " + hm.format(new Date(since)) : "пройдіть в укриття")
                    + (prefs.getBoolean(Prefs.DISAGREE, false) ? " · підтверджує 1 з 2 джерел" : ""));
            for (int i = 0; i < threats.size(); i++)
                chips.addView(chip(AlertSource.threatTitle(threats.get(i)), ballistic && i == 0));
            paintCard(ballistic ? MonitorService.PURPLE : yellow ? MonitorService.YELLOW : RED);
        } else if (state == AlertSource.CLEAR) {
            title.setText("Тривоги немає");
            detail.setText(cleared > 0 ? "Відбій о " + hm.format(new Date(cleared)) : "Спокійно");
            paintCard(GREEN);
        } else if (err == null && note(prefs).length() > 0 && Prefs.raion(this) != 0) {
            title.setText("Немає даних по району");
            detail.setText(note(prefs));
            paintCard(GRAY);
        } else {
            title.setText(err != null ? "Немає зв'язку" : "Перевірка…");
            detail.setText(err != null ? err : "");
            paintCard(GRAY);
        }
        chips.setVisibility(chips.getChildCount() > 0 && !prefs.getBoolean(Prefs.MAP_VIEW, false) ? View.VISIBLE : View.GONE);

        StringBuilder f = new StringBuilder();
        String t = prefs.getString(Prefs.STATUS_TIME, null);
        if (on && t != null) f.append("Оновлено о ").append(t).append(" · ").append(prefs.getString(Prefs.SOURCE, ""));
        String note = prefs.getString(Prefs.NOTE, "");
        if (on && note.length() > 0 && state != AlertSource.UNKNOWN) f.append(" · ").append(note);
        if (on && err != null && state != AlertSource.UNKNOWN) f.append(" · немає зв'язку: ").append(err);
        if (f.length() > 0) f.append("\n\n");
        f.append("Це додатковий сигнал. Тримайте увімкненим і офіційний застосунок «Повітряна тривога».");
        footer.setText(f.toString());

        soundInfo.setText("Сирена: " + (prefs.contains(Prefs.SOUND_ALARM) ? "свій файл" : "вбудована")
                + " · Відбій: " + (prefs.contains(Prefs.SOUND_CLEAR) ? "свій файл" : "вбудований"));
        if (enabledSwitch.isChecked() != on) enabledSwitch.setChecked(on);
        updateAutoUi();
    }
}
