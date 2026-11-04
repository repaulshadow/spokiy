# Наскрізний тест Mac-версії: справжня Тривога.app проти сервера-імітації з test/e2e.py (/status, потік /stream, /threats).
# Реакція програми — за її журналом (stdout: START / UASTREAM / STATE / SOUND), як в Android-тесті.
# python3 mac_e2e.py <шлях до Trevoga.app> [тека для знімків екрана]
import os, re, subprocess, sys, threading, time, datetime as dt
HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.abspath(sys.argv[1])
SHOTS = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else None
sys.argv = [sys.argv[0], "-", os.path.join(HERE, "..", "build", "e2e-unused")]
sys.path.insert(0, os.path.join(HERE, "..", "..", "test"))
import e2e
from e2e import W, change
from http.server import ThreadingHTTPServer

KYIV = "м. Київ"
KYIV_UID = e2e.OU[e2e.ON.index(KYIV)]
LOG = []
PROC = []

def step(name, ok, detail="", latency=None): e2e.step(name, ok, detail, latency)

def start_app(extra=None):
    envv = dict(os.environ, TREVOGA_TEST="1", TREVOGA_SERVER="http://127.0.0.1:%d" % e2e.HTTP_PORT, TREVOGA_REGION=KYIV, TREVOGA_RAION="0")
    envv.update(extra or {})
    p = subprocess.Popen([os.path.join(APP, "Contents/MacOS/Trevoga")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         stdin=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace", env=envv)
    PROC.append(p)
    def rd():
        for line in p.stdout:
            line = line.rstrip()
            LOG.append((time.time(), line))
            print("app:", line, flush=True)
    threading.Thread(target=rd, daemon=True).start()
    return p

def mark(): return len(LOG)
def wait(pattern, timeout, after):
    rx = re.compile(pattern); end = time.time() + timeout
    while time.time() < end:
        for t, msg in LOG[after:]:
            if rx.search(msg): return t, msg
        time.sleep(0.1)
    return None
def expect(name, pattern, timeout, after, t_change=None, max_latency=None):
    r = wait(pattern, timeout, after)
    if not r: step(name, False, f"немає «{pattern}» за {timeout} с"); return None
    lat = max(0.0, r[0] - t_change) if t_change else None
    ok = max_latency is None or (lat is not None and lat <= max_latency)
    step(name, ok, r[1][:140] if ok else f"затримка {lat:.2f} с > {max_latency} с: {r[1][:120]}", lat)
    return r
def expect_none(name, pattern, seconds, after):
    time.sleep(seconds)
    rx = re.compile(pattern)
    bad = [m for t, m in LOG[after:] if rx.search(m)]
    step(name, not bad, ("зайве: " + bad[0][:140]) if bad else f"{seconds} с без «{pattern}»")
def shot(name):
    if not SHOTS: return
    os.makedirs(SHOTS, exist_ok=True)
    time.sleep(2)
    subprocess.run(["screencapture", "-x", os.path.join(SHOTS, name + ".png")])

def main():
    threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", e2e.HTTP_PORT), e2e.H).serve_forever, daemon=True).start()
    subprocess.run(["defaults", "delete", "ua.spokiy.trevoga"], capture_output=True)
    m = mark()
    p = start_app()
    expect("Програма запустилась", r"START v", 60, m)
    expect("Потік сервера (UkraineAlarm) підключено", r"UASTREAM підключено", 60, m)
    expect("Початковий стан: тиша", r"STATE Clear", 60, m)
    expect_none("Без звуку при запуску", r"SOUND", 3, m)
    shot("01_clear")

    # 1. Офіційна тривога потоком
    m = mark(); t = change(lambda: W.ua_o.__setitem__(KYIV, "red"))
    expect("Тривога з UkraineAlarm — швидкість", r"STATE Alert.*UkraineAlarm наживо", 15, m, t, max_latency=2.0)
    expect("Сирена", r"SOUND alarm", 5, m)
    shot("02_alert")
    # 2. Запасні джерела теж показують тривогу, потім офіційний відбій — одразу
    m = mark()
    change(lambda: (W.nep_o.__setitem__(KYIV, ("red", ["Ракетна загроза"])), W.ain.__setitem__(KYIV_UID, (None, "red", ["unspecified_missiles"], e2e.NOW))))
    expect("Запасні джерела (опитування) теж показують тривогу", r"STATE Alert.*unspecified_missiles", 45, m)
    m = mark(); t = change(lambda: W.ua_o.pop(KYIV))
    expect("Офіційний відбій — одразу, не чекаючи NEPTUN", r"STATE Clear.*відбій за UkraineAlarm", 15, m, t, max_latency=2.0)
    expect("Звук відбою", r"SOUND clear", 5, m)
    expect_none("Запізнілі NEPTUN і alerts.in.ua не вмикають тривогу знову", r"STATE Alert|SOUND alarm", 25, m)
    change(lambda: (W.nep_o.clear(), W.ain.clear()))
    time.sleep(12)
    # 3. Потік недоступний — тривога і відбій через опитування сервера
    m = mark(); W.ua_ws_down = True
    expect("Втрату потоку помічено", r"UASTREAM (?!підключено)", 60, m)
    time.sleep(3)
    now_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    m = mark(); t = change(lambda: W.ain.__setitem__(KYIV_UID, (None, "yellow", ["drones"], now_iso)))
    expect("Без потоку — тривога через опитування", r"STATE Alert level=yellow", 25, m, t, max_latency=15)
    m = mark(); t = change(lambda: W.ain.clear())
    expect("Без потоку — відбій через опитування", r"SOUND clear", 25, m, t, max_latency=15)
    m = mark(); W.ua_ws_down = False
    expect("Потік відновився сам", r"UASTREAM підключено", 90, m)
    # 4. Тривога через відновлений потік, балістика, жовтий → червоний
    m = mark(); t = change(lambda: W.ua_o.__setitem__(KYIV, "red"))
    expect("Після відновлення — тривога потоком", r"STATE Alert.*UkraineAlarm наживо", 15, m, t, max_latency=2.0)
    m = mark(); change(lambda: W.ain.__setitem__(KYIV_UID, (None, "red", ["ballistic_missiles"], e2e.NOW)))
    expect("Балістика (через опитування)", r"SOUND ballistic", 45, m)
    shot("03_ballistic")
    m = mark(); change(lambda: (W.ua_o.clear(), W.ain.clear()))
    expect("Фінальний відбій", r"SOUND clear", 15, m)
    step("Програма жива", p.poll() is None)

    # 5. Перезапуск посеред тривоги: без повторної сирени; вигляд «Карта»
    m = mark(); change(lambda: (W.ua_o.__setitem__(KYIV, "red"), W.ua_o.__setitem__("Одеська область", "yellow")))
    expect("Тривога перед перезапуском", r"SOUND alarm", 15, m)
    p.terminate(); p.wait(10)
    subprocess.run(["defaults", "write", "ua.spokiy.trevoga", "mapView", "-bool", "true"])
    m = mark()
    p = start_app()
    expect("Після перезапуску — тривога", r"STATE Alert", 60, m)
    expect_none("Після перезапуску — без повторної сирени", r"SOUND", 8, m)
    shot("04_map")
    subprocess.run(["defaults", "write", "ua.spokiy.trevoga", "mapView", "-bool", "false"])
    step("Без аварій", not any("Fatal error" in x or "Illegal instruction" in x for _, x in LOG), "")
    p.terminate()

if __name__ == "__main__":
    try: main()
    except Exception as ex:
        import traceback; traceback.print_exc(); step("Сценарій без винятків", False, repr(ex))
    for p in PROC:
        try: p.kill()
        except Exception: pass
    rows = [("✔ " if ok else "✘ ") + n + (f" ({lat:.2f} с)" if lat is not None else "") + ("" if ok else " — " + d[:160]) for n, ok, d, lat in e2e.results]
    for i in range(0, len(rows), 12):
        print("::notice title=Mac результати " + str(i // 12 + 1) + "::" + " ¦ ".join(rows[i:i + 12]), flush=True)
    ok = all(r[1] for r in e2e.results)
    print(f"\nПройдено {sum(1 for r in e2e.results if r[1])} з {len(e2e.results)}")
    sys.exit(0 if ok else 1)
