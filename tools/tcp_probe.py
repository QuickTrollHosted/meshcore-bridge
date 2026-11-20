#!/usr/bin/env python3
"""Sonde TCP du protocole compagnon MeshCore (stdlib uniquement).

Valide le framing du pont sans la bibliothèque `meshcore` :
  client -> pont : 0x3C + longueur (uint16 LE) + trame
  pont -> client : 0x3E + longueur (uint16 LE) + trame

Usage : adb forward tcp:5000 tcp:5000   (ou directement l'IP du téléphone)
        python3 tools/tcp_probe.py [--host 127.0.0.1] [--port 5000] [--hex .local/trace.txt]

Envoie CMD_APP_START puis CMD_DEVICE_QUERY, vérifie les codes de réponse et affiche
un résumé. N'affiche que les codes/longueurs, jamais le contenu des messages.
"""
import argparse
import socket
import struct
import sys
import time

TO_NODE, FROM_NODE = 0x3C, 0x3E
RESP_SELF_INFO, RESP_DEVICE_INFO, RESP_ERR = 0x05, 0x0D, 0x01


def frame(payload: bytes) -> bytes:
    return bytes([TO_NODE]) + struct.pack("<H", len(payload)) + payload


def read_exact(sock, n, deadline):
    buf = b""
    while len(buf) < n:
        sock.settimeout(max(0.05, deadline - time.monotonic()))
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connexion fermée par le pont")
        buf += chunk
    return buf


def read_frame(sock, timeout):
    deadline = time.monotonic() + timeout
    hdr = read_exact(sock, 3, deadline)
    if hdr[0] != FROM_NODE:
        raise ValueError(f"marqueur inattendu 0x{hdr[0]:02x} (attendu 0x3e)")
    (length,) = struct.unpack("<H", hdr[1:])
    return read_exact(sock, length, deadline) if length else b""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--timeout", type=float, default=8.0)
    ap.add_argument("--trace", help="fichier où écrire une trace hexadécimale brute (à garder dans .local/)")
    a = ap.parse_args()

    trace = open(a.trace, "a") if a.trace else None

    def tr(direction, data):
        if trace:
            trace.write(f"{time.strftime('%H:%M:%S')} {direction} {data.hex()}\n")
            trace.flush()

    ok = True
    with socket.create_connection((a.host, a.port), timeout=5) as s:
        print(f"connecté à {a.host}:{a.port}")

        app_start = b"\x01" + b"\x00" * 7 + b"tcp_probe"
        s.sendall(frame(app_start)); tr("->", frame(app_start))
        r = read_frame(s, a.timeout); tr("<-", r)
        print(f"APP_START   -> réponse code 0x{r[0]:02x}, {len(r)} octets", "OK" if r[0] == RESP_SELF_INFO else "INATTENDU")
        ok &= r[0] == RESP_SELF_INFO

        dq = b"\x16\x03"
        s.sendall(frame(dq)); tr("->", frame(dq))
        r = read_frame(s, a.timeout); tr("<-", r)
        print(f"DEVICE_QUERY -> réponse code 0x{r[0]:02x}, {len(r)} octets", "OK" if r[0] == RESP_DEVICE_INFO else "INATTENDU")
        ok &= r[0] == RESP_DEVICE_INFO

        # Deux commandes concaténées dans un seul send() : le pont doit les traiter l'une après l'autre.
        s.sendall(frame(dq) + frame(dq))
        r1 = read_frame(s, a.timeout); r2 = read_frame(s, a.timeout)
        both = r1[0] == r2[0] == RESP_DEVICE_INFO
        print("2 commandes concaténées ->", "2 réponses OK" if both else f"codes 0x{r1[0]:02x}/0x{r2[0]:02x}")
        ok &= both

    print("RÉSULTAT :", "SUCCÈS" if ok else "ÉCHEC")
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, ConnectionError) as e:
        print("ERREUR :", e)
        sys.exit(2)
