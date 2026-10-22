# Збирає Trevoga.cs (або варіант для тестів) стандартним компілятором .NET Framework 4 (як build.bat).
# python build_win.py <out.exe> [--main Клас] [--version 9.9.9.9] [--mutate bad|hang] [додаткові .cs ...]
import argparse, os, re, subprocess, sys, tempfile
ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("--main"); ap.add_argument("--version"); ap.add_argument("--mutate")
ap.add_argument("extra", nargs="*")
a = ap.parse_args()
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = open(os.path.join(root, "Trevoga.cs"), encoding="utf-8-sig").read()
if a.version:
    src = re.sub(r'AssemblyVersion\("[^"]+"\)', f'AssemblyVersion("{a.version}")', src)
if a.mutate:
    body = {"bad": 'throw new Exception("broken build (test)");',
            "hang": "System.Threading.Thread.Sleep(System.Threading.Timeout.Infinite);"}[a.mutate]
    src, n = re.subn(r"(public MainForm\(bool startHidden\)\s*\{)", r"\1 " + body.replace("\\", "\\\\"), src)
    assert n == 1, "не знайдено конструктор MainForm"
tmp = os.path.join(tempfile.mkdtemp(), "Trevoga.cs")
open(tmp, "w", encoding="utf-8-sig").write(src)
csc = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), r"Microsoft.NET\Framework\v4.0.30319\csc.exe")
args = [csc, "/nologo", "/optimize+", "/codepage:65001", "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll",
        "/target:" + ("exe" if a.main else "winexe"), "/out:" + os.path.abspath(a.out),
        "/resource:" + os.path.join(root, "config.json") + ",config.json"]
if a.main: args.append("/main:" + a.main)
vdir = os.path.join(root, "voice")
for f in sorted(os.listdir(vdir)):
    if f.endswith(".wav"): args.append("/resource:" + os.path.join(vdir, f) + ",voice." + f)
args += [tmp] + [os.path.abspath(x) for x in a.extra]
r = subprocess.run(args, capture_output=True, text=True)
out = (r.stdout + r.stderr).strip()
if out: print(out)
if r.returncode: sys.exit("csc: помилка збірки")
print("OK:", a.out)
