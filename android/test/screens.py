# Знімки екрана Android-версії в емуляторі (для перевірки дизайну): головний екран, налаштування,
# шторка сповіщень під час тривоги й після відбою, темна тема, значок у меню програм.
# python screens.py <apk> <тека для PNG>
import os, re, subprocess, sys, threading, time
HERE = os.path.dirname(os.path.abspath(__file__))
APK, OUT = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
sys.argv = [sys.argv[0], "-", os.path.join(HERE, "..", "build", "e2e-unused")]
sys.path.insert(0, os.path.join(HERE, "..", "..", "test"))
import e2e
from e2e import W, change
from http.server import ThreadingHTTPServer
PKG = "ua.spokiy.trevoga"
os.makedirs(OUT, exist_ok=True)

def adb(*a): return subprocess.run(["adb"] + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
def shot(name):
    time.sleep(1.5)
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print("знімок", name, flush=True)
def log_has(rx, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if re.search(rx, adb("logcat", "-d", "-s", "Trevoga:I")): return True
        time.sleep(0.5)
    print("::warning::немає", rx, flush=True); return False
def tap_desc(desc):
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    x = adb("shell", "cat", "/sdcard/u.xml")
    m = re.search(r'content-desc="' + re.escape(desc) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if not m: print("::warning::не знайдено", desc, flush=True); return
    a = list(map(int, m.groups()))
    adb("shell", "input", "tap", str((a[0] + a[2]) // 2), str((a[1] + a[3]) // 2))
def size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (320, 640)
def swipe_up():
    w, h = size()
    adb("shell", "input", "swipe", str(w // 2), str(h * 8 // 10), str(w // 2), str(h * 3 // 10), "500")
def tap_text(t):
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    x = adb("shell", "cat", "/sdcard/u.xml")
    m = re.search(r'text="' + re.escape(t) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if m:
        a = list(map(int, m.groups()))
        adb("shell", "input", "tap", str((a[0] + a[2]) // 2), str((a[1] + a[3]) // 2))

threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", e2e.HTTP_PORT), e2e.H).serve_forever, daemon=True).start()
adb("install", "-r", "-g", APK)
adb("shell", "dumpsys", "deviceidle", "whitelist", "+" + PKG)
adb("shell", "settings", "put", "global", "heads_up_notifications_enabled", "0")
adb("logcat", "-c")
adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
log_has(r"STATE Clear", 90)
time.sleep(2); tap_text("OK")   # Android 14: попередження про старий targetSdk
shot("01_home")
tap_desc("Налаштування"); shot("02_settings_1")
swipe_up(); shot("03_settings_2")
swipe_up(); shot("04_settings_3")
swipe_up(); shot("04_settings_4")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
adb("logcat", "-c")
change(lambda: W.ua_o.__setitem__(e2e.ON[e2e.ON.index("м. Київ")], "red"))
log_has(r"STATE Alert", 30)
adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
shot("05_home_alert")
adb("shell", "cmd", "statusbar", "expand-notifications"); shot("06_shade_alert")
adb("shell", "cmd", "statusbar", "collapse")
adb("shell", "cmd", "uimode", "night", "yes"); time.sleep(3); shot("07_home_alert_dark")
tap_desc("Налаштування"); shot("08_settings_dark")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
adb("shell", "cmd", "uimode", "night", "no")
adb("logcat", "-c")
change(lambda: W.ua_o.clear())
log_has(r"SOUND clear", 30)
adb("shell", "cmd", "statusbar", "expand-notifications"); shot("09_shade_clear")
adb("shell", "cmd", "statusbar", "collapse")
adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(2)
swipe_up(); shot("10_launcher")
os._exit(0)
