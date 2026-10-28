// Модульні тести логіки «Тривоги» (C# 5, збирається разом із Trevoga.cs: /main:Trevoga.Tests).
// Код виходу 0 — усе гаразд, 1 — є помилки.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

namespace Trevoga
{
    static class Tests
    {
        static int passed, failed;

        static void Check(bool cond, string name)
        {
            if (cond) passed++;
            else { failed++; Console.WriteLine("FAIL: " + name); }
        }

        static void Eq(object expected, object actual, string name)
        {
            bool ok = expected == null ? actual == null : expected.Equals(actual);
            if (ok) passed++;
            else { failed++; Console.WriteLine("FAIL: " + name + " — очікувалось «" + expected + "», отримано «" + actual + "»"); }
        }

        static void Throws(Action a, string name)
        {
            try { a(); failed++; Console.WriteLine("FAIL: " + name + " — немає винятку"); }
            catch { passed++; }
        }

        // ---------- побудова тестових даних у форматі нашого сервера ----------
        class Root
        {
            public char[] Air = new string('N', 2000).ToCharArray();
            public List<object> Alerts = new List<object>();
            public List<object> R = new List<object>();
            public List<object> O = new List<object>();
            public bool WithAir = true, WithNep = true;
            public List<object> UR = null, UO = null, UC = null;   // UkraineAlarm (null — джерела немає)
            public Root Ua(string obl, string raion, string level, string since)
            {
                if (UR == null) { UR = new List<object>(); UO = new List<object>(); UC = new List<object>(); }
                if (raion != null) UR.Add(new List<object>(new object[] { obl, raion, since, level, new List<object>() }));
                return this;
            }
            public Root UaOblast(string obl, string level, string since)
            {
                Ua(obl, null, null, null);
                UO.Add(new List<object>(new object[] { obl, since, level, new List<object>() }));
                return this;
            }
            public Root UaCommunity(string obl, string raion, string name, string level, string since)
            {
                Ua(obl, null, null, null);
                UC.Add(new List<object>(new object[] { obl, raion, name, since, level, new List<object>() }));
                return this;
            }

            public Root AinRaion(int uid, string oblName, string raionName, string level, string since, params string[] threats)
            {
                Air[uid] = 'A';
                Dictionary<string, object> a = new Dictionary<string, object>();
                a["u"] = uid.ToString(); a["o"] = uid.ToString(); a["on"] = oblName; a["r"] = raionName;
                a["s"] = since; a["l"] = level; a["t"] = new List<object>(threats);
                Alerts.Add(a);
                return this;
            }
            public Root AinOblast(int obl, string oblName, string level, params string[] threats)
            {
                Air[obl] = 'A';
                Dictionary<string, object> a = new Dictionary<string, object>();
                a["u"] = obl.ToString(); a["o"] = obl.ToString(); a["on"] = oblName;
                a["s"] = "2026-10-22T10:00:00Z"; a["l"] = level; a["t"] = new List<object>(threats);
                Alerts.Add(a);
                return this;
            }
            public Root Nep(string obl, string raion, string level, string since, params string[] threats)
            {
                R.Add(new List<object>(new object[] { obl, raion, since, level, new List<object>(threats) }));
                return this;
            }
            public Root NepOblast(string obl, string level, params string[] threats)
            {
                O.Add(new List<object>(new object[] { obl, "2026-10-22T09:00:00Z", level, new List<object>(threats) }));
                return this;
            }
            public Dictionary<string, object> Build()
            {
                Dictionary<string, object> root = new Dictionary<string, object>();
                if (WithAir) { root["air"] = new string(Air); root["alerts"] = Alerts; }
                if (WithNep)
                {
                    Dictionary<string, object> n = new Dictionary<string, object>();
                    n["r"] = R; n["o"] = O;
                    root["neptun"] = n;
                }
                if (UR != null)
                {
                    Dictionary<string, object> u = new Dictionary<string, object>();
                    u["r"] = UR; u["o"] = UO; u["c"] = UC;
                    root["ua"] = u;
                }
                return root;
            }
        }

