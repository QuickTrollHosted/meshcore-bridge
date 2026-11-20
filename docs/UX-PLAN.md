# Plan de refonte UX/UI

Problèmes actuels : écran unique de ~25 éléments en vrac (scan, état, réglages, journal), texte brut sans hiérarchie,
état du relais illisible d'un coup d'œil, réglages critiques (LAN) noyés, pas de thème sombre, aucune aide à la connexion.

## Structure
3 destinations en barre de navigation basse : **Relais** (tableau de bord), **Réglages**, **Journal**.
Le choix du nœud se fait dans une feuille modale (bottom sheet) ouverte depuis le tableau de bord.

## Relais (accueil)
- Carte d'état principale : pastille + titre d'une phrase (« Relais actif », « En attente du nœud… », « Arrêté », « Erreur »), sous-titre explicatif.
- Bouton principal unique, grand : Démarrer / Arrêter.
- Carte Nœud : nom + adresse, bouton « Choisir / Changer ». Si aucun nœud : état vide guidé.
- Carte « Connexion des clients » : adresse(s) à saisir dans les autres applis, bouton copier, clients connectés.
- Carte « Trafic » : compteurs lisibles (octets formatés Ko/Mo), trames.
- Alertes (permissions manquantes, écoute réseau local active) en bandeaux d'action, pas en texte rouge.

## Réglages
Groupes : Connexion (port, écoute réseau local, mode multi), Appairage (PIN auto), Démarrage et fiabilité (boot, batterie,
HyperOS), À propos (version, licence, dépôt). Textes d'aide courts sous chaque option, réglages verrouillés expliqués.

## Journal
Liste monospace filtrable (Tout / Événements / Trames), copie/export, vider l'affichage, défilement auto.

## Transverses
Thème Material 3 (couleur dynamique + repli de marque, clair/sombre), edge-to-edge, cibles ≥ 48 dp, contentDescription,
états d'erreur, prévisualisations, pas de nouvelle dépendance.

## Répartition
- ui/Theme.kt, MainActivity.kt (navigation, état, scan, actions) : agent principal
- ui/HomeScreen.kt : agent A
- ui/SettingsScreen.kt : agent B
- ui/LogScreen.kt + ui/NodePicker.kt : agent C
