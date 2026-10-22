# Звіряє назви районів/областей програми (Locations у Trevoga.cs) з картою NEPTUN (raions.geojson / oblasts.geojson).
# Якщо назва району відрізняється — потік NEPTUN не «побачить» тривогу в цьому районі.
import json, re, sys, urllib.request

def norm(x):
    t = (x or "").strip().lower().replace("’", "'").replace("ʼ", "'").replace("`", "'")
    if t.startswith("м. "): t = t[3:]
    if t in ("ар крим", "автономна республіка крим"): t = "крим"
    return RENAMED.get(t, t)

src = open("Trevoga.cs", encoding="utf-8-sig").read()
# та сама таблиця перейменувань, що й у програмі
RENAMED = dict(re.findall(r'\{ "([^"]+)", "([^"]+)" \}', src.split("RenamedRaions = new")[1].split("};")[0]))
def arr(name, kind):
    m = re.search(r"public static readonly " + kind + r"\[\] " + name + r" = \{(.*?)\};", src, re.S)
    body = m.group(1)
    return re.findall(r'"([^"]*)"', body) if kind == "string" else [int(x) for x in re.findall(r"-?\d+", body)]
rnames, robl = arr("RaionName", "string"), arr("RaionOblast", "int")
onames, ouid = arr("OblastName", "string"), arr("OblastUid", "int")
obl_by_uid = dict(zip(ouid, onames))

def get(path):
    req = urllib.request.Request("https://neptun.in.ua" + path, headers={"User-Agent": "Trevoga-tests/1.0 (+https://github.com/repaulshadow/spokiy)"})
    return json.load(urllib.request.urlopen(req, timeout=60))

ok = True
for path, ours in (("/raions.geojson", [(obl_by_uid.get(o, "?"), n) for n, o in zip(rnames, robl)]),
                   ("/oblasts.geojson", [(n, n) for n in onames])):
    g = get(path)
    feats = g.get("features", [])
    props = [f.get("properties", {}) for f in feats]
    print(f"::notice title={path}::{len(props)} features, properties keys: {sorted(props[0].keys()) if props else []}, sample: {json.dumps(props[0], ensure_ascii=False)[:300] if props else ''}")
    names = set()
    for p in props:
        nm = p.get("rayon") or p.get("region") or p.get("name") or ""
        ob = p.get("oblast") or ""
        names.add(norm(nm))
        if ob: names.add(norm(ob) + "|" + norm(nm))
    missing = [f"{o} / {n}" for o, n in ours if norm(n) not in names]
    if path == "/raions.geojson":
        mism_obl = [f"{o} / {n}" for o, n in ours if norm(n) in names and any("|" in x for x in names) and (norm(o) + "|" + norm(n)) not in names]
        if mism_obl: print(f"::warning title=oblast mismatch::{'; '.join(mism_obl)}")
    if missing:
        ok = False
        print(f"::error title={path}: немає в NEPTUN ({len(missing)})::" + "; ".join(missing))
    else:
        print(f"::notice title={path}::усі {len(ours)} назв збігаються")
    extra = sorted(n for n in names if "|" not in n and n not in {norm(x) for _, x in ours})
    if extra: print(f"::notice title={path}: є в NEPTUN, немає в програмі ({len(extra)})::" + "; ".join(extra[:60]))
sys.exit(0 if ok else 1)