        static int RaionUid(string name)
        {
            int i = Array.IndexOf(Locations.RaionName, name);
            if (i < 0) throw new Exception("немає району " + name);
            return Locations.RaionUid[i];
        }

        static Settings S(string region, string raion, bool partial)
        {
            Settings s = new Settings();
            s.Region = region;
            s.Raion = raion == null ? 0 : RaionUid(raion);
            s.Partial = partial;
            return s;
        }

        static CheckResult M(Settings s, Root r) { return AlertSource.FromRoot(s, r.Build(), null); }

        const string OD = "Одеська область", ODR = "Одеський район";

        static void Main()
        {
            try { Console.OutputEncoding = System.Text.Encoding.UTF8; } catch { }
            Stopwatch sw = Stopwatch.StartNew();
            TestJson();
            TestNorm();
            TestNeptunParsing();
            TestLocations();
            TestMerge();
            TestFastClear();
            TestUkraineAlarm();
            TestSettingsAndLog();
            Console.WriteLine("Пройдено: " + passed + ", помилок: " + failed + " (" + sw.ElapsedMilliseconds + " мс)");
            Environment.Exit(failed == 0 ? 0 : 1);
        }

        static void TestJson()
        {
            Dictionary<string, object> d = (Dictionary<string, object>)Json.Parse(
                " { \"a\" : [1, -2.5e3, true, false, null, \"x\\u0410\\\"\\n\"], \"b\":{}, \"c\":[] , \"d\":\"Кам’янський\"} ");
            List<object> a = (List<object>)d["a"];
            Eq("1", a[0], "json число");
            Eq("-2.5e3", a[1], "json експонента");
            Eq(true, a[2], "json true");
            Eq(false, a[3], "json false");
            Check(a[4] == null, "json null");
            Eq("xА\"\n", a[5], "json екранування");
            Eq(0, ((Dictionary<string, object>)d["b"]).Count, "json порожній об'єкт");
            Eq(0, ((List<object>)d["c"]).Count, "json порожній масив");
            Eq("Кам’янський", d["d"], "json кирилиця");
            Throws(delegate { Json.Parse("{\"a\":[1,2"); }, "json обрізаний");
            Throws(delegate { Json.Parse(""); }, "json порожній");
            Throws(delegate { Json.Parse("{\"a\" 1}"); }, "json без двокрапки");

            // великий документ (як знімок треків дронів, ~3 МБ) — має розбиратися швидко
            StringBuilder sb = new StringBuilder("{\"type\":\"snapshot\",\"data\":{\"threats\":[");
            for (int i = 0; i < 12000; i++)
                sb.Append(i > 0 ? "," : "").Append("{\"id\":\"trk_").Append(i).Append("\",\"lat\":46.48,\"lon\":30.72,\"heading\":null,\"advisory\":false,\"areaOnly\":true,\"title\":\"Група БпЛА (2+)\",\"explanationShort\":\"БпЛА — Одеса, Одеська область. Підтверджень: 7.\",\"count\":2,\"sourceCount\":7}");
            sb.Append("]}}");
            Stopwatch t = Stopwatch.StartNew();
            object big = Json.Parse(sb.ToString());
            t.Stop();
            Check(big != null, "json великий документ");
            Check(t.ElapsedMilliseconds < 3000, "json великий документ швидко (" + t.ElapsedMilliseconds + " мс, " + sb.Length / 1024 + " КБ)");
        }

        static void TestNorm()
        {
            Eq(AlertSource.Norm("Кам'янський район"), AlertSource.Norm("Кам’янський район"), "апостроф ’");
            Eq(AlertSource.Norm("Кам'янський район"), AlertSource.Norm("Камʼянський район"), "апостроф ʼ");
            Eq("київ", AlertSource.Norm("м. Київ"), "м. Київ");
            Eq("крим", AlertSource.Norm("АР Крим"), "АР Крим");
            Eq("крим", AlertSource.Norm("Автономна Республіка Крим"), "Автономна Республіка Крим");
            Eq(AlertSource.Norm("Шептицький район"), AlertSource.Norm("Червоноградський район"), "перейменування Шептицький");
            Eq(AlertSource.Norm("Самарівський район"), AlertSource.Norm("Новомосковський район"), "перейменування Самарівський");
            Eq(AlertSource.Norm("Берестинський район"), AlertSource.Norm("Красноградський район"), "перейменування Берестинський");
            Eq(AlertSource.Norm("Звягельський район"), AlertSource.Norm("Новоград-Волинський район"), "перейменування Звягельський");
            Eq(AlertSource.Norm("Володимирський район"), AlertSource.Norm("Володимир-Волинський район"), "перейменування Володимирський");
            Eq(AlertSource.Norm("Сіверськодонецький район"), AlertSource.Norm("Сєвєродонецький район"), "перейменування Сіверськодонецький");
            Eq("", AlertSource.Norm(null), "Norm(null)");
        }

