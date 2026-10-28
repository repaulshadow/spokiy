import json, urllib.request, collections
server = json.load(open("config.json"))["server"]
d = json.load(urllib.request.urlopen(urllib.request.Request(server + "/ua-hooks", headers={"User-Agent": "Trevoga-check/1.0"}), timeout=30))
rows, keys, st = [], collections.Counter(), collections.Counter()
for h in d["hooks"]:
    try: b = json.loads(h["body"])
    except Exception: rows.append("bad " + h["body"][:80]); continue
    for k in b: keys[k] += 1
    st[(b.get("status"), b.get("alarmType"))] += 1
    rows.append(f'{h["t"][11:19]} {b.get("status")} r={b.get("regionId")} {b.get("alarmType")} {b.get("alertLevel")} c={str(b.get("createdAt"))[11:19]} {b.get("reason") or ""} lv={len(b.get("activeAlertLevels") or [])}')
print("::notice title=ключі::" + json.dumps(keys, ensure_ascii=False))
print("::notice title=статуси::" + json.dumps({f"{a}/{b}": n for (a, b), n in st.items()}, ensure_ascii=False))
print("::notice title=події::" + " ¦ ".join(rows)[:3900])
deact = [h["body"] for h in d["hooks"] if '"Activate"' not in h["body"]]
print("::notice title=приклад не-Activate::" + (deact[0][:1500] if deact else "немає"))
