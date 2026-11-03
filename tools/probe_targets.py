# Розвідка: чи є відкриті дані про рух цілей (дрони/ракети) — UkraineAlarm swagger, NEPTUN, war-monitor.
import json, os, re, urllib.request
def get(url, h=None):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=dict({"User-Agent": "Trevoga/1.0 (+https://github.com/repaulshadow/spokiy)"}, **(h or {}))), timeout=20)
        return r.status, r.read(400000).decode("utf-8", "replace")
    except Exception as e:
        return getattr(e, "code", 0), str(e)
def note(t, s): print("::notice title=" + t + "::" + s.replace("\n", " ¦ ")[:3800], flush=True)
s, b = get("https://api.ukrainealarm.com/swagger/v1/swagger.json")
try:
    paths = json.loads(b).get("paths", {})
    note("UA swagger", " ¦ ".join(p + " " + ",".join(m.upper() + ":" + (v.get("summary") or "") for m, v in d.items()) for p, d in paths.items()))
except Exception: note("UA swagger " + str(s), b[:500])
tok = os.environ.get("UA", "")
for p in ["/api/v3/targets", "/api/v3/threats", "/api/v3/map", "/api/v3/objects"]:
    s, b = get("https://api.ukrainealarm.com" + p, {"Authorization": tok}); note("UA " + p + " " + str(s), b[:300])
for u in ["https://neptun.in.ua/", "https://neptun.in.ua/api/v1/", "https://neptun.in.ua/api/v1/targets", "https://neptun.in.ua/api/v1/threats",
          "https://neptun.in.ua/api/v1/objects", "https://neptun.in.ua/api/v1/drones", "https://neptun.in.ua/api/docs", "https://neptun.in.ua/api"]:
    s, b = get(u)
    links = sorted(set(re.findall(r'["\'](/?(?:api|_next|static|assets)[^"\' ]{0,120})["\']', b)))[:60]
    note("NEP " + u + " " + str(s), b[:600] + " ¦ links: " + " ".join(links))
for u in ["https://map.war-monitor.org/", "https://alerts.in.ua/"]:
    s, b = get(u)
    links = sorted(set(re.findall(r'(?:https?://[^"\' ]+|/api[^"\' ]*)', b)))[:80]
    note("WEB " + u + " " + str(s), " ".join(links))
