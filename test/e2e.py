# Наскрізний тест справжнього Trevoga.exe.
# Скрипт сам грає роль нашого сервера (/status, /version.json, /download) і потоку NEPTUN (WebSocket),
# змінює обстановку крок за кроком і перевіряє реакцію програми за її журналом trevoga.log.
#
# python e2e.py <Trevoga.exe> <робоча папка> [Trevoga_9999.exe Trevoga_bad.exe Trevoga_hang.exe]
import base64, ctypes, datetime as dt, hashlib, json, os, re, shutil, socket, struct, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WIN = os.name == "nt"
EXE, WORK = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
V9999, VBAD, VHANG = (sys.argv[3:6] + [None, None, None])[:3]
HTTP_PORT, WS_PORT, UA_PORT = 8787, 8788, 8789
ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------- назви/коди з Trevoga.cs ----------
src = open(os.path.join(ROOT_DIR, "Trevoga.cs"), encoding="utf-8-sig").read()
def arr(name, kind):
    body = re.search(r"public static readonly " + kind + r"\[\] " + name + r" = \{(.*?)\};", src, re.S).group(1)
    return re.findall(r'"([^"]*)"', body) if kind == "string" else [int(x) for x in re.findall(r"-?\d+", body)]
RN, RU = arr("RaionName", "string"), arr("RaionUid", "int")
ON, OU = arr("OblastName", "string"), arr("OblastUid", "int")
OD, ODR, IZM = "Одеська область", "Одеський район", "Ізмаїльський район"
OD_UID, ODR_UID, IZM_UID = OU[ON.index(OD)], RU[RN.index(ODR)], RU[RN.index(IZM)]

# ---------- «світ»: що зараз показують джерела ----------
class World:
    def __init__(self):
        self.lock = threading.Lock()
        self.ain = {}          # uid -> (назва району або None для області, рівень, загрози, початок)
        self.nep_r = {}        # назва району -> (область, рівень, причини)
        self.nep_o = {}        # область -> (рівень, причини)
        self.server_down = False
        self.ws_down = False
        self.ua_r = {}         # UkraineAlarm (офіційне): назва району -> (область, рівень)
        self.ua_o = {}         # область -> рівень
        self.ua_ws_down = False
        self.version = None    # (версія, шлях до exe)
        self.gen = 0
        self.requests = {}
        self.reports = []      # звіти програми (POST /report)
    def changed(self):
        self.gen += 1

W = World()
NOW = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ")   # тривоги «почалися» хвилину тому
TYPES = {"Дрон": "drones", "Баліст": "ballistic_missiles", "Ракет": "unspecified_missiles"}

FIRST = {}   # коли запис з'явився в «світі» — це його час початку (як у справжніх джерелах)
def since_of(k):
    if k not in FIRST: FIRST[k] = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return FIRST[k]
def prune_first():
    alive = {("n", x) for x in W.nep_r} | {("no", x) for x in W.nep_o} | {("u", x) for x in W.ua_r} | {("uo", x) for x in W.ua_o}
    for k in list(FIRST):
        if k not in alive: del FIRST[k]

def neptun_rest():
    with W.lock:
        return {"version": W.gen, "updatedAt": NOW,
                "raions": [{"key": n.lower(), "name": n, "oblast": o, "since": since_of(("n", n)), "level": l, "reasons": r} for n, (o, l, r) in W.nep_r.items()],
                "oblasts": [{"key": o.lower(), "name": o, "oblast": o, "since": since_of(("no", o)), "level": l, "reasons": r} for o, (l, r) in W.nep_o.items()]}

def ua_rest():
    with W.lock:
        return {"raions": [{"key": n.lower(), "name": n, "oblast": o, "since": since_of(("u", n)), "level": l, "reasons": []} for n, (o, l) in W.ua_r.items()],
                "oblasts": [{"key": o.lower(), "name": o, "oblast": o, "since": since_of(("uo", o)), "level": l, "reasons": []} for o, l in W.ua_o.items()],
                "communities": []}

def types_of(reasons):
    t = []
    for k, v in TYPES.items():
        if any(k.lower() in x.lower() for x in reasons): t.append(v)
    return t

