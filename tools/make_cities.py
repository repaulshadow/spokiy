# Міста для карти в Android-версії: обласні центри й населені пункти від 1000 жителів, назви українською.
# Джерело — GeoNames (CC BY 4.0, https://www.geonames.org): cities1000 + альтернативні назви UA.
# Вихід: android/res/raw/cities.json — [[назва, широта×1e4, довгота×1e4, населення, статус(2 столиця, 1 обл. центр, 0)]]
import csv, io, json, sys, urllib.request, zipfile
def zget(url, name):
    data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Trevoga-cities/1.0"}), timeout=300).read()
    return zipfile.ZipFile(io.BytesIO(data)).read(name).decode("utf-8")
csv.field_size_limit(10**9)
rows = [r for r in csv.reader(io.StringIO(zget("https://download.geonames.org/export/dump/cities1000.zip", "cities1000.txt")), delimiter="\t") if r[8] == "UA"]
uk = {}   # geonameid -> (пріоритет, назва)
for r in csv.reader(io.StringIO(zget("https://download.geonames.org/export/dump/alternatenames/UA.zip", "UA.txt")), delimiter="\t"):
    if len(r) < 8 or r[2] != "uk" or r[7] == "1" or (len(r) > 6 and r[6] == "1"): continue   # без історичних і розмовних
    pr = 0 if r[4] == "1" else 1 if r[5] != "1" else 2   # бажана назва > звичайна > коротка
    if r[1] not in uk or pr < uk[r[1]][0]: uk[r[1]] = (pr, r[3])
out, miss = [], 0
for r in rows:
    gid, name, lat, lon, code, pop = r[0], r[1], float(r[4]), float(r[5]), r[7], int(r[14] or 0)
    if code in ("PPLX", "PPLH", "PPLQ", "PPLW"): continue   # частини міст, історичні, покинуті
    if gid in uk: name = uk[gid][1]
    else: miss += 1; continue
    cap = 2 if code == "PPLC" else 1 if code == "PPLA" or name == "Сімферополь" else 0
    out.append([name, round(lat * 1e4), round(lon * 1e4), pop, cap])
out.sort(key=lambda x: (-x[4], -x[3]))
json.dump(out, open("android/res/raw/cities.json", "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
caps = [x[0] for x in out if x[4]]
print(f"міст: {len(out)}, без укр. назви: {miss}, центрів: {len(caps)}: {', '.join(caps)}")
print(f"::notice title=cities::{len(out)} міст, без укр. назви {miss}; центри: {', '.join(caps)}")
