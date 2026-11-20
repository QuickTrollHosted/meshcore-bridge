#!/usr/bin/env python3
"""Client de test avec la vraie bibliothèque `meshcore` (à installer dans un venv hors dépôt).

    python3 -m venv .local/venv && .local/venv/bin/pip install meshcore
    adb forward tcp:5000 tcp:5000
    .local/venv/bin/python tools/probe_meshcore.py --host 127.0.0.1 --port 5000

N'affiche que le nom du nœud et des compteurs, pas de contenu de messages.
"""
import argparse
import asyncio
import sys

from meshcore import MeshCore


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5000)
    a = ap.parse_args()

    mc = await MeshCore.create_tcp(a.host, a.port, default_timeout=10)
    if mc is None:
        print("ÉCHEC : pas de réponse APP_START (SELF_INFO)")
        return 1
    info = mc.self_info or {}
    print("SELF_INFO reçu : nom =", info.get("name"), "| tx_power =", info.get("tx_power"))

    dev = await mc.commands.send_device_query()
    print("DEVICE_QUERY :", "erreur" if dev.type.name == "ERROR" else f"OK ({dev.type.name})")

    t = await mc.commands.get_time()
    print("GET_TIME :", "erreur" if t.type.name == "ERROR" else f"OK ({t.payload})")

    ct = await mc.commands.get_contacts()
    n = 0 if ct.type.name == "ERROR" else len(ct.payload)
    print(f"GET_CONTACTS : {'erreur' if ct.type.name == 'ERROR' else 'OK'} ({n} contacts)")

    await mc.disconnect()
    ok = dev.type.name != "ERROR"
    print("RÉSULTAT :", "SUCCÈS" if ok else "ÉCHEC")
    return 0 if ok else 1


sys.exit(asyncio.run(main()))
