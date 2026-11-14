# Наскрізний тест збірки для Google Play в емуляторі: сервер-імітація (test/e2e.py) + імітація push
# (той самий зріз області, що надсилає сервер через FCM; подається через тестовий intent — лише в тестовій збірці).
# python3 play_e2e.py <apk>
import json, os, re, subprocess, sys, threading, time
HERE = os.path.dirname(os.path.abspath(__file__))
APK = os.path.abspath(sys.argv[1])
sys.argv = [sys.argv[0], "-", os.path.join(HERE, "..", "build", "e2e-unused")]
sys.path.insert(0, os.path.join(HERE, "..", "..", "test"))
sys.path.insert(0, HERE)
import e2e
from e2e import W, change
from http.server import ThreadingHTTPServer
PKG = "ua.spokiy.trevoga"
KYIV = "м. Київ"
LOG = []

def adb(*a): return subprocess.run(["adb"] + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
def step(name, ok, detail="", latency=None): e2e.step(name, ok, detail, latency)
def reader():
    p = subprocess.Popen(["adb", "logcat", "-v", "brief", "Trevoga:I", "AndroidRuntime:E", "*:S"], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
    for line in p.stdout:
        line = line.rstrip()
        if line: LOG.append((time.time(), line)); print("app:", line, flush=True)
def mark(): return len(LOG)
def expect(name, pattern, timeout, after, t0=None, max_latency=None):
    rx = re.compile(pattern); end = time.time() + timeout
    while time.time() < end:
        for t, msg in LOG[after:]:
            if rx.search(msg):
                lat = max(0.0, t - t0) if t0 else None
                ok = max_latency is None or lat <= max_latency
                step(name, ok, msg[:140], lat); return True
        time.sleep(0.2)
    step(name, False, f"немає «{pattern}» за {timeout} с"); return False
def expect_none(name, pattern, seconds, after):
    time.sleep(seconds)
    bad = [m for t, m in LOG[after:] if re.search(pattern, m)]
    step(name, not bad, ("зайве: " + bad[0][:140]) if bad else "")
def push(d):
    t = time.time()
    adb("shell", "am start -n %s/.MainActivity --es push '%s'" % (PKG, json.dumps(d, ensure_ascii=False)))
    return t
def notif_has(text): return text in adb("shell", "dumpsys", "notification", "--noredact")
def services(): return adb("shell", "dumpsys", "activity", "services", PKG)
def run_job(): adb("shell", "cmd", "jobscheduler", "run", "-f", PKG, "7")

def real_fcm(now):
    """Справжній push через Google (Firebase) на тестову тему — лише де є сервіси Google Play і ключ у FCM_SA."""
    end = time.time() + (90 if os.environ.get("FCM_SA") else 0)
    while True:   # підписка на тему — асинхронна, може з'явитися за хвилину після запуску
        sub = [m for t, m in LOG if "PUSH підписка o_" in m]
        if sub or time.time() > end or any("PUSH підписка не вдалася" in m or "PUSH недоступний" in m for t, m in LOG): break
        time.sleep(2)
    if not os.environ.get("FCM_SA") or not sub:
        print("::notice title=Play FCM::справжній push не перевірявся (%s)" % ("немає ключа" if not os.environ.get("FCM_SA") else "в емуляторі немає сервісів Google Play"), flush=True)
        return
    topic = re.search(r"o_[0-9a-f]+_t", sub[-1])
    step("Тестова тема FCM", topic is not None, sub[-1][-80:])
    if not topic: return
    import fcm_send
    time.sleep(32)   # після попереднього push
    m = mark(); t = time.time()
    try: fcm_send.send(topic.group(0), KYIV, {"r": [], "o": [[KYIV, now, "red", ["drones"]]], "c": []})
    except Exception as ex: step("Справжній push надіслано через Firebase", False, repr(ex)[:200]); return
    step("Справжній push надіслано через Firebase", True)
    expect("Справжній push доставлено Google — швидкість", r"PUSH м\. Київ", 60, m, t, max_latency=20)
    expect("Справжній push: тривога зі звуком", r"SOUND alarm$", 10, m)
    # процес зупинено системою (не користувачем) — Google будить програму
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
    adb("shell", "am", "kill", PKG); time.sleep(3)
    m = mark(); t = time.time()
    fcm_send.send(topic.group(0), KYIV, {"r": [], "o": [], "c": []})
    expect("Справжній push будить зупинену програму — швидкість", r"PUSH м\. Київ", 60, m, t, max_latency=20)
    expect("Справжній push: відбій зі звуком із фону", r"SOUND clear$", 10, m)

def main():
    threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", e2e.HTTP_PORT), e2e.H).serve_forever, daemon=True).start()
    adb("uninstall", PKG)
    out = adb("install", "-r", "-g", APK)
    step("Встановлення", "Success" in out, out[-200:])
    adb("shell", "settings", "put", "global", "heads_up_notifications_enabled", "0")
    adb("logcat", "-c")
    threading.Thread(target=reader, daemon=True).start()
    m = mark()
    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    expect("Перший запуск: стан із сервера", r"STATE Clear", 90, m)
    expect_none("Перший запуск — без звуку", r"SOUND", 3, m)
    step("Постійної служби немає", "MonitorService" not in services())
    step("Запасне опитування заплановане", re.search(r"#u0a\d+/7\b|" + PKG + r"/\.PollJob", adb("shell", "dumpsys", "jobscheduler")) is not None)

    # 1. Push: тривога в області (зріз UkraineAlarm, як із сервера)
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    m = mark(); t = push({"r": [], "o": [[KYIV, now, "red", ["drones"]]], "c": []})
    expect("Push: тривога — швидкість", r"STATE Alert.*push", 15, m, t, max_latency=4.0)
    expect("Push: сирена (зі звуком)", r"SOUND alarm$", 5, m)
    time.sleep(2)
    step("Сповіщення про тривогу", notif_has("ПОВІТРЯНА ТРИВОГА"))
    step("Служба звуку працює під час сирени", "PlaySoundService" in services())
    # 2. Push: нова загроза, потім балістика
    m = mark(); push({"r": [], "o": [[KYIV, now, "red", ["drones", "cruise_missiles"]]], "c": []})
    expect("Push: нова загроза", r"SOUND newthreats", 10, m)
    m = mark(); push({"r": [], "o": [[KYIV, now, "red", ["drones", "cruise_missiles", "ballistic_missiles"]]], "c": []})
    expect("Push: балістика", r"SOUND ballistic", 10, m)
    # 3. Push: відбій (порожній зріз)
    m = mark(); t = push({"r": [], "o": [], "c": []})
    expect("Push: відбій — швидкість", r"STATE Clear", 15, m, t, max_latency=4.0)
    expect("Push: звук відбою", r"SOUND clear$", 5, m)
    time.sleep(2)
    step("Сповіщення про відбій", notif_has("Відбій тривоги"))
    # 4. Повтор того самого push — без повторного звуку
    m = mark(); push({"r": [], "o": [], "c": []})
    expect_none("Повторний push без змін — тиша", r"SOUND", 5, m)
    # 5. Служба звуку сама зупиняється
    end = time.time() + 75
    while time.time() < end and "PlaySoundService" in services(): time.sleep(3)
    step("Служба звуку зупинилась сама", "PlaySoundService" not in services())
    # 6. Push пропущено — тривогу знаходить запасне опитування (програма згорнута): сповіщення й вібрація, без сирени
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
    # одразу після push опитування пропускається (захист від застарілої відповіді сервера)
    m = mark(); t0 = time.time(); push({"r": [], "o": [], "c": []}); time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    change(lambda: W.ua_o.__setitem__(KYIV, "red")); time.sleep(3); run_job()
    expect_none("Одразу після push опитування не перебиває його", r"STATE", 8, m)
    change(lambda: W.ua_o.clear())
    time.sleep(max(0, 34 - (time.time() - t0)))
    m = mark(); change(lambda: W.ua_o.__setitem__(KYIV, "red")); time.sleep(3); run_job()
    expect("Опитування: тривога без push", r"STATE Alert", 30, m)
    expect("Опитування: лише сповіщення (Android не дозволяє звук із фону)", r"SOUND alarm \(без звуку", 5, m)
    time.sleep(1)
    step("Сповіщення про тривогу (опитування)", notif_has("ПОВІТРЯНА ТРИВОГА"))
    m = mark(); change(lambda: W.ua_o.clear()); time.sleep(3); run_job()
    expect("Опитування: відбій", r"SOUND clear", 30, m)
    # 7. Процес убито системою — push усе одно спрацьовує з правильним порівнянням (без «першого запуску»)
    adb("shell", "am", "kill", PKG); adb("shell", "am", "force-stop", PKG); time.sleep(2)
    m = mark(); t = push({"r": [], "o": [[KYIV, now, "yellow", []]], "c": []})
    expect("Після зупинки процесу: push → тривога зі звуком", r"SOUND alarm$", 20, m)
    m = mark(); push({"r": [], "o": [[KYIV, now, "red", []]], "c": []})
    expect("Жовтий → червоний", r"SOUND escalation", 10, m)
    m = mark(); push({"r": [], "o": [], "c": []})
    expect("Фінальний відбій", r"SOUND clear", 10, m)
    real_fcm(now)
    time.sleep(3)
    crashes = [msg for t, msg in LOG if "AndroidRuntime" in msg and ("FATAL" in msg or PKG in msg)]
    step("Без аварій програми", not crashes, crashes[0][:200] if crashes else "")

if __name__ == "__main__":
    try: main()
    except Exception as ex:
        import traceback; traceback.print_exc(); step("Сценарій без винятків", False, repr(ex))
    rows = [("✔ " if ok else "✘ ") + n + (f" ({lat:.2f} с)" if lat is not None else "") + ("" if ok else " — " + d[:160]) for n, ok, d, lat in e2e.results]
    for i in range(0, len(rows), 12):
        print("::notice title=Play результати " + str(i // 12 + 1) + "::" + " ¦ ".join(rows[i:i + 12]), flush=True)
    print(f"\nПройдено {sum(1 for r in e2e.results if r[1])} з {len(e2e.results)}", flush=True)
    os._exit(0 if all(r[1] for r in e2e.results) else 1)