def status():
    with W.lock:
        air = ["N"] * 2000
        alerts = []
        for uid, (rname, lvl, thr, since) in W.ain.items():
            air[uid] = "A"
            a = {"u": uid, "o": uid, "on": OD, "s": since, "l": lvl, "t": thr}
            if rname: a["r"] = rname
            alerts.append(a)
        nep = {"r": [[o, n, since_of(("n", n)), l, types_of(r)] for n, (o, l, r) in W.nep_r.items()],
               "o": [[o, since_of(("no", o)), l, types_of(r)] for o, (l, r) in W.nep_o.items()]}
        ua = {"r": [[o, n, since_of(("u", n)), l, []] for n, (o, l) in W.ua_r.items()], "o": [[o, since_of(("uo", o)), l, []] for o, l in W.ua_o.items()], "c": []}
        return {"ok": True, "poll": 5, "updated": NOW, "air": "".join(air), "alerts": alerts, "neptun": nep, "ua": ua}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, ctype="application/json"):
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        path = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n)
        if W.server_down: return self.send(503, b"down", "text/plain")
        if path == "/report":
            try:
                W.reports.append(json.loads(body.decode("utf-8")))
                return self.send(200, b'{"ok":true}')
            except Exception:
                return self.send(400, b'{"ok":false}')
        self.send(404, b"{}")
    def do_GET(self):
        path = self.path.split("?")[0]
        W.requests[path] = W.requests.get(path, 0) + 1
        if W.server_down: return self.send(503, b"down", "text/plain")
        if path == "/stream" and "websocket" in (self.headers.get("Upgrade") or "").lower():
            # потік на тій самій адресі, що й сервер (як у справжнього сервера; так його бачить Android-версія)
            if ua_down(): return self.send(503, b"down", "text/plain")
            self.close_connection = True
            ws_loop(self.connection, self.headers.get("Sec-WebSocket-Key"), ua_rest, ua_down)
            return
        if path == "/status": return self.send(200, json.dumps(status()).encode())
        if path == "/api/v1/alerts": return self.send(200, json.dumps(neptun_rest()).encode())
        if path == "/version.json":
            if not W.version: return self.send(404, b"{}")
            v, p = W.version
            data = open(p, "rb").read()
            return self.send(200, json.dumps({"windows": {"version": v, "url": "/download/Trevoga.exe",
                                                          "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}}).encode())
        if path == "/download/Trevoga.exe" and W.version:
            return self.send(200, open(W.version[1], "rb").read(), "application/octet-stream")
        self.send(404, b"{}")

# ---------- потік NEPTUN (WebSocket) ----------
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
def frame(op, payload):
    n = len(payload)
    h = struct.pack("!BB", 0x80 | op, n) if n < 126 else struct.pack("!BBH", 0x80 | op, 126, n) if n < 65536 else struct.pack("!BBQ", 0x80 | op, 127, n)
    return h + payload
ws_conns = []
def ws_client(c, data_fn=None, is_down=None):
    data_fn = data_fn or neptun_rest
    is_down = is_down or (lambda: W.ws_down)
    try:
        req = b""
        while b"\r\n\r\n" not in req:
            x = c.recv(4096)
            if not x: return
            req += x
        key = re.search(rb"Sec-WebSocket-Key:\s*(\S+)", req, re.I).group(1).decode()
        ws_loop(c, key, data_fn, is_down)
    except Exception:
        pass
    finally:
        try: c.close()
        except Exception: pass

def ws_loop(c, key, data_fn, is_down):
    """Відповідь 101 і далі — розсилка змін і heartbeat, поки «джерело» не впаде."""
    try:
        acc = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        c.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").encode())
        sent_gen, last_hb = -1, 0
        c.settimeout(0.05)
        while not is_down():
            if W.gen != sent_gen:
                sent_gen = W.gen
                c.sendall(frame(1, json.dumps({"type": "alerts", "ts": NOW, "data": data_fn()}, ensure_ascii=False).encode()))
            if time.time() - last_hb > 5:
                last_hb = time.time(); c.sendall(frame(1, b'{"type":"heartbeat","ts":"x"}'))
            try:
                d = c.recv(4096)          # pong/close від клієнта — просто читаємо
                if d == b"": return
            except socket.timeout: pass
    except Exception:
        pass
    finally:
        try: c.close()
        except Exception: pass
def ws_server():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", WS_PORT)); s.listen(16)
    while True:
        c, _ = s.accept()
        if W.ws_down: c.close(); continue
        threading.Thread(target=ws_client, args=(c,), daemon=True).start()

# потік нашого сервера (UkraineAlarm); «лежить», коли лежить сервер
def ua_down(): return W.ua_ws_down or W.server_down
def ua_ws_server():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", UA_PORT)); s.listen(16)
    while True:
        c, _ = s.accept()
        if ua_down(): c.close(); continue
        threading.Thread(target=ws_client, args=(c, ua_rest, ua_down), daemon=True).start()

# ---------- програма ----------
LOG = os.path.join(WORK, "trevoga.log")
APP = os.path.join(WORK, "Trevoga.exe")
def start_app(*args):
    cmd = ([APP] if WIN else ["mono", APP]) + list(args)
    return subprocess.Popen(cmd, cwd=WORK)
def app_pids():
    if WIN:
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Trevoga.exe", "/FO", "CSV", "/NH"], capture_output=True, text=True).stdout
        return [int(l.split('","')[1]) for l in out.strip().splitlines() if l.startswith('"Trevoga.exe"')]
    out = subprocess.run(["pgrep", "-f", "mono " + APP], capture_output=True, text=True).stdout
    return [int(x) for x in out.split()]
def kill_app():
    for p in app_pids():
        if WIN: subprocess.run(["taskkill", "/F", "/PID", str(p)], capture_output=True)
        else: os.kill(p, 9)
    time.sleep(1)

def log_lines():
    try:
        with open(LOG, encoding="utf-8-sig", errors="replace") as f: return f.read().splitlines()
    except FileNotFoundError: return []
def ts_of(line):
    try: return dt.datetime.strptime(line[:23], "%Y-%m-%d %H:%M:%S.%f").timestamp()
    except Exception: return None
def mark(): return len(log_lines())
def wait_log(pattern, timeout, after):
    end = time.time() + timeout
    rx = re.compile(pattern)
    while time.time() < end:
        for l in log_lines()[after:]:
            if rx.search(l): return l
        time.sleep(0.1)
    return None
def none_in_log(pattern, seconds, after):
    time.sleep(seconds)
    rx = re.compile(pattern)
    return [l for l in log_lines()[after:] if rx.search(l)]

# ---------- звіт ----------
results = []
def step(name, ok, detail="", latency=None):
    results.append((name, ok, detail, latency))
    lat = f" ({latency:.2f} с)" if latency is not None else ""
    print(("PASS " if ok else "FAIL ") + name + lat + (" — " + detail if detail else ""), flush=True)

def expect(name, pattern, timeout, after, t_change=None, max_latency=None):
    l = wait_log(pattern, timeout, after)
    if not l:
        step(name, False, f"немає «{pattern}» за {timeout} с")
        return None
    lat = None
    if t_change is not None and ts_of(l) is not None:
        lat = max(0.0, ts_of(l) - t_change)
    ok = max_latency is None or (lat is not None and lat <= max_latency)
    step(name, ok, l[24:140] if ok else f"затримка {lat:.2f} с > {max_latency} с: {l[24:140]}", lat)
    return l

def expect_none(name, pattern, seconds, after):
    bad = none_in_log(pattern, seconds, after)
    step(name, not bad, ("зайве: " + bad[0][24:140]) if bad else f"{seconds} с без «{pattern}»")

def change(fn):
    with W.lock:
        fn()
        prune_first()
        W.changed()
    return time.time()

# ================= сценарій =================
def main():
    os.makedirs(WORK, exist_ok=True)
    for f in os.listdir(WORK): os.remove(os.path.join(WORK, f))
    shutil.copy(EXE, APP)
    with open(os.path.join(WORK, "trevoga.ini"), "w", encoding="utf-8-sig") as f:
        f.write(f"region={OD}\nraion={ODR_UID}\nsound=1\nvoice=tetiana\nserver=http://127.0.0.1:{HTTP_PORT}\n"
                f"stream=ws://127.0.0.1:{WS_PORT}/api/v1/stream\nuastream=ws://127.0.0.1:{UA_PORT}/stream\ntest=1\n")
    threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), H).serve_forever, daemon=True).start()
    threading.Thread(target=ws_server, daemon=True).start()
    threading.Thread(target=ua_ws_server, daemon=True).start()

    m = mark(); start_app("/min")
    expect("Запуск", r"START v", 30, m)
    expect("Підключення до потоку", r"STREAM підключено", 30, m)
    expect("Початковий стан: тиша", r"STATE Clear", 30, m)
    expect_none("Без звуку при запуску", r"SOUND", 3, m)

    # 1. Тривога приходить потоком NEPTUN (жовта, дрони)
    m = mark(); t = change(lambda: W.nep_r.__setitem__(ODR, (OD, "yellow", ["Дронова загроза (жовтий рівень)"])))
    expect("Тривога з потоку NEPTUN — швидкість", r"STATE Alert level=yellow", 10, m, t, max_latency=1.5)
    expect("Сирена при тривозі", r"SOUND alarm", 3, m)

    # 2. alerts.in.ua: червоний рівень → підвищення рівня
    m = mark(); t = change(lambda: W.ain.__setitem__(ODR_UID, (ODR, "red", ["drones"], NOW)))
    expect("Жовтий → червоний (через опитування сервера)", r"SOUND escalation", 15, m, t, max_latency=7)
    # 3. Додалась балістика
    m = mark(); t = change(lambda: W.ain.__setitem__(ODR_UID, (ODR, "red", ["drones", "ballistic_missiles"], NOW)))
    expect("Нова загроза: балістика", r"SOUND ballistic", 15, m, t, max_latency=7)

    # 4. NEPTUN дає відбій, alerts.in.ua ще показує тривогу → відбій одразу
    m = mark(); t = change(lambda: W.nep_r.pop(ODR))
    expect("Швидкий відбій за NEPTUN — швидкість", r"STATE Clear.*відбій за NEPTUN", 10, m, t, max_latency=1.5)
    expect("Звук відбою", r"SOUND clear", 3, m)
    expect_none("alerts.in.ua із запізненням не вмикає тривогу знову", r"STATE Alert|SOUND alarm", 20, m)
    m = mark(); change(lambda: W.ain.clear())
    expect_none("Після повного відбою тиша", r"STATE Alert|SOUND", 8, m)

    # 5. Тривога лише в сусідньому районі
    m = mark()
    change(lambda: (W.nep_r.__setitem__(IZM, (OD, "red", ["Ракетна загроза"])), W.ain.__setitem__(IZM_UID, (IZM, "red", ["unspecified_missiles"], NOW))))
    expect_none("Тривога в сусідньому районі — тиша", r"STATE Alert|SOUND", 12, m)
    change(lambda: (W.nep_r.clear(), W.ain.clear()))

    # 6. Тривога на всю область (NEPTUN), потім відбій
    m = mark(); t = change(lambda: W.nep_o.__setitem__(OD, ("red", ["Ракетна загроза"])))
    expect("Тривога по всій області", r"STATE Alert level=red", 10, m, t, max_latency=1.5)
    m = mark(); t = change(lambda: W.nep_o.clear())
    expect("Відбій по області", r"SOUND clear", 10, m, t, max_latency=1.5)

    # 7. Потік NEPTUN недоступний → працює опитування сервера
    m = mark(); W.ws_down = True
    expect("Втрату потоку помічено", r"STREAM (?!підключено)", 45, m)
    time.sleep(2)
    m = mark(); t = change(lambda: W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])))
    expect("Без потоку тривога через сервер", r"STATE Alert", 15, m, t, max_latency=8)
    m = mark(); t = change(lambda: W.nep_r.clear())
    expect("Без потоку відбій через сервер", r"SOUND clear", 15, m, t, max_latency=8)
    m = mark(); W.ws_down = False
    expect("Потік відновився сам", r"STREAM підключено", 75, m)

    # 8. Сервер недоступний, потік працює
    m = mark(); W.server_down = True
    time.sleep(7)
    t = change(lambda: W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])))
    expect("Сервер лежить — тривога з потоку", r"STATE Alert", 10, m, t, max_latency=1.5)
    m = mark(); t = change(lambda: W.nep_r.clear())
    expect("Сервер лежить — відбій з потоку", r"SOUND clear", 10, m, t, max_latency=1.5)
    W.server_down = False
    m_server_back = mark()

    # U. Офіційне джерело UkraineAlarm (потік нашого сервера; після «падіння» сервера він перепідключається сам)
    expect("Потік UkraineAlarm підключено", r"UASTREAM підключено", 75, m_server_back)
    m = mark(); t = change(lambda: W.ua_r.__setitem__(ODR, (OD, "red")))
    expect("Тривога з UkraineAlarm — швидкість", r"STATE Alert.*UkraineAlarm наживо", 10, m, t, max_latency=1.5)
    expect("Сирена за UkraineAlarm", r"SOUND alarm", 3, m)
    change(lambda: (W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])), W.ain.__setitem__(ODR_UID, (ODR, "red", ["unspecified_missiles"], NOW))))
    time.sleep(7)
    m = mark(); t = change(lambda: W.ua_r.pop(ODR))
    expect("Офіційний відбій UkraineAlarm — одразу, не чекаючи NEPTUN", r"STATE Clear.*відбій за UkraineAlarm", 10, m, t, max_latency=1.5)
    expect("Звук відбою за UkraineAlarm", r"SOUND clear", 3, m)
    expect_none("NEPTUN і alerts.in.ua із запізненням не вмикають тривогу знову", r"STATE Alert|SOUND alarm", 25, m)
    change(lambda: (W.nep_r.clear(), W.ain.clear()))
    time.sleep(6)
    m = mark(); W.ua_ws_down = True
    expect("Втрату потоку UkraineAlarm помічено", r"UASTREAM (?!підключено)", 45, m)
    m = mark(); t = change(lambda: W.ua_r.__setitem__(ODR, (OD, "yellow")))
    expect("Без потоку UkraineAlarm — тривога через сервер", r"STATE Alert", 15, m, t, max_latency=8)
    m = mark(); t = change(lambda: W.ua_r.clear())
    expect("Без потоку UkraineAlarm — відбій через сервер", r"SOUND clear", 15, m, t, max_latency=8)
    m = mark(); W.ua_ws_down = False
    expect("Потік UkraineAlarm відновився сам", r"UASTREAM підключено", 75, m)

    # 9. Зв'язку немає зовсім посеред тривоги: без повторної сирени і без «з'їденого» відбою
    m = mark(); change(lambda: (W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])), W.ua_r.__setitem__(ODR, (OD, "red"))))
    expect("Тривога перед обривом зв'язку", r"SOUND alarm", 10, m)
    m = mark(); W.server_down = True; W.ws_down = True
    expect("Обрив зв'язку помічено", r"STATE Unknown|ERROR", 60, m)
    W.server_down = False; W.ws_down = False
    expect_none("Після відновлення — без повторної сирени", r"SOUND alarm", 20, m)
    m = mark(); t = change(lambda: (W.nep_r.clear(), W.ua_r.clear()))
    expect("Відбій після обриву зв'язку звучить", r"SOUND clear", 75, m)
    time.sleep(3)

    # Звіти про спрацювання дійшли на сервер (у т.ч. ті, що чекали в черзі, поки сервер «лежав»)
    time.sleep(12)
    reps = list(W.reports)
    kinds = [(r.get("e"), r.get("st") or r.get("kind")) for r in reps]
    step("Звіт: запуск", ("start", None) in kinds, f"звітів {len(reps)}")
    step("Звіт: тривога з часом початку", any(r.get("e") == "state" and r.get("st") == "Alert" and r.get("since") for r in reps))
    step("Звіт: відбій", any(r.get("e") == "state" and r.get("st") == "Clear" and r.get("old") == "Alert" for r in reps))
    step("Звіт: сирена і звук відбою", ("sound", "alarm") in kinds and ("sound", "clear") in kinds)
    step("Звіт: «живий» щопівгодини (у тесті — щохвилини)", any(r.get("e") == "alive" and r.get("stream") in ("0", "1") for r in reps))
    step("Звіт: місце і id", all(r.get("id") and len(r["id"]) == 12 for r in reps) and any(ODR in (r.get("place") or "") for r in reps))
    step("Звіт: швидкий відбій під час падіння сервера теж дійшов (черга)",
         sum(1 for r in reps if r.get("e") == "sound" and r.get("kind") == "clear") >= 5,
         f"звуків відбою у звітах: {sum(1 for r in reps if r.get('e') == 'sound' and r.get('kind') == 'clear')}")

    # 10. Друга копія програми не запускається
    if not WIN: return finish()
    before = app_pids()
    p2 = start_app("/min"); time.sleep(8)
    after = app_pids()
    step("Друга копія не запускається", len(after) == 1 and p2.poll() is not None, f"процесів: {len(before)} → {len(after)}")

    if WIN:
        # 11. Штучний збій → журнал + автоматичний перезапуск
        m = mark()
        k = ctypes.windll.kernel32
        h = k.OpenEventW(0x0002, False, "Trevoga_Test_Crash")
        if h: k.SetEvent(h); k.CloseHandle(h)
        expect("Збій записано в журнал", r"CRASH .*тестовий збій", 15, m)
        expect("Перезапуск після збою", r"START v.*/crash:1", 30, m)
        expect("Після перезапуску знову тиша", r"STATE Clear", 30, m)
        time.sleep(3)
        step("Після перезапуску одна копія", len(app_pids()) == 1, f"процесів: {len(app_pids())}")

    if V9999:
        # 12. Оновлення не ставиться під час тривоги, ставиться після відбою
        m = mark(); change(lambda: W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])))
        expect("Тривога перед оновленням", r"STATE Alert", 10, m)
        W.version = ("9.9.9.9", V9999)
        expect_none("Під час тривоги оновлення не ставиться", r"UPDATE applying", 30, m)
        m = mark(); change(lambda: W.nep_r.clear())
        expect("Після відбою оновлення встановлюється", r"UPDATE applying", 30, m)
        expect("Нова версія запустилась", r"START v9\.9\.9\.9 .*/updated", 30, m)
        expect("Нова версія підтвердила роботу", r"UPDATE confirmed v9\.9\.9\.9", 40, m)
        expect("Нова версія працює з потоком", r"STREAM підключено", 30, m)
        step("Резервну копію прибрано", not os.path.exists(APP + ".old") and not os.path.exists(os.path.join(WORK, "update.pending")))
        step("Після оновлення одна копія", len(app_pids()) == 1, f"процесів: {len(app_pids())}")
        m = mark(); t = change(lambda: W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])))
        expect("Нова версія ловить тривогу", r"STATE Alert", 10, m, t, max_latency=1.5)
        change(lambda: W.nep_r.clear())
        expect("Нова версія — відбій", r"SOUND clear", 10, m)

    if V9999 and VBAD:
        # 13. Зламане оновлення падає при старті → відкат на попередню версію і більше не ставиться
        m = mark(); W.version = ("9.9.9.10", VBAD)
        expect("Зламана версія запустилась", r"START v9\.9\.9\.10", 40, m)
        expect("Зламана версія впала", r"CRASH", 20, m)
        expect("Відкат", r"ROLLBACK v9\.9\.9\.10", 20, m)
        expect("Попередня версія знову працює", r"START v9\.9\.9\.9 .*/rolledback", 30, m)
        expect("Після відкату — тиша", r"STATE Clear", 30, m)
        m = mark()
        expect_none("Зламану версію не ставить вдруге", r"START v9\.9\.9\.10", 35, m)

    if V9999 and VHANG:
        # 14. Версія зависає при старті: після 3 «перезавантажень» без підтвердження — відкат
        m = mark(); W.version = ("9.9.9.11", VHANG)
        for i in range(3):
            expect(f"Завислу версію запущено ({i + 1})", r"START v9\.9\.9\.11", 45, m)
            time.sleep(3)
            if i < 2:
                kill_app(); m = mark(); start_app("/min")
        expect("Відкат після 3 запусків", r"ROLLBACK v9\.9\.9\.11", 20, m)
        expect("Попередня версія після відкату", r"START v9\.9\.9\.9 .*/rolledback", 30, m)
        time.sleep(3)
        step("Після відкату одна копія", len(app_pids()) == 1, f"процесів: {len(app_pids())}")

    finish()

