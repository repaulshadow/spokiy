# Слухає потік NEPTUN (як Trevoga.exe) DURATION хвилин, записує, коли прийшли тривога/відбій по кожному району,
# і порівнює з офіційним часом з історії alerts.in.ua. Плюс Telegram-канал для Одещини.
import asyncio, json, os, re, statistics, time, urllib.request, datetime as dt
import websockets
TOKEN = os.environ["ALERTS_TOKEN"]; DUR = float(os.environ.get("MINUTES", "60"))
kyiv = dt.timezone(dt.timedelta(hours=2))
def norm(x): return (x or "").lower().replace("’", "'").replace("ʼ", "'").replace("м. ", "").strip()
def T(s): return dt.datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None
seen = {}; got = []   # (час, подія, назва, область)

def apply(data):
    now = dt.datetime.now(dt.timezone.utc)
    cur = {}
    for r in data.get("raions") or []: cur[(norm(r.get("name")), norm(r.get("oblast")), "raion")] = r
    for o in data.get("oblasts") or []: cur[(norm(o.get("name")), norm(o.get("name")), "oblast")] = o
    if seen.get("init"):
        for k in cur.keys() - seen["keys"]: got.append((now, "тривога", *k))
        for k in seen["keys"] - cur.keys(): got.append((now, "відбій", *k))
    seen["keys"] = set(cur.keys()); seen["init"] = True

async def listen():
    end = time.time() + DUR * 60
    while time.time() < end:
        try:
            async with websockets.connect("wss://neptun.in.ua/api/v1/stream", open_timeout=20, max_size=None,
                                          extra_headers={"User-Agent": "Trevoga-latency/1.0 (+https://github.com/repaulshadow/spokiy)"}) as ws:
                while time.time() < end:
                    raw = await asyncio.wait_for(ws.recv(), timeout=60)
                    if '"alerts"' in raw[:60]:
                        apply(json.loads(raw)["data"])
        except Exception as e:
            print("::warning::stream " + repr(e)); await asyncio.sleep(3)
asyncio.run(listen())
t_start = min((g[0] for g in got), default=dt.datetime.now(dt.timezone.utc))
print(f"::notice title=потік NEPTUN::{len(got)} змін за {DUR:.0f} хв")
time.sleep(60)   # дати alerts.in.ua зафіксувати кінець подій

src = open("Trevoga.cs", encoding="utf-8-sig").read()
body = re.search(r"OblastUid = \{(.*?)\};", src, re.S).group(1)
uids = [int(x) for x in re.findall(r"\d+", body)]
lo = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=DUR + 5)
official = []
for uid in uids:
    req = urllib.request.Request(f"https://api.alerts.in.ua/v1/regions/{uid}/alerts/month_ago.json",
                                 headers={"Authorization": "Bearer " + TOKEN, "User-Agent": "Trevoga-latency/1.0"})
    data = {"alerts": []}
    for _ in range(3):
        try: data = json.load(urllib.request.urlopen(req, timeout=30)); break
        except Exception: time.sleep(15)
    for a in data.get("alerts", []):
        if a.get("location_type") not in ("raion", "oblast", "city"): continue
        for kind, ts in (("тривога", a.get("started_at")), ("відбій", a.get("finished_at"))):
            t = T(ts)
            if t and t >= lo: official.append((t, kind, norm(a.get("location_title")), norm(a.get("location_oblast")), a["location_type"]))
    time.sleep(7)

res = {}; od = []
for t, kind, title, obl, typ in official:
    best = None
    for g in got:
        if g[1] != kind or g[2] != title: continue
        if typ == "raion" and obl.split()[0] not in g[3]: continue
        d = (g[0] - t).total_seconds()
        if -120 <= d <= 900 and (best is None or abs(d) < abs(best)): best = d
    if best is None: continue
    res.setdefault(kind, []).append(best)
    if "одеськ" in obl: res.setdefault(kind + " Одещина", []).append(best); od.append((t, kind, title, best))
def stats(xs):
    xs = sorted(xs)
    if not xs: return "немає даних"
    return (f"n={len(xs)}, середня {statistics.mean(xs):.1f} с, медіана {statistics.median(xs):.1f} с, "
            f"мін {xs[0]:.0f} с, 90% ≤ {xs[min(len(xs)-1, int(.9*len(xs)))]:.0f} с, макс {xs[-1]:.0f} с")
for k in ("тривога", "відбій", "тривога Одещина", "відбій Одещина"):
    print(f"::notice title=Потік NEPTUN після офіційного часу · {k}::{stats(res.get(k, []))}")
print("::notice title=Одещина по подіях::" + ("; ".join(f"{t.astimezone(kyiv):%H:%M:%S} {k} {n}: +{d:.0f}с" for t, k, n, d in sorted(od)) or "подій не було"))
unmatched = len([o for o in official if o[1] == "тривога"]) - len(res.get("тривога", []))
print(f"::notice title=зіставлено::офіційних подій {len(official)}, з потоком зіставлено {sum(len(v) for k, v in res.items() if 'Одещина' not in k)}")

# Telegram-канал Одещини для порівняння
try:
    page = urllib.request.urlopen(urllib.request.Request("https://t.me/s/tairoverada", headers={"User-Agent": "Mozilla/5.0"}), timeout=30).read().decode("utf-8", "replace")
    rows = []
    for m in re.finditer(r'tgme_widget_message_text[^>]*>(.*?)</div>.*?<time datetime="([^"]+)"', page, re.S):
        t = dt.datetime.fromisoformat(m.group(2))
        if t >= lo:
            txt = re.sub(r"<[^>]+>", " ", m.group(1)).lower()
            if "тривог" in txt or "відбій" in txt: rows.append(f"{t.astimezone(kyiv):%H:%M:%S} {txt.strip()[:50]}")
    print("::notice title=Telegram tairoverada::" + ("; ".join(rows) or "постів про тривогу не було"))
except Exception as e: print("::warning::telegram " + repr(e))
