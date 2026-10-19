// Тревога — монитор воздушной тревоги для Windows 7/8/10/11
// Собирается встроенным компилятором .NET Framework 4.x (см. build.bat).
// Только C# 5 — чтобы собирался старым csc.exe из Windows 7.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Media;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Reflection.AssemblyTitle("Trevoga")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

namespace Trevoga
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            // Windows 7: включаем TLS 1.2 / 1.1 явно (3072 = Tls12, 768 = Tls11).
            try
            {
                ServicePointManager.SecurityProtocol =
                    (SecurityProtocolType)3072 | (SecurityProtocolType)768 | SecurityProtocolType.Tls;
            }
            catch
            {
                try { ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls; } catch { }
            }

            bool createdNew;
            using (Mutex m = new Mutex(true, "Trevoga_SingleInstance_Mutex", out createdNew))
            {
                if (!createdNew)
                {
                    MessageBox.Show("Программа уже запущена (значок возле часов).", "Тревога",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                bool startHidden = false;
                foreach (string a in Environment.GetCommandLineArgs())
                    if (a.Equals("/min", StringComparison.OrdinalIgnoreCase)) startHidden = true;
                Application.Run(new MainForm(startHidden));
            }
        }
    }

    // ---------------- Настройки (trevoga.ini рядом с exe) ----------------
    class Settings
    {
        public string Region = "м. Київ";
        public string Token = "";          // токен alerts.in.ua (необязательно)
        public int Interval = 15;          // секунд между проверками
        public bool Partial = false;       // считать частичную тревогу (только alerts.in.ua)
        public bool Sound = true;

        static string FilePath
        {
            get { return Path.Combine(Application.StartupPath, "trevoga.ini"); }
        }

        public static Settings Load()
        {
            Settings s = new Settings();
            try
            {
                if (!File.Exists(FilePath)) return s;
                foreach (string raw in File.ReadAllLines(FilePath, Encoding.UTF8))
                {
                    string line = raw.Trim();
                    if (line.Length == 0 || line.StartsWith(";") || line.StartsWith("#")) continue;
                    int eq = line.IndexOf('=');
                    if (eq < 0) continue;
                    string k = line.Substring(0, eq).Trim().ToLowerInvariant();
                    string v = line.Substring(eq + 1).Trim();
                    int n;
                    if (k == "region" && v.Length > 0) s.Region = v;
                    else if (k == "token") s.Token = v;
                    else if (k == "interval" && int.TryParse(v, out n)) s.Interval = n;
                    else if (k == "partial") s.Partial = (v == "1" || v.ToLower() == "true");
                    else if (k == "sound") s.Sound = !(v == "0" || v.ToLower() == "false");
                }
            }
            catch { }
            if (s.Interval < 10) s.Interval = 10;   // лимит API: не чаще ~8 запросов в минуту
            return s;
        }

        public void Save()
        {
            try
            {
                StringBuilder sb = new StringBuilder();
                sb.AppendLine("; Настройки программы Тревога");
                sb.AppendLine("; region   - область (как в списке программы)");
                sb.AppendLine("; token    - токен alerts.in.ua (можно оставить пустым)");
                sb.AppendLine("; interval - как часто проверять, секунд (минимум 10)");
                sb.AppendLine("; partial  - 1 = считать тревогой и частичную (в отдельных районах), только с токеном");
                sb.AppendLine("; sound    - 1 = звук включён, 0 = выключен");
                sb.AppendLine("region=" + Region);
                sb.AppendLine("token=" + Token);
                sb.AppendLine("interval=" + Interval);
                sb.AppendLine("partial=" + (Partial ? "1" : "0"));
                sb.AppendLine("sound=" + (Sound ? "1" : "0"));
                File.WriteAllText(FilePath, sb.ToString(), new UTF8Encoding(true));
            }
            catch { }
        }
    }

    // ---------------- Получение статуса ----------------
    enum AlertState { Unknown, Alert, Clear }

    static class AlertSource
    {
        // Порядок областей в строке alerts.in.ua /v1/iot/active_air_raid_alerts_by_oblast.json
        public static readonly string[] Regions = new string[] {
            "Автономна Республіка Крим", "Волинська область", "Вінницька область",
            "Дніпропетровська область", "Донецька область", "Житомирська область",
            "Закарпатська область", "Запорізька область", "Івано-Франківська область",
            "м. Київ", "Київська область", "Кіровоградська область", "Луганська область",
            "Львівська область", "Миколаївська область", "Одеська область",
            "Полтавська область", "Рівненська область", "м. Севастополь", "Сумська область",
            "Тернопільська область", "Харківська область", "Херсонська область",
            "Хмельницька область", "Черкаська область", "Чернівецька область",
            "Чернігівська область"
        };

        static string Download(string url, string bearer)
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
            req.Timeout = 15000;
            req.ReadWriteTimeout = 15000;
            req.UserAgent = "Trevoga-Win/1.0";
            req.Accept = "application/json";
            if (!string.IsNullOrEmpty(bearer))
                req.Headers.Add("Authorization", "Bearer " + bearer);
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            using (StreamReader r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                return r.ReadToEnd();
        }

        static string DecodeJsonEscapes(string s)
        {
            return Regex.Replace(s, @"\\u([0-9a-fA-F]{4})", delegate(Match m)
            {
                return ((char)Convert.ToInt32(m.Groups[1].Value, 16)).ToString();
            });
        }

        // Возвращает состояние; source — откуда взяли, для отображения.
        public static AlertState Check(Settings s, out string source)
        {
            if (!string.IsNullOrEmpty(s.Token))
            {
                source = "alerts.in.ua";
                return CheckAlertsInUa(s);
            }
            source = "ubilling.net.ua";
            return CheckUbilling(s.Region);
        }

        static AlertState CheckAlertsInUa(Settings s)
        {
            int idx = Array.IndexOf(Regions, s.Region);
            if (idx < 0) throw new Exception("Неизвестная область: " + s.Region);
            string body = Download("https://api.alerts.in.ua/v1/iot/active_air_raid_alerts_by_oblast.json", s.Token);
            string str = body.Trim().Trim('"');
            if (str.Length <= idx) throw new Exception("Неожиданный ответ сервера");
            char c = char.ToUpperInvariant(str[idx]);
            if (c == 'A') return AlertState.Alert;
            if (c == 'P') return s.Partial ? AlertState.Alert : AlertState.Clear;
            if (c == 'N') return AlertState.Clear;
            throw new Exception("Неожиданный ответ сервера");
        }

        static AlertState CheckUbilling(string region)
        {
            // В этом источнике Севастополь называется без "м."
            string name = region == "м. Севастополь" ? "Севастополь" : region;
            if (region == "Автономна Республіка Крим")
                throw new Exception("Для Крыма нужен токен alerts.in.ua");
            string body = DecodeJsonEscapes(Download("https://ubilling.net.ua/aerialalerts/", null));
            Match m = Regex.Match(body,
                "\"" + Regex.Escape(name) + "\"\\s*:\\s*\\{[^}]*?\"alertnow\"\\s*:\\s*(true|false)",
                RegexOptions.IgnoreCase);
            if (!m.Success) throw new Exception("Область не найдена в ответе сервера");
            return m.Groups[1].Value.ToLower() == "true" ? AlertState.Alert : AlertState.Clear;
        }
    }

    // ---------------- Звуки ----------------
    static class Sounds
    {
        static SoundPlayer player;

        public static void Stop()
        {
            try { if (player != null) player.Stop(); } catch { }
        }

        public static void Play(bool alarm)
        {
            Stop();
            try
            {
                string custom = Path.Combine(Application.StartupPath, alarm ? "alarm.wav" : "clear.wav");
                if (File.Exists(custom))
                    player = new SoundPlayer(custom);
                else
                    player = new SoundPlayer(new MemoryStream(alarm ? Siren() : AllClear()));
                player.Play();   // асинхронно
            }
            catch
            {
                try { if (alarm) SystemSounds.Hand.Play(); else SystemSounds.Asterisk.Play(); } catch { }
            }
        }

        const int Rate = 22050;

        static byte[] ToWav(short[] samples)
        {
            MemoryStream ms = new MemoryStream();
            BinaryWriter w = new BinaryWriter(ms);
            int dataLen = samples.Length * 2;
            w.Write(Encoding.ASCII.GetBytes("RIFF")); w.Write(36 + dataLen);
            w.Write(Encoding.ASCII.GetBytes("WAVE"));
            w.Write(Encoding.ASCII.GetBytes("fmt ")); w.Write(16);
            w.Write((short)1); w.Write((short)1); w.Write(Rate); w.Write(Rate * 2);
            w.Write((short)2); w.Write((short)16);
            w.Write(Encoding.ASCII.GetBytes("data")); w.Write(dataLen);
            foreach (short s in samples) w.Write(s);
            w.Flush();
            return ms.ToArray();
        }

        // Сирена: тон плавно поднимается и опускается, 4 цикла (~16 сек)
        static byte[] Siren()
        {
            double cycle = 4.0;
            int cycles = 4;
            int n = (int)(Rate * cycle * cycles);
            short[] buf = new short[n];
            double phase = 0;
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / Rate;
                double pos = (t % cycle) / cycle;                       // 0..1
                double k = pos < 0.5 ? pos * 2 : (1 - pos) * 2;          // 0..1..0
                k = 0.5 - 0.5 * Math.Cos(Math.PI * k);                   // плавнее
                double f = 450 + 650 * k;
                phase += 2 * Math.PI * f / Rate;
                double v = 0.62 * Math.Sin(phase) + 0.25 * Math.Sin(2 * phase) + 0.12 * Math.Sin(3 * phase);
                double env = Math.Min(1.0, t / 0.3) * Math.Min(1.0, (n - i) / (Rate * 0.5));
                buf[i] = (short)(v * env * 0.8 * 32767);
            }
            return ToWav(buf);
        }

        // Отбой: спокойный восходящий трезвучный сигнал, два раза
        static byte[] AllClear()
        {
            double[] notes = { 523.25, 659.25, 783.99, 1046.5 };
            double noteLen = 0.45, gap = 0.9;
            int reps = 2;
            double total = reps * (notes.Length * noteLen + gap) + 1.2;
            int n = (int)(Rate * total);
            double[] mix = new double[n];
            for (int r = 0; r < reps; r++)
            {
                for (int j = 0; j < notes.Length; j++)
                {
                    double start = r * (notes.Length * noteLen + gap) + j * noteLen;
                    int s0 = (int)(start * Rate);
                    int len = (int)(1.4 * Rate);
                    for (int i = 0; i < len && s0 + i < n; i++)
                    {
                        double t = (double)i / Rate;
                        double env = Math.Min(1.0, t / 0.01) * Math.Exp(-t * 3.2);
                        double v = Math.Sin(2 * Math.PI * notes[j] * t) + 0.3 * Math.Sin(4 * Math.PI * notes[j] * t);
                        mix[s0 + i] += v * env * 0.35;
                    }
                }
            }
            short[] buf = new short[n];
            for (int i = 0; i < n; i++)
            {
                double v = Math.Max(-1.0, Math.Min(1.0, mix[i]));
                buf[i] = (short)(v * 32767);
            }
            return ToWav(buf);
        }
    }

    // ---------------- Окно ----------------
    class MainForm : Form
    {
        Settings settings;
        ComboBox cbRegion;
        Label lblStatus, lblInfo;
        CheckBox chkSound, chkAutorun;
        Button btnTestAlarm, btnTestClear, btnStop;
        NotifyIcon tray;
        System.Windows.Forms.Timer timer;
        AlertState state = AlertState.Unknown;
        bool checking = false, firstCheck = true, reallyExit = false, startHidden;
        int requestId = 0;

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public MainForm(bool startHidden)
        {
            this.startHidden = startHidden;
            settings = Settings.Load();

            Text = "Тревога — монитор воздушной тревоги";
            Font = new Font("Segoe UI", 9.75f);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(420, 300);
            Icon = SystemIcons.Shield;

            Label l1 = new Label();
            l1.Text = "Область:";
            l1.Location = new Point(14, 17);
            l1.AutoSize = true;
            Controls.Add(l1);

            cbRegion = new ComboBox();
            cbRegion.DropDownStyle = ComboBoxStyle.DropDownList;
            cbRegion.Location = new Point(85, 13);
            cbRegion.Width = 320;
            cbRegion.Items.AddRange(AlertSource.Regions);
            int idx = Array.IndexOf(AlertSource.Regions, settings.Region);
            cbRegion.SelectedIndex = idx >= 0 ? idx : 9;
            cbRegion.SelectedIndexChanged += delegate
            {
                settings.Region = (string)cbRegion.SelectedItem;
                settings.Save();
                state = AlertState.Unknown;
                firstCheck = true;
                CheckNow();
            };
            Controls.Add(cbRegion);

            lblStatus = new Label();
            lblStatus.Location = new Point(14, 50);
            lblStatus.Size = new Size(392, 80);
            lblStatus.TextAlign = ContentAlignment.MiddleCenter;
            lblStatus.Font = new Font("Segoe UI", 20f, FontStyle.Bold);
            lblStatus.ForeColor = Color.White;
            lblStatus.BackColor = Color.Gray;
            lblStatus.Text = "Проверка…";
            Controls.Add(lblStatus);

            lblInfo = new Label();
            lblInfo.Location = new Point(14, 135);
            lblInfo.Size = new Size(392, 36);
            lblInfo.ForeColor = Color.DimGray;
            Controls.Add(lblInfo);

            chkSound = new CheckBox();
            chkSound.Text = "Звук включён";
            chkSound.Location = new Point(14, 178);
            chkSound.AutoSize = true;
            chkSound.Checked = settings.Sound;
            chkSound.CheckedChanged += delegate
            {
                settings.Sound = chkSound.Checked;
                settings.Save();
                if (!settings.Sound) Sounds.Stop();
            };
            Controls.Add(chkSound);

            chkAutorun = new CheckBox();
            chkAutorun.Text = "Запускать вместе с Windows";
            chkAutorun.Location = new Point(185, 178);
            chkAutorun.AutoSize = true;
            chkAutorun.Checked = IsAutorun();
            chkAutorun.CheckedChanged += delegate { SetAutorun(chkAutorun.Checked); };
            Controls.Add(chkAutorun);

            btnTestAlarm = MakeButton("Тест тревоги", 14, 212);
            btnTestAlarm.Click += delegate { Sounds.Play(true); };
            btnTestClear = MakeButton("Тест отбоя", 148, 212);
            btnTestClear.Click += delegate { Sounds.Play(false); };
            btnStop = MakeButton("Стоп звук", 282, 212);
            btnStop.Click += delegate { Sounds.Stop(); };

            Label hint = new Label();
            hint.Text = "Крестик сворачивает программу к часам. Выход — правый клик по значку.";
            hint.Location = new Point(14, 255);
            hint.Size = new Size(392, 36);
            hint.ForeColor = Color.Gray;
            Controls.Add(hint);

            // Значок в трее
            ContextMenu menu = new ContextMenu();
            menu.MenuItems.Add("Открыть", delegate { ShowWindow(); });
            menu.MenuItems.Add("Проверить сейчас", delegate { CheckNow(); });
            menu.MenuItems.Add("Стоп звук", delegate { Sounds.Stop(); });
            menu.MenuItems.Add("-");
            menu.MenuItems.Add("Выход", delegate { reallyExit = true; Close(); });
            tray = new NotifyIcon();
            tray.Icon = SystemIcons.Shield;
            tray.Text = "Тревога: проверка…";
            tray.ContextMenu = menu;
            tray.Visible = true;
            tray.DoubleClick += delegate { ShowWindow(); };

            timer = new System.Windows.Forms.Timer();
            timer.Interval = settings.Interval * 1000;
            timer.Tick += delegate { CheckNow(); };
            timer.Start();

            settings.Save();   // создаёт trevoga.ini при первом запуске
        }

        Button MakeButton(string text, int x, int y)
        {
            Button b = new Button();
            b.Text = text;
            b.Location = new Point(x, y);
            b.Size = new Size(124, 32);
            Controls.Add(b);
            return b;
        }

        protected override void SetVisibleCore(bool value)
        {
            if (startHidden && !IsHandleCreated)
            {
                CreateHandle();
                value = false;
            }
            base.SetVisibleCore(value);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            CheckNow();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            if (startHidden) BeginInvoke((MethodInvoker)delegate { CheckNow(); });
        }

        void ShowWindow()
        {
            startHidden = false;
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!reallyExit && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                tray.ShowBalloonTip(3000, "Тревога", "Программа продолжает следить за тревогой.", ToolTipIcon.Info);
                return;
            }
            timer.Stop();
            Sounds.Stop();
            tray.Visible = false;
            tray.Dispose();
            base.OnFormClosing(e);
        }

        void CheckNow()
        {
            if (checking) return;
            checking = true;
            int myId = ++requestId;
            Settings snapshot = settings;
            ThreadPool.QueueUserWorkItem(delegate
            {
                AlertState result = AlertState.Unknown;
                string error = null, source = "";
                try { result = AlertSource.Check(snapshot, out source); }
                catch (Exception ex) { error = ex.Message; }
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        checking = false;
                        if (myId == requestId) ApplyResult(result, error, source);
                    });
                }
                catch { checking = false; }
            });
        }

        void ApplyResult(AlertState result, string error, string source)
        {
            string time = DateTime.Now.ToString("HH:mm:ss");
            if (error != null)
            {
                lblInfo.Text = "Нет связи с сервером (" + time + "): " + error;
                if (state == AlertState.Unknown)
                {
                    lblStatus.BackColor = Color.Gray;
                    lblStatus.Text = "Нет данных";
                }
                tray.Text = Trim63("Тревога: нет связи");
                return;
            }

            AlertState old = state;
            state = result;
            string region = settings.Region;
            if (state == AlertState.Alert)
            {
                lblStatus.BackColor = Color.FromArgb(200, 30, 30);
                lblStatus.Text = "ПОВІТРЯНА ТРИВОГА";
                tray.Icon = SystemIcons.Error;
            }
            else
            {
                lblStatus.BackColor = Color.FromArgb(30, 140, 60);
                lblStatus.Text = "Тривоги немає";
                tray.Icon = SystemIcons.Shield;
            }
            lblInfo.Text = region + " · проверено в " + time + " · источник: " + source;
            tray.Text = Trim63((state == AlertState.Alert ? "ТРЕВОГА: " : "Нет тревоги: ") + region);

            if (firstCheck)
            {
                firstCheck = false;
                if (state == AlertState.Alert)
                    tray.ShowBalloonTip(5000, "Повітряна тривога", "Сейчас действует тревога: " + region, ToolTipIcon.Warning);
                return;
            }

            if (old == AlertState.Clear && state == AlertState.Alert)
            {
                if (settings.Sound) Sounds.Play(true);
                tray.ShowBalloonTip(10000, "ПОВІТРЯНА ТРИВОГА!", region + ". Пройдите в укрытие!", ToolTipIcon.Error);
            }
            else if (old == AlertState.Alert && state == AlertState.Clear)
            {
                if (settings.Sound) Sounds.Play(false);
                tray.ShowBalloonTip(10000, "Відбій тривоги", region, ToolTipIcon.Info);
            }
        }

        static string Trim63(string s) { return s.Length > 63 ? s.Substring(0, 63) : s; }

        static bool IsAutorun()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey))
                    return k != null && k.GetValue("Trevoga") != null;
            }
            catch { return false; }
        }

        static void SetAutorun(bool on)
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (k == null) return;
                    if (on) k.SetValue("Trevoga", "\"" + Application.ExecutablePath + "\" /min");
                    else k.DeleteValue("Trevoga", false);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show("Не удалось изменить автозапуск: " + ex.Message, "Тревога");
            }
        }
    }
}