def finish():
    crashes = [l for l in log_lines() if "CRASH" in l and "тестовий збій" not in l and "broken build" not in l]
    step("Немає неочікуваних збоїв у журналі", not crashes, crashes[0][24:300] if crashes else "")
    kill_app()

def report():
    ok = all(r[1] for r in results)
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    lines = ["| | Перевірка | Затримка | Деталі |", "|---|---|---|---|"]
    for n, good, d, lat in results:
        lines.append(f"| {'✅' if good else '❌'} | {n} | {'' if lat is None else f'{lat:.2f} с'} | {d.replace('|', '/')[:120]} |")
    if summ:
        with open(summ, "a", encoding="utf-8") as f: f.write("## Наскрізний тест Trevoga.exe\n\n" + "\n".join(lines) + "\n")
    print(f"\nПройдено {sum(1 for r in results if r[1])} з {len(results)}")
    shutil.copy(LOG, os.path.join(os.path.dirname(WORK), "e2e-trevoga.log")) if os.path.exists(LOG) else None
    return ok

if __name__ == "__main__":
    try: main()
    except Exception as e:
        import traceback; traceback.print_exc(); step("Сценарій завершився без винятків", False, repr(e))
        try: kill_app()
        except Exception: pass
    sys.exit(0 if report() else 1)
