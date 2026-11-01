# Alléger son Android TV + écran d'accueil Horizon

Tout se lance **depuis ton ordinateur**, connecté au même Wi-Fi que la télé.
Rien n'est jamais désinstallé ni effacé : on *désactive* seulement (`pm disable-user`),
et tout s'annule avec `restore`.

## 1. Préparer (une seule fois)

1. Installe **Python 3** et **adb** ([Platform-Tools](https://developer.android.com/tools/releases/platform-tools)).
2. Sur la télé : *Réglages › Préférences de l'appareil › À propos* → clique 7 fois sur **Build**.
3. *Réglages › Préférences de l'appareil › Options pour les développeurs* → active
   **Débogage USB** et **Débogage réseau / ADB sans fil**.
4. Télécharge l'écran d'accueil : onglet **Actions** du dépôt GitHub → dernier
   « Écran d'accueil TV (APK) » → artefact **horizon-launcher** → dézippe
   `horizon-launcher.apk` dans ce dossier `tv/`.

## 2. Tout faire en une fois

```bash
python3 tvtool.py tout
```

Dans l'ordre, l'outil :

1. **trouve la télé** sur le Wi-Fi (port ADB 5555) et s'y connecte ; accepte le message
   « Autoriser le débogage ? » sur la télé ;
2. **sauvegarde** l'état complet (liste des applis, réglages, accueil d'origine, capture
   d'écran) dans `sauvegardes/<date>_<modèle>/` ;
3. **désactive les pubs et la télémétrie** connues des fabricants (Google, Sony, TCL, Xiaomi,
   Philips, Hisense, NVIDIA), après te les avoir montrées ;
4. **demande une par une** pour les applis et fonctions que tu pourrais utiliser (Assistant
   vocal, Chromecast, mode Ambiant, YouTube, TV en direct…) ; par défaut, la réponse est *non* ;
5. propose d'**accélérer les animations** (×0,5) ;
6. installe **Horizon**, te fait choisir le dock, vérifie qu'il s'affiche, puis en fait
   l'accueil du bouton HOME ;
7. prend une **capture de la télé après chaque étape** (`captures/` dans la sauvegarde).

Pour voir ce qui serait fait sans rien toucher : `python3 tvtool.py --dry-run tout`.

Les composants indispensables (télécommande, clavier, Bluetooth, HDMI, réglages, Play Store,
services Google…) ne sont **jamais** proposés.

## 3. Tout restaurer

```bash
python3 tvtool.py restore
```

Ou, sans Python, avec le script généré dans le dossier de sauvegarde :

```bash
sh sauvegardes/<date>_<modèle>/restore.sh
```

Sous Windows : `restore.bat`. Le script est réécrit après chaque action, donc il est
toujours à jour, même si tu interromps le programme en cours de route.

## Horizon : l'écran d'accueil

- Fond nature qui change toutes les 45 s (fondu + zoom lent) : montagnes, forêt brumeuse,
  océan, aurore boréale, dunes, lac. Ces paysages sont générés sur la télé : aucun
  téléchargement, aucun fichier.
- Pour mettre **tes propres photos** : `python3 tvtool.py wallpapers ~/Images/nature`
- Horloge et date ; l'interface s'efface après 30 s pour ne laisser que le paysage.
- Dock : appui long (ou MENU) sur une appli → déplacer / retirer.
  **⋯ Toutes les applis** → appui long pour ajouter au dock.
- Aucune pub, aucune connexion réseau, aucune dépendance (≈ 50 Ko).

Autres commandes : `scan`, `connect IP`, `backup`, `debloat`, `launcher`, `shot NOM`.
