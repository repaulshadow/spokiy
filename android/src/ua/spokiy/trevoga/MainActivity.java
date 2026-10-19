package ua.spokiy.trevoga;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView status, info, threatsView;
    private Button toggle;
    private SharedPreferences prefs;
    private Spinner raionSpinner;
    private TextView soundInfo;
    private final java.util.ArrayList<Integer> raionUids = new java.util.ArrayList<Integer>();
    private static final int PICK_ALARM = 11, PICK_CLEAR = 12;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        Prefs.createChannels(this);

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Повітряна тривога");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(label("Область:"));
        final Spinner spinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, AlertSource.REGIONS);
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
        root.addView(spinner);

        root.addView(label("Район:"));
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
        root.addView(raionSpinner);
        fillRaions();

        status = new TextView(this);
        status.setTextSize(26);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(pad, dp(28), pad, dp(28));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(16);
        root.addView(status, sp);

        threatsView = new TextView(this);
        threatsView.setTextSize(18);
        threatsView.setTypeface(Typeface.DEFAULT_BOLD);
        threatsView.setTextColor(Color.rgb(170, 20, 20));
        threatsView.setGravity(Gravity.CENTER);
        threatsView.setPadding(0, dp(8), 0, 0);
        root.addView(threatsView);

        info = new TextView(this);
        info.setTextColor(Color.GRAY);
        info.setPadding(0, dp(6), 0, dp(10));
        root.addView(info);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = !prefs.getBoolean(Prefs.ENABLED, true);
                prefs.edit().putBoolean(Prefs.ENABLED, on).apply();
                if (on) MonitorService.start(MainActivity.this);
                else {
                    stopService(new Intent(MainActivity.this, MonitorService.class));
                    prefs.edit().putInt(Prefs.STATE, AlertSource.UNKNOWN).apply();
                }
                refresh();
            }
        });
        root.addView(toggle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Тест тревоги", new View.OnClickListener() {
            public void onClick(View v) {
                List<String> t = MonitorService.parseList(prefs.getString(Prefs.THREATS, ""));
                if (t.isEmpty()) t.add("drones");
                Sounds.alarm(MainActivity.this, t);
            }
        }), weight());
        row.addView(button("Тест отбоя", new View.OnClickListener() {
            public void onClick(View v) { Sounds.clear(MainActivity.this); }
        }), weight());
        row.addView(button("Стоп звук", new View.OnClickListener() {
            public void onClick(View v) { Sounds.stop(); }
        }), weight());
        root.addView(row);

        root.addView(label("Голос перед сиреною:"));
        final Spinner voice = new Spinner(this);
        final String[] voiceIds = {"tetiana", "mykyta", "off"};
        ArrayAdapter<String> va = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"Жіночий (Тетяна)", "Чоловічий (Микита)", "Без голосу, тільки сирена"});
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
        root.addView(voice);

        root.addView(label("Звуки:"));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("Свой звук тревоги…", new View.OnClickListener() {
            public void onClick(View v) { pickSound(PICK_ALARM); }
        }), weight());
        row2.addView(button("Свой звук отбоя…", new View.OnClickListener() {
            public void onClick(View v) { pickSound(PICK_CLEAR); }
        }), weight());
        root.addView(row2);
        soundInfo = new TextView(this);
        soundInfo.setTextColor(Color.GRAY);
        root.addView(soundInfo);
        root.addView(button("Вернуть встроенные звуки", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().remove(Prefs.SOUND_ALARM).remove(Prefs.SOUND_CLEAR).apply();
                refresh();
            }
        }));

        root.addView(button("Работа в фоне (экономия батареи)", new View.OnClickListener() {
            public void onClick(View v) { askBattery(true); }
        }));

        root.addView(label("Токен alerts.in.ua (необязательно, надёжнее):"));
        final EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("оставьте пустым — ubilling.net.ua");
        token.setText(prefs.getString(Prefs.TOKEN, ""));
        root.addView(token);

        final CheckBox partial = new CheckBox(this);
        partial.setText("Учитывать частичную тревогу (в районах), только с токеном");
        partial.setChecked(prefs.getBoolean(Prefs.PARTIAL, false));
        partial.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean v) {
                prefs.edit().putBoolean(Prefs.PARTIAL, v).apply();
            }
        });
        root.addView(partial);

        root.addView(button("Сохранить токен", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putString(Prefs.TOKEN, token.getText().toString().trim()).apply();
                if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.checkNow(MainActivity.this);
            }
        }));

        TextView note = label("Это дополнительный сигнал. Держите включённым и официальное приложение «Повітряна тривога».");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        setContentView(scroll);

        if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.start(this);
        askBattery(false);
    }

    private void fillRaions() {
        raionUids.clear();
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
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
            if (force) new AlertDialog.Builder(this).setMessage("Экономия батареи для приложения уже отключена — оно может работать в фоне.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (!force && prefs.getBoolean(Prefs.ASKED_BATTERY, false)) return;
        prefs.edit().putBoolean(Prefs.ASKED_BATTERY, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Работа в фоне")
                .setMessage("Чтобы телефон не «усыплял» слежение за тревогой, разрешите приложению работать без ограничений батареи.")
                .setPositiveButton("Разрешить", new DialogInterface.OnClickListener() {
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
                .setNegativeButton("Позже", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        runOnUiThread(new Runnable() { public void run() { refresh(); } });
    }

    private void refresh() {
        boolean on = prefs.getBoolean(Prefs.ENABLED, true);
        int state = prefs.getInt(Prefs.STATE, AlertSource.UNKNOWN);
        if (!on) {
            status.setText("Слежение выключено");
            status.setBackgroundColor(Color.rgb(120, 120, 120));
        } else if (state == AlertSource.ALERT) {
            status.setText("ПОВІТРЯНА ТРИВОГА");
            status.setBackgroundColor(Color.rgb(200, 30, 30));
        } else if (state == AlertSource.CLEAR) {
            status.setText("Тривоги немає");
            status.setBackgroundColor(Color.rgb(30, 140, 60));
        } else {
            status.setText("Проверка…");
            status.setBackgroundColor(Color.rgb(120, 120, 120));
        }
        String th = AlertSource.titles(MonitorService.parseList(prefs.getString(Prefs.THREATS, "")));
        threatsView.setText(on && state == AlertSource.ALERT ? th : "");
        threatsView.setVisibility(threatsView.getText().length() > 0 ? View.VISIBLE : View.GONE);
        String err = prefs.getString(Prefs.ERROR, null);
        String t = prefs.getString(Prefs.STATUS_TIME, null);
        if (!on) info.setText("");
        else if (err != null) info.setText(err);
        else if (t != null) info.setText(Prefs.place(this) + " · проверено в " + t + " · " + prefs.getString(Prefs.SOURCE, ""));
        else info.setText("");
        if (soundInfo != null) soundInfo.setText("Тревога: " + (prefs.contains(Prefs.SOUND_ALARM) ? "свой файл" : "встроенная сирена")
                + " · Отбой: " + (prefs.contains(Prefs.SOUND_CLEAR) ? "свой файл" : "встроенный сигнал"));
        toggle.setText(on ? "Выключить слежение" : "Включить слежение");
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
