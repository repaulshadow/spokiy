# Обережне вимірювання ліміту частоти UkraineAlarm (ключ не друкується).
import json, os, time, urllib.request, urllib.error
KEY = os.environ["UA_KEY"]; B = "https://api.ukrainealarm.com/api/v3"
def get(p):
    req = urllib.request.Request(B + p, headers={"accept": "application/json", "Authorization": KEY, "User-Agent": "Trevoga/1.0"})
    try:
        r = urllib.request.urlopen(req, timeout=30); return r.status, r.read().decode(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300], dict(e.headers)
def note(t, m): print(f"::notice title={t}::{m[:3800]}", flush=True)
time.sleep(70)
log = []
# 1) статус з інтервалом 10, 5, 3, 2 с (по 6 запитів), фіксуємо 429
for gap in (10, 5, 3, 2):
    res = []
    for i in range(6):
        c, b, h = get("/alerts/status"); res.append(c)
        if c == 429: log.append(f"gap {gap}: 429 headers {dict((k, v) for k, v in h.items() if k.lower() in ('retry-after','x-ratelimit-limit','x-ratelimit-remaining','x-ratelimit-reset'))} body {b[:120]}")
        time.sleep(gap)
    log.append(f"статус кожні {gap} с: {res}")
    if 429 in res: time.sleep(70)
note("ліміт /alerts/status", " ¦ ".join(log))
time.sleep(70)
# 2) повний список тривог і регіони (по одному, з паузою)
c, b, h = get("/alerts")
if c == 200:
    d = json.loads(b)
    od = [x for x in d if "Одес" in json.dumps(x, ensure_ascii=False)]
    note("GET /alerts", f"{len(d)} регіонів з тривогою; перший: {json.dumps(d[0], ensure_ascii=False)[:1200] if d else None} ¦ Одеса: {json.dumps(od, ensure_ascii=False)[:1500]}")
else: note("GET /alerts", f"{c} {b}")
time.sleep(70)
c, b, h = get("/regions")
if c == 200:
    d = json.loads(b); st = d.get("states", [])
    o = [x for x in st if "Одес" in x.get("regionName", "")]
    note("GET /regions", f"{len(st)} областей; ключі: {list(st[0].keys()) if st else ''}; " + (
        f"Одеська id={o[0].get('regionId')} ¦ райони: " + "; ".join(f"{r.get('regionName')} id={r.get('regionId')} {r.get('regionType')} громад={len(r.get('regionChildIds') or [])}" for r in o[0].get('regionChildIds') or []) if o else json.dumps(d, ensure_ascii=False)[:1500]))
    open("regions.json", "w").write(b)
else: note("GET /regions", f"{c} {b}")
