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

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView status, info;
    private Button toggle;
    private SharedPreferences prefs;

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
                    prefs.edit().putString(Prefs.REGION, r).putInt(Prefs.STATE, AlertSource.UNKNOWN).apply();
                    if (prefs.getBoolean(Prefs.ENABLED, true)) MonitorService.checkNow(MainActivity.this);
                }
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(spinner);

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
            public void onClick(View v) { Sounds.play(MainActivity.this, true); }
        }), weight());
        row.addView(button("Тест отбоя", new View.OnClickListener() {
            public void onClick(View v) { Sounds.play(MainActivity.this, false); }
        }), weight());
        row.addView(button("Стоп звук", new View.OnClickListener() {
            public void onClick(View v) { Sounds.stop(); }
        }), weight());
        root.addView(row);

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
        String err = prefs.getString(Prefs.ERROR, null);
        String t = prefs.getString(Prefs.STATUS_TIME, null);
        if (!on) info.setText("");
        else if (err != null) info.setText(err);
        else if (t != null) info.setText(Prefs.region(this) + " · проверено в " + t + " · " + prefs.getString(Prefs.SOURCE, ""));
        else info.setText("");
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
