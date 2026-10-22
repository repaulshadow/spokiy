// Перевірка на справжніх даних: потік NEPTUN + наш сервер. /main:Trevoga.LiveTest
// Аргументи: секунд, адреса сервера.
using System;
using System.Collections.Generic;
using System.Net;
using System.Threading;

namespace Trevoga
{
    static class LiveTest
    {
        static void Main(string[] args)
        {
            try { ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | (SecurityProtocolType)768 | SecurityProtocolType.Tls; } catch { }
            int secs = int.Parse(args[0]);
            string server = args[1];
            int fails = 0;
            DateTime t0 = DateTime.UtcNow;
            Dictionary<string, object> root = null;
            try { root = AlertSource.FetchServer(server); Console.WriteLine("Сервер: OK, poll=" + AlertSource.PollOf(root)); }
            catch (Exception ex) { Console.WriteLine("FAIL: сервер — " + ex.Message); fails++; }

            double firstData = -1;
            int events = 0;
            NeptunStream.Changed += delegate
            {
                events++;
                if (firstData < 0) firstData = (DateTime.UtcNow - t0).TotalSeconds;
            };
            NeptunStream.Start();
            while ((DateTime.UtcNow - t0).TotalSeconds < secs) Thread.Sleep(1000);

            Console.WriteLine("Потік: статус «" + NeptunStream.Status + "», fresh=" + NeptunStream.Fresh + ", подій " + events + ", перші дані через " + firstData.ToString("0.0") + " с");
            if (!NeptunStream.Fresh) { Console.WriteLine("FAIL: потік NEPTUN не свіжий"); fails++; }
            if (firstData < 0 || firstData > 15) { Console.WriteLine("FAIL: дані з потоку не прийшли за 15 с"); fails++; }

            Dictionary<string, object> nep = NeptunStream.Nep;
            if (nep != null && root != null)
            {
                // Зведення для КОЖНОЇ області й КОЖНОГО району — без винятків
                int alerts = 0, checkedN = 0, disagree = 0;
                List<string> diffs = new List<string>();
                Dictionary<string, object> rt = new Dictionary<string, object>(root);
                rt["neptun"] = nep;
                foreach (string region in AlertSource.Regions)
                {
                    List<int> raions = new List<int> { 0 };
                    int obl = AlertSource.OblastUid(region);
                    for (int i = 0; i < Locations.RaionUid.Length; i++) if (Locations.RaionOblast[i] == obl) raions.Add(Locations.RaionUid[i]);
                    foreach (int r in raions)
                    {
                        Settings s = new Settings(); s.Region = region; s.Raion = r;
                        try
                        {
                            CheckResult c = AlertSource.FromRoot(s, rt, null);
                            checkedN++;
                            if (c.State == AlertState.Alert) alerts++;
                            // NEPTUN із потоку і NEPTUN, який бачить сервер, мають збігатися (з точністю до кількох секунд)
                            CheckResult viaServer = AlertSource.FromRoot(s, root, null);
                            if (viaServer.NepState != c.NepState)
                            {
                                disagree++;
                                if (diffs.Count < 10) diffs.Add(region + (r != 0 ? " / " + AlertSource.RaionName(r) : "") + ": потік " + c.NepState + ", сервер " + viaServer.NepState);
                            }
                        }
                        catch (Exception ex) { fails++; Console.WriteLine("FAIL: " + region + " #" + r + ": " + ex.Message); }
                    }
                }
                Console.WriteLine("Зведення: перевірено " + checkedN + " місць, зараз тривога в " + alerts + ", розбіжностей потік/сервер: " + disagree);
                foreach (string d in diffs) Console.WriteLine("  " + d);
                if (disagree > 8) { Console.WriteLine("FAIL: забагато розбіжностей між потоком і сервером"); fails++; }
            }
            Console.WriteLine(fails == 0 ? "OK: живі дані — усі перевірки пройдено" : "Помилок: " + fails);
            Environment.Exit(fails == 0 ? 0 : 1);
        }
    }
}
