# Тижневий звіт: тривоги в Одеському районі (офіційна історія alerts.in.ua) і за скільки спрацьовувала програма
# на кожному комп'ютері, обраному на цей район (звіти POST /report). Результат — анотації та Step Summary.
import json, os, statistics, urllib.parse, urllib.request, datetime as dt
TOKEN = os.environ["ALERTS_TOKEN"]
RAION, OBL, OBL_UID = os.environ.get("RAION", "Одеський район"), "Одеська область", 18
kyiv = dt.timezone(dt.timedelta(hours=2))
now = dt.datetime.now(dt.timezone.utc)
since = dt.datetime.fromisoformat(os.environ.get("SINCE") or (now - dt.timedelta(days=7)).isoformat().replace("+00:00", "Z").replace("Z", "+00:00"))
if since.tzinfo is None: since = since.replace(tzinfo=kyiv)
server = json.load(open("config.json"))["server"]
def T(s): return dt.datetime.fromisoformat(s.replace("Z", "+00:00")) if s else None
def get(url, auth=False):
    h = {"User-Agent": "Trevoga-report/1.0"}
    if auth: h["Authorization"] = "Bearer " + TOKEN
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=60))
def k(t): return t.astimezone(kyiv).strftime("%a %d.%m %H:%M:%S")
def fmt(sec):
    sec = int(round(sec)); return f"{sec // 3600} год {sec % 3600 // 60} хв" if sec >= 3600 else f"{sec // 60} хв {sec % 60} с"
out = []

# 1. Офіційні тривоги, що стосуються району: сам район, уся область, громади району (частково)
hist = get(f"https://api.alerts.in.ua/v1/regions/{OBL_UID}/alerts/month_ago.json", auth=True).get("alerts", [])
alerts = []
for a in hist:
    st = T(a.get("started_at")); fin = T(a.get("finished_at"))
    if not st or (fin or now) < since: continue
    title, typ = a.get("location_title", ""), a.get("location_type")
    if typ == "raion" and title == RAION: scope = "район"
    elif typ == "oblast": scope = "вся область"
    elif typ in ("hromada", "city") and (a.get("location_raion") or "") == RAION: scope = "частково: " + title
    else: continue
    alerts.append({"st": st, "fin": fin, "scope": scope, "type": a.get("alert_type"), "level": a.get("alert_level"), "notes": a.get("notes") or ""})
alerts.sort(key=lambda x: x["st"])

# 2. Рівні й причини з журналу сервера (NEPTUN) для району
levels = {}
try:
    lg = get(server + "/log?" + urllib.parse.urlencode({"q": RAION.split()[0].lower(), "since": since.isoformat(), "limit": 50000}))
    for e in lg.get("events", []):
        if e["e"] == "тривога" and e["k"].lower().startswith(RAION.lower()):
            levels.setdefault(e["k"].split(" · ")[-1], []).append(T(e["t"]))
except Exception as ex: out.append(f"(журнал сервера недоступний: {ex})")

# 3. Звіти програм, налаштованих на цей район
reps = get(server + "/reports?" + urllib.parse.urlencode({"since": since.isoformat()})).get("reports", [])
reps = [r for r in reps if RAION in (r.get("place") or "")]
def true_time(r, key="t"):
    # час події за годинником сервера: прийом − (відправка − подія) за годинником ПК
    try: return T(r["srv"]) - (T(r["sent"]) - T(r[key]))
    except Exception: return T(r.get("srv"))
devices = {}
for r in reps: devices.setdefault(r["id"], []).append(r)

raion_alerts = [a for a in alerts if a["scope"] in ("район", "вся область")]
lines = [f"## Тривоги: {RAION}, {OBL}", f"Період: {k(since)} — {k(now)} (київський час)", ""]
tot = sum(((a["fin"] or now) - a["st"]).total_seconds() for a in raion_alerts)
lines.append(f"**Усього тривог по району: {len(raion_alerts)}**, сумарно {fmt(tot)}; окремо часткових (лише громади району): {len(alerts) - len(raion_alerts)}")
by_level = {}
for a in raion_alerts: by_level[a["level"] or "невідомо"] = by_level.get(a["level"] or "невідомо", 0) + 1
lines.append("За рівнем (alerts.in.ua): " + ", ".join(f"{l}: {n}" for l, n in by_level.items()))
# типи загроз — зі звітів програми (threats на момент тривоги)
thr = {}
for r in reps:
    if r.get("e") == "state" and r.get("st") == "Alert":
        for t in filter(None, (r.get("th") or "").split(",")): thr[t] = thr.get(t, 0) + 1