        static Dictionary<string, object> NeptunItem(string obl, string name, string level, params string[] reasons)
        {
            Dictionary<string, object> x = new Dictionary<string, object>();
            x["key"] = name.ToLowerInvariant(); x["name"] = name; x["oblast"] = obl;
            x["since"] = "2026-10-22T10:00:00Z"; x["level"] = level;
            x["reasons"] = new List<object>(reasons);
            return x;
        }

        static List<object> TypesOf(string reason)
        {
            Dictionary<string, object> j = new Dictionary<string, object>();
            j["raions"] = new List<object> { NeptunItem(OD, ODR, "red", reason) };
            Dictionary<string, object> n = AlertSource.NeptunToNep(j);
            return (List<object>)((List<object>)((List<object>)n["r"])[0])[4];
        }

        static void TestNeptunParsing()
        {
            Check(TypesOf("Дронова загроза (жовтий рівень)").Contains("drones"), "причина: дрони");
            Check(TypesOf("Загроза балістичних ракет").Contains("ballistic_missiles"), "причина: балістика");
            Check(TypesOf("Ракетна загроза (червоний рівень)").Contains("unspecified_missiles"), "причина: ракети");
            Check(TypesOf("Крилаті ракети").Contains("cruise_missiles"), "причина: крилаті");
            Check(!TypesOf("Крилаті ракети").Contains("unspecified_missiles"), "крилаті — не «ракети взагалі»");
            Check(TypesOf("Загроза застосування КАБ").Contains("guided_aerial_bombs"), "причина: КАБ");
            Check(TypesOf("Зліт МіГ-31К").Contains("mig31k_departure"), "причина: МіГ");
            Eq(0, TypesOf("Повітряна тривога").Count, "причина без типу");

            Dictionary<string, object> j = new Dictionary<string, object>();
            j["raions"] = new List<object> { NeptunItem(OD, ODR, "RED"), "сміття", null };
            j["oblasts"] = new List<object> { NeptunItem(OD, OD, "Yellow") };
            Dictionary<string, object> n = AlertSource.NeptunToNep(j);
            Eq(1, ((List<object>)n["r"]).Count, "NEPTUN: пропускає некоректні елементи");
            Eq("red", ((List<object>)((List<object>)n["r"])[0])[3], "NEPTUN: рівень у нижньому регістрі");
            Eq("yellow", ((List<object>)((List<object>)n["o"])[0])[2], "NEPTUN: рівень області");
            Check(AlertSource.NeptunToNep(new Dictionary<string, object>()) == null, "NEPTUN: без raions/oblasts → null");
            Check(AlertSource.NeptunToNep(null) == null, "NEPTUN: null");

            Dictionary<string, object> partial = new Dictionary<string, object>();
            partial["raions"] = new List<object> { new Dictionary<string, object>() };   // елемент без полів
            Check(AlertSource.NeptunToNep(partial) != null, "NEPTUN: елемент без полів не ламає розбір");
        }

