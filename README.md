# MeshCore Bridge

Relais Android entre un nœud **MeshCore** (firmware compagnon BLE) et des clients **TCP**
qui parlent le protocole compagnon comme s'ils étaient branchés directement au nœud.

> ⚠️ Le nœud n'accepte qu'**une seule** connexion BLE. Tant que le relais est connecté,
> l'appli officielle MeshCore ne peut plus s'y connecter en BLE : elle devra passer par le TCP du relais.

## Protocole (vérifié dans le firmware `examples/companion_radio` et `meshcore-proxy`)

| Transport | Format d'une trame |
|---|---|
| BLE (Nordic UART `6E400001-…`, RX `…02`, TX `…03`) | 1 écriture / 1 notification = 1 trame brute, **sans en-tête** |
| TCP client → pont | `0x3C` + longueur *uint16 little-endian* + trame |
| TCP pont → client | `0x3E` + longueur *uint16 little-endian* + trame |

`MAX_FRAME_SIZE` vaut 176 dans le firmware actuel (le pont accepte jusqu'à 352 par marge).
Le protocole est conçu pour **un seul client** : les réponses ne sont pas routées par client.

## État actuel (prototype)

- Un écran : choisir le nœud (scan BLE), démarrer / arrêter, journal (sens, longueur, code de paquet — jamais le contenu).
- Service premier plan `connectedDevice`, TCP sur `127.0.0.1:5000`, **un seul client** (les suivants sont refusés).
- Reconnexion BLE automatique (backoff 2 s → 30 s), MTU 517 demandé.
- Démarrage au boot optionnel, journal persistant `files/bridge.log` (survie du service, boot).
- `targetSdk 36` (compile en SDK 37.2) : `ACCESS_LOCAL_NETWORK` (API 37) ne concerne que le LAN, hors périmètre de la phase 0 (loopback uniquement).

## Build et tests

```
export ANDROID_HOME=~/.local/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Test depuis le PC

```
adb forward tcp:5000 tcp:5000
python3 tools/tcp_probe.py                         # sonde stdlib : APP_START, DEVICE_QUERY, trames concaténées
python3 -m venv .local/venv && .local/venv/bin/pip install meshcore
.local/venv/bin/python tools/probe_meshcore.py     # vraie bibliothèque meshcore
```

`tools/fake_node_tcp.py` simule le pont pour valider `tcp_probe.py` sans téléphone.

## Installation et mise à jour (Obtainium)

Distribution : **GitHub Releases uniquement** (pas de Play Store). Les APK sont signés par une clé propre au projet.

1. Installer [Obtainium](https://github.com/ImranR98/Obtainium).
2. « Ajouter une appli » → URL : `https://github.com/QuickTrollHosted/meshcore-bridge`.
3. Tant que le dépôt est **privé**, renseigner dans les paramètres de la source GitHub d'Obtainium un jeton d'accès
   (fine-grained, lecture seule du contenu de ce dépôt). Aucun jeton n'est nécessaire une fois le dépôt public.
4. Obtainium proposera les mises à jour à chaque nouvelle release `vX.Y.Z`.

Installation manuelle : télécharger `meshcore-bridge-vX.Y.Z.apk` depuis la page Releases.

## CI/CD

- Push et PR : build, lint, tests unitaires, APK debug en artefact (`.github/workflows/ci.yml`).
- Tag `v*` : build release signé et GitHub Release avec notes générées (`.github/workflows/release.yml`).
- `versionCode` = nombre de commits ; `versionName` = tag (`v0.1.0` → `0.1.0`), sinon `<dernier tag>-dev.<n>`.
- Secrets du dépôt : `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
  Build signé en local : exporter `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` puis `./gradlew :app:assembleRelease`.
- Dependabot : Gradle et GitHub Actions, hebdomadaire.

## Licence

MIT.
