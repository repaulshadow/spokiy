# Реєстрація webhook UkraineAlarm на наш сервер (ключ і секретна адреса не друкуються).
import hashlib, json, os, sys, time, urllib.request, urllib.error
KEY = os.environ["UA_KEY"]
server = json.load(open("config.json"))["server"]
hook = server + "/ua-hook/" + hashlib.sha256(("trevoga-hook:" + KEY).encode()).hexdigest()[:24]
def call(method, body=None):
    for attempt in range(4):
        req = urllib.request.Request("https://api.ukrainealarm.com/api/v3/webhook", method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": KEY, "Content-Type": "application/json", "accept": "application/json", "User-Agent": "Trevoga/1.0"})
        try:
            r = urllib.request.urlopen(req, timeout=30); return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            b = e.read().decode("utf-8", "replace")
            if e.code == 429: time.sleep(65); continue
            return e.code, b
    return 429, "ліміт"
time.sleep(65)   # звільнити хвилинний ліміт від запитів сервера
code, body = call(os.environ.get("METHOD", "POST"), {"webHookUrl": hook})
safe = body.replace(hook, "<наш webhook>")[:1500]
print(f"::notice title=webhook {os.environ.get('METHOD','POST')}::{code} {safe}")
if code >= 400 and os.environ.get("METHOD", "POST") == "POST":
    time.sleep(65)
    code, body = call("PATCH", {"webHookUrl": hook})
    print(f"::notice title=webhook PATCH::{code} {body.replace(hook, '<наш webhook>')[:1500]}")
sys.exit(0 if code < 400 else 1)
