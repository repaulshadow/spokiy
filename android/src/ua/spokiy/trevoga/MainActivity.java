package ua.spokiy.trevoga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
    static final int BG = 0xFFF3F4F6, TEXT = 0xFF111827, MUTED = 0xFF6B7280, LINE = 0xFFE5E7EB;
    static final int RED = 0xFFDC2626, DARK_RED = 0xFF991B1B, GREEN = 0xFF16A34A, GRAY = 0xFF6B7280;

    private SharedPreferences prefs;
    private LinearLayout home, settings;
    private ScrollView scroll;
    private LinearLayout statusCard;
    private TextView title, place, detail, footer, soundInfo;
    private FlowLayout chips;
    private Spinner raionSpinner;
    private Switch enabledSwitch;
    private final List<Integer> raionUids = new ArrayList<Integer>();
    private final Handler ticker = new Handler();
    private static final int PICK_ALARM = 11, PICK_CLEAR = 12;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        Prefs.createChannels(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
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
        TextView back = text("‹  Назад", 16, TEXT, true);
        back.setPadding(dp(4), dp(10), dp(16), dp(10));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(false); }
        });
        head.addView(back);
        TextView st = text("Налаштування", 20, TEXT, true);
        head.addView(st);
        settings.addView(head);

        // ---------- картка статусу ----------
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(22), dp(22), dp(22), dp(22));
        statusCard.setMinimumHeight(dp(190));
        statusCard.setElevation(dp(3));
        title = text("", 28, Color.WHITE, true);
        place = text("", 16, 0xE6FFFFFF, false);
        place.setPadding(0, dp(6), 0, 0);
        detail = text("", 14, 0xD9FFFFFF, false);
        detail.setPadding(0, dp(2), 0, 0);
        chips = new FlowLayout(this, dp(8));
        chips.setPadding(0, dp(16), 0, 0);
        statusCard.addView(title);
        statusCard.addView(place);
        statusCard.addView(detail);
        statusCard.addView(chips);
        home.addView(statusCard, matchWrap(0));

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
                    stopService(new Intent(MainActivity.this, MonitorService.class));
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
        where.addView(spinner);
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
        where.addView(raionSpinner);
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
        notif.addView(voice);
        final View[] soundViews = {voiceLabel, voice};
        quiet.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) {
                prefs.edit().putBoolean(Prefs.NOTIFY_ONLY, v).apply();
                if (v) Sounds.stop();
                for (View x : soundViews) { x.setEnabled(!v); x.setAlpha(v ? 0.4f : 1f); }
            }
        });
        for (View x : soundViews) { x.setEnabled(!quiet.isChecked()); x.setAlpha(quiet.isChecked() ? 0.4f : 1f); }

        notif.addView(fieldLabel("Свої звуки"));
        LinearLayout row2 = row();
        row2.addView(button("Сирена…", false, new View.OnClickListener() {
            public void onClick(View v) { pickSound(PICK_ALARM); }
        }), weight(0));
        row2.addView(button("Відбій…", false, new View.OnClickListener() {
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

        View batt = button("Дозволити роботу у фоні", false, new View.OnClickListener() {
            public void onClick(View v) { askBattery(true); }
        });
        LinearLayout.LayoutParams bl = matchWrap(dp(12));
        notif.addView(batt, bl);
        if (Build.VERSION.SDK_INT >= 26) {
            // Android вимагає постійне сповіщення для роботи у фоні; його можна сховати в налаштуваннях каналу
            View hide = button("Сховати постійне сповіщення", false, new View.OnClickListener() {
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
        TextView testsLabel = fieldLabel("ПЕРЕВІРКА ЗВУКУ І СПОВІЩЕНЬ");
        testsLabel.setPadding(dp(4), dp(18), 0, dp(6));
        settings.addView(testsLabel);
        settings.addView(row, matchWrap(0));

        // ---------- головний екран: кнопки ----------
        LinearLayout homeRow = row();
        homeRow.addView(button("⚙  Налаштування і тести", false, new View.OnClickListener() {
            public void onClick(View v) { showSettings(true); }
        }), new LinearLayout.LayoutParams(0, dp(52), 2f));
        View hush = button("Тиша", true, new View.OnClickListener() {
            public void onClick(View v) { Sounds.stop(); }
        });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(52), 1f);
        hl.leftMargin = dp(8);
        homeRow.addView(hush, hl);
        home.addView(homeRow, matchWrap(dp(16)));

        footer = text("", 12, MUTED, false);
        footer.setPadding(dp(4), dp(14), dp(4), 0);
        home.addView(footer);

        // Обов'язкове посилання на NEPTUN (умова використання їхніх даних)
        TextView credit = text("", 12, MUTED, false);
        credit.setPadding(dp(4), dp(10), dp(4), 0);
        credit.setText(android.text.Html.fromHtml("Дані: alerts.in.ua · <a href=\"https://neptun.in.ua/\">Карта повітряних тривог — NEPTUN</a>"));
        credit.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        credit.setLinkTextColor(TEXT);
        home.addView(credit);

        setContentView(scroll);

        if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.start(this);
        askBattery(false);
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

    private LinearLayout card(String caption) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), LINE);
        c.setBackground(bg);
        if (caption != null) {
            TextView t = text(caption.toUpperCase(Locale.getDefault()), 12, MUTED, true);
            t.setLetterSpacing(0.08f);
            c.addView(t);
        }
        return c;
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
        TextView btn = text(s, 14, dark ? Color.WHITE : TEXT, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(8), dp(12), dp(8), dp(12));
        btn.setMinHeight(dp(48));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(dark ? TEXT : Color.WHITE);
        shape.setCornerRadius(dp(12));
        if (!dark) shape.setStroke(dp(1), LINE);
        Drawable bg = new RippleDrawable(ColorStateList.valueOf(dark ? 0x40FFFFFF : 0x22000000), shape, null);
        btn.setBackground(bg);
        btn.setClickable(true);
        btn.setOnClickListener(l);
        return btn;
    }

    private TextView chip(String s, boolean hot) {
        TextView t = text(s, 13, hot ? DARK_RED : Color.WHITE, true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(hot ? Color.WHITE : 0x38FFFFFF);
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        return t;
    }

    private void paintCard(int color) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(color, 0.08f), darken(color, 0.18f)});
        g.setCornerRadius(dp(24));
        statusCard.setBackground(g);
        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.setStatusBarColor(darken(color, 0.25f));
        }
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
        if (sel == 0 && cur != 0) prefs.edit().putInt(Prefs.RAION, 0).apply();
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
        if (Build.VERSION.SDK_INT < 23) return;
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
                .setMessage("Щоб телефон не «присипляв» стеження за тривогою, дозвольте програмі працювати без обмежень батареї.")
                .setPositiveButton("Дозволити", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
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

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        runOnUiThread(new Runnable() { public void run() { refresh(); } });
    }

    private void refresh() {
        boolean on = prefs.getBoolean(Prefs.ENABLED, true);
        int state = prefs.getInt(Prefs.STATE, AlertSource.UNKNOWN);
        List<String> threats = MonitorService.parseList(prefs.getString(Prefs.THREATS, ""));
        String err = prefs.getString(Prefs.ERROR, null);
        long since = prefs.getLong(Prefs.SINCE, 0), cleared = prefs.getLong(Prefs.CLEARED_AT, 0);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

        place.setText(Prefs.place(this));
        chips.removeAllViews();
        if (!on) {
            title.setText("Стеження вимкнено");
            detail.setText("Увімкніть, щоб отримувати сигнали");
            paintCard(GRAY);
        } else if (state == AlertSource.ALERT) {
            boolean ballistic = threats.contains("ballistic_missiles");
            title.setText(ballistic ? "БАЛІСТИКА!" : "ПОВІТРЯНА ТРИВОГА");
            detail.setText((since > 0 ? "Триває " + ago(since) + " · з " + hm.format(new Date(since)) : "Пройдіть в укриття")
                    + (prefs.getBoolean(Prefs.DISAGREE, false) ? " · підтверджує 1 з 2 джерел" : ""));
            for (int i = 0; i < threats.size(); i++)
                chips.addView(chip(AlertSource.threatTitle(threats.get(i)), ballistic && i == 0));
            paintCard(ballistic ? DARK_RED : RED);
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
        chips.setVisibility(chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);

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
    }
}
