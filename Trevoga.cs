// Тривога — монітор повітряної тривоги для Windows 7/8/10/11
// Збирається вбудованим компілятором .NET Framework 4.x (див. build.bat). Лише C# 5.
//
// Дані: сервер-посередник (адреса в config.json, токен alerts.in.ua зберігається лише на сервері).
// Якщо сервер недоступний — ubilling.net.ua (лише області).

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Media;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Reflection.AssemblyTitle("Trevoga")]
[assembly: System.Reflection.AssemblyVersion("3.5.0.0")]

namespace Trevoga
{
    static class Program
    {
        public static readonly DateTime Started = DateTime.UtcNow;

        [STAThread]
        static void Main()
        {
            Log.W("START v" + Updater.Current + " args=[" + string.Join(" ", Environment.GetCommandLineArgs(), 1, Environment.GetCommandLineArgs().Length - 1)
                + "] os=" + Environment.OSVersion + " clr=" + Environment.Version);
            // Збій у будь-якому місці — записуємо в журнал і перезапускаємось (або відкочуємо невдале оновлення)
            try { Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException); } catch { }
            Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e) { Crash.Handle(e.Exception); };
            AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs e) { Crash.Handle(e.ExceptionObject as Exception); };

            // Windows 7: явно вмикаємо TLS 1.2 / 1.1 (3072 = Tls12, 768 = Tls11).
            try
            {
                ServicePointManager.SecurityProtocol =
                    (SecurityProtocolType)3072 | (SecurityProtocolType)768 | SecurityProtocolType.Tls;
            }
            catch
            {
                try { ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls; } catch { }
            }

            // Після автооновлення: чекаємо, поки стара копія програми закриється
            foreach (string a in Environment.GetCommandLineArgs())
                if (a.StartsWith("/waitpid:", StringComparison.OrdinalIgnoreCase))
                {
                    int pid;
                    if (int.TryParse(a.Substring(9), out pid))
                        try { System.Diagnostics.Process.GetProcessById(pid).WaitForExit(15000); } catch { }
                }
            foreach (string a in Environment.GetCommandLineArgs())
                if (a.StartsWith("/delay:", StringComparison.OrdinalIgnoreCase))
                {
                    int d;
                    if (int.TryParse(a.Substring(7), out d) && d > 0 && d < 120) Thread.Sleep(d * 1000);
                }

            bool createdNew;
            using (Mutex m = new Mutex(true, "Trevoga_SingleInstance_Mutex", out createdNew))
            {
                if (!createdNew)
                {
                    // Друга копія (наприклад, ще один ярлик в автозавантаженні): просимо першу показати вікно і тихо виходимо
                    bool minimized = false;
                    foreach (string a in Environment.GetCommandLineArgs())
                        if (a.Equals("/min", StringComparison.OrdinalIgnoreCase)) minimized = true;
                    if (!minimized)
                        try { using (EventWaitHandle ev = EventWaitHandle.OpenExisting(MainForm.ShowEventName)) ev.Set(); }
                        catch { }
                    return;
                }
                // Нова версія тричі запускалась і жодного разу не пропрацювала хвилину — повертаємо попередню
                if (Updater.CountStartWithoutConfirm() && Updater.Rollback("3 запуски без підтвердження")) return;
                if (!Updater.Pending) Updater.CleanupOld();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                bool startHidden = false;
                foreach (string a in Environment.GetCommandLineArgs())
                    if (a.Equals("/min", StringComparison.OrdinalIgnoreCase)) startHidden = true;
                Application.Run(new MainForm(startHidden));
            }
        }
    }

    // ---------------- Автооновлення ----------------
    static class Updater
    {
        public static Version Current
        {
            get { return typeof(Updater).Assembly.GetName().Version; }
        }

        static string ExePath { get { return Application.ExecutablePath; } }
        static string NewPath { get { return ExePath + ".new"; } }
        static string OldPath { get { return ExePath + ".old"; } }
        static string MarkerPath { get { return Path.Combine(Application.StartupPath, "update.pending"); } }
        static string SkipPath { get { return Path.Combine(Application.StartupPath, "update.skip"); } }

        /// <summary>Оновлення встановлено, але нова версія ще не підтвердила, що працює.</summary>
        public static bool Pending { get { try { return File.Exists(MarkerPath) && File.Exists(OldPath); } catch { return false; } } }

        /// <summary>Рахує запуски нової версії без підтвердження; true — це вже 3-й такий запуск (вона не працює).</summary>
        public static bool CountStartWithoutConfirm()
        {
            try
            {
                if (!Pending) return false;
                int n;
                if (!int.TryParse(File.ReadAllText(MarkerPath).Trim(), out n)) n = 0;
                n++;
                File.WriteAllText(MarkerPath, n.ToString());
                return n >= 3;
            }
            catch { return false; }
        }

        /// <summary>Нова версія працює нормально: прибираємо резервну копію.</summary>
        public static void Confirm()
        {
            try
            {
                if (!File.Exists(MarkerPath)) return;
                File.Delete(MarkerPath);
                Log.W("UPDATE confirmed v" + Current);
                CleanupOld();
            }
            catch { }
        }

        /// <summary>Повертає попередню версію (Trevoga.exe.old) і запускає її. true — поточний процес треба завершити.</summary>
        public static bool Rollback(string why)
        {
            if (!Pending) return false;
            try
            {
                Log.W("ROLLBACK v" + Current + ": " + why);
                try { File.WriteAllText(SkipPath, Current.ToString()); } catch { }
                string bad = ExePath + ".bad";
                if (File.Exists(bad)) File.Delete(bad);
                File.Move(ExePath, bad);
                File.Move(OldPath, ExePath);
                File.Delete(MarkerPath);
                System.Diagnostics.Process.Start(ExePath, "/min /rolledback /waitpid:" + System.Diagnostics.Process.GetCurrentProcess().Id);
                return true;
            }
            catch (Exception ex) { Log.W("ROLLBACK failed: " + ex.Message); return false; }
        }

        static bool Skipped(Version v)
        {
            try { return File.Exists(SkipPath) && File.ReadAllText(SkipPath).Trim() == v.ToString(); }
            catch { return false; }
        }

        public static void CleanupOld()
        {
            for (int i = 0; i < 5; i++)
            {
                try { if (File.Exists(OldPath)) File.Delete(OldPath); return; }
                catch { Thread.Sleep(500); }
            }
        }

        static string Sha256(byte[] data)
        {
            using (System.Security.Cryptography.SHA256 h = System.Security.Cryptography.SHA256.Create())
            {
                StringBuilder sb = new StringBuilder();
                foreach (byte b in h.ComputeHash(data)) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }

        // Перевіряє наявність нової версії і завантажує її поруч (Trevoga.exe.new).
        // Повертає номер нової версії, якщо файл готовий до встановлення; null — оновлень немає.
        public static string Download(string server)
        {
            if (string.IsNullOrEmpty(server)) return null;
            Dictionary<string, object> j = Json.Parse(AlertSource.Download(server + "/version.json?t=" + DateTime.UtcNow.Ticks)) as Dictionary<string, object>;
            object v;
            if (j == null || !j.TryGetValue("windows", out v)) return null;
            Dictionary<string, object> w = v as Dictionary<string, object>;
            if (w == null) return null;
            object ver, url, sha;
            if (!w.TryGetValue("version", out ver) || !w.TryGetValue("url", out url) || !w.TryGetValue("sha256", out sha)) return null;
            Version nv;
            try { nv = new Version(Convert.ToString(ver)); } catch { return null; }
            if (nv <= Current) return null;
            if (Skipped(nv)) return null;            // ця версія вже відкотилася — не ставимо її знову

            string u = Convert.ToString(url);
            if (u.StartsWith("/")) u = server + u;
            byte[] data;
            using (WebClient wc = new WebClient())
            {
                wc.Headers.Add("User-Agent", "Trevoga-Win/" + Current);
                data = wc.DownloadData(u + "?v=" + nv);
            }
            if (Sha256(data) != Convert.ToString(sha).ToLowerInvariant())
                throw new Exception("контрольна сума не збігається");
            File.WriteAllBytes(NewPath, data);
            return nv.ToString();
        }

        // Підміняє exe і запускає нову версію. true — нова версія запущена, поточну треба закрити.
        public static bool Apply(bool hidden)
        {
            if (!File.Exists(NewPath)) return false;
            try
            {
                if (File.Exists(OldPath)) File.Delete(OldPath);
                File.Move(ExePath, OldPath);            // запущений exe можна перейменувати
                try { File.Move(NewPath, ExePath); }
                catch { File.Move(OldPath, ExePath); throw; }
                File.WriteAllText(MarkerPath, "0");
                Log.W("UPDATE applying, restarting");
                System.Diagnostics.Process.Start(ExePath,
                    (hidden ? "/min " : "") + "/updated /waitpid:" + System.Diagnostics.Process.GetCurrentProcess().Id);
                return true;
            }
            catch (Exception ex)
            {
                Log.W("UPDATE apply failed: " + ex.Message);
                return false;
            }
        }
    }

    // ---------------- Звіти про спрацювання ----------------
    // Тривога/відбій/звук із точним часом надсилаються на наш сервер (POST /report), щоб рахувати, за скільки
    // спрацьовує програма. Лише випадковий id, версія, обране місце і стан — нічого особистого.
    // Якщо зв'язку немає — чекають у черзі (до 200) і йдуть пізніше.
    static class Reporter
    {
        static readonly object lk = new object();
        static readonly Queue<string> queue = new Queue<string>();
        static bool busy;
        public static string Server = "";
        public static string Id = "";

        public static string Esc(string x)
        {
            if (x == null) return "";
            StringBuilder sb = new StringBuilder();
            foreach (char c in x)
            {
                if (c == '"' || c == '\\') sb.Append('\\').Append(c);
                else if (c < ' ') sb.Append("\\u").Append(((int)c).ToString("x4"));
                else sb.Append(c);
            }
            return sb.ToString();
        }

        /// <summary>Подія: start | state | sound. fields — пари ключ/значення (рядки).</summary>
        public static void Send(string ev, params string[] fields)
        {
            StringBuilder sb = new StringBuilder();
            sb.Append("{\"e\":\"").Append(Esc(ev)).Append("\",\"t\":\"").Append(DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ"))
              .Append("\",\"id\":\"").Append(Esc(Id)).Append("\",\"v\":\"").Append(Updater.Current).Append("\"");
            for (int i = 0; i + 1 < fields.Length; i += 2)
                sb.Append(",\"").Append(Esc(fields[i])).Append("\":\"").Append(Esc(fields[i + 1])).Append("\"");
            sb.Append("}");
            lock (lk)
            {
                if (queue.Count >= 200) queue.Dequeue();
                queue.Enqueue(sb.ToString());
                if (busy) return;
                busy = true;
            }
            ThreadPool.QueueUserWorkItem(delegate { Pump(); });
        }

        static void Pump()
        {
            int fails = 0;
            while (true)
            {
                string body;
                lock (lk)
                {
                    if (queue.Count == 0 || string.IsNullOrEmpty(Server)) { busy = false; return; }
                    body = queue.Peek();
                }
                try
                {
                    // повідомляємо, скільки подія пролежала в черзі (за годинником ПК — він може бути неточним, а різниця точна)
                    string withQ = body.Substring(0, body.Length - 1) + ",\"sent\":\"" + DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ") + "\"}";
                    HttpWebRequest req = (HttpWebRequest)WebRequest.Create(Server + "/report");
                    req.Method = "POST";
                    req.Timeout = 10000;
                    req.ReadWriteTimeout = 10000;
                    req.UserAgent = "Trevoga-Win/" + Updater.Current;
                    req.ContentType = "application/json";
                    byte[] b = Encoding.UTF8.GetBytes(withQ);
                    req.ContentLength = b.Length;
                    using (Stream rs = req.GetRequestStream()) rs.Write(b, 0, b.Length);
                    using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse()) { }
                    lock (lk) { if (queue.Count > 0 && queue.Peek() == body) queue.Dequeue(); }
                    fails = 0;
                }
                catch (WebException ex)
                {
                    HttpWebResponse r = ex.Response as HttpWebResponse;
                    if (r != null && (int)r.StatusCode >= 400 && (int)r.StatusCode < 500)
                    {   // сервер не приймає звіти (старий сервер, 404) — не накопичуємо
                        lock (lk) { if (queue.Count > 0 && queue.Peek() == body) queue.Dequeue(); }
                        continue;
                    }
                    if (++fails > 3) { lock (lk) busy = false; return; }   // дошлемо з наступною подією
                    Thread.Sleep(5000 * fails);
                }
                catch
                {
                    lock (lk) { if (queue.Count > 0 && queue.Peek() == body) queue.Dequeue(); }
                }
            }
        }

        /// <summary>Під час виходу: спробувати дослати чергу, чекаючи не довше ms.</summary>
        public static void FlushSync(int ms)
        {
            lock (lk) { if (queue.Count == 0) return; }
            Thread t = new Thread(delegate() { lock (lk) busy = true; Pump(); });
            t.IsBackground = true;
            t.Start();
            t.Join(ms);
        }

        /// <summary>Періодично: дослати те, що лишилось у черзі.</summary>
        public static void Flush()
        {
            lock (lk) { if (busy || queue.Count == 0) return; busy = true; }
            ThreadPool.QueueUserWorkItem(delegate { Pump(); });
        }
    }

    // ---------------- Журнал ----------------
    // trevoga.log поруч із програмою: запуск, стан тривоги, звуки, зв'язок, оновлення, збої. До 1 МБ + trevoga.log.1
    static class Log
    {
        static readonly object lk = new object();
        public static string FilePath { get { return Path.Combine(Application.StartupPath, "trevoga.log"); } }

        public static void W(string msg)
        {
            try
            {
                lock (lk)
                {
                    string p = FilePath;
                    FileInfo fi = new FileInfo(p);
                    if (fi.Exists && fi.Length > 1024 * 1024)
                    {
                        string o = p + ".1";
                        if (File.Exists(o)) File.Delete(o);
                        File.Move(p, o);
                    }
                    File.AppendAllText(p, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " " + msg + "\r\n", Encoding.UTF8);
                }
            }
            catch { }
        }
    }

    // ---------------- Збої ----------------
    static class Crash
    {
        static int handling;

        public static void Handle(Exception ex)
        {
            if (Interlocked.Exchange(ref handling, 1) == 1) return;
            try
            {
                Log.W("CRASH " + (ex != null ? ex.ToString().Replace("\r\n", " | ") : "невідома помилка"));
                // Збій одразу після оновлення — повертаємо попередню версію
                if (!Updater.Pending || !Updater.Rollback("збій: " + (ex != null ? ex.Message : "?")))
                {
                    // Інакше перезапускаємось. Якщо падаємо одразу після старту — з паузою, яка росте (не більше 6 спроб поспіль)
                    int n = 0;
                    foreach (string a in Environment.GetCommandLineArgs())
                        if (a.StartsWith("/crash:", StringComparison.OrdinalIgnoreCase)) int.TryParse(a.Substring(7), out n);
                    bool early = (DateTime.UtcNow - Program.Started).TotalSeconds < 60;
                    n = early ? n + 1 : 1;
                    if (n <= 6)
                    {
                        Log.W("RESTART after crash #" + n);
                        System.Diagnostics.Process.Start(Application.ExecutablePath, "/min /crash:" + n + " /delay:" + (early ? 5 * n : 2)
                            + " /waitpid:" + System.Diagnostics.Process.GetCurrentProcess().Id);
                    }
                    else Log.W("too many crashes in a row, giving up");
                }
            }
            catch { }
            Environment.Exit(1);
        }
    }

    // ---------------- Налаштування (trevoga.ini поруч з exe) ----------------
    class Settings
    {
        public string Region = "м. Київ";
        public int Raion = 0;              // uid району alerts.in.ua, 0 = вся область
        public int Interval = 10;          // секунд між перевірками (сервер може змінити)
        public bool Partial = false;       // вважати тривогою часткову (окремі громади)
        public bool Sound = true;
        public string Voice = "tetiana";   // tetiana, mykyta, off
        public string Server = "";         // остання адреса сервера з config.json на GitHub
        public string Stream = "";         // (службове) інша адреса потоку NEPTUN
        public string UaStream = "";       // (службове) інша адреса потоку нашого сервера
        public bool Test = false;          // (службове) режим автотестів: швидкі перевірки оновлень
        public string Id = "";             // випадковий ідентифікатор пристрою для звітів про спрацювання

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
                    else if (k == "interval" && int.TryParse(v, out n)) s.Interval = n;
                    else if (k == "partial") s.Partial = (v == "1" || v.ToLower() == "true");
                    else if (k == "sound") s.Sound = !(v == "0" || v.ToLower() == "false");
                    else if (k == "voice" && (v == "tetiana" || v == "mykyta" || v == "off")) s.Voice = v;
                    else if (k == "server") s.Server = v;
                    else if (k == "stream") s.Stream = v;
                    else if (k == "uastream") s.UaStream = v;
                    else if (k == "test") s.Test = v == "1";
                    else if (k == "id") s.Id = v;
                }
            }
            catch { }
            if (s.Interval == 20 || s.Interval == 15) s.Interval = 10;   // старі значення за замовчуванням
            if (s.Interval < 10) s.Interval = 10;
            if (!System.Text.RegularExpressions.Regex.IsMatch(s.Id, "^[0-9a-f]{12}$")) s.Id = Guid.NewGuid().ToString("N").Substring(0, 12);
            return s;
        }

        public void Save()
        {
            try
            {
                StringBuilder sb = new StringBuilder();
                sb.AppendLine("; Налаштування програми Тривога");
                sb.AppendLine("; region   - область (як у списку програми)");
                sb.AppendLine("; raion    - код району (0 = вся область)");
                sb.AppendLine("; interval - як часто перевіряти, секунд (мінімум 10)");
                sb.AppendLine("; partial  - 1 = вважати тривогою і часткову (окремі громади)");
                sb.AppendLine("; sound    - 1 = звук увімкнено, 0 = вимкнено");
                sb.AppendLine("; voice    - голос: tetiana (жіночий), mykyta (чоловічий), off (без голосу)");
                sb.AppendLine("; server   - адреса сервера; програма оновлює її сама з config.json на GitHub");
                sb.AppendLine("region=" + Region);
                sb.AppendLine("raion=" + Raion);
                sb.AppendLine("interval=" + Interval);
                sb.AppendLine("partial=" + (Partial ? "1" : "0"));
                sb.AppendLine("sound=" + (Sound ? "1" : "0"));
                sb.AppendLine("voice=" + Voice);
                sb.AppendLine("server=" + Server);
                if (Stream.Length > 0) sb.AppendLine("stream=" + Stream);
                if (UaStream.Length > 0) sb.AppendLine("uastream=" + UaStream);
                if (Test) sb.AppendLine("test=1");
                sb.AppendLine("; id       - випадковий номер цієї копії програми (для звітів про швидкість спрацювання)");
                sb.AppendLine("id=" + Id);
                File.WriteAllText(FilePath, sb.ToString(), new UTF8Encoding(true));
            }
            catch { }
        }
    }

    // ---------------- Адреса сервера ----------------
    static class AppConfig
    {
        // Працює, якщо репозиторій публічний. Якщо ні — використовується адреса, вшита при збірці.
        public const string ConfigUrl = "https://raw.githubusercontent.com/repaulshadow/spokiy/main/config.json";

        static string ParseServer(string json)
        {
            Dictionary<string, object> d = Json.Parse(json) as Dictionary<string, object>;
            object v;
            if (d == null || !d.TryGetValue("server", out v) || v == null) return "";
            return Convert.ToString(v).Trim().TrimEnd('/');
        }

        public static string Embedded()
        {
            try
            {
                Stream st = typeof(AppConfig).Assembly.GetManifestResourceStream("config.json");
                if (st == null) return "";
                using (StreamReader r = new StreamReader(st, Encoding.UTF8)) return ParseServer(r.ReadToEnd());
            }
            catch { return ""; }
        }

        // null — не вдалося завантажити
        public static string Fetch()
        {
            try { return ParseServer(AlertSource.Download(ConfigUrl + "?t=" + DateTime.UtcNow.Ticks)); }
            catch { return null; }
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

    // ---------------- Мини-парсер JSON (в .NET 4 Client Profile нет своего) ----------------
    class Json
    {
        string s; int i;
        Json(string text) { s = text; }
        public static object Parse(string text) { Json j = new Json(text); j.Ws(); return j.Value(); }

        void Ws() { while (i < s.Length && char.IsWhiteSpace(s[i])) i++; }

        object Value()
        {
            Ws();
            if (i >= s.Length) throw new Exception("JSON: конец данных");
            char c = s[i];
            if (c == '{')
            {
                Dictionary<string, object> d = new Dictionary<string, object>();
                i++; Ws();
                if (s[i] == '}') { i++; return d; }
                while (true)
                {
                    Ws(); string k = Str(); Ws();
                    if (s[i] != ':') throw new Exception("JSON: ожидалось ':'");
                    i++;
                    d[k] = Value(); Ws();
                    if (s[i] == ',') { i++; continue; }
                    if (s[i] == '}') { i++; return d; }
                    throw new Exception("JSON: ошибка в объекте");
                }
            }
            if (c == '[')
            {
                List<object> l = new List<object>();
                i++; Ws();
                if (s[i] == ']') { i++; return l; }
                while (true)
                {
                    l.Add(Value()); Ws();
                    if (s[i] == ',') { i++; continue; }
                    if (s[i] == ']') { i++; return l; }
                    throw new Exception("JSON: ошибка в массиве");
                }
            }
            if (c == '"') return Str();
            if (string.CompareOrdinal(s, i, "true", 0, 4) == 0) { i += 4; return true; }
            if (string.CompareOrdinal(s, i, "false", 0, 5) == 0) { i += 5; return false; }
            if (string.CompareOrdinal(s, i, "null", 0, 4) == 0) { i += 4; return null; }
            int st = i;
            while (i < s.Length && "+-0123456789.eE".IndexOf(s[i]) >= 0) i++;
            if (st == i) throw new Exception("JSON: неизвестный символ");
            return s.Substring(st, i - st);
        }

        string Str()
        {
            if (s[i] != '"') throw new Exception("JSON: ожидалась строка");
            i++;
            StringBuilder sb = new StringBuilder();
            while (s[i] != '"')
            {
                char c = s[i++];
                if (c != '\\') { sb.Append(c); continue; }
                char e = s[i++];
                switch (e)
                {
                    case 'n': sb.Append('\n'); break;
                    case 't': sb.Append('\t'); break;
                    case 'r': sb.Append('\r'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'u': sb.Append((char)Convert.ToInt32(s.Substring(i, 4), 16)); i += 4; break;
                    default: sb.Append(e); break;
                }
            }
            i++;
            return sb.ToString();
        }
    }

    // ---------------- Отримання статусу ----------------
    enum AlertState { Unknown, Alert, Clear }

    class CheckResult
    {
        public AlertState State = AlertState.Unknown;
        public List<string> Threats = new List<string>();
        public DateTime? Since;          // з якого часу триває поточний стан (якщо відомо)
        public string Source = "";
        public string Note = "";         // пояснення: сервер недоступний, збіг джерел тощо
        public bool Disagree = false;    // джерела показують різне
        public int PollS = 0;            // рекомендована сервером частота опитування, с
        public string Level = "";        // рівень тривоги: "red" | "yellow" | "" (невідомо)
        public AlertState AinState = AlertState.Unknown;   // що показує alerts.in.ua
        public AlertState NepState = AlertState.Unknown;   // що показує NEPTUN
        public AlertState UaState = AlertState.Unknown;    // що показує UkraineAlarm (офіційне джерело)
    }

    static class AlertSource
    {
        // Порядок областей у списку програми
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

        public static string Download(string url)
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
            req.Timeout = 10000;
            req.ReadWriteTimeout = 10000;
            req.UserAgent = "Trevoga-Win/2.0";
            req.Accept = "application/json";
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            using (StreamReader r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                return r.ReadToEnd();
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

        public static string ThreatTitle(string t)
        {
            switch (t)
            {
                case "ballistic_missiles": return "Балістика";
                case "cruise_missiles": return "Крилаті ракети";
                case "unspecified_missiles": return "Ракети";
                case "drones": return "Дрони";
                case "guided_aerial_bombs": return "КАБи";
                case "tactic_aircraft_activity": return "Тактична авіація";
                case "strategic_aircraft_activity": return "Стратегічна авіація";
                case "mig31k_departure": return "Зліт МіГ-31К";
                case "air_defense": return "Робота ППО";
                case "artillery_shelling": return "Артобстріл";
                case "urban_fights": return "Вуличні бої";
                case "chemical": return "Хімічна загроза";
                case "nuclear": return "Радіаційна загроза";
                default: return "Інша загроза";
            }
        }

        static readonly string[] Order = { "ballistic_missiles", "cruise_missiles", "unspecified_missiles",
            "mig31k_departure", "guided_aerial_bombs", "drones", "strategic_aircraft_activity",
            "tactic_aircraft_activity", "air_defense", "artillery_shelling", "urban_fights", "chemical", "nuclear" };

        static int ToInt(object o)
        {
            int n;
            return o != null && int.TryParse(Convert.ToString(o, System.Globalization.CultureInfo.InvariantCulture).Split('.')[0], out n) ? n : -1;
        }

        static char CharAt(string str, int uid)
        {
            if (uid < 0 || uid >= str.Length || str[uid] == ' ') throw new Exception("неочікувана відповідь сервера");
            return char.ToUpperInvariant(str[uid]);
        }

        /// <summary>Запасні джерела, коли немає ні сервера, ні потоку NEPTUN.</summary>
        public static CheckResult Fallback(Settings s, string serverError)
        {
            // Запасний шлях 1: NEPTUN напряму (дані по районах, без ключа)
            try
            {
                CheckResult n = FromNeptunDirect(s);
                n.Note = (serverError != null ? "сервер недоступний (" + serverError + ")" : "сервер ще не налаштовано")
                    + (n.Note.Length > 0 ? " · " + n.Note : "");
                return n;
            }
            catch { }
            // Запасний шлях 2: ubilling.net.ua (лише області)
            CheckResult r = FromUbilling(s.Region);
            if (s.Raion != 0)
            {
                // Обрано район: дані по всій області сигналу не дають — лише для довідки
                bool oblAlert = r.State == AlertState.Alert;
                r.State = AlertState.Unknown;
                r.Threats.Clear();
                r.Since = null;
                r.Note = (serverError != null ? "сервер і NEPTUN недоступні" : "NEPTUN недоступний")
                    + " · в області зараз " + (oblAlert ? "тривога" : "тиша");
                return r;
            }
            if (serverError != null) r.Note = "сервер недоступний (" + serverError + ")";
            return r;
        }

        public static string Short(string m)
        {
            if (string.IsNullOrEmpty(m)) return "помилка";
            m = m.Replace("\r", " ").Replace("\n", " ");
            return m.Length > 60 ? m.Substring(0, 60) + "…" : m;
        }

        // NEPTUN напряму: перетворюємо їхню відповідь у формат нашого сервера і розбираємо тим самим кодом
        public static string NeptunBase = "https://neptun.in.ua";

        static CheckResult FromNeptunDirect(Settings s)
        {
            Dictionary<string, object> j = Json.Parse(Download(NeptunBase + "/api/v1/alerts")) as Dictionary<string, object>;
            Dictionary<string, object> nep = NeptunToNep(j);
            if (nep == null) throw new Exception("NEPTUN: неочікувана відповідь");
            Dictionary<string, object> root = new Dictionary<string, object>();
            root["neptun"] = nep;
            CheckResult res = FromRoot(s, root, null);
            res.Source = "NEPTUN напряму";
            return res;
        }

        // «reasons» NEPTUN (напр. «Дронова загроза (жовтий рівень)») → типи загроз як у alerts.in.ua
        static List<object> NeptunTypes(object reasons)
        {
            string txt = "";
            List<object> l = reasons as List<object>;
            if (l != null) foreach (object o in l) txt += " " + Convert.ToString(o);
            else if (reasons != null) txt = Convert.ToString(reasons);
            txt = txt.ToLowerInvariant();
            List<object> t = new List<object>();
            if (txt.Contains("баліст")) t.Add("ballistic_missiles");
            if (txt.Contains("крилат")) t.Add("cruise_missiles");
            if (txt.Contains("ракет") && t.Count == 0) t.Add("unspecified_missiles");
            if (txt.Contains("міг") || txt.Contains("mig")) t.Add("mig31k_departure");
            if (txt.Contains("каб") || txt.Contains("авіабомб") || txt.Contains("керован")) t.Add("guided_aerial_bombs");
            if (txt.Contains("дрон") || txt.Contains("бпла") || txt.Contains("шахед") || txt.Contains("безпілот")) t.Add("drones");
            if (txt.Contains("артобстріл") || txt.Contains("артилер")) t.Add("artillery_shelling");
            return t;
        }

        static string Lower(object x) { return x != null ? Convert.ToString(x).ToLowerInvariant() : null; }

        /// <summary>Відповідь NEPTUN (/api/v1/alerts або повідомлення «alerts» з потоку) → формат нашого сервера.</summary>
        public static Dictionary<string, object> NeptunToNep(Dictionary<string, object> j)
        {
            if (j == null) return null;
            object v;
            List<object> rs = j.TryGetValue("raions", out v) ? v as List<object> : null;
            List<object> os = j.TryGetValue("oblasts", out v) ? v as List<object> : null;
            if (rs == null && os == null) return null;
            List<object> r = new List<object>(), o = new List<object>();
            if (rs != null)
                foreach (object item in rs)
                {
                    Dictionary<string, object> x = item as Dictionary<string, object>;
                    if (x == null) continue;
                    object a, b, c, d, e;
                    x.TryGetValue("oblast", out a); x.TryGetValue("name", out b); x.TryGetValue("since", out c);
                    x.TryGetValue("level", out d); x.TryGetValue("reasons", out e);
                    r.Add(new List<object>(new object[] { a, b, c, Lower(d), NeptunTypes(e) }));
                }
            if (os != null)
                foreach (object item in os)
                {
                    Dictionary<string, object> x = item as Dictionary<string, object>;
                    if (x == null) continue;
                    object b, c, d, e;
                    x.TryGetValue("name", out b); x.TryGetValue("since", out c);
                    x.TryGetValue("level", out d); x.TryGetValue("reasons", out e);
                    o.Add(new List<object>(new object[] { b, c, Lower(d), NeptunTypes(e) }));
                }
            Dictionary<string, object> nep = new Dictionary<string, object>();
            nep["r"] = r;
            nep["o"] = o;
            // громади (лише в потоці нашого сервера): [область, район, громада, початок, рівень, типи]
            List<object> cs = j.TryGetValue("communities", out v) ? v as List<object> : null;
            if (cs != null)
            {
                List<object> c = new List<object>();
                foreach (object item in cs)
                {
                    Dictionary<string, object> x = item as Dictionary<string, object>;
                    if (x == null) continue;
                    object a, b, n, d, e;
                    x.TryGetValue("oblast", out a); x.TryGetValue("raion", out b); x.TryGetValue("name", out n);
                    x.TryGetValue("since", out d); x.TryGetValue("level", out e);
                    c.Add(new List<object>(new object[] { a, b, n, d, Lower(e), new List<object>() }));
                }
                nep["c"] = c;
            }
            return nep;
        }

        // Нормалізація назв для порівняння з NEPTUN: апостроф, «м.», Крим
        public static string Norm(string x)
        {
            if (x == null) return "";
            string t = x.Trim().ToLowerInvariant().Replace('\u2019', '\'').Replace('\u02BC', '\'').Replace('`', '\'');
            if (t.StartsWith("м. ")) t = t.Substring(3);
            if (t == "ар крим" || t == "автономна республіка крим") t = "крим";
            string alias;
            if (RenamedRaions.TryGetValue(t, out alias)) t = alias;
            return t;
        }

        // Перейменовані райони: NEPTUN (карта) подекуди вживає старі назви — зводимо до нових
        static readonly Dictionary<string, string> RenamedRaions = new Dictionary<string, string>
        {
            { "володимир-волинський район", "володимирський район" },
            { "новомосковський район", "самарівський район" },
            { "новоград-волинський район", "звягельський район" },
            { "сєвєродонецький район", "сіверськодонецький район" },
            { "червоноградський район", "шептицький район" },
            { "красноградський район", "берестинський район" },
        };

        static DateTime? ParseIso(object x)
        {
            if (x == null) return null;
            try
            {
                return DateTime.Parse(Convert.ToString(x), System.Globalization.CultureInfo.InvariantCulture,
                    System.Globalization.DateTimeStyles.RoundtripKind).ToLocalTime();
            }
            catch { return null; }
        }

        static void Earliest(CheckResult r, DateTime? t)
        {
            if (t.HasValue && (r.Since == null || t.Value < r.Since.Value)) r.Since = t;
        }

        public static Dictionary<string, object> FetchServer(string server)
        {
            Dictionary<string, object> root = Json.Parse(Download(server + "/status")) as Dictionary<string, object>;
            if (root == null || (!root.ContainsKey("air") && !root.ContainsKey("neptun"))) throw new Exception("неочікувана відповідь сервера");
            return root;
        }

        public static int PollOf(Dictionary<string, object> root)
        {
            object p;
            return root != null && root.TryGetValue("poll", out p) && p != null ? ToInt(p) : 0;
        }

        static int Sev(string lv) { return lv == "red" ? 2 : lv == "yellow" ? 1 : 0; }
        static string MaxLevel(string a, string b) { return Sev(b) > Sev(a) ? b : a; }

        static void AddTypes(List<string> dst, object list)
        {
            List<object> l = list as List<object>;
            if (l == null) return;
            foreach (object t in l)
            {
                string ts = Convert.ToString(t);
                if (!string.IsNullOrEmpty(ts) && !dst.Contains(ts)) dst.Add(ts);
            }
        }

        // ignoreAinBefore: NEPTUN щойно дав відбій — тривоги alerts.in.ua, що почалися раніше, вважаємо застарілими
        // (alerts.in.ua сервер оновлює раз на 20 с), щоб відбій прозвучав одразу.
        public static CheckResult FromRoot(Settings s, Dictionary<string, object> root, DateTime? ignoreAinBefore)
        {
            return FromRoot(s, root, ignoreAinBefore, null);
        }

        class SrcEval
        {
            public AlertState State = AlertState.Clear;
            public string Level = "";
            public List<string> Threats = new List<string>();
            public DateTime? Since;
        }

        // Джерело у форматі NEPTUN: o — області, r — райони, c — громади (часткова тривога)
        static SrcEval EvalNepLike(Settings s, Dictionary<string, object> nep, string oblN, string raionN, int obl)
        {
            SrcEval res = new SrcEval();
            CheckResult tmp = new CheckResult();
            object v;
            bool oblWide = false, raionHit = false, partialHit = false;
            int alertedInObl = 0;
            if (nep.TryGetValue("o", out v) && v is List<object>)
                foreach (object item in (List<object>)v)
                {
                    List<object> e = item as List<object>;
                    if (e == null || e.Count < 1 || Norm(Convert.ToString(e[0])) != oblN) continue;
                    oblWide = true;
                    if (e.Count > 1) Earliest(tmp, ParseIso(e[1]));
                    if (e.Count > 2 && e[2] != null) res.Level = MaxLevel(res.Level, Convert.ToString(e[2]));
                    if (e.Count > 3) AddTypes(res.Threats, e[3]);
                }
            if (nep.TryGetValue("r", out v) && v is List<object>)
                foreach (object item in (List<object>)v)
                {
                    List<object> e = item as List<object>;
                    if (e == null || e.Count < 2 || Norm(Convert.ToString(e[0])) != oblN) continue;
                    alertedInObl++;
                    bool mine = s.Raion != 0 && Norm(Convert.ToString(e[1])) == raionN;
                    if (mine) raionHit = true;
                    string lv = e.Count > 3 && e[3] != null ? Convert.ToString(e[3]) : "";
                    if (mine || s.Raion == 0)
                    {
                        if (e.Count > 2) Earliest(tmp, ParseIso(e[2]));
                        res.Level = MaxLevel(res.Level, lv);
                        if (e.Count > 4) AddTypes(res.Threats, e[4]);
                    }
                }
            if (s.Partial && nep.TryGetValue("c", out v) && v is List<object>)
                foreach (object item in (List<object>)v)
                {
                    List<object> e = item as List<object>;
                    if (e == null || e.Count < 3 || Norm(Convert.ToString(e[0])) != oblN) continue;
                    if (s.Raion != 0 && Norm(Convert.ToString(e[1])) != raionN) continue;
                    partialHit = true;
                    if (e.Count > 3) Earliest(tmp, ParseIso(e[3]));
                    if (e.Count > 4 && e[4] != null) res.Level = MaxLevel(res.Level, Convert.ToString(e[4]));
                }
            int raionsInObl = 0;
            for (int i = 0; i < Locations.RaionOblast.Length; i++) if (Locations.RaionOblast[i] == obl) raionsInObl++;
            bool allRaions = raionsInObl > 0 && alertedInObl >= raionsInObl;
            if (s.Raion != 0) res.State = (oblWide || raionHit || partialHit) ? AlertState.Alert : AlertState.Clear;
            else res.State = (oblWide || allRaions || (s.Partial && (alertedInObl > 0 || partialHit))) ? AlertState.Alert : AlertState.Clear;
            res.Since = tmp.Since;
            return res;
        }

        // ignoreNepBefore: UkraineAlarm щойно дав відбій — тривоги NEPTUN, що почалися раніше, вважаємо застарілими
        public static CheckResult FromRoot(Settings s, Dictionary<string, object> root, DateTime? ignoreAinBefore, DateTime? ignoreNepBefore)
        {
            int obl = OblastUid(s.Region);
            if (obl < 0) throw new Exception("невідома область");
            object v;
            string air = root.TryGetValue("air", out v) ? v as string : null;
            Dictionary<string, object> nep = root.TryGetValue("neptun", out v) ? v as Dictionary<string, object> : null;
            if (air == null && nep == null && !(root.ContainsKey("ua") && root["ua"] is Dictionary<string, object>)) throw new Exception("сервер без даних");

            string raionName = s.Raion != 0 ? RaionName(s.Raion) : null;
            string oblN = Norm(s.Region), raionN = Norm(raionName);
            CheckResult r = new CheckResult();
            AlertState ain = AlertState.Unknown, nepState = AlertState.Unknown;
            CheckResult ainRes = new CheckResult();
            string ainLevel = "";
            List<string> ainThreats = new List<string>();
            bool ainPartial = false;      // alerts.in.ua: тривога лише в частині району/області

            // --- Джерело 1: alerts.in.ua
            if (air != null)
            {
                try
                {
                    char o = CharAt(air, obl);
                    char c = s.Raion != 0 ? CharAt(air, s.Raion) : o;
                    if (o == 'A' || c == 'A') ain = AlertState.Alert;
                    else if (c == 'P') { ain = s.Partial ? AlertState.Alert : AlertState.Clear; ainPartial = true; }
                    else if (c == 'N') ain = AlertState.Clear;

                    if (root.TryGetValue("alerts", out v) && v is List<object>)
                    {
                        foreach (object item in (List<object>)v)
                        {
                            Dictionary<string, object> a = item as Dictionary<string, object>;
                            if (a == null) continue;
                            object x;
                            int u = a.TryGetValue("u", out x) ? ToInt(x) : -1;
                            int ao = a.TryGetValue("o", out x) ? ToInt(x) : -1;
                            string on = a.TryGetValue("on", out x) && x != null ? Convert.ToString(x) : null;
                            string rn = a.TryGetValue("r", out x) && x != null ? Convert.ToString(x) : null;
                            // у районних тривог alerts.in.ua код області буває некоректним — звіряємо ще й за назвою
                            bool inObl = u == obl || ao == obl || (on != null && Norm(on) == oblN);
                            if (!inObl) continue;
                            if (s.Raion != 0 && u != obl && u != s.Raion && (rn == null || Norm(rn) != raionN)) continue;
                            if (a.TryGetValue("s", out x)) Earliest(ainRes, ParseIso(x));
                            if (a.TryGetValue("l", out x) && x != null) ainLevel = MaxLevel(ainLevel, Convert.ToString(x));
                            if (a.TryGetValue("t", out x)) AddTypes(ainThreats, x);
                        }
                    }
                }
                catch { ain = AlertState.Unknown; }
            }
            bool ainIgnored = false;
            if (ain == AlertState.Alert && ignoreAinBefore.HasValue
                && (!ainRes.Since.HasValue || ainRes.Since.Value <= ignoreAinBefore.Value))
            {
                ain = AlertState.Clear;
                ainPartial = false;
                ainIgnored = true;
            }

            // --- Джерело 2: NEPTUN; джерело 3: UkraineAlarm (офіційне, через наш сервер) — однаковий формат
            SrcEval nepE = nep != null ? EvalNepLike(s, nep, oblN, raionN, obl) : null;
            object uav;
            Dictionary<string, object> uaD = root.TryGetValue("ua", out uav) ? uav as Dictionary<string, object> : null;
            SrcEval uaE = uaD != null ? EvalNepLike(s, uaD, oblN, raionN, obl) : null;
            AlertState uaState = uaE != null ? uaE.State : AlertState.Unknown;
            if (nepE != null) nepState = nepE.State;
            // UkraineAlarm щойно дав відбій — тривоги NEPTUN, що почалися раніше, вважаємо застарілими (NEPTUN запізнюється)
            bool nepIgnored = false;
            if (nepE != null && nepE.State == AlertState.Alert && ignoreNepBefore.HasValue
                && (!nepE.Since.HasValue || nepE.Since.Value <= ignoreNepBefore.Value))
            {
                nepState = AlertState.Clear;
                nepIgnored = true;
            }

            if (ain == AlertState.Unknown && nepState == AlertState.Unknown && uaState == AlertState.Unknown) throw new Exception("неочікувана відповідь сервера");

            // --- Зведення: тривога, якщо її показує хоча б одне джерело
            r.State = (ain == AlertState.Alert || nepState == AlertState.Alert || uaState == AlertState.Alert) ? AlertState.Alert : AlertState.Clear;
            if (r.State == AlertState.Alert)
            {
                // Рівень — найвищий серед джерел, що бачать тривогу; якщо невідомий — червоний
                string lv = "";
                if (ain == AlertState.Alert || ainPartial) lv = MaxLevel(lv, ainLevel);
                if (nepState == AlertState.Alert) lv = MaxLevel(lv, nepE.Level);
                if (uaState == AlertState.Alert) lv = MaxLevel(lv, uaE.Level);
                r.Level = lv.Length > 0 ? lv : "red";
                foreach (string t in ainThreats) if (!r.Threats.Contains(t)) r.Threats.Add(t);
                if (nepState == AlertState.Alert) foreach (string t in nepE.Threats) if (!r.Threats.Contains(t)) r.Threats.Add(t);
                if (uaState == AlertState.Alert) foreach (string t in uaE.Threats) if (!r.Threats.Contains(t)) r.Threats.Add(t);
                if (ain == AlertState.Alert || ainPartial) Earliest(r, ainRes.Since);
                if (nepState == AlertState.Alert) Earliest(r, nepE.Since);
                if (uaState == AlertState.Alert) Earliest(r, uaE.Since);
            }

            List<string> names = new List<string>();
            List<string> alerting = new List<string>();
            int known = 0, alerts = 0;
            if (ain != AlertState.Unknown) { names.Add("alerts.in.ua"); known++; if (ain == AlertState.Alert) { alerts++; alerting.Add("alerts.in.ua"); } }
            if (nepState != AlertState.Unknown) { names.Add("NEPTUN"); known++; if (nepState == AlertState.Alert) { alerts++; alerting.Add("NEPTUN"); } }
            if (uaState != AlertState.Unknown) { names.Add("UkraineAlarm"); known++; if (uaState == AlertState.Alert) { alerts++; alerting.Add("UkraineAlarm"); } }
            r.Source = string.Join(" + ", names.ToArray());
            r.AinState = ain;
            r.NepState = nepE != null ? nepE.State : AlertState.Unknown;
            r.UaState = uaState;
            if ((ainIgnored || nepIgnored) && r.State == AlertState.Clear)
                r.Note = nepIgnored ? "відбій за UkraineAlarm (інші джерела ще оновлюються)" : "відбій за NEPTUN (alerts.in.ua ще оновлюється)";
            else if (known >= 2)
            {
                if (alerts == 0 || alerts == known) r.Note = "джерела збігаються";
                else if (ainPartial && alerts == known - 1 && ain != AlertState.Alert)
                    r.Note = "alerts.in.ua: тривога в частині " + (s.Raion != 0 ? "району" : "області") + ", інші: у всьому";
                else
                {
                    r.Note = "джерела розходяться: тривогу показує " + (alerting.Count == 1 ? "лише " : "") + string.Join(" і ", alerting.ToArray());
                    r.Disagree = true;
                }
            }

            if (r.State != AlertState.Alert) { r.Threats.Clear(); r.Since = null; }
            r.Threats.Sort(delegate(string a1, string b1)
            {
                int ia = Array.IndexOf(Order, a1), ib = Array.IndexOf(Order, b1);
                if (ia < 0) ia = 99; if (ib < 0) ib = 99;
                return ia.CompareTo(ib);
            });
            return r;
        }

        static string DecodeJsonEscapes(string s)
        {
            return Regex.Replace(s, @"\\u([0-9a-fA-F]{4})", delegate(Match m)
            {
                return ((char)Convert.ToInt32(m.Groups[1].Value, 16)).ToString();
            });
        }

        static CheckResult FromUbilling(string region)
        {
            if (region == "Автономна Республіка Крим")
                throw new Exception("для Криму потрібен сервер");
            string name = region == "м. Севастополь" ? "Севастополь" : region;
            string body = DecodeJsonEscapes(Download("https://ubilling.net.ua/aerialalerts/"));
            Match m = Regex.Match(body, "\"" + Regex.Escape(name) + "\"\\s*:\\s*\\{([^}]*)\\}", RegexOptions.IgnoreCase);
            if (!m.Success) throw new Exception("область не знайдено у відповіді");
            Match a = Regex.Match(m.Groups[1].Value, "\"alertnow\"\\s*:\\s*(true|false)", RegexOptions.IgnoreCase);
            if (!a.Success) throw new Exception("неочікувана відповідь");
            CheckResult r = new CheckResult();
            r.Source = "ubilling.net.ua";
            r.State = a.Groups[1].Value.ToLower() == "true" ? AlertState.Alert : AlertState.Clear;
            Match ch = Regex.Match(m.Groups[1].Value, "\"changed\"\\s*:\\s*\"([^\"]+)\"");
            DateTime dt;
            if (r.State == AlertState.Alert && ch.Success &&
                DateTime.TryParse(ch.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture,
                    System.Globalization.DateTimeStyles.AssumeLocal, out dt))
                r.Since = dt;
            return r;
        }
    }

    // ---------------- Потік NEPTUN (WebSocket) ----------------
    // NEPTUN надсилає зміну тривог одразу, без опитування. У .NET 4 на Windows 7 немає ClientWebSocket,
    // тому — мінімальний WebSocket-клієнт на TcpClient + SslStream (з підтримкою системного проксі).
    class StreamClient
    {
        public string Url;
        readonly string logName;
        public StreamClient(string logName, string url) { this.logName = logName; Url = url; }
        readonly object lk = new object();
        Dictionary<string, object> nep;
        string sig = "";
        bool connected, haveData;   // haveData — після (пере)підключення вже прийшов перелік тривог
        DateTime lastMsg = DateTime.MinValue;
        Thread thread;
        public string Status = "";
        /// <summary>Викликається з фонового потоку, коли змінився перелік тривог.</summary>
        public event Action Changed;

        /// <summary>Потік підключений, дані отримано, і сервер NEPTUN подавав ознаки життя протягом 45 с.</summary>
        public bool Fresh
        {
            get { lock (lk) return connected && haveData && nep != null && (DateTime.UtcNow - lastMsg).TotalSeconds < 35; }
        }

        public Dictionary<string, object> Nep { get { lock (lk) return nep; } }

        public void Start()
        {
            if (thread != null) return;
            thread = new Thread(Loop);
            thread.IsBackground = true;
            thread.Name = logName;
            thread.Start();
        }

        void Loop()
        {
            int fails = 0;
            while (true)
            {
                DateTime t0 = DateTime.UtcNow;
                try { RunOnce(); SetStatus("закрито сервером"); }
                catch (Exception ex) { SetStatus(AlertSource.Short(ex.Message)); }
                lock (lk) connected = false;
                if ((DateTime.UtcNow - t0).TotalSeconds > 60) fails = 0;
                fails++;
                Thread.Sleep(Math.Min(60, 2 * fails) * 1000);   // 2, 4, 6 … до 60 с
            }
        }

        void SetStatus(string st)
        {
            if (st != Status) Log.W(logName + " " + st);
            Status = st;
        }

        static void ReadFull(Stream st, byte[] b, int n)
        {
            int o = 0;
            while (o < n)
            {
                int r = st.Read(b, o, n - o);
                if (r <= 0) throw new IOException("з'єднання закрито");
                o += r;
            }
        }

        static string ReadHeaders(Stream st)
        {
            StringBuilder sb = new StringBuilder();
            byte[] one = new byte[1];
            while (sb.Length < 16384)
            {
                ReadFull(st, one, 1);
                sb.Append((char)one[0]);
                if (sb.Length >= 4 && sb[sb.Length - 1] == '\n' && sb[sb.Length - 2] == '\r' && sb[sb.Length - 3] == '\n' && sb[sb.Length - 4] == '\r')
                    return sb.ToString();
            }
            throw new IOException("завеликі заголовки");
        }

        static readonly Random rnd = new Random();

        static void Send(Stream st, int op, byte[] data)
        {
            MemoryStream f = new MemoryStream();
            f.WriteByte((byte)(0x80 | op));
            if (data.Length < 126) f.WriteByte((byte)(0x80 | data.Length));
            else if (data.Length < 65536) { f.WriteByte(0x80 | 126); f.WriteByte((byte)(data.Length >> 8)); f.WriteByte((byte)data.Length); }
            else
            {
                f.WriteByte(0x80 | 127);
                for (int i = 7; i >= 0; i--) f.WriteByte((byte)((long)data.Length >> (8 * i)));
            }
            byte[] mask = new byte[4];
            lock (rnd) rnd.NextBytes(mask);
            f.Write(mask, 0, 4);
            for (int i = 0; i < data.Length; i++) f.WriteByte((byte)(data[i] ^ mask[i & 3]));
            byte[] b = f.ToArray();
            st.Write(b, 0, b.Length);
            st.Flush();
        }

        static Uri SystemProxy(Uri target)
        {
            try
            {
                IWebProxy p = WebRequest.GetSystemWebProxy();
                if (p == null || p.IsBypassed(target)) return null;
                Uri pu = p.GetProxy(target);
                if (pu == null || pu.Host.Equals(target.Host, StringComparison.OrdinalIgnoreCase)) return null;
                return pu;
            }
            catch { return null; }
        }

        void RunOnce()
        {
            Uri u = new Uri(Url.Replace("wss://", "https://").Replace("ws://", "http://"));
            string host = u.Host;
            int port = u.Port;
            using (TcpClient tcp = new TcpClient())
            {
                tcp.NoDelay = true;
                Uri proxy = SystemProxy(u);
                NetworkStream ns;
                if (proxy != null)
                {
                    tcp.Connect(proxy.Host, proxy.Port);
                    ns = tcp.GetStream();
                    ns.ReadTimeout = 15000;
                    byte[] c = Encoding.ASCII.GetBytes("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n\r\n");
                    ns.Write(c, 0, c.Length);
                    string ph = ReadHeaders(ns);
                    if (!Regex.IsMatch(ph, @"^HTTP/1\.\d 200")) throw new IOException("проксі не пропускає потік");
                }
                else
                {
                    tcp.Connect(host, port);
                    ns = tcp.GetStream();
                }
                ns.ReadTimeout = 15000;
                ns.WriteTimeout = 15000;
                Stream st = ns;
                SslStream ssl = null;
                if (u.Scheme == "https")
                {
                    ssl = new SslStream(ns, false);
                    SslProtocols prot;
                    try { prot = (SslProtocols)3072 | (SslProtocols)768 | SslProtocols.Tls; }
                    catch { prot = SslProtocols.Tls; }
                    ssl.AuthenticateAsClient(host, null, prot, true);
                    st = ssl;
                }
                try
                {
                    byte[] k = new byte[16];
                    lock (rnd) rnd.NextBytes(k);
                    string req = "GET " + u.PathAndQuery + " HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + Convert.ToBase64String(k) + "\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "User-Agent: Trevoga-Win/" + Updater.Current + " (+https://github.com/repaulshadow/spokiy)\r\n\r\n";
                    byte[] rb = Encoding.ASCII.GetBytes(req);
                    st.Write(rb, 0, rb.Length);
                    st.Flush();
                    string h = ReadHeaders(st);
                    if (!Regex.IsMatch(h, @"^HTTP/1\.\d 101")) throw new IOException("NEPTUN: " + h.Split('\r')[0]);
                    ns.ReadTimeout = 40000;      // heartbeat приходить приблизно раз на 15 с
                    lock (lk) { connected = true; haveData = false; lastMsg = DateTime.UtcNow; }
                    SetStatus("підключено");

                    byte[] hdr = new byte[8];
                    MemoryStream msg = new MemoryStream();
                    int msgOp = 0;
                    while (true)
                    {
                        ReadFull(st, hdr, 2);
                        bool fin = (hdr[0] & 0x80) != 0;
                        int op = hdr[0] & 0x0F;
                        bool masked = (hdr[1] & 0x80) != 0;
                        long len = hdr[1] & 0x7F;
                        if (len == 126) { ReadFull(st, hdr, 2); len = (hdr[0] << 8) | hdr[1]; }
                        else if (len == 127)
                        {
                            ReadFull(st, hdr, 8);
                            len = 0;
                            for (int i = 0; i < 8; i++) len = (len << 8) | hdr[i];
                        }
                        if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("завелике повідомлення");
                        byte[] mk = null;
                        if (masked) { mk = new byte[4]; ReadFull(st, mk, 4); }
                        byte[] pl = new byte[len];
                        ReadFull(st, pl, (int)len);
                        if (masked) for (int i = 0; i < pl.Length; i++) pl[i] ^= mk[i & 3];
                        lock (lk) lastMsg = DateTime.UtcNow;

                        if (op == 8) { try { Send(st, 8, new byte[0]); } catch { } return; }
                        if (op == 9) { Send(st, 10, pl); continue; }
                        if (op == 10) continue;
                        if (op == 1 || op == 2) { msg.SetLength(0); msgOp = op; }
                        else if (op != 0) continue;
                        msg.Write(pl, 0, pl.Length);
                        if (!fin) continue;
                        if (msgOp == 1) Handle(Encoding.UTF8.GetString(msg.GetBuffer(), 0, (int)msg.Length));
                        msg.SetLength(0);
                    }
                }
                finally { if (ssl != null) ssl.Dispose(); }
            }
        }

        void Handle(string text)
        {
            // Потік надсилає й треки дронів (великі повідомлення) — розбираємо лише «alerts»
            string head = text.Length > 200 ? text.Substring(0, 200) : text;
            Match m = Regex.Match(head, "\"type\"\\s*:\\s*\"([A-Za-z_]+)\"");
            if (m.Success ? m.Groups[1].Value != "alerts" : text.IndexOf("\"raions\"", StringComparison.Ordinal) < 0) return;
            Dictionary<string, object> j = Json.Parse(text) as Dictionary<string, object>;
            if (j == null) return;
            object d;
            Dictionary<string, object> data = j.TryGetValue("data", out d) ? d as Dictionary<string, object> : j;
            Dictionary<string, object> n = AlertSource.NeptunToNep(data);
            if (n == null) return;
            string s2 = Signature(n);
            bool changed;
            lock (lk)
            {
                changed = nep == null || s2 != sig || !haveData;
                nep = n;
                sig = s2;
                haveData = true;
            }
            if (changed)
            {
                Action h = Changed;
                if (h != null) try { h(); } catch { }
            }
        }

        static string Signature(Dictionary<string, object> n)
        {
            StringBuilder sb = new StringBuilder();
            foreach (string key in new string[] { "r", "o", "c" })
            {
                if (!n.ContainsKey(key)) continue;
                sb.Append(key).Append(':');
                foreach (object e in (List<object>)n[key])
                {
                    foreach (object x in (List<object>)e)
                    {
                        List<object> l = x as List<object>;
                        if (l != null) foreach (object t in l) sb.Append(t).Append(',');
                        else sb.Append(x);
                        sb.Append('|');
                    }
                    sb.Append('\n');
                }
            }
            return sb.ToString();
        }
    }


    // Потік NEPTUN і потік нашого сервера (офіційні дані UkraineAlarm у тому ж форматі)
    static class NeptunStream
    {
        public static readonly StreamClient C = new StreamClient("STREAM", "wss://neptun.in.ua/api/v1/stream");
        public static string Url { get { return C.Url; } set { C.Url = value; } }
        public static bool Fresh { get { return C.Fresh; } }
        public static Dictionary<string, object> Nep { get { return C.Nep; } }
        public static string Status { get { return C.Status; } }
        public static event Action Changed { add { C.Changed += value; } remove { C.Changed -= value; } }
        public static void Start() { C.Start(); }
    }

    static class UaStream
    {
        public static readonly StreamClient C = new StreamClient("UASTREAM", "");
        public static string Url { get { return C.Url; } set { C.Url = value; } }
        public static bool Fresh { get { return C.Url.Length > 0 && C.Fresh; } }
        public static Dictionary<string, object> Nep { get { return C.Nep; } }
        public static string Status { get { return C.Status; } }
        public static event Action Changed { add { C.Changed += value; } remove { C.Changed -= value; } }
        public static void Start() { if (C.Url.Length > 0) C.Start(); }
    }

    // ---------------- Звуки ----------------
    static class Sounds
    {
        static SoundPlayer player;
        static bool mciOpen;
        static int gen = 0;                       // меняется при Stop — отменяет очередь «голос → сирена»
        public static string Voice = "tetiana";   // tetiana, mykyta, off
        public static Control Ui;                 // окно, в потоке которого играем основные звуки
        public const int KindNone = -1, KindAlarm = 0, KindClear = 1, KindBallistic = 2;

        [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
        static extern int mciSendString(string command, StringBuilder ret, int retLen, IntPtr callback);

        public static void Stop()
        {
            gen++;
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

        // Срочный сигнал «Балістика»: частые чередующиеся высокие тона (~8 сек)
        static void MainBallistic()
        {
            try
            {
                string f = Path.Combine(Application.StartupPath, "ballistic.wav");
                string m = Path.Combine(Application.StartupPath, "ballistic.mp3");
                if (File.Exists(m) && mciSendString("open \"" + m + "\" type mpegvideo alias trevoga_snd", null, 0, IntPtr.Zero) == 0)
                {
                    mciOpen = true;
                    mciSendString("play trevoga_snd", null, 0, IntPtr.Zero);
                    return;
                }
                player = File.Exists(f) ? new SoundPlayer(f) : new SoundPlayer(new MemoryStream(Ballistic()));
                player.Play();
            }
            catch { try { SystemSounds.Hand.Play(); } catch { } }
        }

        static byte[] Ballistic()
        {
            const double T = 8, beep = 0.12;
            int n = (int)(Rate * T);
            short[] buf = new short[n];
            for (int i = 0; i < n; i++)
            {
                double t = (double)i / Rate;
                int k = (int)(t / beep);
                double local = t - k * beep;
                bool burstGap = (t % 2.0) > 1.6;               // короткая пауза каждые 2 сек
                double f = (k % 2 == 0) ? 1250 : 1650;
                double env = burstGap ? 0 : Math.Min(1.0, local / 0.005) * Math.Min(1.0, (beep - local) / 0.01);
                double v = Math.Sin(2 * Math.PI * f * t) + 0.35 * Math.Sin(6 * Math.PI * f * t);
                buf[i] = (short)(Math.Tanh(1.5 * v) / Math.Tanh(1.5) * env * 0.85 * 32767);
            }
            return ToWav(buf);
        }

        static void MainSound(bool alarm)
        {
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

        // ---- Голос: клипы вшиты в exe как ресурсы voice.<голос>_<фраза>.wav (22050 Гц, моно, 16 бит)
        static short[] ReadWav(Stream st)
        {
            BinaryReader r = new BinaryReader(st);
            r.ReadBytes(12);                                      // RIFF....WAVE
            while (st.Position < st.Length)
            {
                string id = Encoding.ASCII.GetString(r.ReadBytes(4));
                int len = r.ReadInt32();
                if (id == "data")
                {
                    short[] d = new short[len / 2];
                    for (int i = 0; i < d.Length; i++) d[i] = r.ReadInt16();
                    return d;
                }
                r.ReadBytes(len + (len & 1));
            }
            return new short[0];
        }

        static short[] Clip(string key)
        {
            try
            {
                Stream st = typeof(Sounds).Assembly.GetManifestResourceStream("voice." + Voice + "_" + key + ".wav");
                if (st == null) return null;
                using (st) return ReadWav(st);
            }
            catch { return null; }
        }

        // Склеивает фразы с паузами в один WAV. null — если голос выключен или фраз нет.
        static byte[] VoiceWav(List<string> keys)
        {
            if (Voice == "off" || keys == null || keys.Count == 0) return null;
            List<short> all = new List<short>();
            foreach (string k in keys)
            {
                short[] c = Clip(k);
                if (c == null) continue;
                all.AddRange(c);
                all.AddRange(new short[(int)(Rate * 0.25)]);
            }
            if (all.Count == 0) return null;
            all.AddRange(new short[(int)(Rate * 0.3)]);
            return ToWav(all.ToArray());
        }

        static void PlayMain(int kind)
        {
            if (kind == KindAlarm) MainSound(true);
            else if (kind == KindClear) MainSound(false);
            else if (kind == KindBallistic) MainBallistic();
        }

        // Сначала голос (фразы keys), потом основной звук kind
        public static void Play(List<string> keys, int kind)
        {
            Stop();
            int my = gen;
            byte[] voice = VoiceWav(keys);
            if (voice == null) { PlayMain(kind); return; }
            Thread t = new Thread(delegate()
            {
                try
                {
                    SoundPlayer vp = new SoundPlayer(new MemoryStream(voice));
                    player = vp;
                    vp.PlaySync();
                }
                catch { }
                if (my != gen || kind == KindNone) return;
                try
                {
                    if (Ui != null && Ui.IsHandleCreated)
                        Ui.BeginInvoke((MethodInvoker)delegate { if (my == gen) PlayMain(kind); });
                    else PlayMain(kind);
                }
                catch { }
            });
            t.IsBackground = true;
            t.Start();
        }

        static List<string> L(params string[] a) { return new List<string>(a); }

        // Готовые сценарии
        public static void Alarm(List<string> threats)
        {
            List<string> k = L("alert");
            int n = 0;
            foreach (string t in threats) if (n++ < 4) k.Add(t);
            k.Add("shelter");
            Play(k, KindAlarm);
        }

        public static void Ballistic(bool alertStarted, List<string> threats)
        {
            List<string> k = alertStarted ? L("alert") : L("attention");
            k.Add("ballistic_missiles");
            int n = 0;
            foreach (string t in threats) if (t != "ballistic_missiles" && n++ < 3) k.Add(t);
            k.Add("now");
            Play(k, KindBallistic);
        }

        public static void NewThreats(List<string> added)
        {
            List<string> k = L("attention");
            k.AddRange(added);
            Play(k, KindNone);
        }

        public static void Clear()
        {
            Play(L("clear"), KindClear);
        }

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

    // ---------------- Малювання ----------------
    static class Gfx
    {
        public static GraphicsPath Round(RectangleF r, float rad)
        {
            GraphicsPath p = new GraphicsPath();
            float d = rad * 2;
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        public static Color Mix(Color a, Color b, float k)
        {
            return Color.FromArgb((int)(a.R + (b.R - a.R) * k), (int)(a.G + (b.G - a.G) * k), (int)(a.B + (b.B - a.B) * k));
        }

        public static readonly Color Bg = Color.FromArgb(243, 244, 246);
        public static readonly Color Text = Color.FromArgb(17, 24, 39);
        public static readonly Color Muted = Color.FromArgb(107, 114, 128);
        public static readonly Color Line = Color.FromArgb(229, 231, 235);
        public static readonly Color Red = Color.FromArgb(220, 38, 38);
        public static readonly Color DarkRed = Color.FromArgb(153, 27, 27);
        public static readonly Color Yellow = Color.FromArgb(217, 119, 6);      // жовтий рівень (дрони)
        public static readonly Color Purple = Color.FromArgb(124, 58, 237);    // балістика (наша позначка)
        public static readonly Color Green = Color.FromArgb(22, 163, 74);
        public static readonly Color Gray = Color.FromArgb(107, 114, 128);

        // Круглий значок для трею та вікна
        public static Icon Dot(Color c, int size)
        {
            Bitmap b = new Bitmap(size, size);
            using (Graphics g = Graphics.FromImage(b))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                float m = size / 16f;
                using (SolidBrush br = new SolidBrush(c)) g.FillEllipse(br, m, m, size - 2 * m, size - 2 * m);
                using (Pen pen = new Pen(Color.FromArgb(200, 255, 255, 255), Math.Max(1f, size / 12f)))
                    g.DrawEllipse(pen, m, m, size - 2 * m, size - 2 * m);
                // маленький «сигнал» усередині
                using (SolidBrush w = new SolidBrush(Color.White))
                {
                    float cx = size / 2f, cy = size / 2f, r = size * 0.14f;
                    g.FillEllipse(w, cx - r, cy - r, 2 * r, 2 * r);
                }
            }
            return Icon.FromHandle(b.GetHicon());
        }
    }

    // Велика картка статусу
    class StatusCard : Control
    {
        public string Title = "Перевірка…";
        public string Place = "";
        public string Detail = "";
        public List<string> Chips = new List<string>();
        public bool HotFirstChip = false;     // перша плашка — балістика, виділяємо
        public Color HotColor = Gfx.DarkRed;
        public Color CardColor = Gfx.Gray;

        public StatusCard()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                     ControlStyles.ResizeRedraw | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Gfx.Bg;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            RectangleF r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (GraphicsPath p = Gfx.Round(r, 16))
            using (LinearGradientBrush br = new LinearGradientBrush(r, Gfx.Mix(CardColor, Color.White, 0.08f),
                       Gfx.Mix(CardColor, Color.Black, 0.18f), LinearGradientMode.ForwardDiagonal))
                g.FillPath(br, p);

            float x = 22, y = 18;
            using (Font fTitle = new Font("Segoe UI Semibold", 20f, FontStyle.Bold))
            using (Font fPlace = new Font("Segoe UI", 10.5f))
            using (Font fDetail = new Font("Segoe UI", 9.5f))
            using (SolidBrush white = new SolidBrush(Color.White))
            using (SolidBrush soft = new SolidBrush(Color.FromArgb(225, 255, 255, 255)))
            {
                g.DrawString(Title, fTitle, white, new RectangleF(x, y, Width - 2 * x, 40));
                y += 42;
                if (Place.Length > 0) { g.DrawString(Place, fPlace, soft, new RectangleF(x, y, Width - 2 * x, 22)); y += 22; }
                if (Detail.Length > 0) { g.DrawString(Detail, fDetail, soft, new RectangleF(x, y, Width - 2 * x, 20)); y += 22; }
            }

            if (Chips.Count == 0) return;
            float cx = 22, cy = Height - 22 - 26;
            using (Font fChip = new Font("Segoe UI Semibold", 9.5f, FontStyle.Bold))
            {
                // переносимо плашки на другий рядок, якщо не влазять
                List<RectangleF> rects = new List<RectangleF>();
                float rowY = 0;
                foreach (string c in Chips)
                {
                    SizeF sz = g.MeasureString(c, fChip);
                    float w = sz.Width + 18;
                    if (cx + w > Width - 18 && cx > 22) { cx = 22; rowY += 30; }
                    rects.Add(new RectangleF(cx, rowY, w, 26));
                    cx += w + 8;
                }
                float shift = cy - rowY;
                for (int i = 0; i < Chips.Count; i++)
                {
                    RectangleF cr = rects[i];
                    cr.Y += shift;
                    bool hot = HotFirstChip && i == 0;
                    using (GraphicsPath p = Gfx.Round(cr, 13))
                    using (SolidBrush b = new SolidBrush(hot ? Color.White : Color.FromArgb(55, 255, 255, 255)))
                        g.FillPath(b, p);
                    using (SolidBrush t = new SolidBrush(hot ? HotColor : Color.White))
                    {
                        StringFormat sf = new StringFormat();
                        sf.Alignment = StringAlignment.Center;
                        sf.LineAlignment = StringAlignment.Center;
                        g.DrawString(Chips[i], fChip, t, cr, sf);
                    }
                }
            }
        }
    }

    // Біла картка-секція із заголовком
    class Card : Panel
    {
        public string Caption = "";

        public Card()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                     ControlStyles.ResizeRedraw | ControlStyles.UserPaint, true);
            BackColor = Gfx.Bg;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            RectangleF r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (GraphicsPath p = Gfx.Round(r, 12))
            {
                using (SolidBrush b = new SolidBrush(Color.White)) g.FillPath(b, p);
                using (Pen pen = new Pen(Gfx.Line)) g.DrawPath(pen, p);
            }
            if (Caption.Length > 0)
                using (Font f = new Font("Segoe UI Semibold", 9f, FontStyle.Bold))
                using (SolidBrush b = new SolidBrush(Gfx.Muted))
                    g.DrawString(Caption.ToUpperInvariant(), f, b, 16, 12);
        }
    }

    // Пласка кнопка із заокругленням
    class FlatBtn : Control
    {
        public Color Fill = Color.White, Fore = Gfx.Text, Border = Gfx.Line;
        bool hover, down;

        public FlatBtn()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                     ControlStyles.ResizeRedraw | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Gfx.Bg;
            Cursor = Cursors.Hand;
            Font = new Font("Segoe UI Semibold", 9.75f, FontStyle.Bold);
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            Color f = down ? Gfx.Mix(Fill, Color.Black, 0.12f) : hover ? Gfx.Mix(Fill, Color.Black, 0.05f) : Fill;
            RectangleF r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (GraphicsPath p = Gfx.Round(r, 9))
            {
                using (SolidBrush b = new SolidBrush(f)) g.FillPath(b, p);
                using (Pen pen = new Pen(Border)) g.DrawPath(pen, p);
            }
            StringFormat sf = new StringFormat();
            sf.Alignment = StringAlignment.Center;
            sf.LineAlignment = StringAlignment.Center;
            using (SolidBrush t = new SolidBrush(Fore)) g.DrawString(Text, Font, t, r, sf);
        }
    }

    // ---------------- Вікно ----------------
    // Таймер, що не залежить від видимості вікна: відлік у пуловому потоці, подія — у потоці вікна.
    // (Звичайний таймер WinForms у прихованому вікні подекуди не спрацьовує.)
    class UiTimer
    {
        readonly Control ui;
        System.Threading.Timer t;
        int interval = 1000;
        volatile bool running;
        public event EventHandler Tick;

        public UiTimer(Control ui) { this.ui = ui; }

        public int Interval
        {
            get { return interval; }
            set { interval = Math.Max(100, value); if (running && t != null) t.Change(interval, interval); }
        }

        public void Start()
        {
            running = true;
            if (t == null) t = new System.Threading.Timer(Fire, null, interval, interval);
            else t.Change(interval, interval);
        }

        public void Stop()
        {
            running = false;
            if (t != null) t.Change(Timeout.Infinite, Timeout.Infinite);
        }

        void Fire(object state)
        {
            if (!running) return;
            try
            {
                if (ui.IsHandleCreated && !ui.IsDisposed)
                    ui.BeginInvoke((MethodInvoker)delegate
                    {
                        if (!running) return;
                        EventHandler h = Tick;
                        if (h != null) h(this, EventArgs.Empty);
                    });
            }
            catch { }
        }
    }

    class MainForm : Form
    {
        Settings settings;
        StatusCard card;
        ComboBox cbRegion, cbRaion, cbVoice;
        CheckBox chkSound, chkAutorun, chkPartial;
        Label lblFooter;
        NotifyIcon tray;
        UiTimer timer, configTimer, updateTimer;
        // Усі таймери тримаємо тут: таймер без посилання збирач сміття може знищити до спрацювання
        readonly List<UiTimer> timers = new List<UiTimer>();
        bool justUpdated = false, updateReady = false, updateBusy = false;
        string updateVersion = null;
        Icon icoRed, icoGreen, icoGray, icoYellow, icoPurple;
        string level = "";
        AlertState state = AlertState.Unknown;
        AlertState lastKnown = AlertState.Unknown;      // останній стан, коли дані були
        List<string> lastKnownThreats = new List<string>();
        string lastKnownLevel = "";
        List<string> threats = new List<string>();
        DateTime? since;
        DateTime? clearedAt;
        string server = "";
        bool checking = false, firstCheck = true, reallyExit = false, startHidden, loading = true;
        int requestId = 0;

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        public const string ShowEventName = "Trevoga_ShowWindow_Event";
        EventWaitHandle showEvent;

        public MainForm(bool startHidden)
        {
            this.startHidden = startHidden;
            settings = Settings.Load();
            server = settings.Server.Length > 0 ? settings.Server : AppConfig.Embedded();

            icoRed = Gfx.Dot(Gfx.Red, 32);
            icoYellow = Gfx.Dot(Gfx.Yellow, 32);
            icoPurple = Gfx.Dot(Gfx.Purple, 32);
            icoGreen = Gfx.Dot(Gfx.Green, 32);
            icoGray = Gfx.Dot(Gfx.Gray, 32);

            Text = "Тривога";
            Font = new Font("Segoe UI", 9.75f);
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96f, 96f);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Gfx.Bg;
            ClientSize = new Size(460, 636);
            Icon = icoGray;

            // --- статус
            card = new StatusCard();
            card.Location = new Point(16, 16);
            card.Size = new Size(428, 178);
            Controls.Add(card);

            // --- місце
            Card place = new Card();
            place.Caption = "Місце";
            place.Location = new Point(16, 208);
            place.Size = new Size(428, 118);
            Controls.Add(place);

            place.Controls.Add(FieldLabel("Область", 16, 44));
            cbRegion = Combo(96, 40);
            cbRegion.Items.AddRange(AlertSource.Regions);
            place.Controls.Add(cbRegion);

            place.Controls.Add(FieldLabel("Район", 16, 80));
            cbRaion = Combo(96, 76);
            place.Controls.Add(cbRaion);

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

            // --- сповіщення
            Card notif = new Card();
            notif.Caption = "Сповіщення";
            notif.Location = new Point(16, 340);
            notif.Size = new Size(428, 166);
            Controls.Add(notif);

            notif.Controls.Add(FieldLabel("Голос", 16, 44));
            cbVoice = Combo(96, 40);
            cbVoice.Items.AddRange(new object[] { "Жіночий (Тетяна)", "Чоловічий (Микита)", "Без голосу, лише сирена" });
            cbVoice.SelectedIndex = settings.Voice == "mykyta" ? 1 : settings.Voice == "off" ? 2 : 0;
            Sounds.Voice = settings.Voice;
            cbVoice.SelectedIndexChanged += delegate
            {
                settings.Voice = cbVoice.SelectedIndex == 1 ? "mykyta" : cbVoice.SelectedIndex == 2 ? "off" : "tetiana";
                Sounds.Voice = settings.Voice;
                settings.Save();
            };
            notif.Controls.Add(cbVoice);

            chkSound = Check("Звук увімкнено", 16, 78, settings.Sound);
            chkSound.CheckedChanged += delegate
            {
                settings.Sound = chkSound.Checked;
                settings.Save();
                if (!settings.Sound) Sounds.Stop();
            };
            notif.Controls.Add(chkSound);

            chkAutorun = Check("Запускати разом з Windows", 16, 104, IsAutorun());
            chkAutorun.CheckedChanged += delegate
            {
                if (loading) return;
                if (chkAutorun.Checked && RunningFromTemp())
                {
                    MessageBox.Show("Програму запущено прямо з архіву. Спершу розпакуйте її в постійну папку " +
                        "(наприклад C:\\Trevoga) і запустіть звідти — інакше автозапуск не спрацює.", "Тривога",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    loading = true; chkAutorun.Checked = false; loading = false;
                    return;
                }
                if (!SetAutorun(chkAutorun.Checked)) { loading = true; chkAutorun.Checked = IsAutorun(); loading = false; }
            };
            notif.Controls.Add(chkAutorun);

            chkPartial = Check("Сповіщати й про тривогу в окремих громадах", 16, 130, settings.Partial);
            chkPartial.CheckedChanged += delegate
            {
                settings.Partial = chkPartial.Checked;
                SettingsChanged();
            };
            notif.Controls.Add(chkPartial);

            // --- кнопки
            FlatBtn bAlarm = Button("Тест тривоги", 16, 520, 136);
            bAlarm.Click += delegate { Sounds.Alarm(threats.Count > 0 ? threats : new List<string>(new string[] { "drones" })); };
            FlatBtn bClear = Button("Тест відбою", 162, 520, 136);
            bClear.Click += delegate { Sounds.Clear(); };
            FlatBtn bStop = Button("Тиша", 308, 520, 136);
            bStop.Fill = Gfx.Text; bStop.Fore = Color.White; bStop.Border = Gfx.Text;
            bStop.Click += delegate { Sounds.Stop(); };

            lblFooter = new Label();
            lblFooter.Location = new Point(16, 566);
            lblFooter.Size = new Size(428, 40);
            lblFooter.ForeColor = Gfx.Muted;
            lblFooter.Font = new Font("Segoe UI", 8.5f);
            lblFooter.Text = "Хрестик ховає програму до годинника. Вихід — правою кнопкою на значку.";
            Controls.Add(lblFooter);

            // Обов'язкове посилання на NEPTUN (умова використання їхніх даних)
            LinkLabel lnk = new LinkLabel();
            lnk.Text = "v" + Updater.Current.ToString(3) + " · Дані: alerts.in.ua · Карта повітряних тривог — NEPTUN (neptun.in.ua)";
            lnk.Location = new Point(16, 610);
            lnk.AutoSize = true;
            lnk.Font = new Font("Segoe UI", 8.5f);
            lnk.LinkColor = Gfx.Muted;
            lnk.ActiveLinkColor = Gfx.Text;
            lnk.LinkArea = new LinkArea(lnk.Text.IndexOf("Карта"), lnk.Text.Length - lnk.Text.IndexOf("Карта"));
            lnk.LinkClicked += delegate { try { System.Diagnostics.Process.Start("https://neptun.in.ua/"); } catch { } };
            Controls.Add(lnk);

            // --- трей
            ContextMenu menu = new ContextMenu();
            menu.MenuItems.Add("Відкрити", delegate { ShowWindow(); });
            menu.MenuItems.Add("Перевірити зараз", delegate { CheckNow(); });
            menu.MenuItems.Add("Тиша", delegate { Sounds.Stop(); });
            menu.MenuItems.Add("Перевірити оновлення", delegate { CheckUpdate(true); });
            menu.MenuItems.Add("-");
            menu.MenuItems.Add("Вихід", delegate { reallyExit = true; Close(); });
            tray = new NotifyIcon();
            tray.Icon = icoGray;
            tray.Text = "Тривога: перевірка…";
            tray.ContextMenu = menu;
            tray.Visible = true;
            tray.DoubleClick += delegate { ShowWindow(); };

            Sounds.Ui = this;

            // Сигнал від другої копії програми: показати вікно
            try
            {
                showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
                Thread w = new Thread(delegate()
                {
                    while (true)
                    {
                        showEvent.WaitOne();
                        try { BeginInvoke((MethodInvoker)delegate { ShowWindow(); }); } catch { }
                    }
                });
                w.IsBackground = true;
                w.Start();
            }
            catch { }

            // Автозапуск: переносимо старий запис із реєстру на ярлик і оновлюємо шлях, якщо програму перемістили
            if (IsAutorun() && !RunningFromTemp())
            {
                try { RemoveRunKey(); CreateShortcut(StartupLnk); } catch { }
            }

            // Запасний варіант для Windows 7: ще раз показати значок через хвилину після запуску
            if (startHidden)
            {
                UiTimer re = new UiTimer(this);
                timers.Add(re);
                re.Interval = 60000;
                re.Tick += delegate { re.Stop(); ReAddTray(); };
                re.Start();
            }

            // Потік NEPTUN: тривога і відбій приходять одразу, без очікування наступного опитування
            if (settings.Stream.Length > 0) NeptunStream.Url = settings.Stream;
            if (settings.Test) AlertSource.NeptunBase = server;
            NeptunStream.Changed += OnStreamChanged;
            NeptunStream.Start();
            // Потік нашого сервера: офіційні тривоги UkraineAlarm (webhook) — найшвидше джерело
            UaStream.Url = settings.UaStream.Length > 0 ? settings.UaStream : StreamUrlOf(server);
            UaStream.Changed += OnStreamChanged;
            UaStream.Start();
            Reporter.Server = server;
            Reporter.Id = settings.Id;
            Reporter.Send("start", "place", PlaceKey());
            // «Живий» раз на 30 хв: щоб у звіті відрізняти «комп'ютер вимкнений» від «програма пропустила»
            UiTimer alive = new UiTimer(this);
            alive.Interval = settings.Test ? 10000 : 30 * 60 * 1000;
            alive.Tick += delegate
            {
                Reporter.Send("alive", "place", PlaceKey(), "stream", NeptunStream.Fresh ? "1" : "0", "uastream", UaStream.Fresh ? "1" : "0",
                    "st", state.ToString(), "srv_ok", lastServerError == null ? "1" : "0");
            };
            alive.Start();
            timers.Add(alive);

            // Нова версія після оновлення працює хвилину — підтверджуємо, резервну копію можна прибрати
            UiTimer confirm = new UiTimer(this);
            timers.Add(confirm);
            confirm.Interval = settings.Test ? 15000 : 60000;
            confirm.Tick += delegate { confirm.Stop(); Updater.Confirm(); };
            confirm.Start();

            // Автотести: частий примусовий збір сміття, щоб помилки з втраченими посиланнями проявлялися одразу
            if (settings.Test)
            {
                UiTimer gc = new UiTimer(this);
                gc.Interval = 3000;
                gc.Tick += delegate { GC.Collect(); GC.WaitForPendingFinalizers(); };
                gc.Start();
                timers.Add(gc);
            }

            // Службовий сигнал для автотестів: штучний збій
            if (settings.Test)
                try
                {
                    EventWaitHandle crashEv = new EventWaitHandle(false, EventResetMode.AutoReset, "Trevoga_Test_Crash");
                    Thread ct = new Thread(delegate()
                    {
                        crashEv.WaitOne();
                        try { BeginInvoke((MethodInvoker)delegate { throw new InvalidOperationException("тестовий збій"); }); } catch { }
                    });
                    ct.IsBackground = true;
                    ct.Start();
                }
                catch { }

            timer = new UiTimer(this);
            timer.Interval = settings.Interval * 1000;
            timer.Tick += delegate { CheckNow(); Reporter.Flush(); };
            timer.Start();

            // адресу сервера оновлюємо з GitHub при запуску і раз на 6 годин
            configTimer = new UiTimer(this);
            configTimer.Interval = 6 * 3600 * 1000;
            configTimer.Tick += delegate { if (!settings.Test) RefreshConfig(); };
            configTimer.Start();

            // Автооновлення: через 2 хв після запуску і далі раз на 6 годин
            updateTimer = new UiTimer(this);
            updateTimer.Interval = settings.Test ? 5000 : 2 * 60 * 1000;
            updateTimer.Tick += delegate { updateTimer.Interval = settings.Test ? 10000 : 6 * 3600 * 1000; CheckUpdate(false); };
            updateTimer.Start();
            foreach (string a in Environment.GetCommandLineArgs())
                if (a.Equals("/updated", StringComparison.OrdinalIgnoreCase)) justUpdated = true;

            UpdateCard();
            settings.Save();
            loading = false;
        }

        Label FieldLabel(string text, int x, int y)
        {
            Label l = new Label();
            l.Text = text;
            l.Location = new Point(x, y);
            l.AutoSize = true;
            l.ForeColor = Gfx.Text;
            l.BackColor = Color.White;
            return l;
        }

        ComboBox Combo(int x, int y)
        {
            ComboBox c = new ComboBox();
            c.DropDownStyle = ComboBoxStyle.DropDownList;
            c.FlatStyle = FlatStyle.Flat;
            c.BackColor = Color.White;
            c.Location = new Point(x, y);
            c.Width = 316;
            c.MaxDropDownItems = 14;
            return c;
        }

        CheckBox Check(string text, int x, int y, bool value)
        {
            CheckBox c = new CheckBox();
            c.Text = text;
            c.Location = new Point(x, y);
            c.AutoSize = true;
            c.Checked = value;
            c.BackColor = Color.White;
            c.ForeColor = Gfx.Text;
            return c;
        }

        FlatBtn Button(string text, int x, int y, int w)
        {
            FlatBtn b = new FlatBtn();
            b.Text = text;
            b.Location = new Point(x, y);
            b.Size = new Size(w, 38);
            Controls.Add(b);
            return b;
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
            lastKnown = AlertState.Unknown;
            firstCheck = true;
            threats = new List<string>();
            level = "";
            since = null;
            clearedAt = null;
            requestId++;          // відповідь на старий запит (інше місце) не застосовуємо
            checking = false;
            UpdateCard();
            CheckNow();
        }

        static string StreamUrlOf(string srv)
        {
            if (string.IsNullOrEmpty(srv)) return "";
            return srv.TrimEnd('/').Replace("https://", "wss://").Replace("http://", "ws://") + "/stream";
        }

        // Місце для звітів: область|uid району|назва району
        string PlaceKey()
        {
            return settings.Region + "|" + settings.Raion + "|" + (settings.Raion != 0 ? AlertSource.RaionName(settings.Raion) : "");
        }

        string PlaceName()
        {
            string r = settings.Raion != 0 ? AlertSource.RaionName(settings.Raion) : null;
            return r != null ? r + ", " + settings.Region : settings.Region;
        }

        // manual = натиснули «Перевірити оновлення»
        void CheckUpdate(bool manual)
        {
            if (updateBusy) return;
            if (updateReady) { TryApplyUpdate(); return; }
            updateBusy = true;
            string srv = server;
            ThreadPool.QueueUserWorkItem(delegate
            {
                string ver = null, err = null;
                try { ver = Updater.Download(srv); }
                catch (Exception ex) { err = ex.Message; }
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        updateBusy = false;
                        if (ver != null)
                        {
                            updateReady = true;
                            updateVersion = ver;
                            TryApplyUpdate();
                        }
                        else if (manual)
                            MessageBox.Show(err != null ? "Не вдалося перевірити оновлення: " + err
                                : "У вас найновіша версія (" + Updater.Current + ").", "Тривога");
                    });
                }
                catch { updateBusy = false; }
            });
        }

        // Оновлюємося лише коли тривоги немає, щоб не перервати сирену
        void TryApplyUpdate()
        {
            if (!updateReady || state == AlertState.Alert) return;
            Log.W("UPDATE downloaded v" + updateVersion);
            if (Updater.Apply(!Visible))
            {
                reallyExit = true;
                Close();
            }
            else
            {
                updateReady = false;
                tray.ShowBalloonTip(8000, "Тривога", "Вийшла нова версія " + updateVersion +
                    ", але оновитися автоматично не вдалося. Завантажте її вручну.", ToolTipIcon.Warning);
            }
        }

        void RefreshConfig()
        {
            ThreadPool.QueueUserWorkItem(delegate
            {
                string s = AppConfig.Fetch();
                if (string.IsNullOrEmpty(s)) return;
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        if (s == server) return;
                        server = s;
                        Reporter.Server = s;
                        if (settings.UaStream.Length == 0) UaStream.Url = StreamUrlOf(s);
                        settings.Server = s;
                        settings.Save();
                        CheckNow();
                    });
                }
                catch { }
            });
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

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            BeginInvoke((MethodInvoker)delegate { if (!settings.Test) RefreshConfig(); CheckNow(); });
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
                tray.ShowBalloonTip(3000, "Тривога", "Програма далі стежить за тривогою.", ToolTipIcon.Info);
                return;
            }
            Log.W("EXIT " + e.CloseReason);
            Reporter.Send("stop", "reason", e.CloseReason.ToString(), "place", PlaceKey());
            Reporter.FlushSync(3000);
            timer.Stop();
            configTimer.Stop();
            Sounds.Stop();
            tray.Visible = false;
            tray.Dispose();
            base.OnFormClosing(e);
        }

        // Останні дані сервера (alerts.in.ua + NEPTUN); зводяться з потоком NEPTUN у Recompute()
        Dictionary<string, object> lastRoot;
        DateTime lastRootAt = DateTime.MinValue;
        string lastServerError = null;
        // Відстеження відбою за потоком NEPTUN
        string nepKey = "";
        AlertState prevNep = AlertState.Unknown;
        DateTime? nepClearedAt;
        DateTime nepSeenAt = DateTime.MinValue, uaSeenAt = DateTime.MinValue;   // час найсвіжіших даних кожного джерела
        AlertState prevUa = AlertState.Unknown;      // UkraineAlarm: попередній стан і час офіційного відбою
        DateTime? uaClearedAt;

        void CheckNow()
        {
            if (checking) return;
            checking = true;
            int myId = ++requestId;
            Settings snapshot = settings;
            string srv = server;
            ThreadPool.QueueUserWorkItem(delegate
            {
                Dictionary<string, object> root = null;
                string serverError = null;
                CheckResult fallback = null;
                string error = null;
                if (!string.IsNullOrEmpty(srv))
                {
                    try { root = AlertSource.FetchServer(srv); }
                    catch (Exception ex) { serverError = AlertSource.Short(ex.Message); }
                }
                // Без сервера і без потоку NEPTUN — запасні джерела
                if (root == null && !NeptunStream.Fresh && !UaStream.Fresh)
                {
                    try { fallback = AlertSource.Fallback(snapshot, serverError); }
                    catch (Exception ex) { error = ex.Message; }
                }
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        checking = false;
                        if (myId != requestId) return;
                        if (root != null) { lastRoot = root; lastRootAt = DateTime.UtcNow; lastServerError = null; }
                        else lastServerError = serverError ?? "сервер ще не налаштовано";
                        if (root == null && !NeptunStream.Fresh && !UaStream.Fresh) ApplyResult(fallback, error);
                        else Recompute();
                    });
                }
                catch { checking = false; }
            });
        }

        /// <summary>Зведення без мережі: останні дані сервера + свіжий потік NEPTUN. Викликається на кожну зміну в потоці.</summary>
        void Recompute()
        {
            bool live = NeptunStream.Fresh, uaLive = UaStream.Fresh;
            Dictionary<string, object> nep = live ? NeptunStream.Nep : null;
            Dictionary<string, object> ua = uaLive ? UaStream.Nep : null;
            Dictionary<string, object> root = new Dictionary<string, object>();
            bool rootFresh = lastRoot != null && (DateTime.UtcNow - lastRootAt).TotalSeconds < 90;
            if (rootFresh) foreach (KeyValuePair<string, object> kv in lastRoot) root[kv.Key] = kv.Value;
            // Не змішуємо свіже зі старим: якщо потік уже показав новіші дані, ніж остання відповідь сервера,
            // а тепер потоку немає — старі дані сервера про це джерело не беремо, доки не прийде новіша відповідь
            DateTime nowU = DateTime.UtcNow;
            if (nep != null) { root["neptun"] = nep; nepSeenAt = nowU; }
            else if (root.ContainsKey("neptun"))
            {
                if (lastRootAt < nepSeenAt) root.Remove("neptun"); else nepSeenAt = lastRootAt;
            }
            if (ua != null) { root["ua"] = ua; uaSeenAt = nowU; }
            else if (root.ContainsKey("ua") && root["ua"] is Dictionary<string, object>)
            {
                if (lastRootAt < uaSeenAt) root.Remove("ua"); else uaSeenAt = lastRootAt;
            }
            bool uaKnown = root.ContainsKey("ua") && root["ua"] is Dictionary<string, object>;
            if (!root.ContainsKey("air") && !root.ContainsKey("neptun") && !uaKnown) return;   // даних немає — дочекаємося опитування

            string key = settings.Region + "|" + settings.Raion + "|" + settings.Partial;
            if (key != nepKey) { nepKey = key; prevNep = AlertState.Unknown; nepClearedAt = null; prevUa = AlertState.Unknown; uaClearedAt = null; }
            if (nepClearedAt.HasValue && (DateTime.Now - nepClearedAt.Value).TotalSeconds > 90) nepClearedAt = null;
            if (uaClearedAt.HasValue && (DateTime.Now - uaClearedAt.Value).TotalSeconds > 180) uaClearedAt = null;

            CheckResult r;
            try
            {
                r = Merge(root, live);
                bool again = false;
                // Офіційний відбій UkraineAlarm — не чекаємо NEPTUN (запізнюється до ~2 хв) і alerts.in.ua
                if (uaKnown)
                {
                    if (prevUa == AlertState.Alert && r.UaState == AlertState.Clear) { uaClearedAt = DateTime.Now; again = true; }
                    else if (r.UaState == AlertState.Alert) uaClearedAt = null;
                    prevUa = r.UaState;
                }
                // NEPTUN дав відбій — не чекаємо alerts.in.ua
                if (live)
                {
                    if (prevNep == AlertState.Alert && r.NepState == AlertState.Clear) { nepClearedAt = DateTime.Now; again = true; }
                    else if (r.NepState == AlertState.Alert) nepClearedAt = null;
                    prevNep = r.NepState;
                }
                if (again) r = Merge(root, live);
            }
            catch (Exception ex) { ApplyResult(null, ex.Message); return; }
            r.PollS = rootFresh ? AlertSource.PollOf(lastRoot) : 0;
            if (live) r.Source = r.Source.Replace("NEPTUN", "NEPTUN наживо");
            if (uaLive) r.Source = r.Source.Replace("UkraineAlarm", "UkraineAlarm наживо");
            if (lastServerError != null)
                r.Note = "сервер недоступний (" + lastServerError + ")" + (r.Note.Length > 0 ? " · " + r.Note : "");
            ApplyResult(r, null);
        }

        CheckResult Merge(Dictionary<string, object> root, bool live)
        {
            DateTime? ignAin = live ? nepClearedAt : null;
            if (uaClearedAt.HasValue && (!ignAin.HasValue || uaClearedAt.Value > ignAin.Value)) ignAin = uaClearedAt;
            // NEPTUN запізнюється й на початку тривоги (до ~1 хв): його «нова» тривога в перші 90 с після офіційного
            // відбою — це зазвичай стара. Справді нову тривогу першим покаже сам UkraineAlarm.
            DateTime? ignNep = uaClearedAt.HasValue ? (DateTime?)uaClearedAt.Value.AddSeconds(90) : null;
            return AlertSource.FromRoot(settings, root, ignAin, ignNep);
        }

        void OnStreamChanged()
        {
            try
            {
                if (IsHandleCreated) BeginInvoke((MethodInvoker)delegate { Recompute(); });
            }
            catch { }
        }

        static string Ago(DateTime t)
        {
            TimeSpan d = DateTime.Now - t;
            if (d.TotalMinutes < 1) return "щойно";
            if (d.TotalHours < 1) return (int)d.TotalMinutes + " хв";
            return (int)d.TotalHours + " год " + d.Minutes + " хв";
        }

        string lastNote = "", lastSource = "", lastTime = "";
        bool lastDisagree = false;

        void UpdateCard()
        {
            card.Place = PlaceName();
            card.Chips = new List<string>();
            card.HotFirstChip = false;
            if (state == AlertState.Alert)
            {
                bool ballistic = threats.Contains("ballistic_missiles");
                bool yellow = level == "yellow" && !ballistic;
                card.Title = ballistic ? "БАЛІСТИКА!" : "ПОВІТРЯНА ТРИВОГА";
                card.CardColor = ballistic ? Gfx.Purple : yellow ? Gfx.Yellow : Gfx.Red;
                card.Detail = (ballistic ? "Червоний рівень · " : yellow ? "Жовтий рівень · " : level == "red" ? "Червоний рівень · " : "")
                    + (since.HasValue ? "триває " + Ago(since.Value) + " · з " + since.Value.ToString("HH:mm") : "пройдіть в укриття");
                if (lastDisagree) card.Detail += " · підтверджує 1 з 2 джерел";
                foreach (string t in threats) card.Chips.Add(AlertSource.ThreatTitle(t));
                card.HotFirstChip = ballistic;
                card.HotColor = ballistic ? Gfx.Purple : Gfx.DarkRed;
            }
            else if (state == AlertState.Clear)
            {
                card.Title = "Тривоги немає";
                card.CardColor = Gfx.Green;
                card.Detail = clearedAt.HasValue ? "Відбій о " + clearedAt.Value.ToString("HH:mm") : "Спокійно";
            }
            else if (lastNote.Length > 0 && !lastNote.StartsWith("!") && settings.Raion != 0)
            {
                card.Title = "Немає даних по району";
                card.CardColor = Gfx.Gray;
                card.Detail = lastNote;
            }
            else
            {
                card.Title = lastNote.StartsWith("!") ? "Немає зв'язку" : "Перевірка…";
                card.CardColor = Gfx.Gray;
                card.Detail = lastNote.StartsWith("!") ? lastNote.Substring(1) : "";
            }
            card.Invalidate();

            string foot = lastTime.Length > 0 ? "Оновлено о " + lastTime + " · " + lastSource : "";
            if (lastNote.Length > 0 && !lastNote.StartsWith("!") && state != AlertState.Unknown) foot += (foot.Length > 0 ? " · " : "") + lastNote;
            if (lastNote.StartsWith("!") && state != AlertState.Unknown) foot += " · немає зв'язку: " + lastNote.Substring(1);
            lblFooter.Text = (foot.Length > 0 ? foot + "\n" : "") + "Хрестик ховає програму до годинника. Вихід — правою кнопкою на значку.";

            Icon ic = state == AlertState.Alert
                ? (threats.Contains("ballistic_missiles") ? icoPurple : level == "yellow" ? icoYellow : icoRed)
                : state == AlertState.Clear ? icoGreen : icoGray;
            tray.Icon = ic;
            Icon = ic;
            string tip = (state == AlertState.Alert ? "ТРИВОГА: " : state == AlertState.Clear ? "Тривоги немає: " : "Тривога: ") + PlaceName();
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
        }

        void ApplyResult(CheckResult r, string error)
        {
            if (error != null || r == null)
            {
                string en = "!" + (error ?? "помилка");
                if (en != lastNote) Log.W("ERROR " + en.Substring(1));
                lastNote = en;
                UpdateCard();
                return;
            }
            lastNote = r.Note;
            lastDisagree = r.Disagree;
            if (r.PollS >= 5 && r.PollS <= 120 && timer.Interval != r.PollS * 1000) timer.Interval = r.PollS * 1000;
            lastSource = r.Source;
            lastTime = DateTime.Now.ToString("HH:mm:ss");

            // Порівнюємо з останнім ВІДОМИМ станом: коротка втрата зв'язку посеред тривоги
            // не повинна ні повторювати сирену, ні «з'їдати» відбій
            AlertState old = state == AlertState.Unknown ? lastKnown : state;
            List<string> oldThreats = threats;
            string oldLevel = level;
            if (state == AlertState.Unknown && lastKnown == AlertState.Alert) { oldThreats = lastKnownThreats; oldLevel = lastKnownLevel; }
            state = r.State;
            threats = r.Threats;
            level = r.Level;
            if (state == AlertState.Alert)
            {
                if (r.Since.HasValue) since = r.Since;
                else if (old != AlertState.Alert) since = firstCheck ? (DateTime?)null : DateTime.Now;
                clearedAt = null;
            }
            else if (state == AlertState.Clear)
            {
                since = null;
                if (old == AlertState.Alert) clearedAt = DateTime.Now;
            }
            UpdateCard();
            if (state != AlertState.Unknown) { lastKnown = state; lastKnownThreats = threats; lastKnownLevel = level; }
            if (state != old || level != oldLevel || string.Join(",", threats.ToArray()) != string.Join(",", oldThreats.ToArray()))
            {
                Log.W("STATE " + state + " level=" + level + " threats=" + string.Join(",", threats.ToArray())
                    + " src=" + r.Source + (r.Note.Length > 0 ? " note=" + r.Note : ""));
                Reporter.Send("state", "st", state.ToString(), "old", old.ToString(), "lv", level, "th", string.Join(",", threats.ToArray()),
                    "src", r.Source, "note", r.Note, "place", PlaceKey(),
                    "since", state == AlertState.Alert && r.Since.HasValue ? r.Since.Value.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") : "",
                    "first", firstCheck ? "1" : "0");
            }
            if (updateReady && state != AlertState.Alert) BeginInvoke((MethodInvoker)delegate { TryApplyUpdate(); });

            string place = PlaceName();
            List<string> titles = new List<string>();
            foreach (string t in threats) titles.Add(AlertSource.ThreatTitle(t));
            string th = string.Join(", ", titles.ToArray());

            if (justUpdated)
            {
                justUpdated = false;
                tray.ShowBalloonTip(5000, "Тривога", "Програму оновлено до версії " + Updater.Current + ".", ToolTipIcon.Info);
            }
            if (state == AlertState.Unknown) return;     // поки даних немає — нічого не озвучуємо
            if (firstCheck)
            {
                firstCheck = false;
                if (state == AlertState.Alert)
                    tray.ShowBalloonTip(5000, "Повітряна тривога", "Зараз триває тривога: " + place
                        + (th.Length > 0 ? "\n" + th : ""), ToolTipIcon.Warning);
                return;
            }

            List<string> added = new List<string>();
            if (state == AlertState.Alert)
                foreach (string t in threats) if (!oldThreats.Contains(t)) added.Add(t);

            if (added.Contains("ballistic_missiles"))
            {
                Log.W("SOUND ballistic"); Reporter.Send("sound", "kind", "ballistic", "place", PlaceKey()); if (settings.Sound) Sounds.Ballistic(old != AlertState.Alert, threats);
                tray.ShowBalloonTip(10000, "БАЛІСТИКА!", place + ". Негайно в укриття!", ToolTipIcon.Error);
            }
            else if (old != AlertState.Alert && state == AlertState.Alert)
            {
                Log.W("SOUND alarm"); Reporter.Send("sound", "kind", "alarm", "place", PlaceKey()); if (settings.Sound) Sounds.Alarm(threats);
                tray.ShowBalloonTip(10000, level == "yellow" ? "ПОВІТРЯНА ТРИВОГА — жовтий рівень" : "ПОВІТРЯНА ТРИВОГА!",
                    place + ". Пройдіть в укриття!" + (th.Length > 0 ? "\n" + th : ""), ToolTipIcon.Error);
            }
            else if (old == AlertState.Alert && state == AlertState.Alert && oldLevel == "yellow" && level == "red")
            {
                // Рівень підвищено: жовтий → червоний — повна сирена
                Log.W("SOUND escalation"); Reporter.Send("sound", "kind", "escalation", "place", PlaceKey()); if (settings.Sound) Sounds.Alarm(threats);
                tray.ShowBalloonTip(10000, "ЧЕРВОНИЙ РІВЕНЬ ТРИВОГИ!", place + ". Негайно в укриття!"
                    + (th.Length > 0 ? "\n" + th : ""), ToolTipIcon.Error);
            }
            else if (old == AlertState.Alert && state == AlertState.Alert && added.Count > 0)
            {
                Log.W("SOUND newthreats " + string.Join(",", added.ToArray())); Reporter.Send("sound", "kind", "newthreats", "place", PlaceKey()); if (settings.Sound) Sounds.NewThreats(added);
                List<string> at = new List<string>();
                foreach (string t in added) at.Add(AlertSource.ThreatTitle(t));
                tray.ShowBalloonTip(8000, "Нова загроза", place + ": " + string.Join(", ", at.ToArray()), ToolTipIcon.Warning);
            }
            else if (old == AlertState.Alert && state == AlertState.Clear)
            {
                Log.W("SOUND clear"); Reporter.Send("sound", "kind", "clear", "place", PlaceKey()); if (settings.Sound) Sounds.Clear();
                tray.ShowBalloonTip(10000, "Відбій тривоги", place, ToolTipIcon.Info);
            }
        }

        // ---------- Автозапуск: ярлик у папці «Автозавантаження» (надійніше на Windows 7, ніж реєстр) ----------

        static string StartupLnk
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Startup), "Тривога.lnk"); }
        }

        static bool HasRunKey()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey))
                    return k != null && k.GetValue("Trevoga") != null;
            }
            catch { return false; }
        }

        static void RemoveRunKey()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                    if (k != null) k.DeleteValue("Trevoga", false);
            }
            catch { }
        }

        static bool IsAutorun()
        {
            return File.Exists(StartupLnk) || HasRunKey();
        }

        static void CreateShortcut(string lnkPath)
        {
            Type t = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(t);
            object lnk = null;
            try
            {
                lnk = t.InvokeMember("CreateShortcut", System.Reflection.BindingFlags.InvokeMethod, null, shell, new object[] { lnkPath });
                Type lt = lnk.GetType();
                System.Reflection.BindingFlags set = System.Reflection.BindingFlags.SetProperty;
                lt.InvokeMember("TargetPath", set, null, lnk, new object[] { Application.ExecutablePath });
                lt.InvokeMember("Arguments", set, null, lnk, new object[] { "/min" });
                lt.InvokeMember("WorkingDirectory", set, null, lnk, new object[] { Application.StartupPath });
                lt.InvokeMember("Description", set, null, lnk, new object[] { "Тривога — монітор повітряної тривоги" });
                lt.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod, null, lnk, null);
            }
            finally
            {
                if (lnk != null) Marshal.ReleaseComObject(lnk);
                Marshal.ReleaseComObject(shell);
            }
        }

        static bool SetAutorun(bool on)
        {
            try
            {
                RemoveRunKey();                          // старий спосіб (версії до 2.4)
                if (on) CreateShortcut(StartupLnk);
                else if (File.Exists(StartupLnk)) File.Delete(StartupLnk);
                return true;
            }
            catch (Exception ex)
            {
                MessageBox.Show("Не вдалося змінити автозапуск: " + ex.Message +
                    "\n\nМожна додати ярлик програми в папку «Автозавантаження» вручну.", "Тривога");
                return false;
            }
        }

        // Програма запущена прямо з архіву (з тимчасової папки)?
        static bool RunningFromTemp()
        {
            try
            {
                string tmp = Path.GetFullPath(Path.GetTempPath()).TrimEnd('\\') + "\\";
                return (Application.StartupPath.TrimEnd('\\') + "\\").StartsWith(tmp, StringComparison.OrdinalIgnoreCase);
            }
            catch { return false; }
        }

        // ---------- Значок у треї: Windows 7 при вході може запустити програму раніше за панель задач ----------

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern int RegisterWindowMessage(string msg);
        static readonly int WM_TASKBARCREATED = SafeRegister("TaskbarCreated");

        static int SafeRegister(string name)
        {
            try { return RegisterWindowMessage(name); } catch { return -1; }
        }

        void ReAddTray()
        {
            try { tray.Visible = false; tray.Visible = true; } catch { }
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_TASKBARCREATED && tray != null) ReAddTray();   // панель задач (пере)створено
            base.WndProc(ref m);
        }
    }
}
