# Publication Google Play

## Choix de signature : clé de signature importée (option A)
La clé de release du projet (`.local/meshcore-bridge-release.jks`, alias `meshcore-bridge`) devient la **clé de signature
de l'application Play** : mêmes APK/AAB signés partout (GitHub, Obtainium, Play), mises à jour croisées possibles.
Elle se choisit à la **création** de l'application et ne peut pas être changée ensuite (sauf rotation Play).

### Export chiffré avec PEPK
Dans la Play Console (Configuration → Intégrité de l'application → Signature de l'application → « Exporter et importer une
clé depuis un keystore Java »), télécharger `pepk.jar` et copier la **clé de chiffrement publique** affichée, puis :

    set -a; . .local/signing.env; set +a
    java -jar pepk.jar --keystore="$KEYSTORE_FILE" --alias="$KEY_ALIAS" \
      --output=.local/play_signing_key.zip --include-cert --rsa-aes-encryption \
      --encryption-key-path=<chemin du fichier de clé publique téléchargé>

Téléverser `.local/play_signing_key.zip` (jamais le .jks lui-même). Supprimer ensuite le zip.

## Déclarations de l'application
- **Service au premier plan `connectedDevice`** : « Le service maintient une connexion Bluetooth LE permanente avec le nœud
  MeshCore de l'utilisateur et relaie ses données vers des clients TCP locaux. Sans lui, la connexion est coupée dès que
  l'écran s'éteint ; l'appli n'a alors aucune fonction. »
- **Permissions** : Bluetooth scan/connect (nœud), notifications (service), démarrage (option), réseau local (LAN optionnel).
- **Sécurité des données** : aucune donnée collectée ni partagée ; chiffrement en transit : sans objet.
- **Politique de confidentialité** : `https://static.qth.fr/meshcore-bridge/privacy-policy.html`
- **Classification** : utilitaire/outils, public adulte, pas de pubs, gratuit.
- Fiche : `store/listing/{fr-FR,en-US}/`, visuels `store/graphics/` (icône 512, image 1024×500) ; captures à fournir (≥ 2).

## Publication automatique depuis la CI (tag `vX.Y.Z`)
Le workflow `.github/workflows/release.yml` fait, sur un tag `v*` :
1. job `release` : tests, APK signé (GitHub Release, hébergement sur le serveur / Obtainium) et AAB signé avec **le même keystore**
   (`versionCode` = nombre de commits, d'où `fetch-depth: 0`) ;
2. job `play` : envoi de l'AAB sur Google Play via `r0adkll/upload-google-play`, avec les notes de version fr-FR.
   Ce job est **ignoré tant que le secret `PLAY_SERVICE_ACCOUNT_JSON` n'existe pas**.

**Pourquoi `r0adkll/upload-google-play` plutôt que Gradle Play Publisher (GPP)** : l'AAB est déjà produit par `bundleRelease`,
il suffit de l'envoyer. L'action n'ajoute aucun plugin Gradle (le projet vise un minimum de dépendances, et GPP doit suivre
chaque version d'AGP, ici 9.x avec Kotlin intégré). GPP serait utile pour synchroniser la fiche et les visuels depuis
`play/` ; ce n'est pas nécessaire ici.

### Réglages sans modifier le code (Settings → Secrets and variables → Actions → **Variables**)
| Variable | Valeurs | Défaut |
|---|---|---|
| `PLAY_TRACK` | `internal`, `alpha`, `beta`, `production` | `internal` |
| `PLAY_STATUS` | `draft`, `completed` | `draft` |

Commencer en `draft` : la version est créée dans la console, à vérifier et lancer à la main. Passer `PLAY_STATUS` à `completed`
quand l'envoi est éprouvé. Test fermé « Alpha » : `PLAY_TRACK=alpha`. Une appli encore jamais examinée par Google n'accepte que
des versions `draft`.

### Notes de version
Message du tag **annoté** (`git tag -a vX.Y.Z -m "..."`), sinon sujets des commits `feat`/`fix`/`perf`… depuis le tag
précédent (les `ci`, `docs`, `chore`, `test`, `build` sont exclus). Tronqué à 480 caractères (limite Play : 500).

### Création du compte de service (à faire à la main)
1. **Google Cloud** (console.cloud.google.com) : créer ou choisir un projet, puis activer l'API **Google Play Android Developer**.
2. IAM et administration → Comptes de service → **Créer** (nom `meshcore-bridge-ci`, aucun rôle Cloud nécessaire).
3. Ce compte → Clés → Ajouter une clé → **JSON** : le fichier est téléchargé. Ne jamais le committer ; le ranger dans `.local/`.
4. **Play Console** → Utilisateurs et autorisations → Inviter de nouveaux utilisateurs → coller l'e-mail du compte de service
   (`…@….iam.gserviceaccount.com`).
5. Autorisations **de l'application** (onglet « Autorisations de l'application », pas du compte), sur **MeshCore Bridge uniquement** :
   *Publier des applications sur la piste de test* (et *Gérer les versions de production* seulement le jour où la production est visée),
   plus *Voir les informations de l'application* si proposé. Aucune autorisation au niveau du compte.
6. Les droits peuvent mettre quelques heures à se propager.
7. GitHub : `gh secret set PLAY_SERVICE_ACCOUNT_JSON < .local/play-service-account.json` (aucun affichage du contenu).

### Premier envoi
Un premier AAB doit exister dans la console (déjà fait) ; ensuite `git tag -a v0.2.0 -m "notes" && git push origin v0.2.0`.
Un `versionCode` déjà utilisé est refusé par Play : chaque tag vient d'un commit plus récent, donc le compteur croît.

## Fiche et visuels
Sources versionnées : `store/listing/{fr-FR,en-US}/` et `store/graphics/`. Leur envoi vers la console reste manuel
(copier-coller / téléversement) ; aucune synchronisation automatique n'est en place.