        static void TestLocations()
        {
            Eq(Locations.RaionUid.Length, Locations.RaionName.Length, "районів: uid = назв");
            Eq(Locations.RaionUid.Length, Locations.RaionOblast.Length, "районів: uid = областей");
            Eq(Locations.OblastUid.Length, Locations.OblastName.Length, "областей: uid = назв");
            HashSet<int> u = new HashSet<int>(Locations.RaionUid);
            Eq(Locations.RaionUid.Length, u.Count, "uid районів унікальні");
            foreach (int o in Locations.RaionOblast)
                Check(Array.IndexOf(Locations.OblastUid, o) >= 0, "область району існує: " + o);
            foreach (string r in AlertSource.Regions)
                Check(AlertSource.OblastUid(r) > 0, "у списку програми відома область: " + r);
            Eq(Locations.OblastName.Length, AlertSource.Regions.Length, "усі області є у списку програми");
            foreach (string r in AlertSource.Regions)
            {
                int obl = AlertSource.OblastUid(r);
                int n = 0;
                foreach (int o in Locations.RaionOblast) if (o == obl) n++;
                if (r != "м. Київ" && r != "м. Севастополь" && r != "Автономна Республіка Крим")
                    Check(n > 0, "в області є райони: " + r);
            }
            foreach (int uid in Locations.RaionUid) Check(uid > 0 && uid < 2000, "uid району в межах рядка air: " + uid);
            Eq(ODR, AlertSource.RaionName(RaionUid(ODR)), "RaionName");
        }

