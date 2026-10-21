// Перевірка потоку NEPTUN і зведення (запускається у GitHub Actions під Mono, не входить у Trevoga.exe)
using System;
using System.Collections.Generic;
using System.Net;
using System.Threading;

namespace Trevoga
{
    static class StreamTest
    {
        static void Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;
            int secs = args.Length > 0 ? int.Parse(args[0]) : 120;
            string server = args.Length > 1 ? args[1] : "";
            DateTime t0 = DateTime.UtcNow;
            Dictionary<string, object> root = null;
            try { root = AlertSource.FetchServer(server); Console.WriteLine("server ok, poll=" + AlertSource.PollOf(root)); }
            catch (Exception ex) { Console.WriteLine("server error: " + ex.Message); }
            NeptunStream.Changed += delegate
            {
                Dictionary<string, object> nep = NeptunStream.Nep;
                List<object> r = (List<object>)nep["r"];
                Console.WriteLine(string.Format("+{0:0.0}s changed: raions={1} oblasts={2} fresh={3}",
                    (DateTime.UtcNow - t0).TotalSeconds, r.Count, ((List<object>)nep["o"]).Count, NeptunStream.Fresh));
                // зводимо для першого району з тривогою і для Одеського району
                List<Settings> tests = new List<Settings>();
                if (r.Count > 0)
                {
                    List<object> e = (List<object>)r[0];
                    Settings a = new Settings(); a.Region = Convert.ToString(e[0]);
                    for (int i = 0; i < Locations.RaionName.Length; i++)
                        if (AlertSource.Norm(Locations.RaionName[i]) == AlertSource.Norm(Convert.ToString(e[1]))) a.Raion = Locations.RaionUid[i];
                    Console.WriteLine("  first alerted: " + e[0] + " / " + e[1] + " level=" + e[3] + " types=" + string.Join(",", ((List<object>)e[4]).ConvertAll(x => x.ToString()).ToArray()));
                    tests.Add(a);
                }
                Settings od = new Settings(); od.Region = "Одеська область"; od.Raion = 0;
                for (int i = 0; i < Locations.RaionName.Length; i++) if (Locations.RaionName[i].StartsWith("Одеськ")) od.Raion = Locations.RaionUid[i];
                tests.Add(od);
                foreach (Settings s in tests)
                {
                    Dictionary<string, object> rt = new Dictionary<string, object>();
                    if (root != null) foreach (KeyValuePair<string, object> kv in root) rt[kv.Key] = kv.Value;
                    rt["neptun"] = nep;
                    try
                    {
                        CheckResult c = AlertSource.FromRoot(s, rt, null);
                        Console.WriteLine("  " + s.Region + " #" + s.Raion + ": " + c.State + " level=" + c.Level + " nep=" + c.NepState + " ain=" + c.AinState
                            + " threats=" + string.Join(",", c.Threats.ToArray()) + " since=" + c.Since + " | " + c.Source + " | " + c.Note);
                    }
                    catch (Exception ex) { Console.WriteLine("  merge error: " + ex); }
                }
            };
            NeptunStream.Start();
            while ((DateTime.UtcNow - t0).TotalSeconds < secs)
            {
                Thread.Sleep(15000);
                Console.WriteLine(string.Format("+{0:0}s status={1} fresh={2}", (DateTime.UtcNow - t0).TotalSeconds, NeptunStream.Status, NeptunStream.Fresh));
            }
        }
    }
}
