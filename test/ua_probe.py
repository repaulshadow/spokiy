# Розвідка API UkraineAlarm: опис Swagger і пробні запити з ключем (ключ не друкується).
import json, os, time, urllib.request
KEY = os.environ.get("UA_KEY", "")
B = "https://api.ukrainealarm.com"
def get(path, auth=True):
    h = {"accept": "application/json,text/html;q=0.9,*/*;q=0.8", "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36", "Accept-Language": "uk,en;q=0.8"}
    if auth: h["Authorization"] = KEY
    t = time.time()
    r = urllib.request.urlopen(urllib.request.Request(B + path, headers=h), timeout=30)
    return json.loads(r.read().decode("utf-8")), time.time() - t, dict(r.headers)
def note(title, msg): print(f"::notice title={title}::{msg[:3800]}")

if not KEY: print("::error::секрет UKRAINEALARM_TOKEN порожній")
for p in ("/swagger/v3/swagger.json", "/swagger/v1/swagger.json"):
    try:
        sw, _, _ = get(p, auth=False)
        open("swagger.json", "w").write(json.dumps(sw, ensure_ascii=False, indent=1))
        paths = []
        for path, ops in sw.get("paths", {}).items():
            for m, op in ops.items():
                params = [x.get("name") + ("(" + x.get("in") + ")") for x in op.get("parameters", [])]
                body = op.get("requestBody", {}).get("content", {})
                bref = [v.get("schema", {}).get("$ref", "") for v in body.values()]
                paths.append(f"{m.upper()} {path} {params} {bref} — {op.get('summary') or op.get('description') or ''}")
        note("swagger " + p, " ¦ ".join(paths))
        schemas = sw.get("components", {}).get("schemas", {})
        for name, sc in list(schemas.items())[:30]:
            props = {k: (v.get("type") or v.get("$ref", "").split("/")[-1] or ("enum" if "enum" in v else "?")) for k, v in sc.get("properties", {}).items()}
            enum = sc.get("enum")
            note("schema " + name, json.dumps(props if props else enum, ensure_ascii=False))
        sec = sw.get("components", {}).get("securitySchemes", {})
        note("security", json.dumps(sec, ensure_ascii=False))
        break
    except Exception as e:
        print(f"::warning::{p}: {e}")

if KEY:
    for p in ("/api/v3/alerts/status", "/api/v3/alerts", "/api/v3/regions"):
        try:
            d, dt_, hdr = get(p)
            rl = {k: v for k, v in hdr.items() if "limit" in k.lower() or "cache" in k.lower() or k.lower() == "age"}
            if isinstance(d, list):
                s = f"список з {len(d)} елементів за {dt_:.2f} с; заголовки {rl}; перший: " + json.dumps(d[0] if d else None, ensure_ascii=False)[:1500]
                od = [x for x in d if "Одес" in json.dumps(x, ensure_ascii=False)]
                if od: s += " ¦ Одеса: " + json.dumps(od[0], ensure_ascii=False)[:1500]
            else:
                s = f"{dt_:.2f} с; заголовки {rl}; " + json.dumps(d, ensure_ascii=False)[:2500]
                if p.endswith("regions"):
                    st = d.get("states", [])
                    odesa = [x for x in st if "Одес" in x.get("regionName", "")]
                    if odesa:
                        o = odesa[0]
                        s = (f"{len(st)} областей; ключі області {list(o.keys())}; Одеська: id={o.get('regionId')} тип={o.get('regionType')} ¦ райони: "
                             + "; ".join(f"{r.get('regionName')} id={r.get('regionId')} {r.get('regionType')} громад={len(r.get('regionChildIds') or [])}" for r in o.get("regionChildIds") or []))
            note("GET " + p, s)
        except Exception as e:
            print(f"::error title=GET {p}::{e}")
    # як часто змінюється статус (для вибору частоти опитування)
    try:
        seen = []
        for i in range(30):
            d, dt_, _ = get("/api/v3/alerts/status"); seen.append((round(time.time()), d.get("lastActionIndex"), round(dt_, 2)))
            time.sleep(2)
        note("status кожні 2 с (60 с)", str(seen))
    except Exception as e:
        print(f"::error title=status loop::{e}")
