// Тревога — монитор воздушной тревоги для Windows 7/8/10/11
// Собирается встроенным компилятором .NET Framework 4.x (см. build.bat).
// Только C# 5 — чтобы собирался старым csc.exe из Windows 7.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Media;
using System.Runtime.InteropServices;
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
        public int Raion = 0;              // uid района alerts.in.ua, 0 = вся область
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
                    else if (k == "raion" && int.TryParse(v, out n)) s.Raion = n;
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
                sb.AppendLine("; raion    - код района (0 = вся область). Районы работают только с токеном");
                sb.AppendLine("; token    - токен alerts.in.ua (можно оставить пустым)");
                sb.AppendLine("; interval - как часто проверять, секунд (минимум 10)");
                sb.AppendLine("; partial  - 1 = считать тревогой и частичную (в части района/области), только с токеном");
                sb.AppendLine("; sound    - 1 = звук включён, 0 = выключен");
                sb.AppendLine("region=" + Region);
                sb.AppendLine("raion=" + Raion);
                sb.AppendLine("token=" + Token);
                sb.AppendLine("interval=" + Interval);
                sb.AppendLine("partial=" + (Partial ? "1" : "0"));
                sb.AppendLine("sound=" + (Sound ? "1" : "0"));
                File.WriteAllText(FilePath, sb.ToString(), new UTF8Encoding(true));
            }
            catch { }
        }
    }

    // ---------------- Области и районы ----------------
    static class Locations
    {
        // uid, название, uid области (из alerts.in.ua). Район uid → индекс в строке /v1/iot/active_air_raid_alerts.json
        public static readonly int[] RaionUid = {38, 41, 40, 39, 36, 37, 35, 33, 32, 34, 44, 42, 46, 47, 45, 43, 48, 54, 55, 51, 53, 49, 50, 52, 56, 57, 59, 60, 58, 61, 65, 63, 64, 66, 62, 147, 146, 149, 148, 145, 78, 79, 75, 73, 74, 76, 77, 82, 81, 83, 80, 1803, 1804, 1801, 1802, 85, 86, 84, 87, 91, 94, 90, 88, 89, 92, 93, 96, 95, 98, 97, 100, 105, 102, 104, 99, 103, 101, 107, 106, 108, 109, 110, 111, 112, 113, 117, 118, 116, 114, 115, 120, 119, 121, 127, 126, 123, 128, 124, 122, 125, 129, 133, 131, 130, 132, 135, 134, 136, 150, 153, 151, 152, 138, 139, 137, 144, 141, 142, 143, 140, 67, 71, 70, 69, 72, 68};
        public static readonly int[] RaionOblast = {8, 8, 8, 8, 4, 4, 4, 4, 4, 4, 9, 9, 9, 9, 9, 9, 9, 28, 28, 28, 28, 28, 28, 28, 28, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 14, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16, 16, 16, 16, 16, 27, 27, 27, 27, 27, 27, 27, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 19, 19, 19, 19, 5, 5, 5, 5, 20, 20, 20, 20, 20, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 23, 23, 23, 23, 23, 3, 3, 3, 24, 24, 24, 24, 26, 26, 26, 25, 25, 25, 25, 25, 13, 13, 13, 13, 13, 13};
        public static readonly string[] RaionName = {"Володимирський район", "Камінь-Каширський район", "Ковельський район", "Луцький район", "Вінницький район", "Гайсинський район", "Жмеринський район", "Могилів-Подільський район", "Тульчинський район", "Хмільницький район", "Дніпровський район", "Кам'янський район", "Криворізький район", "Нікопольський район", "Павлоградський район", "Самарівський район", "Синельниківський район", "Бахмутський район", "Волноваський район", "Горлівський район", "Донецький район", "Кальміуський район", "Краматорський район", "Маріупольський район", "Покровський район", "Бердичівський район", "Житомирський район", "Звягельський район", "Коростенський район", "Берегівський район", "Мукачівський район", "Рахівський район", "Тячівський район", "Ужгородський район", "Хустський район", "Бердянський район", "Василівський район", "Запорізький район", "Мелітопольський район", "Пологівський район", "Бориспільський район", "Броварський район", "Бучанський район", "Білоцерківський район", "Вишгородський район", "Обухівський район", "Фастівський район", "Голованівський район", "Кропивницький район", "Новоукраїнський район", "Олександрійський район", "Алчевський район", "Довжанський район", "Луганський район", "Ровеньківський район", "Сватівський район", "Старобільський район", "Сіверськодонецький район", "Щастинський район", "Дрогобицький район", "Золочівський район", "Львівський район", "Самбірський район", "Стрийський район", "Шептицький район", "Яворівський район", "Баштанський район", "Вознесенський район", "Миколаївський район", "Первомайський район", "Березівський район", "Болградський район", "Білгород-Дністровський район", "Одеський район", "Подільський район", "Роздільнянський район", "Ізмаїльський район", "Кременчуцький район", "Лубенський район", "Миргородський район", "Полтавський район", "Вараський район", "Дубенський район", "Рівненський район", "Сарненський район", "Конотопський район", "Охтирський район", "Роменський район", "Сумський район", "Шосткинський район", "Кременецький район", "Тернопільський район", "Чортківський район", "Берестинський район", "Богодухівський район", "Куп'янський район", "Лозівський район", "Харківський район", "Чугуївський район", "Ізюмський район", "Бериславський район", "Генічеський район", "Каховський район", "Скадовський район", "Херсонський район", "Кам'янець-Подільський район", "Хмельницький район", "Шепетівський район", "Звенигородський район", "Золотоніський район", "Уманський район", "Черкаський район", "Вижницький район", "Дністровський район", "Чернівецький район", "Корюківський район", "Новгород-Сіверський район", "Ніжинський район", "Прилуцький район", "Чернігівський район", "Верховинський район", "Калуський район", "Коломийський район", "Косівський район", "Надвірнянський район", "Івано-Франківський район"};
        public static readonly string[] OblastName = {"Хмельницька область", "Вінницька область", "Рівненська область", "Волинська область", "Дніпропетровська область", "Житомирська область", "Закарпатська область", "Запорізька область", "Івано-Франківська область", "Київська область", "Кіровоградська область", "Луганська область", "Миколаївська область", "Одеська область", "Полтавська область", "Сумська область", "Тернопільська область", "Харківська область", "Херсонська область", "Черкаська область", "Чернігівська область", "Чернівецька область", "Львівська область", "Донецька область", "Автономна Республіка Крим", "м. Севастополь", "м. Київ"};
        public static readonly int[] OblastUid = {3, 4, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31};
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

        public static int OblastUid(string name)
        {
            int i = Array.IndexOf(Locations.OblastName, name);
            return i >= 0 ? Locations.OblastUid[i] : -1;
        }

        public static string RaionName(int uid)
        {
            int i = Array.IndexOf(Locations.RaionUid, uid);
            return i >= 0 ? Locations.RaionName[i] : null;
        }

        // Возвращает состояние; source — откуда взяли, для отображения.
        public static AlertState Check(Settings s, out string source)
        {
            if (!string.IsNullOrEmpty(s.Token))
            {
                source = "alerts.in.ua";
                return CheckAlertsInUa(s);
            }
            source = s.Raion != 0 ? "ubilling.net.ua · район без токена недоступен, следим за всей областью"
                                  : "ubilling.net.ua";
            return CheckUbilling(s.Region);
        }

        static char CharAt(string str, int uid)
        {
            if (uid < 0 || uid >= str.Length || str[uid] == ' ') throw new Exception("Неожиданный ответ сервера");
            return char.ToUpperInvariant(str[uid]);
        }

        // Строка, где символ с индексом uid — статус области/района: A — тревога, P — частичная, N — нет
        static AlertState CheckAlertsInUa(Settings s)
        {
            int obl = OblastUid(s.Region);
            if (obl < 0) throw new Exception("Неизвестная область: " + s.Region);
            string body = Download("https://api.alerts.in.ua/v1/iot/active_air_raid_alerts.json", s.Token).Trim();
            if (body.StartsWith("\"")) body = body.Substring(1);
            if (body.EndsWith("\"")) body = body.Substring(0, body.Length - 1);
            char o = CharAt(body, obl);
            if (o == 'A') return AlertState.Alert;               // тревога по всей области
            char c = s.Raion != 0 ? CharAt(body, s.Raion) : o;
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
        static bool mciOpen;

        [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
        static extern int mciSendString(string command, StringBuilder ret, int retLen, IntPtr callback);

        public static void Stop()
        {
            try { if (player != null) player.Stop(); } catch { }
            try { if (mciOpen) { mciSendString("close trevoga_snd", null, 0, IntPtr.Zero); mciOpen = false; } } catch { }
        }

        // Свой звук: alarm.wav / alarm.mp3 и clear.wav / clear.mp3 рядом с программой
        static string CustomFile(bool alarm)
        {
            string name = alarm ? "alarm" : "clear";
            foreach (string ext in new string[] { ".wav", ".mp3" })
            {
                string f = Path.Combine(Application.StartupPath, name + ext);
                if (File.Exists(f)) return f;
            }
            return null;
        }

        public static void Play(bool alarm)
        {
            Stop();
            try
            {
                string custom = CustomFile(alarm);
                if (custom != null && custom.EndsWith(".mp3", StringComparison.OrdinalIgnoreCase))
                {
                    if (mciSendString("open \"" + custom + "\" type mpegvideo alias trevoga_snd", null, 0, IntPtr.Zero) == 0)
                    {
                        mciOpen = true;
                        mciSendString("play trevoga_snd", null, 0, IntPtr.Zero);
                        return;
                    }
                    custom = null;
                }
                if (custom != null)
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

        // Сирена гражданской обороны: раскрутка мотора, протяжный вой вверх-вниз, затухание (~24 сек)
        static double SirenFreq(double t)
        {
            const double T = 24, up = 3, down = 3.5, lo = 150, top = 620;
            if (t < up) return lo + (top - lo) * (1 - Math.Exp(-3 * t / up)) / (1 - Math.Exp(-3));
            double wail = 500 + 120 * Math.Cos(2 * Math.PI * (Math.Min(t, T - down) - up) / 5.0);
            if (t <= T - down) return wail;
            double k = (t - (T - down)) / down;
            return lo + (wail - lo) * Math.Pow(1 - Math.Min(1, k), 1.5);
        }

        static byte[] Siren()
        {
            const double T = 24;
            int n = (int)(Rate * T);
            short[] buf = new short[n];
            double[] harm = { 1.0, 0.5, 0.35, 0.22, 0.14, 0.08 };
            double norm = 0; foreach (double h in harm) norm += h;
            Random rnd = new Random(7);
            double phase = 0;
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / Rate;
                double f = SirenFreq(t) * (1 + 0.004 * Math.Sin(2 * Math.PI * 5.5 * t));
                phase += 2 * Math.PI * f / Rate;
                double v = 0;
                for (int h = 0; h < harm.Length; h++) v += harm[h] * Math.Sin((h + 1) * phase);
                v = v / norm + 0.03 * (rnd.NextDouble() * 2 - 1);
                v = Math.Tanh(1.8 * v) / Math.Tanh(1.8);
                double env = Math.Min(1.0, f / 400.0) * Math.Min(1.0, t / 0.2) * Math.Min(1.0, (n - i) / (Rate * 0.4));
                buf[i] = (short)(v * env * 0.85 * 32767);
            }
            return ToWav(buf);
        }

        // Отбой: спокойный восходящий сигнал, два раза
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
        ComboBox cbRegion, cbRaion;
        bool loading = true;
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
            ClientSize = new Size(420, 334);
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
            Controls.Add(cbRegion);

            Label l2 = new Label();
            l2.Text = "Район:";
            l2.Location = new Point(14, 51);
            l2.AutoSize = true;
            Controls.Add(l2);

            cbRaion = new ComboBox();
            cbRaion.DropDownStyle = ComboBoxStyle.DropDownList;
            cbRaion.Location = new Point(85, 47);
            cbRaion.Width = 320;
            Controls.Add(cbRaion);

            int idx = Array.IndexOf(AlertSource.Regions, settings.Region);
            cbRegion.SelectedIndex = idx >= 0 ? idx : 9;
            settings.Region = (string)cbRegion.SelectedItem;
            FillRaions(settings.Raion);

            cbRegion.SelectedIndexChanged += delegate
            {
                if (loading) return;
                settings.Region = (string)cbRegion.SelectedItem;
                FillRaions(0);
                settings.Raion = 0;
                SettingsChanged();
            };
            cbRaion.SelectedIndexChanged += delegate
            {
                if (loading) return;
                RaionItem it = cbRaion.SelectedItem as RaionItem;
                settings.Raion = it != null ? it.Uid : 0;
                SettingsChanged();
            };

            lblStatus = new Label();
            lblStatus.Location = new Point(14, 84);
            lblStatus.Size = new Size(392, 80);
            lblStatus.TextAlign = ContentAlignment.MiddleCenter;
            lblStatus.Font = new Font("Segoe UI", 20f, FontStyle.Bold);
            lblStatus.ForeColor = Color.White;
            lblStatus.BackColor = Color.Gray;
            lblStatus.Text = "Проверка…";
            Controls.Add(lblStatus);

            lblInfo = new Label();
            lblInfo.Location = new Point(14, 169);
            lblInfo.Size = new Size(392, 36);
            lblInfo.ForeColor = Color.DimGray;
            Controls.Add(lblInfo);

            chkSound = new CheckBox();
            chkSound.Text = "Звук включён";
            chkSound.Location = new Point(14, 212);
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
            chkAutorun.Location = new Point(185, 212);
            chkAutorun.AutoSize = true;
            chkAutorun.Checked = IsAutorun();
            chkAutorun.CheckedChanged += delegate { SetAutorun(chkAutorun.Checked); };
            Controls.Add(chkAutorun);

            btnTestAlarm = MakeButton("Тест тревоги", 14, 246);
            btnTestAlarm.Click += delegate { Sounds.Play(true); };
            btnTestClear = MakeButton("Тест отбоя", 148, 246);
            btnTestClear.Click += delegate { Sounds.Play(false); };
            btnStop = MakeButton("Стоп звук", 282, 246);
            btnStop.Click += delegate { Sounds.Stop(); };

            Label hint = new Label();
            hint.Text = "Крестик сворачивает программу к часам. Выход — правый клик по значку.";
            hint.Location = new Point(14, 289);
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
            loading = false;
        }

        class RaionItem
        {
            public int Uid; public string Name;
            public override string ToString() { return Name; }
        }

        void FillRaions(int selectUid)
        {
            bool was = loading;
            loading = true;
            cbRaion.Items.Clear();
            RaionItem all = new RaionItem(); all.Uid = 0; all.Name = "Вся область";
            cbRaion.Items.Add(all);
            int obl = AlertSource.OblastUid(settings.Region);
            int sel = 0;
            for (int i = 0; i < Locations.RaionUid.Length; i++)
            {
                if (Locations.RaionOblast[i] != obl) continue;
                RaionItem it = new RaionItem(); it.Uid = Locations.RaionUid[i]; it.Name = Locations.RaionName[i];
                cbRaion.Items.Add(it);
                if (it.Uid == selectUid) sel = cbRaion.Items.Count - 1;
            }
            cbRaion.SelectedIndex = sel;
            settings.Raion = ((RaionItem)cbRaion.SelectedItem).Uid;
            loading = was;
        }

        void SettingsChanged()
        {
            settings.Save();
            state = AlertState.Unknown;
            firstCheck = true;
            requestId++;          // ответ на старый запрос (другая область) не применяем
            checking = false;
            CheckNow();
        }

        string PlaceName()
        {
            string r = settings.Raion != 0 ? AlertSource.RaionName(settings.Raion) : null;
            return r != null ? r + " (" + settings.Region + ")" : settings.Region;
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
            string region = PlaceName();
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
