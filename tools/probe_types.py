import json, urllib.request, collections, time
def get(u):
    return json.loads(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "Trevoga/1.0 (+https://github.com/repaulshadow/spokiy)"}), timeout=20).read())
keys = collections.Counter(); vals = collections.defaultdict(collections.Counter); samples = {}
for i in range(6):
    j = get("https://neptun.in.ua/api/v1/threats")
    for t in j.get("threats", []):
        for k, v in t.items():
            keys[k] += 1
            if isinstance(v, (str, int, bool)) and k not in ("id", "lat", "lon", "updatedAt", "createdAt", "locality", "lastSeenAt", "firstSeenAt"):
                vals[k][str(v)[:60]] += 1
        samples.setdefault(t.get("type"), {k: v for k, v in t.items() if k != "trail"})
    time.sleep(20)
print("::notice title=keys::" + json.dumps(keys, ensure_ascii=False))
for k, c in vals.items():
    print("::notice title=vals " + k + "::" + json.dumps(c.most_common(25), ensure_ascii=False)[:3000])
for k, v in samples.items():
    print("::notice title=sample " + str(k) + "::" + json.dumps(v, ensure_ascii=False)[:2500])
