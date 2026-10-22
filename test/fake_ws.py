# Навмисно «незручний» сервер-імітація потоку NEPTUN для перевірки WebSocket-клієнта Trevoga.cs.
# Кожне нове підключення — наступний сценарій. Результати перевірок сервера — у файл (аргумент 2).
import base64, hashlib, json, os, socket, struct, sys, threading, time

PORT = int(sys.argv[1]); OUT = sys.argv[2]
results = {"connections": 0, "pong_ok": False, "close_reply_ok": False, "client_masked": True,
           "handshake_ok": True, "errors": []}
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

def alerts(marker, pad=0, type_last=False):
    data = {"version": 1, "updatedAt": "2026-10-22T20:00:00Z",
            "raions": [{"key": marker.lower(), "name": marker, "oblast": "Одеська область",
                        "since": "2026-10-22T20:00:00Z", "level": "red", "reasons": ["Ракетна загроза (червоний рівень)"]}],
            "oblasts": []}
    if pad: data["padding"] = "x" * pad
    if type_last:
        return json.dumps({"ts": "2026-10-22T20:00:00Z", "data": data, "type": "alerts"}, ensure_ascii=False).encode()
    return json.dumps({"type": "alerts", "ts": "2026-10-22T20:00:00Z", "data": data}, ensure_ascii=False).encode()

def frame(op, payload, fin=True):
    b0 = (0x80 if fin else 0) | op
    n = len(payload)
    if n < 126: h = struct.pack("!BB", b0, n)
    elif n < 65536: h = struct.pack("!BBH", b0, 126, n)
    else: h = struct.pack("!BBQ", b0, 127, n)
    return h + payload

def recv_exact(c, n):
    b = b""
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b

def read_frame(c):
    b0, b1 = recv_exact(c, 2)
    op, masked, n = b0 & 0x0F, b1 & 0x80, b1 & 0x7F
    if n == 126: n = struct.unpack("!H", recv_exact(c, 2))[0]
    elif n == 127: n = struct.unpack("!Q", recv_exact(c, 8))[0]
    if not masked: results["client_masked"] = False
    mk = recv_exact(c, 4) if masked else b"\0\0\0\0"
    p = bytearray(recv_exact(c, n))
    for i in range(n): p[i] ^= mk[i & 3]
    return op, bytes(p)

def handshake(c, accept=True):
    req = b""
    while b"\r\n\r\n" not in req:
        x = c.recv(4096)
        if not x: raise EOFError
        req += x
    t = req.decode("latin1")
    hdr = {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in t.split("\r\n")[1:] if ":" in l}
    if not (t.startswith("GET /api/v1/stream ") and hdr.get("upgrade", "").lower() == "websocket"
            and hdr.get("sec-websocket-version") == "13" and len(base64.b64decode(hdr.get("sec-websocket-key", ""))) == 16):
        results["handshake_ok"] = False
        results["errors"].append("bad handshake: " + t[:200])
    if not accept:
        c.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"); return False
    acc = base64.b64encode(hashlib.sha1((hdr.get("sec-websocket-key", "") + GUID).encode()).digest()).decode()
    c.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").encode())
    return True

def hb(): return frame(1, json.dumps({"type": "heartbeat", "ts": "2026-10-22T20:00:00Z"}).encode())

def scenario(n, c):
    if n == 1:
        handshake(c)
        c.sendall(frame(1, alerts("A")) + hb())
        c.sendall(frame(9, b"p1"))
        op, p = read_frame(c)
        results["pong_ok"] = (op == 10 and p == b"p1")
        b = alerts("B"); k = len(b) // 3
        c.sendall(frame(1, b[:k], fin=False) + frame(9, b"p2") + frame(0, b[k:2 * k], fin=False) + frame(0, b[2 * k:]))
        op, p = read_frame(c)
        if not (op == 10 and p == b"p2"): results["pong_ok"] = False
        time.sleep(0.3)
        c.sendall(frame(1, alerts("B")))                      # те саме — не має бути події
        c.sendall(frame(2, alerts("BIN")))                    # бінарний кадр — ігнорувати
        c.sendall(frame(1, alerts("C", pad=70000)))           # 64-бітна довжина
        big = json.dumps({"type": "snapshot", "ts": "x", "data": {"threats": [{"id": i, "title": "Група БпЛА", "raions": []} for i in range(60000)]}}).encode()
        c.sendall(frame(1, big))                              # ~3 МБ треків — ігнорувати
        c.sendall(frame(1, alerts("D")))
        c.sendall(frame(1, alerts("E", pad=500, type_last=True)))  # «type» наприкінці
        time.sleep(1.0)
        c.sendall(frame(8, struct.pack("!H", 1000)))
        op, p = read_frame(c)
        results["close_reply_ok"] = op == 8
    elif n == 2:
        handshake(c, accept=False)
    elif n == 3:
        handshake(c)
        c.sendall(frame(1, alerts("F")))
        time.sleep(75)                                        # мовчанка: клієнт має сам перепідключитися
    elif n == 4:
        handshake(c)
        c.sendall(frame(1, alerts("G")))
        time.sleep(0.5)
        c.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("hh" if os.name == "nt" else "ii", 1, 0))   # обрив (RST)
    else:
        handshake(c)
        c.sendall(frame(1, alerts("H")))
        while True:
            time.sleep(5); c.sendall(hb())

def serve(c):
    results["connections"] += 1
    n = results["connections"]
    try: scenario(n, c)
    except Exception as e:
        if n != 3: results["errors"].append(f"conn{n}: {type(e).__name__} {e}")
    finally:
        try: c.close()
        except Exception: pass
    save()

def save():
    with open(OUT, "w") as f: json.dump(results, f, ensure_ascii=False)

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", PORT)); s.listen(8); save()
while True:
    c, _ = s.accept()
    threading.Thread(target=serve, args=(c,), daemon=True).start()
