# Границі районів і областей для автовизначення району в Android-версії.
# Джерело — карта NEPTUN (neptun.in.ua/raions.geojson, /oblasts.geojson; ті самі назви, що звіряє test/check_names.py).
# Вихід: android/res/raw/geo.json — компактно: координати ×1e4 (≈10 м), спрощені (~100 м), з рамками для швидкого пошуку.
import json, re, sys, urllib.request
from shapely.geometry import shape, mapping
from shapely.ops import unary_union

def get(path):
    req = urllib.request.Request("https://neptun.in.ua" + path, headers={"User-Agent": "Trevoga-geo/1.0 (+https://github.com/repaulshadow/spokiy)"})
    return json.load(urllib.request.urlopen(req, timeout=120))

src = open("Trevoga.cs", encoding="utf-8-sig").read()
def arr(name, kind):
    body = re.search(r"public static readonly " + kind + r"\[\] " + name + r" = \{(.*?)\};", src, re.S).group(1)
    return re.findall(r'"([^"]*)"', body) if kind == "string" else [int(x) for x in re.findall(r"-?\d+", body)]
RN, RU, RO = arr("RaionName", "string"), arr("RaionUid", "int"), arr("RaionOblast", "int")
ON, OU = arr("OblastName", "string"), arr("OblastUid", "int")
RENAMED = dict(re.findall(r'\{ "([^"]+)", "([^"]+)" \}', src.split("RenamedRaions = new")[1].split("};")[0]))
def norm(x):
    t = (x or "").strip().lower().replace("’", "'").replace("ʼ", "'").replace("`", "'")
    if t.startswith("м. "): t = t[3:]
    if t in ("ар крим", "автономна республіка крим"): t = "крим"
    return RENAMED.get(t, t)
raion_by_norm = {norm(n): (u, o) for n, u, o in zip(RN, RU, RO)}
obl_by_norm = {norm(n): u for n, u in zip(ON, OU)}
TOL = 0.001
def pack(geom):
    g = geom.simplify(TOL, preserve_topology=True)
    polys = [g] if g.geom_type == "Polygon" else list(g.geoms)
    rings = []
    for p in polys:
        for ring in [p.exterior] + list(p.interiors):
            pts = []
            for x, y in ring.coords: pts += [round(x * 1e4), round(y * 1e4)]
            if len(pts) >= 8: rings.append(pts)
    minx, miny, maxx, maxy = g.bounds
    return [round(minx * 1e4), round(miny * 1e4), round(maxx * 1e4), round(maxy * 1e4)], rings

oj, rj = get("/oblasts.geojson"), get("/raions.geojson")
obls, oshapes = [], {}
for f in oj["features"]:
    name = f["properties"].get("region") or f["properties"].get("name")
    uid = obl_by_norm.get(norm(name))
    if uid is None: print("::warning::невідома область " + name); continue
    s = shape(f["geometry"]).buffer(0)
    oshapes[uid] = s
    bbox, rings = pack(s)
    obls.append({"n": name, "u": uid, "b": bbox, "g": rings})
raions, missing = [], []
for f in rj["features"]:
    name = f["properties"].get("rayon") or f["properties"].get("name")
    s = shape(f["geometry"]).buffer(0)
    hit = raion_by_norm.get(norm(name))
    if hit: uid, ouid = hit
    else:
        # райони, яких немає у списку програми (напр. Крим): лише область — за центром
        c = s.representative_point()
        ouid = next((u for u, os_ in oshapes.items() if os_.contains(c)), None)
        uid = 0
        missing.append(f"{name} → область {ouid}")
    bbox, rings = pack(s)
    raions.append({"n": name, "u": uid, "o": ouid, "b": bbox, "g": rings})
out = {"src": "NEPTUN (neptun.in.ua) raions/oblasts geojson", "scale": 10000, "oblasts": obls, "raions": raions}
txt = json.dumps(out, ensure_ascii=False, separators=(",", ":"))
open("android/res/raw/geo.json", "w", encoding="utf-8").write(txt)
npts = sum(len(r) for x in obls + raions for r in x["g"]) // 2
print(f"::notice title=geo.json::{len(obls)} областей, {len(raions)} районів, {npts} точок, {len(txt)//1024} КБ; без району в програмі: {'; '.join(missing)}")
known = {x["u"] for x in raions if x["u"]}
lost = [n for n, u in zip(RN, RU) if u not in known]
if lost: print("::error::райони програми без межі: " + "; ".join(lost)); sys.exit(1)
