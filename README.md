# MeshCore Bridge

Relais Android entre un nœud **MeshCore** (firmware compagnon BLE) et des clients **TCP** (autres applis du
téléphone, machines du LAN comme Home Assistant) qui parlent le protocole compagnon comme s'ils étaient
branchés directement au nœud.

> ⚠️ **Le nœud n'accepte qu'une seule connexion BLE.** Tant que le relais est connecté, l'appli officielle MeshCore
> ne peut plus s'y connecter en BLE : elle doit passer par le **TCP du relais** (hôte `127.0.0.1` ou IP du téléphone, port 5000 par défaut).

## Fonctionnement

- Service **au premier plan** (`connectedDevice`) avec notification persistante, actif écran éteint.
- BLE natif (Nordic UART `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, RX `…02`, TX `…03`), MTU 517 demandé,
  reconnexion automatique avec backoff (2 s → 30 s), PIN d'appairage saisi automatiquement (défaut MeshCore `123456`, modifiable).
- Serveur TCP, **sur `127.0.0.1` par défaut**.

### Protocole (vérifié dans le firmware `examples/companion_radio` et `meshcore-proxy`)

| Transport | Format d'une trame |
|---|---|
| BLE | 1 écriture / 1 notification GATT = 1 trame brute, **sans en-tête** |
| TCP client → pont | `0x3C` + longueur *uint16 little-endian* + trame |
| TCP pont → client | `0x3E` + longueur *uint16 little-endian* + trame |

`MAX_FRAME_SIZE` vaut 176 dans le firmware actuel (le pont accepte jusqu'à 352 par marge).

## Modes clients

| Mode | Comportement |
|---|---|
| **Un seul client** | Un client à la fois, les suivants sont refusés. Le plus fidèle au protocole. |
| **Multi-clients** (défaut) | Les trames du nœud sont **diffusées à tous** les clients ; celles des clients sont **sérialisées dans une file unique** vers la radio (jamais entremêlées, ordre de chaque client préservé). 8 clients maximum. |

**Limites du mode multi-clients** — le protocole est conçu pour un seul client et n'identifie pas l'émetteur d'une commande :
- chaque client reçoit aussi les réponses destinées aux autres (il peut les prendre pour les siennes) ;
- les notifications du nœud (messages entrants…) sont reçues par tous, ce qui est voulu ;
- deux clients qui pilotent le nœud simultanément (ex. deux synchronisations de contacts) peuvent se perturber ;
- un client lent est déconnecté (file pleine) sans gêner les autres ni la radio.

Recommandation : un client « principal » qui écrit, les autres en lecture (supervision, passerelle MQTT…).

## Sécurité

- **Le TCP n'a aucune authentification.** Toute appli ayant `INTERNET` peut joindre `127.0.0.1`.
- Écoute **loopback par défaut**. « Écouter sur le réseau local » (`0.0.0.0`) est un réglage explicite, précédé d'un avertissement,
  et un bandeau rouge reste affiché tant qu'il est actif. À n'utiliser que sur un réseau de confiance : un client LAN peut lire les
  messages et envoyer n'importe quelle commande au nœud.
- **Android 17 (API 37)** : le trafic LAN exige `ACCESS_LOCAL_NETWORK` pour les applis ciblant l'API 37. L'appli déclare la
  permission et la demande à l'exécution (API 37+) avant d'écouter sur le LAN ; refusée, l'écoute retombe sur `127.0.0.1`.
  **`targetSdk` reste 36** (compilation avec le SDK 37.2) : la restriction n'est donc pas appliquée aujourd'hui, mais la
  permission est prête pour le passage à 37. Le loopback n'est pas concerné.

## Constructeurs (Xiaomi / HyperOS…)

Le système peut tuer le service même au premier plan. Dans l'appli : bouton d'exemption de la batterie, puis dans les réglages
système mettre la batterie sur « Sans restriction », activer le **démarrage automatique** et **verrouiller l'appli** dans les récentes.
Un arrêt non demandé est détecté (journal et notification « touchez pour relancer ») ; un redémarrage par le système est journalisé.

Démarrage au boot (option) : l'appli tente de démarrer le service sur `BOOT_COMPLETED` ; en cas de refus d'Android, elle affiche une
notification pour le relancer. Chaque étape est écrite dans le journal.

## Installation et mise à jour (Obtainium)

Distribution : **GitHub Releases uniquement** (pas de Play Store). Les APK sont signés par une clé propre au projet.

1. Installer [Obtainium](https://github.com/ImranR98/Obtainium).
2. « Ajouter une appli » → URL : `https://github.com/QuickTrollHosted/meshcore-bridge`.
3. Tant que le dépôt est **privé**, renseigner dans les paramètres de la source GitHub d'Obtainium un jeton d'accès
   (fine-grained, lecture seule du contenu de ce dépôt). Aucun jeton n'est nécessaire une fois le dépôt public.
4. Obtainium proposera les mises à jour à chaque nouvelle release `vX.Y.Z`.

Installation manuelle : télécharger `meshcore-bridge-vX.Y.Z.apk` depuis la page Releases.

## Développement

```
export ANDROID_HOME=~/.local/android-sdk
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests : framing (fragmentation, trames concaténées/incomplètes, longueurs invalides), serveur TCP (un client, multi-clients,
diffusion, sérialisation, client lent/invalide) et intégration avec un **faux nœud MeshCore** derrière de vrais sockets
(`FakeNodeIntegrationTest`). La pile BLE Android elle-même ne se teste que sur appareil.

Test depuis le PC (téléphone en USB) :
```
adb forward tcp:5000 tcp:5000
python3 tools/tcp_probe.py                         # sonde stdlib : APP_START, DEVICE_QUERY, trames concaténées
python3 -m venv .local/venv && .local/venv/bin/pip install meshcore
.local/venv/bin/python tools/probe_meshcore.py     # vraie bibliothèque meshcore
```
`tools/fake_node_tcp.py` simule le pont pour valider `tcp_probe.py` sans téléphone.

## CI/CD

- Push et PR : build, lint, tests unitaires, APK debug en artefact (`.github/workflows/ci.yml`).
- Tag `v*` : build release signé et GitHub Release avec notes générées (`.github/workflows/release.yml`).
- `versionCode` = nombre de commits ; `versionName` = tag (`v0.1.0` → `0.1.0`), sinon `<dernier tag>-dev.<n>`.
- Secrets du dépôt : `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
  Build signé en local : exporter `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` puis `./gradlew :app:assembleRelease`.
- Dependabot : Gradle et GitHub Actions, hebdomadaire.

## Licence

MIT.
