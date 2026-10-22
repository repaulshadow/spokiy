# Запускає команду, показує вивід і дублює важливе в анотації GitHub (щоб бачити результат без повних логів).
import subprocess, sys, os
p = subprocess.run(sys.argv[1:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
out = p.stdout.decode("utf-8", "replace") if os.name != "nt" else p.stdout.decode("utf-8", "replace")
print(out, flush=True)
lines = [l.strip() for l in out.splitlines() if l.strip()]
bad = [l for l in lines if l.startswith("FAIL") or "error" in l.lower() or "exception" in l.lower()]
name = os.path.basename(sys.argv[1]) if len(sys.argv) > 1 else "?"
for l in bad[:9]:
    print(f"::error title={name}::{l[:900]}")
print(f"::notice title={name} (код {p.returncode})::" + " ¦ ".join(lines[-12:])[:3000])
sys.exit(p.returncode)
