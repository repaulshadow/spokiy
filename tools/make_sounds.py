# Сирена, відбій і сигнал «Балістика» у WAV — ті самі звуки, що синтезує Android-версія (Sounds.java).
# python3 make_sounds.py <тека>
import math, os, random, struct, sys, wave
RATE = 22050
def tanh(x): return math.tanh(x)
def write(path, s):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", max(-32768, min(32767, int(v)))) for v in s))
def siren_freq(t):
    T, up, down, lo, top = 24, 3, 3.5, 150, 620
    if t < up: return lo + (top - lo) * (1 - math.exp(-3 * t / up)) / (1 - math.exp(-3))
    wail = 500 + 120 * math.cos(2 * math.pi * (min(t, T - down) - up) / 5.0)
    if t <= T - down: return wail
    k = (t - (T - down)) / down
    return lo + (wail - lo) * (1 - min(1, k)) ** 1.5
def siren():
    T = 24; n = int(RATE * T); harm = [1.0, 0.5, 0.35, 0.22, 0.14, 0.08]; norm = sum(harm)
    rnd = random.Random(7); phase = 0; sat = tanh(1.8); out = []
    for i in range(n):
        t = i / RATE
        f = siren_freq(t) * (1 + 0.004 * math.sin(2 * math.pi * 5.5 * t))
        phase += 2 * math.pi * f / RATE
        v = sum(h * math.sin((k + 1) * phase) for k, h in enumerate(harm)) / norm + 0.03 * (rnd.random() * 2 - 1)
        v = tanh(1.8 * v) / sat
        env = min(1.0, f / 400.0) * min(1.0, t / 0.2) * min(1.0, (n - i) / (RATE * 0.4))
        out.append(v * env * 0.85 * 32767)
    return out
def all_clear():
    notes = [523.25, 659.25, 783.99, 1046.5]; note_len, gap, reps = 0.45, 0.9, 2
    total = reps * (len(notes) * note_len + gap) + 1.2; n = int(RATE * total); mix = [0.0] * n
    for r in range(reps):
        for j, fr in enumerate(notes):
            s0 = int((r * (len(notes) * note_len + gap) + j * note_len) * RATE)
            for i in range(int(1.4 * RATE)):
                if s0 + i >= n: break
                t = i / RATE
                env = min(1.0, t / 0.01) * math.exp(-t * 3.2)
                mix[s0 + i] += (math.sin(2 * math.pi * fr * t) + 0.3 * math.sin(4 * math.pi * fr * t)) * env * 0.35
    return [max(-1.0, min(1.0, v)) * 32767 for v in mix]
def ballistic():
    T, beep = 8, 0.12; n = int(RATE * T); sat = tanh(1.5); out = []
    for i in range(n):
        t = i / RATE; k = int(t / beep); local = t - k * beep
        g = (t % 2.0) > 1.6
        f = 1250 if k % 2 == 0 else 1650
        env = 0 if g else min(1.0, local / 0.005) * min(1.0, (beep - local) / 0.01)
        v = math.sin(2 * math.pi * f * t) + 0.35 * math.sin(6 * math.pi * f * t)
        out.append(tanh(1.5 * v) / sat * env * 0.85 * 32767)
    return out
d = sys.argv[1]; os.makedirs(d, exist_ok=True)
write(os.path.join(d, "siren.wav"), siren()); write(os.path.join(d, "clear.wav"), all_clear()); write(os.path.join(d, "ballistic.wav"), ballistic())
print("звуки готові:", d)
