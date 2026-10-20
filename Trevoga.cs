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
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Reflection.AssemblyTitle("Trevoga")]
[assembly: System.Reflection.AssemblyVersion("2.0.0.0")]

namespace Trevoga
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
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

            bool createdNew;
            using (Mutex m = new Mutex(true, "Trevoga_SingleInstance_Mutex", out createdNew))
            {
                if (!createdNew)
                {
                    MessageBox.Show("Програма вже запущена (значок біля годинника).", "Тривога",
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

    // ---------------- Налаштування (trevoga.ini поруч з exe) ----------------
    class Settings
    {
        public string Region = "м. Київ";
        public int Raion = 0;              // uid району alerts.in.ua, 0 = вся область
        public int Interval = 20;          // секунд між перевірками
        public bool Partial = false;       // вважати тривогою часткову (окремі громади)
        public bool Sound = true;
        public string Voice = "tetiana";   // tetiana, mykyta, off
        public string Server = "";         // остання адреса сервера з config.json на GitHub

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
                }
            }
            catch { }
            if (s.Interval < 10) s.Interval = 10;
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
            if (s.Substring(i).StartsWith("true")) { i += 4; return true; }
            if (s.Substring(i).StartsWith("false")) { i += 5; return false; }
            if (s.Substring(i).StartsWith("null")) { i += 4; return null; }
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
        public string Note = "";         // пояснення: сервер недоступний, район лише через сервер тощо
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
            req.Timeout = 15000;
            req.ReadWriteTimeout = 15000;
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

        public static CheckResult Check(Settings s, string server)
        {
            string serverError = null;
            if (!string.IsNullOrEmpty(server))
            {
                try { return FromServer(s, server); }
                catch (Exception ex) { serverError = ex.Message; }
            }
            CheckResult r = FromUbilling(s.Region);
            List<string> notes = new List<string>();
            if (serverError != null) notes.Add("сервер недоступний");
            if (s.Raion != 0) notes.Add("район — лише через сервер, стежимо за всією областю");
            r.Note = string.Join(" · ", notes.ToArray());
            return r;
        }

        static CheckResult FromServer(Settings s, string server)
        {
            int obl = OblastUid(s.Region);
            if (obl < 0) throw new Exception("невідома область");
            Dictionary<string, object> root = Json.Parse(Download(server + "/status")) as Dictionary<string, object>;
            object v;
            if (root == null || !root.TryGetValue("air", out v) || !(v is string)) throw new Exception("неочікувана відповідь сервера");
            string air = (string)v;

            CheckResult r = new CheckResult();
            r.Source = "сервер";
            char o = CharAt(air, obl);
            char c = s.Raion != 0 ? CharAt(air, s.Raion) : o;
            if (o == 'A' || c == 'A') r.State = AlertState.Alert;
            else if (c == 'P') r.State = s.Partial ? AlertState.Alert : AlertState.Clear;
            else if (c == 'N') r.State = AlertState.Clear;
            else throw new Exception("неочікувана відповідь сервера");

            string raionName = s.Raion != 0 ? RaionName(s.Raion) : null;
            if (root.TryGetValue("alerts", out v) && v is List<object>)
            {
                foreach (object item in (List<object>)v)
                {
                    Dictionary<string, object> a = item as Dictionary<string, object>;
                    if (a == null) continue;
                    object x;
                    int u = a.TryGetValue("u", out x) ? ToInt(x) : -1;
                    int ao = a.TryGetValue("o", out x) ? ToInt(x) : -1;
                    if (ao != obl && u != obl) continue;
                    if (s.Raion != 0 && u != obl && u != s.Raion)
                    {
                        string rn = a.TryGetValue("r", out x) && x != null ? Convert.ToString(x) : null;
                        if (rn == null || rn != raionName) continue;
                    }
                    if (s.Raion == 0 && u != obl && !(s.Partial || o == 'A')) continue;   // лише частина області
                    if (a.TryGetValue("s", out x) && x != null)
                    {
                        try
                        {
                            DateTime st = DateTime.Parse(Convert.ToString(x), System.Globalization.CultureInfo.InvariantCulture,
                                System.Globalization.DateTimeStyles.RoundtripKind).ToLocalTime();
                            if (r.Since == null || st < r.Since.Value) r.Since = st;
                        }
                        catch { }
                    }
                    if (a.TryGetValue("t", out x) && x is List<object>)
                        foreach (object t in (List<object>)x)
                        {
                            string ts = Convert.ToString(t);
                            if (!string.IsNullOrEmpty(ts) && !r.Threats.Contains(ts)) r.Threats.Add(ts);
                        }
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
                    using (SolidBrush t = new SolidBrush(hot ? Gfx.DarkRed : Color.White))
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
    class MainForm : Form
    {
        Settings settings;
        StatusCard card;
        ComboBox cbRegion, cbRaion, cbVoice;
        CheckBox chkSound, chkAutorun, chkPartial;
        Label lblFooter;
        NotifyIcon tray;
        System.Windows.Forms.Timer timer, configTimer;
        Icon icoRed, icoGreen, icoGray;
        AlertState state = AlertState.Unknown;
        List<string> threats = new List<string>();
        DateTime? since;
        DateTime? clearedAt;
        string server = "";
        bool checking = false, firstCheck = true, reallyExit = false, startHidden, loading = true;
        int requestId = 0;

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public MainForm(bool startHidden)
        {
            this.startHidden = startHidden;
            settings = Settings.Load();
            server = settings.Server.Length > 0 ? settings.Server : AppConfig.Embedded();

            icoRed = Gfx.Dot(Gfx.Red, 32);
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
            ClientSize = new Size(460, 612);
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
            chkAutorun.CheckedChanged += delegate { SetAutorun(chkAutorun.Checked); };
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

            // --- трей
            ContextMenu menu = new ContextMenu();
            menu.MenuItems.Add("Відкрити", delegate { ShowWindow(); });
            menu.MenuItems.Add("Перевірити зараз", delegate { CheckNow(); });
            menu.MenuItems.Add("Тиша", delegate { Sounds.Stop(); });
            menu.MenuItems.Add("-");
            menu.MenuItems.Add("Вихід", delegate { reallyExit = true; Close(); });
            tray = new NotifyIcon();
            tray.Icon = icoGray;
            tray.Text = "Тривога: перевірка…";
            tray.ContextMenu = menu;
            tray.Visible = true;
            tray.DoubleClick += delegate { ShowWindow(); };

            Sounds.Ui = this;

            timer = new System.Windows.Forms.Timer();
            timer.Interval = settings.Interval * 1000;
            timer.Tick += delegate { CheckNow(); };
            timer.Start();

            // адресу сервера оновлюємо з GitHub при запуску і раз на 6 годин
            configTimer = new System.Windows.Forms.Timer();
            configTimer.Interval = 6 * 3600 * 1000;
            configTimer.Tick += delegate { RefreshConfig(); };
            configTimer.Start();

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
            firstCheck = true;
            threats = new List<string>();
            since = null;
            clearedAt = null;
            requestId++;          // відповідь на старий запит (інше місце) не застосовуємо
            checking = false;
            UpdateCard();
            CheckNow();
        }

        string PlaceName()
        {
            string r = settings.Raion != 0 ? AlertSource.RaionName(settings.Raion) : null;
            return r != null ? r + ", " + settings.Region : settings.Region;
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
            BeginInvoke((MethodInvoker)delegate { RefreshConfig(); CheckNow(); });
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
            timer.Stop();
            configTimer.Stop();
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
            string srv = server;
            ThreadPool.QueueUserWorkItem(delegate
            {
                CheckResult result = null;
                string error = null;
                try { result = AlertSource.Check(snapshot, srv); }
                catch (Exception ex) { error = ex.Message; }
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        checking = false;
                        if (myId == requestId) ApplyResult(result, error);
                    });
                }
                catch { checking = false; }
            });
        }

        static string Ago(DateTime t)
        {
            TimeSpan d = DateTime.Now - t;
            if (d.TotalMinutes < 1) return "щойно";
            if (d.TotalHours < 1) return (int)d.TotalMinutes + " хв";
            return (int)d.TotalHours + " год " + d.Minutes + " хв";
        }

        string lastNote = "", lastSource = "", lastTime = "";

        void UpdateCard()
        {
            card.Place = PlaceName();
            card.Chips = new List<string>();
            card.HotFirstChip = false;
            if (state == AlertState.Alert)
            {
                bool ballistic = threats.Contains("ballistic_missiles");
                card.Title = ballistic ? "БАЛІСТИКА!" : "ПОВІТРЯНА ТРИВОГА";
                card.CardColor = ballistic ? Gfx.DarkRed : Gfx.Red;
                card.Detail = since.HasValue ? "Триває " + Ago(since.Value) + " · з " + since.Value.ToString("HH:mm") : "Пройдіть в укриття";
                foreach (string t in threats) card.Chips.Add(AlertSource.ThreatTitle(t));
                card.HotFirstChip = ballistic;
            }
            else if (state == AlertState.Clear)
            {
                card.Title = "Тривоги немає";
                card.CardColor = Gfx.Green;
                card.Detail = clearedAt.HasValue ? "Відбій о " + clearedAt.Value.ToString("HH:mm") : "Спокійно";
            }
            else
            {
                card.Title = lastNote.StartsWith("!") ? "Немає зв'язку" : "Перевірка…";
                card.CardColor = Gfx.Gray;
                card.Detail = lastNote.StartsWith("!") ? lastNote.Substring(1) : "";
            }
            card.Invalidate();

            string foot = lastTime.Length > 0 ? "Оновлено о " + lastTime + " · " + lastSource : "";
            if (lastNote.Length > 0 && !lastNote.StartsWith("!")) foot += (foot.Length > 0 ? " · " : "") + lastNote;
            if (lastNote.StartsWith("!") && state != AlertState.Unknown) foot += " · немає зв'язку: " + lastNote.Substring(1);
            lblFooter.Text = (foot.Length > 0 ? foot + "\n" : "") + "Хрестик ховає програму до годинника. Вихід — правою кнопкою на значку.";

            Icon ic = state == AlertState.Alert ? icoRed : state == AlertState.Clear ? icoGreen : icoGray;
            tray.Icon = ic;
            Icon = ic;
            string tip = (state == AlertState.Alert ? "ТРИВОГА: " : state == AlertState.Clear ? "Тривоги немає: " : "Тривога: ") + PlaceName();
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
        }

        void ApplyResult(CheckResult r, string error)
        {
            if (error != null || r == null)
            {
                lastNote = "!" + (error ?? "помилка");
                UpdateCard();
                return;
            }
            lastNote = r.Note;
            lastSource = r.Source;
            lastTime = DateTime.Now.ToString("HH:mm:ss");

            AlertState old = state;
            List<string> oldThreats = threats;
            state = r.State;
            threats = r.Threats;
            if (state == AlertState.Alert)
            {
                if (r.Since.HasValue) since = r.Since;
                else if (old != AlertState.Alert) since = firstCheck ? (DateTime?)null : DateTime.Now;
                clearedAt = null;
            }
            else
            {
                since = null;
                if (old == AlertState.Alert) clearedAt = DateTime.Now;
            }
            UpdateCard();

            string place = PlaceName();
            List<string> titles = new List<string>();
            foreach (string t in threats) titles.Add(AlertSource.ThreatTitle(t));
            string th = string.Join(", ", titles.ToArray());

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
                if (settings.Sound) Sounds.Ballistic(old != AlertState.Alert, threats);
                tray.ShowBalloonTip(10000, "БАЛІСТИКА!", place + ". Негайно в укриття!", ToolTipIcon.Error);
            }
            else if (old != AlertState.Alert && state == AlertState.Alert)
            {
                if (settings.Sound) Sounds.Alarm(threats);
                tray.ShowBalloonTip(10000, "ПОВІТРЯНА ТРИВОГА!", place + ". Пройдіть в укриття!"
                    + (th.Length > 0 ? "\n" + th : ""), ToolTipIcon.Error);
            }
            else if (old == AlertState.Alert && state == AlertState.Alert && added.Count > 0)
            {
                if (settings.Sound) Sounds.NewThreats(added);
                List<string> at = new List<string>();
                foreach (string t in added) at.Add(AlertSource.ThreatTitle(t));
                tray.ShowBalloonTip(8000, "Нова загроза", place + ": " + string.Join(", ", at.ToArray()), ToolTipIcon.Warning);
            }
            else if (old == AlertState.Alert && state == AlertState.Clear)
            {
                if (settings.Sound) Sounds.Clear();
                tray.ShowBalloonTip(10000, "Відбій тривоги", place, ToolTipIcon.Info);
            }
        }

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
                MessageBox.Show("Не вдалося змінити автозапуск: " + ex.Message, "Тривога");
            }
        }
    }
}
