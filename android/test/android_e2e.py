# Наскрізний тест Android-версії в емуляторі: справжній APK (зібраний з адресою сервера-імітації 10.0.2.2:8787),
# сервер-імітація з test/e2e.py (/status і потік /stream), реакція програми — за системним журналом (logcat, тег Trevoga).
# python android_e2e.py <apk>
import os, re, subprocess, sys, threading, time, datetime as dt

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = [sys.argv[0], "-", os.path.join(HERE, "..", "build", "e2e-unused")] + sys.argv[1:]
APK = os.path.abspath(sys.argv[2 + 1]) if len(sys.argv) > 3 else None
sys.path.insert(0, os.path.join(HERE, "..", "..", "test"))
import e2e
from e2e import W, change, report
import e2e as _e2e

def step(name, ok, detail="", latency=None):
    _e2e.step(name, ok, detail, latency)

def summary():
    rows = [("✔ " if ok else "✘ ") + n + (f" ({lat:.2f} с)" if lat is not None else "") + ("" if ok else " — " + d[:160]) for n, ok, d, lat in _e2e.results]
    for i in range(0, len(rows), 12):
        print("::notice title=результати " + str(i // 12 + 1) + "::" + " ¦ ".join(rows[i:i + 12]), flush=True)
    crash = adb("logcat", "-d", "-b", "crash")
    if crash.strip():
        print("::error title=журнал аварій Android::" + crash.replace("\n", " ¦ ")[:3500], flush=True)
    tail = adb("logcat", "-d", "-v", "time", "-s", "Trevoga:I", "AndroidRuntime:E", "MediaPlayer:E", "AudioTrack:E")
    print("::notice title=останні рядки журналу::" + " ¦ ".join(tail.strip().splitlines()[-30:])[:3500], flush=True)
    pid = adb("shell", "pidof", PKG).strip()
    print("::notice title=процес::" + (pid or "немає"), flush=True)
from http.server import ThreadingHTTPServer

PKG = "ua.spokiy.trevoga"
KYIV = "м. Київ"
KYIV_UID = e2e.OU[e2e.ON.index(KYIV)]

def adb(*a, check=False):
    return subprocess.run(["adb"] + list(a), capture_output=True, text=True, check=check).stdout

LOG = []
LOGCAT = []
def logcat_reader():
    # stderr/stdin — окремо, щоб процес logcat не тримав вивід скрипта (інакше обгортка чекає вічно)
    p = subprocess.Popen(["adb", "logcat", "-v", "epoch", "-s", "Trevoga:I", "AndroidRuntime:E"], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
    LOGCAT.append(p)
    for line in p.stdout:
        line = line.rstrip()
        m = re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(\S+)\s*:\s*(.*)", line)
        if m: LOG.append((float(m.group(1)), m.group(2), m.group(3)))
        if "Trevoga" in line or "FATAL" in line: print("logcat:", line, flush=True)

def mark(): return len(LOG)
def wait(pattern, timeout, after):
    rx = re.compile(pattern); end = time.time() + timeout
    while time.time() < end:
        for t, tag, msg in LOG[after:]:
            if tag == "Trevoga" and rx.search(msg): return t, msg
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
    bad = [m for t, tag, m in LOG[after:] if tag == "Trevoga" and rx.search(m)]
    step(name, not bad, ("зайве: " + bad[0][:140]) if bad else f"{seconds} с без «{pattern}»")

def main():
    threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", e2e.HTTP_PORT), e2e.H).serve_forever, daemon=True).start()
    adb("logcat", "-c")
    threading.Thread(target=logcat_reader, daemon=True).start()
    out = adb("install", "-r", "-g", APK)
    step("APK встановлено", "Success" in out, out.strip()[-200:])
    adb("shell", "dumpsys", "deviceidle", "whitelist", "+" + PKG)
    m = mark()
    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    expect("Служба запустилась", r"^START v", 60, m)
    expect("Потік сервера (UkraineAlarm) підключено", r"UASTREAM підключено", 60, m)
    expect("Початковий стан: тиша", r"STATE Clear", 60, m)
    expect_none("Без звуку при запуску", r"SOUND", 3, m)

    # 1. Офіційна тривога потоком
    m = mark(); t = change(lambda: W.ua_o.__setitem__(KYIV, "red"))
    expect("Тривога з UkraineAlarm — швидкість", r"STATE Alert.*UkraineAlarm наживо", 15, m, t, max_latency=2.0)
    expect("Сирена", r"SOUND alarm", 5, m)
    # 2. NEPTUN і alerts.in.ua теж показують (через опитування), потім офіційний відбій — одразу
    m = mark()
    change(lambda: (W.nep_o.__setitem__(KYIV, ("red", ["Ракетна загроза"])), W.ain.__setitem__(KYIV_UID, (None, "red", ["unspecified_missiles"], e2e.NOW))))
    # поки потік працює, запасні джерела опитуються раз на 30 с — чекаємо, доки вони теж покажуть тривогу
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
    # (нова тривога alerts.in.ua — з поточним часом початку; «нові» тривоги NEPTUN одразу після офіційного відбою
    #  програма свідомо ігнорує 90 с, бо NEPTUN запізнюється)
    now_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    m = mark(); t = change(lambda: W.ain.__setitem__(KYIV_UID, (None, "yellow", ["drones"], now_iso)))
    expect("Без потоку — тривога через опитування", r"STATE Alert level=yellow", 25, m, t, max_latency=15)
    m = mark(); t = change(lambda: W.ain.clear())
    expect("Без потоку — відбій через опитування", r"SOUND clear", 25, m, t, max_latency=15)
    m = mark(); W.ua_ws_down = False
    expect("Потік відновився сам", r"UASTREAM підключено", 90, m)
    # 4. Тривога через відновлений потік, балістика
    m = mark(); t = change(lambda: W.ua_o.__setitem__(KYIV, "red"))
    expect("Після відновлення — тривога потоком", r"STATE Alert.*UkraineAlarm наживо", 15, m, t, max_latency=2.0)
    m = mark(); change(lambda: W.ain.__setitem__(KYIV_UID, (None, "red", ["ballistic_missiles"], e2e.NOW)))
    expect("Балістика (через опитування)", r"SOUND ballistic", 45, m)
    m = mark(); change(lambda: (W.ua_o.clear(), W.ain.clear()))
    expect("Фінальний відбій", r"SOUND clear", 15, m)

    crashes = [msg for t, tag, msg in LOG if tag == "AndroidRuntime" and ("FATAL" in msg or PKG in msg)]
    step("Без аварій програми", not crashes, crashes[0][:200] if crashes else "")
    step("Служба жива наприкінці", adb("shell", "pidof", PKG).strip() != "")

if __name__ == "__main__":
    import signal
    if hasattr(signal, "SIGALRM"):
        def _timeout(*_):
            step("Сценарій уклався в 20 хв", False, "зупинено за таймаутом")
            try: summary()
            except Exception: pass
            for p in LOGCAT:
                try: p.kill()
                except Exception: pass
            os._exit(1)
        signal.signal(signal.SIGALRM, _timeout); signal.alarm(20 * 60)
    try: main()
    except Exception as ex:
        import traceback; traceback.print_exc(); step("Сценарій без винятків", False, repr(ex))
    for p in LOGCAT:
        try: p.kill()
        except Exception: pass
    try: summary()
    except Exception as ex: print("::warning::summary " + repr(ex))
    ok = all(r[1] for r in e2e.results)
    print(f"\nПройдено {sum(1 for r in e2e.results if r[1])} з {len(e2e.results)}")
    sys.exit(0 if ok else 1)