        static void TestMerge()
        {
            Settings od = S(OD, ODR, false);
            int odUid = RaionUid(ODR), obl = AlertSource.OblastUid(OD);
            const string T0 = "2026-10-22T17:43:20Z";

            CheckResult r = M(od, new Root());
            Eq(AlertState.Clear, r.State, "усе тихо");
            Eq("джерела збігаються", r.Note, "усе тихо: примітка");
            Check(!r.Disagree, "усе тихо: без розбіжності");

            r = M(od, new Root().Nep(OD, ODR, "yellow", T0, "drones"));
            Eq(AlertState.Alert, r.State, "лише NEPTUN: тривога");
            Eq("yellow", r.Level, "лише NEPTUN: жовтий рівень");
            Check(r.Disagree, "лише NEPTUN: розбіжність");
            Eq(AlertState.Alert, r.NepState, "лише NEPTUN: NepState");
            Eq(AlertState.Clear, r.AinState, "лише NEPTUN: AinState");
            Check(r.Threats.Contains("drones"), "лише NEPTUN: загроза");
            Check(r.Since.HasValue, "лише NEPTUN: час початку");

            r = M(od, new Root().AinRaion(odUid, OD, ODR, "red", T0, "cruise_missiles"));
            Eq(AlertState.Alert, r.State, "лише alerts.in.ua: тривога");
            Eq("red", r.Level, "лише alerts.in.ua: рівень");

            r = M(od, new Root().AinRaion(odUid, OD, ODR, "yellow", T0, "drones").Nep(OD, ODR, "red", T0, "unspecified_missiles"));
            Eq("red", r.Level, "рівень — найвищий із джерел");
            Check(r.Threats.Contains("drones") && r.Threats.Contains("unspecified_missiles"), "загрози — об'єднання");
            Eq("джерела збігаються", r.Note, "обидва джерела: збіг");

            r = M(od, new Root().Nep(OD, ODR, null, T0));
            Eq("red", r.Level, "рівень невідомий → червоний");

            r = M(od, new Root().AinRaion(odUid, OD, ODR, "red", T0, "drones", "ballistic_missiles", "cruise_missiles"));
            Eq("ballistic_missiles", r.Threats[0], "балістика першою");

            r = M(od, new Root().Nep(OD, ODR, "red", "2026-10-22T17:00:00Z").AinRaion(odUid, OD, ODR, "red", "2026-10-22T16:00:00Z"));
            Eq(new DateTime(2026, 10, 22, 16, 0, 0, DateTimeKind.Utc).ToLocalTime(), r.Since, "час початку — найраніший");

            // Тривога лише в іншому районі області — для мого району тиша
            r = M(od, new Root().Nep(OD, "Ізмаїльський район", "red", T0).AinRaion(RaionUid("Ізмаїльський район"), OD, "Ізмаїльський район", "red", T0));
            Eq(AlertState.Clear, r.State, "тривога в сусідньому районі — тиша");

            // Район із такою ж назвою в іншій області не плутаємо
            r = M(od, new Root().Nep("Миколаївська область", ODR, "red", T0));
            Eq(AlertState.Clear, r.State, "район з іншої області не враховується");

            // Тривога на всю область
            r = M(od, new Root().NepOblast(OD, "red", "ballistic_missiles"));
            Eq(AlertState.Alert, r.State, "NEPTUN: тривога по всій області");
            Check(r.Threats.Contains("ballistic_missiles"), "NEPTUN: загроза області");
            r = M(od, new Root().AinOblast(obl, OD, "red"));
            Eq(AlertState.Alert, r.State, "alerts.in.ua: тривога по всій області");

            // Часткова тривога (окремі громади)
            Root part = new Root(); part.Air[odUid] = 'P';
            r = M(od, part);
            Eq(AlertState.Clear, r.State, "часткова тривога без галочки — тиша");
            part = new Root(); part.Air[odUid] = 'P';
            r = M(S(OD, ODR, true), part);
            Eq(AlertState.Alert, r.State, "часткова тривога з галочкою — тривога");
            part = new Root(); part.Air[odUid] = 'P'; part.Nep(OD, ODR, "red", T0);
            r = M(od, part);
            Eq(AlertState.Alert, r.State, "часткова + NEPTUN: тривога");
            Check(!r.Disagree && r.Note.Contains("частині"), "часткова + NEPTUN: не розбіжність");

            // Уся область (район не обрано)
            Settings whole = S(OD, null, false);
            r = M(whole, new Root().Nep(OD, ODR, "red", T0));
            Eq(AlertState.Clear, r.State, "вся область: тривога лише в одному районі — тиша");
            r = M(S(OD, null, true), new Root().Nep(OD, ODR, "red", T0));
            Eq(AlertState.Alert, r.State, "вся область + часткові: тривога");
            Root all = new Root();
            for (int i = 0; i < Locations.RaionUid.Length; i++)
                if (Locations.RaionOblast[i] == obl) all.Nep(OD, Locations.RaionName[i], "red", T0);
            r = M(whole, all);
            Eq(AlertState.Alert, r.State, "вся область: тривога в усіх районах");

            // Київ, апострофи, перейменування
            r = M(S("м. Київ", null, false), new Root().NepOblast("м. Київ", "red"));
            Eq(AlertState.Alert, r.State, "м. Київ (NEPTUN «м. Київ»)");
            r = M(S("м. Київ", null, false), new Root().NepOblast("Київ", "red"));
            Eq(AlertState.Alert, r.State, "м. Київ (NEPTUN «Київ»)");
            r = M(S("Дніпропетровська область", "Кам'янський район", false), new Root().Nep("Дніпропетровська область", "Кам’янський район", "red", T0));
            Eq(AlertState.Alert, r.State, "апостроф у назві району");
            r = M(S("Львівська область", "Шептицький район", false), new Root().Nep("Львівська область", "Червоноградський район", "red", T0));
            Eq(AlertState.Alert, r.State, "стара назва району в NEPTUN");

            // Лише одне джерело в даних
            Root onlyNep = new Root(); onlyNep.WithAir = false; onlyNep.Nep(OD, ODR, "red", T0);
            r = M(od, onlyNep);
            Eq(AlertState.Alert, r.State, "лише дані NEPTUN");
            Eq("NEPTUN", r.Source, "лише дані NEPTUN: джерело");
            Root onlyAin = new Root(); onlyAin.WithNep = false; onlyAin.AinRaion(odUid, OD, ODR, "red", T0);
            r = M(od, onlyAin);
            Eq(AlertState.Alert, r.State, "лише дані alerts.in.ua");

            // Пошкоджений рядок air — NEPTUN усе одно працює
            Dictionary<string, object> bad = new Root().Nep(OD, ODR, "red", T0).Build();
            bad["air"] = "NNN";
            r = AlertSource.FromRoot(od, bad, null);
            Eq(AlertState.Alert, r.State, "зіпсований air: працює NEPTUN");
            Eq(AlertState.Unknown, r.AinState, "зіпсований air: AinState Unknown");

            Throws(delegate { AlertSource.FromRoot(od, new Dictionary<string, object>(), null); }, "порожня відповідь сервера");
            Throws(delegate { AlertSource.FromRoot(S("Неіснуюча область", null, false), new Root().Build(), null); }, "невідома область");

            // Результат стабільний: однакові дані → однаковий стан
            Root same = new Root().Nep(OD, ODR, "yellow", T0, "drones");
            Eq(M(od, same).Level, M(od, same).Level, "детермінованість");
        }

