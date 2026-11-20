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

## Versions suivantes (fastlane supply)
Compte de service Google Cloud lié à la Play Console (droit « Publier »), clé JSON conservée dans `.local/` et en secret CI.
