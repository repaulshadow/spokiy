# Довга перевірка справжнього Trevoga.exe: прискорена «життя за тиждень» — тривоги кожні кілька секунд,
# обриви потоку й сервера; стежимо, щоб пам'ять, дескриптори й GDI не росли, а кожна тривога спрацьовувала.
# python soak.py <Trevoga.exe> <робоча папка> <хвилин>
import csv, os, statistics, sys, time
MINUTES = float(sys.argv[3])
sys.argv = sys.argv[:3]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e
from e2e import W, ODR, OD, change, mark, wait_log, log_lines, step, start_app, app_pids, kill_app

def metrics(pid):
    import psutil, ctypes
    p = psutil.Process(pid)
    gdi = user = -1
    if os.name == "nt":
        h = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
        gdi = ctypes.windll.user32.GetGuiResources(h, 0); user = ctypes.windll.user32.GetGuiResources(h, 1)
        ctypes.windll.kernel32.CloseHandle(h)
        handles = p.num_handles()
    else:
        handles = p.num_fds()
    mi = p.memory_info()
    return {"private_mb": round(getattr(mi, "private", mi.rss) / 1048576, 1), "ws_mb": round(mi.rss / 1048576, 1),
            "handles": handles, "threads": p.num_threads(), "gdi": gdi, "user": user}

def main():
    import shutil, threading
    from http.server import ThreadingHTTPServer
    os.makedirs(e2e.WORK, exist_ok=True)
    for f in os.listdir(e2e.WORK): os.remove(os.path.join(e2e.WORK, f))
    shutil.copy(e2e.EXE, e2e.APP)
    with open(os.path.join(e2e.WORK, "trevoga.ini"), "w", encoding="utf-8-sig") as f:
        f.write(f"region={OD}\nraion={e2e.ODR_UID}\nsound=1\nvoice=tetiana\nserver=http://127.0.0.1:{e2e.HTTP_PORT}\n"
                f"stream=ws://127.0.0.1:{e2e.WS_PORT}/api/v1/stream\ntest=1\n")
    threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", e2e.HTTP_PORT), e2e.H).serve_forever, daemon=True).start()
    threading.Thread(target=e2e.ws_server, daemon=True).start()
    m0 = mark(); start_app("/min")
    if not wait_log(r"STATE Clear", 60, m0):
        step("Soak: запуск", False); return
    pid = app_pids()[0]
    t0 = time.time(); samples = []; toggles = 0; last_sample = 0
    alerting = False
    while time.time() - t0 < MINUTES * 60:
        el = time.time() - t0
        # тривога / відбій кожні 4 с (через потік), рівень alerts.in.ua міняється кожні 9 с
        alerting = not alerting
        change(lambda: W.nep_r.__setitem__(ODR, (OD, "red", ["Ракетна загроза"])) if alerting else W.nep_r.pop(ODR, None))
        toggles += 1
        if int(el) % 9 < 4:
            change(lambda: W.ain.__setitem__(e2e.ODR_UID, (ODR, "yellow", ["drones"], e2e.NOW)) if alerting else W.ain.clear())
        # обрив потоку раз на ~2 хв на 5 с, сервер «лежить» раз на ~7 хв по 20 с
        if int(el) % 120 < 4: W.ws_down = True
        elif int(el) % 120 > 9: W.ws_down = False
        W.server_down = int(el) % 420 >= 400
        if el - last_sample >= 30:
            last_sample = el
            if pid not in app_pids():
                step("Soak: програма працює весь час", False, f"процес зник на {el/60:.1f} хв"); break
            s = metrics(pid); s["min"] = round(el / 60, 1); samples.append(s)
            print(s, flush=True)
        time.sleep(4)
    W.ws_down = W.server_down = False
    time.sleep(10)
    alive = pid in app_pids()
    lines = log_lines()[m0:]
    alarms = sum(1 for l in lines if "SOUND alarm" in l)
    clears = sum(1 for l in lines if "SOUND clear" in l)
    crashes = [l for l in lines if "CRASH" in l]
    step("Soak: програма жива наприкінці", alive)
    step("Soak: жодного збою", not crashes, crashes[0][24:300] if crashes else "")
    exp = toggles // 2
    step("Soak: тривоги спрацьовували", alarms >= exp * 0.8, f"сирен {alarms}, відбоїв {clears}, перемикань {toggles}")
    if len(samples) >= 6:
        base = samples[min(4, len(samples) - 3):min(8, len(samples) - 2)]
        tail = samples[-3:]
        def med(lst, k): return statistics.median(x[k] for x in lst)
        for k, lim_abs, lim_rel in (("private_mb", 40, 0.6), ("handles", 300, 0.5), ("gdi", 50, 0.5), ("user", 50, 0.5), ("threads", 15, 0.5)):
            b, t = med(base, k), med(tail, k)
            if b < 0: continue
            grow = t - b
            step(f"Soak: {k} не росте", grow <= max(lim_abs, b * lim_rel), f"{b} → {t}")
    with open(os.path.join(os.path.dirname(e2e.WORK), "soak.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(samples[0].keys()) if samples else ["min"]); w.writeheader(); w.writerows(samples)
    kill_app()

if __name__ == "__main__":
    try: main()
    except Exception as ex:
        import traceback; traceback.print_exc(); step("Soak завершився без винятків", False, repr(ex))
    sys.exit(0 if e2e.report() else 1)