        static void TestUkraineAlarm()
        {
            Settings od = S(OD, ODR, false);
            int odUid = RaionUid(ODR);
            const string T0 = "2026-10-28T10:00:00Z";
            CheckResult r = M(od, new Root().Ua(OD, null, null, null));
            Eq(AlertState.Clear, r.State, "UA: тиша");
            Eq(AlertState.Clear, r.UaState, "UA: UaState тиша");
            Check(r.Source.Contains("UkraineAlarm"), "UA: джерело в переліку");

            r = M(od, new Root().Ua(OD, ODR, "yellow", T0));
            Eq(AlertState.Alert, r.State, "UA: тривога лише з UkraineAlarm");
            Eq("yellow", r.Level, "UA: жовтий рівень");
            Eq(AlertState.Alert, r.UaState, "UA: UaState");
            Check(r.Disagree && r.Note.Contains("UkraineAlarm"), "UA: розбіжність названо");

            r = M(od, new Root().UaOblast(OD, "red", T0));
            Eq(AlertState.Alert, r.State, "UA: тривога по області");
            r = M(od, new Root().Ua(OD, IzmName, "red", T0));
            Eq(AlertState.Clear, r.State, "UA: сусідній район — тиша");

            r = M(od, new Root().UaCommunity(OD, ODR, "Чорноморська громада", "red", T0));
            Eq(AlertState.Clear, r.State, "UA: громада без галочки — тиша");
            r = M(S(OD, ODR, true), new Root().UaCommunity(OD, ODR, "Чорноморська громада", "red", T0));
            Eq(AlertState.Alert, r.State, "UA: громада з галочкою — тривога");

            // усі три джерела показують тривогу → збіг
            r = M(od, new Root().AinRaion(odUid, OD, ODR, "red", T0).Nep(OD, ODR, "red", T0).Ua(OD, ODR, "red", T0));
            Eq("джерела збігаються", r.Note, "UA: три джерела збігаються");

            // Офіційний відбій: UA тиша, NEPTUN і alerts.in.ua ще показують тривогу, що почалась раніше
            Root late = new Root().AinRaion(odUid, OD, ODR, "red", T0).Nep(OD, ODR, "red", T0).Ua(OD, null, null, null);
            r = AlertSource.FromRoot(od, late.Build(), null, null);
            Eq(AlertState.Alert, r.State, "UA відбій: без вікна — ще тривога (інші джерела)");
            DateTime clear = new DateTime(2026, 10, 28, 12, 30, 0, DateTimeKind.Local);
            r = AlertSource.FromRoot(od, late.Build(), clear, clear);
            Eq(AlertState.Clear, r.State, "UA відбій: одразу");
            Check(r.Note.Contains("UkraineAlarm"), "UA відбій: примітка");
            // нова тривога NEPTUN після офіційного відбою — тривога
            Root fresh = new Root().Nep(OD, ODR, "red", "2026-10-28T11:00:00Z").Ua(OD, null, null, null);
            r = AlertSource.FromRoot(od, fresh.Build(), new DateTime(2026, 10, 28, 12, 0, 0, DateTimeKind.Utc).ToLocalTime().AddMinutes(-90), new DateTime(2026, 10, 28, 12, 0, 0, DateTimeKind.Utc).ToLocalTime().AddMinutes(-90));
            Eq(AlertState.Alert, r.State, "UA відбій: нова тривога NEPTUN після нього — тривога");
            // UA знову тривога — тривога, хоч би що
            r = AlertSource.FromRoot(od, new Root().Ua(OD, ODR, "red", T0).Build(), clear, clear);
            Eq(AlertState.Alert, r.State, "UA: тривога сильніша за вікно відбою");

            // формат потоку нашого сервера (як NEPTUN + громади)
            Dictionary<string, object> j = (Dictionary<string, object>)Json.Parse("{\"raions\":[{\"name\":\"Одеський район\",\"oblast\":\"Одеська область\",\"since\":\"" + T0 + "\",\"level\":\"red\",\"reasons\":[\"Артобстріл\"]}],\"oblasts\":[],\"communities\":[{\"oblast\":\"Одеська область\",\"raion\":\"Одеський район\",\"name\":\"Х\",\"since\":\"" + T0 + "\",\"level\":\"yellow\"}]}");
            Dictionary<string, object> n = AlertSource.NeptunToNep(j);
            Eq(1, ((List<object>)n["c"]).Count, "потік сервера: громади");
            Dictionary<string, object> root = new Dictionary<string, object>(); root["ua"] = n;
            r = AlertSource.FromRoot(od, root, null);
            Eq(AlertState.Alert, r.State, "потік сервера: тривога району");
            Check(r.Threats.Contains("artillery_shelling"), "потік сервера: тип загрози");
        }