if thr: lines.append("Типи загроз (за даними програми): " + ", ".join(f"{t}: {n}" for t, n in sorted(thr.items(), key=lambda x: -x[1])))
lines += ["", "| Початок | Відбій | Тривалість | Охоплення | Рівень |", "|---|---|---|---|---|"]
for a in alerts:
    lines.append(f"| {k(a['st'])} | {k(a['fin']) if a['fin'] else 'триває'} | {fmt(((a['fin'] or now) - a['st']).total_seconds())} | {a['scope']} | {a['level'] or ''} |")

def stats(xs):
    if not xs: return "немає даних"
    return f"n={len(xs)}, середня **{statistics.mean(xs):.1f} с**, мін {min(xs):.1f} с, макс {max(xs):.1f} с"
for dev, rs in devices.items():
    starts = [r for r in rs if r.get("e") == "start"]
    vers = sorted({r.get("v") for r in rs})
    lines += ["", f"## Комп'ютер id={dev} (версії {', '.join(vers)}; запусків {len(starts)}; звітів {len(rs)})"]
    st_ev = [(true_time(r), r) for r in rs if r.get("e") == "state" and r.get("first") != "1"]
    snd = [(true_time(r), r) for r in rs if r.get("e") == "sound"]
    d_alarm, d_clear, rows = [], [], []
    for a in raion_alerts:
        m1 = [t for t, r in snd if r.get("kind") in ("alarm", "ballistic") and -120 <= (t - a["st"]).total_seconds() <= 900]
        m2 = [t for t, r in snd if r.get("kind") == "clear" and a["fin"] and -120 <= (t - a["fin"]).total_seconds() <= 900]
        da = (min(m1) - a["st"]).total_seconds() if m1 else None
        dc = (min(m2) - a["fin"]).total_seconds() if m2 else None
        if da is not None: d_alarm.append(da)
        if dc is not None: d_clear.append(dc)
        rows.append(f"| {k(a['st'])} | {'' if da is None else f'+{da:.1f} с'} | {'' if dc is None else f'+{dc:.1f} с'} |")
    print("::notice title=сирі звіти " + dev + "::" + " ¦ ".join(
        f"{k(T(r['srv']))} {r.get('e')} {r.get('st') or r.get('kind') or ''} {('було ' + r['old']) if r.get('old') else ''} {r.get('src') or ''} {r.get('note') or ''} first={r.get('first','')}"
        for r in rs[-40:])[:3800])
    lines.append("Сирена після офіційного початку тривоги: " + stats(d_alarm))
    lines.append("Відбій після офіційного кінця: " + stats(d_clear))
    missed = sum(1 for r in rows if "| |" in r.split(" | ", 1)[1][:4])
    lines += ["", "| Тривога (офіц.) | Сирена на ПК | Відбій на ПК |", "|---|---|---|"] + rows
    srcs = {}
    for t, r in st_ev:
        if r.get("st") == "Alert": srcs[r.get("src")] = srcs.get(r.get("src"), 0) + 1
    if srcs: lines.append("Джерело, з якого прийшла тривога: " + ", ".join(f"{s}: {n}" for s, n in srcs.items()))
if not devices: lines += ["", "Звітів від програм на цей район ще немає (потрібна версія 3.3+)."]
md = "\n".join(lines)
print(md)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8").write(md + "\n")
open("odesa_report.md", "w", encoding="utf-8").write(md)
# анотації (частинами), щоб звіт можна було прочитати без доступу до логів
chunk = ""
n = 0
for l in lines:
    if len(chunk) + len(l) > 3500 and n < 9:
        print("::notice title=звіт " + str(n + 1) + "::" + chunk.replace("\n", " ¶ ")); chunk = ""; n += 1
    chunk += l + "\n"
print("::notice title=звіт " + str(n + 1) + "::" + chunk.replace("\n", " ¶ "))
