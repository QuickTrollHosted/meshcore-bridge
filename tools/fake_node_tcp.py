#!/usr/bin/env python3
"""Faux pont TCP (stdlib) pour valider tcp_probe.py sans téléphone ni nœud.
Répond SELF_INFO (0x05) à APP_START (0x01) et DEVICE_INFO (0x0D) à DEVICE_QUERY (0x16).
Usage : python3 tools/fake_node_tcp.py [port]   puis   python3 tools/tcp_probe.py --port <port>"""
import socket, struct, sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port)); srv.listen(1); print("faux nœud sur", port, flush=True)
REPLY = {0x01: 0x05, 0x16: 0x0D}
while True:
    c, _ = srv.accept(); buf = b""
    while True:
        d = c.recv(1024)
        if not d: break
        buf += d
        while len(buf) >= 3:
            assert buf[0] == 0x3C, "marqueur client invalide"
            n = struct.unpack("<H", buf[1:3])[0]
            if len(buf) < 3 + n: break
            cmd, buf = buf[3], buf[3 + n:]
            body = bytes([REPLY.get(cmd, 0x01)]) + b"\x00" * 10
            c.sendall(bytes([0x3E]) + struct.pack("<H", len(body)) + body)
    c.close()
