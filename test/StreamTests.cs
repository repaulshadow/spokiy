// Перевірка WebSocket-клієнта (NeptunStream) проти test/fake_ws.py. /main:Trevoga.StreamTests
// Аргументи: адреса ws://..., тривалість у секундах.
using System;
using System.Collections.Generic;
using System.Threading;

namespace Trevoga
{
    static class StreamTests
    {
        static void Main(string[] args)
        {
            try { Console.OutputEncoding = System.Text.Encoding.UTF8; } catch { }
            NeptunStream.Url = args[0];
            int secs = int.Parse(args[1]);
            DateTime t0 = DateTime.UtcNow;
            List<string> seq = new List<string>();
            object lk = new object();
            NeptunStream.Changed += delegate
            {
                List<object> r = (List<object>)NeptunStream.Nep["r"];
                string m = r.Count > 0 ? Convert.ToString(((List<object>)r[0])[1]) : "-";
                lock (lk) seq.Add(m);
                Console.WriteLine(string.Format("+{0:0.0}s подія {1} (fresh={2})", (DateTime.UtcNow - t0).TotalSeconds, m, NeptunStream.Fresh));
            };
            NeptunStream.Start();
            bool wasFresh = false;
            double staleFrom = -1, longestStale = 0;
            while ((DateTime.UtcNow - t0).TotalSeconds < secs)
            {
                Thread.Sleep(250);
                double t = (DateTime.UtcNow - t0).TotalSeconds;
                bool f = NeptunStream.Fresh;
                if (f != wasFresh) Console.WriteLine(string.Format("+{0:0.0}s fresh={1} status={2}", t, f, NeptunStream.Status));
                if (!f && wasFresh) staleFrom = t;
                if (f && !wasFresh && staleFrom >= 0) { longestStale = Math.Max(longestStale, t - staleFrom); staleFrom = -1; }
                wasFresh = f;
            }
            int fails = 0;
            string got = string.Join(",", seq.ToArray());
            // A→B (фрагменти), повтор B без події, BIN ігнорується, C (64-біт), D після 3 МБ, E («type» наприкінці),
            // F (після відмови 403), G (після мовчання), H (після обриву)
            const string expected = "A,B,C,D,E,F,G,H";
            if (got != expected) { fails++; Console.WriteLine("FAIL: послідовність " + got + ", очікувалось " + expected); }
            if (!NeptunStream.Fresh) { fails++; Console.WriteLine("FAIL: наприкінці потік не свіжий"); }
            if (longestStale < 3 || longestStale > 90) { fails++; Console.WriteLine("FAIL: мовчазне з'єднання розпізнано неправильно (без даних " + longestStale + " с)"); }
            Console.WriteLine(fails == 0 ? "OK: потік — усі перевірки пройдено" : "Помилок: " + fails);
            Environment.Exit(fails == 0 ? 0 : 1);
        }
    }
}