        const string IzmName = "Ізмаїльський район";

        static void TestFastClear()
        {
            Settings od = S(OD, ODR, false);
            int odUid = RaionUid(ODR);
            Root stillAin = new Root().AinRaion(odUid, OD, ODR, "red", "2026-10-22T17:43:20Z", "drones");
            CheckResult r = AlertSource.FromRoot(od, stillAin.Build(), null);
            Eq(AlertState.Alert, r.State, "без швидкого відбою чекаємо обидва джерела");
            r = AlertSource.FromRoot(od, stillAin.Build(), DateTime.Now);
            Eq(AlertState.Clear, r.State, "швидкий відбій за NEPTUN");
            Check(r.Note.Contains("відбій за NEPTUN"), "швидкий відбій: примітка");
            r = AlertSource.FromRoot(od, stillAin.Build(), new DateTime(2026, 10, 22, 10, 0, 0));
            Eq(AlertState.Alert, r.State, "нова тривога alerts.in.ua після відбою — тривога");
            Root both = new Root().AinRaion(odUid, OD, ODR, "red", "2026-10-22T17:43:20Z").Nep(OD, ODR, "red", "2026-10-22T17:43:20Z");
            r = AlertSource.FromRoot(od, both.Build(), DateTime.Now);
            Eq(AlertState.Alert, r.State, "NEPTUN знову показує тривогу — тривога");
        }

        static void TestSettingsAndLog()
        {
            Settings s = new Settings();
            s.Region = OD; s.Raion = RaionUid(ODR); s.Partial = true; s.Sound = false; s.Voice = "mykyta";
            s.Server = "http://127.0.0.1:1"; s.Stream = "ws://127.0.0.1:2/x"; s.Test = true;
            s.Save();
            Settings l = Settings.Load();
            Eq(OD, l.Region, "ini: область");
            Eq(s.Raion, l.Raion, "ini: район");
            Eq(true, l.Partial, "ini: часткові");
            Eq(false, l.Sound, "ini: звук");
            Eq("mykyta", l.Voice, "ini: голос");
            Eq(s.Server, l.Server, "ini: сервер");
            Eq(s.Stream, l.Stream, "ini: потік");
            Eq(true, l.Test, "ini: тестовий режим");
            File.WriteAllText(Path.Combine(System.Windows.Forms.Application.StartupPath, "trevoga.ini"), "interval=3\r\nraion=abc\r\n");
            l = Settings.Load();
            Eq(10, l.Interval, "ini: інтервал не менше 10 с");
            Eq(0, l.Raion, "ini: зіпсований район → 0");
            File.Delete(Path.Combine(System.Windows.Forms.Application.StartupPath, "trevoga.ini"));

            string lp = Log.FilePath;
            if (File.Exists(lp)) File.Delete(lp);
            if (File.Exists(lp + ".1")) File.Delete(lp + ".1");
            string line = new string('x', 1000);
            for (int i = 0; i < 1200; i++) Log.W(line);
            Check(File.Exists(lp + ".1"), "журнал: ротація після 1 МБ");
            Check(new FileInfo(lp).Length < 1024 * 1024, "журнал: поточний файл менше 1 МБ");
            File.Delete(lp); File.Delete(lp + ".1");
        }
    }
}
