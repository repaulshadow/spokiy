# Простий HTTP-проксі з методом CONNECT (як корпоративний проксі в навчальному закладі). Пише, куди підключались.
import socket, sys, threading
PORT, LOG = int(sys.argv[1]), sys.argv[2]
def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except Exception: pass
    finally:
        for s in (a, b):
            try: s.close()
            except Exception: pass
def handle(c):
    try:
        req = b""
        while b"\r\n\r\n" not in req:
            d = c.recv(4096)
            if not d: return
            req += d
        line = req.split(b"\r\n")[0].decode()
        with open(LOG, "a") as f: f.write(line + "\n")
        method, target = line.split()[:2]
        if method != "CONNECT":
            c.sendall(b"HTTP/1.1 405 Only CONNECT\r\n\r\n"); c.close(); return
        host, port = target.rsplit(":", 1)
        u = socket.create_connection((host, int(port)), timeout=20)
        c.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
        threading.Thread(target=pipe, args=(c, u), daemon=True).start()
        pipe(u, c)
    except Exception as e:
        with open(LOG, "a") as f: f.write("error " + repr(e) + "\n")
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", PORT)); s.listen(32)
while True:
    c, _ = s.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
