# Затримка спрацювання: офіційний час тривоги/відбою (історія alerts.in.ua) проти часу, коли зміну побачив наш сервер
# (журнал /log: окремо джерела NEPTUN і alerts.in.ua). Результат — анотації GitHub.
import json, os, re, statistics, sys, time, urllib.parse, urllib.request, datetime as dt
TOKEN = os.environ["ALERTS_TOKEN"]; HOURS = float(os.environ.get("HOURS", "96"))
server = json.load(open("config.json"))["server"]
src = open("Trevoga.cs", encoding="utf-8-sig").read()
def arr(name, kind):
    body = re.search(r"public static readonly " + kind + r"\[\] " + name + r" = \{(.*?)\};", src, re.S).group(1)
    return re.findall(r'"([^"]*)"', body) if kind == "string" else [int(x) for x in re.findall(r"-?\d+", body)]
ON, OU = arr("OblastName", "string"), arr("OblastUid", "int")
now = dt.datetime.now(dt.timezone.utc); since = now - dt.timedelta(hours=HOURS)
kyiv = dt.timezone(dt.timedelta(hours=2))
def T(s): return dt.datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None
def norm(x): return (x or "").lower().replace("’", "'").replace("ʼ", "'").strip()

# 1. Журнал сервера — по кожній області окремо (сервер віддає до 300 подій на запит)
events = []
for name in ON:
    q = name.split()[0][:-3] if name.endswith("область") else name.replace("м. ", "")
    try:
        d = json.load(urllib.request.urlopen(server + "/log?q=" + urllib.parse.quote(q), timeout=30))
        events += d.get("events", [])
    except Exception as e: print("::warning::log " + q + ": " + str(e))
uniq = {(e["t"], e["src"], e["k"], e["e"]) for e in events}
events = [{"t": T(t), "src": s, "k": k, "e": ev} for t, s, k, ev in uniq]
oldest = min((e["t"] for e in events), default=now)
print(f"::notice title=журнал сервера::{len(events)} подій, найстаріша {oldest.astimezone(kyiv):%d.%m %H:%M}")
start_from = max(since, oldest + dt.timedelta(minutes=2))

# 2. Офіційна історія alerts.in.ua (усі області; ліміт API — пауза між запитами)
official = []
for uid, name in zip(OU, ON):
    req = urllib.request.Request(f"https://api.alerts.in.ua/v1/regions/{uid}/alerts/month_ago.json",
                                 headers={"Authorization": "Bearer " + TOKEN, "User-Agent": "Trevoga-latency/1.0"})
    for attempt in range(3):
        try:
            data = json.load(urllib.request.urlopen(req, timeout=30)); break
        except Exception as e:
            time.sleep(15); data = {"alerts": []}
    for a in data.get("alerts", []):
        if a.get("location_type") not in ("raion", "oblast", "city"): continue
        for kind, ts in (("тривога", a.get("started_at")), ("відбій", a.get("finished_at"))):
            t = T(ts)
            if t and start_from <= t <= now - dt.timedelta(minutes=2):
                official.append({"t": t, "e": kind, "title": a.get("location_title", ""), "obl": a.get("location_oblast", name), "type": a["location_type"]})
    time.sleep(7)
print(f"::notice title=офіційні події::{len(official)} (тривог {sum(o['e']=='тривога' for o in official)}, відбоїв {sum(o['e']=='відбій' for o in official)}) з {start_from.astimezone(kyiv):%d.%m %H:%M}")

# 3. Зіставлення: найближча подія сервера того ж типу для того ж місця в межах -60…+600 с
def match(o, source):
    best = None
    tl = norm(o["title"]); ol = norm(o["obl"])
    for e in events:
        if e["src"] != source or e["e"] != o["e"]: continue
        k = norm(e["k"])
        if o["type"] == "raion":
            if not k.startswith(tl + " ·") or ol.split()[0] not in k: continue
        else:
            if source == "NEPTUN" and not k.startswith(norm(o["title"]).replace("м. ", "")): continue
            if source == "alerts.in.ua": continue      # для областей ключ у журналі — лише uid
        d = (e["t"] - o["t"]).total_seconds()
        if -60 <= d <= 600 and (best is None or abs(d) < abs(best)): best = d
    return best

def stats(xs):
    xs = sorted(xs)
    if not xs: return "немає даних"
    p = lambda q: xs[min(len(xs) - 1, int(q * len(xs)))]
    return f"n={len(xs)}, середня {statistics.mean(xs):.1f} с, медіана {statistics.median(xs):.1f} с, 90% ≤ {p(0.9):.1f} с, макс {xs[-1]:.0f} с"

res = {}
for o in official:
    for s in ("NEPTUN", "alerts.in.ua"):
        d = match(o, s)
        if d is not None:
            res.setdefault((s, o["e"], "all"), []).append(d)
            if "одеськ" in norm(o["obl"]): res.setdefault((s, o["e"], "odesa"), []).append(d)
            if norm(o["title"]) == "одеський район": res.setdefault((s, o["e"], "odr"), []).append((o["t"], d))
for s in ("NEPTUN", "alerts.in.ua"):
    for e in ("тривога", "відбій"):
        print(f"::notice title={s} · {e} · уся Україна::{stats(res.get((s, e, 'all'), []))}")
        print(f"::notice title={s} · {e} · Одеська обл.::{stats(res.get((s, e, 'odesa'), []))}")
for s in ("NEPTUN", "alerts.in.ua"):
    rows = sorted(res.get((s, "тривога", "odr"), []) + [(t, d) for t, d in res.get((s, "відбій", "odr"), [])])
    print(f"::notice title={s} · Одеський район (кожна подія)::" + "; ".join(f"{t.astimezone(kyiv):%d.%m %H:%M:%S} +{d:.0f}с" for t, d in rows[-40:]))
