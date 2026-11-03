import json, re, urllib.request, collections, time
def get(url):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Trevoga/1.0 (+https://github.com/repaulshadow/spokiy)"}), timeout=20)
        return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except Exception as e:
        return getattr(e, "code", 0), str(e), {}
def note(t, s): print("::notice title=" + t + "::" + s.replace("\n", " ¦ ")[:3900], flush=True)
s, b, h = get("https://neptun.in.ua/api/v1/threats")
j = json.loads(b)
note("hdr", json.dumps({k: v for k, v in h.items() if k.lower() in ("cache-control", "content-length", "etag", "age", "x-ratelimit-limit", "access-control-allow-origin", "content-encoding")}) + " bytes=" + str(len(b)))
note("top", json.dumps({k: (v if not isinstance(v, list) else "list%d" % len(v)) for k, v in j.items()}, ensure_ascii=False))
th = j.get("threats", [])
note("types", json.dumps(collections.Counter((t.get("type"), t.get("title")) for t in th).most_common(), ensure_ascii=False))
note("keys", json.dumps(collections.Counter(k for t in th for k in t).most_common(), ensure_ascii=False))
for k in ("status", "confidenceLevel", "positionQuality"):
    note("vals " + k, json.dumps(collections.Counter(str(t.get(k)) for t in th).most_common(), ensure_ascii=False))
if th:
    t = dict(th[0]); t["trail"] = (t.get("trail") or [])[:3]
    note("sample", json.dumps(t, ensure_ascii=False))
    note("trail lens", str([len(x.get("trail") or []) for x in th][:60]))
    hd = [x for x in th if x.get("heading") is not None][:2]
    note("with heading", json.dumps([{k: v for k, v in x.items() if k != "trail"} for x in hd], ensure_ascii=False))
seq = j.get("seq")
for q in ["?since=%s" % seq, "?seq=%s" % seq, "/stream", "/history", "/delta?since=%s" % seq]:
    s, b2, _ = get("https://neptun.in.ua/api/v1/threats" + q); note("q " + q + " " + str(s), b2[:300] + " len=" + str(len(b2)))
s, b, _ = get("https://neptun.in.ua/")
for c in sorted(set(re.findall(r'/_next/static/chunks/[^"\' \\]+\.js', b))):
    s2, js, _ = get("https://neptun.in.ua" + c)
    eps = sorted(set(re.findall(r'["\'`](/api/[^"\'`]{1,80}|wss?://[^"\'`]{1,80})', js)))
    if eps: note("eps " + c[-30:], " ".join(eps))
for i in range(3):
    time.sleep(10); s, b3, _ = get("https://neptun.in.ua/api/v1/threats"); jj = json.loads(b3)
    note("poll %d" % i, "seq=%s n=%d time=%s" % (jj.get("seq"), len(jj.get("threats", [])), jj.get("serverTime")))
