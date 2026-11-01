#!/usr/bin/env python3
"""
tvtool.py — allège une Android TV via ADB, de façon 100 % réversible.

  python3 tvtool.py tout              # parcours guidé complet (recommandé)
  python3 tvtool.py scan              # trouver la télé sur le Wi-Fi
  python3 tvtool.py connect 192.168.1.42
  python3 tvtool.py backup            # sauvegarde (faite automatiquement avant toute modif)
  python3 tvtool.py debloat           # désactiver pubs / télémétrie / applis (avec questions)
  python3 tvtool.py launcher          # installer l'écran d'accueil Horizon
  python3 tvtool.py wallpapers DOSSIER  # envoyer tes photos nature sur la télé
  python3 tvtool.py shot NOM          # capture d'écran de la télé
  python3 tvtool.py restore           # tout remettre comme avant

Règles : rien n'est jamais désinstallé (sauf Horizon, si tu le demandes à la
restauration), rien n'est effacé ; on utilise seulement « pm disable-user »,
annulable par « pm enable ». Chaque action est notée dans un journal et un
script restore.sh / restore.bat est régénéré après chaque action.

Option globale : --dry-run (affiche les commandes sans rien modifier).
"""

import argparse
import datetime as dt
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
BACKUPS = HERE / "sauvegardes"
STATE = HERE / ".tvtool.json"
LAUNCHER_PKG = "com.elbidi.horizon"
LAUNCHER_ACTIVITY = LAUNCHER_PKG + "/.MainActivity"
ADB = os.environ.get("ADB", "adb")

SERIAL = None
DRY_RUN = False

# --------------------------------------------------------------------------- catalogue
# Seuls les paquets réellement présents sur la télé sont proposés.
# "pub" / "telemetrie" : proposés en groupe (avec possibilité de revoir un par un).
# "option" : applis/fonctions que tu pourrais utiliser -> question une par une.

CATALOG = {
    # --- Pubs / promos des fabricants et de Google
    "com.google.android.tvrecommendations": ("option", "Services de recommandations Android TV (alimente les rangées de l'accueil d'origine)"),
    "com.google.android.leanbacklauncher.recommendations": ("pub", "Recommandations sponsorisées de l'ancien accueil Android TV"),
    "com.google.android.apps.tv.launcherx.coreservices": ("option", "Services de l'accueil Google TV (contenus mis en avant)"),
    "com.sony.dtv.promos": ("pub", "Promotions Sony"),
    "com.sony.dtv.recommendationservice": ("pub", "Recommandations Sony"),
    "com.sony.dtv.discovery": ("option", "Sony « Discover » / barre de contenus"),
    "com.sony.dtv.smarthelp": ("option", "Aide Sony (manuel intégré)"),
    "com.tcl.ad": ("pub", "Publicités TCL"),
    "com.tcl.adservice": ("pub", "Service publicitaire TCL"),
    "com.tcl.advert": ("pub", "Publicités TCL"),
    "com.tcl.tvweishi": ("pub", "Promotions TCL"),
    "com.tcl.waterfall.overseas": ("option", "Accueil TCL « Channel/Waterfall »"),
    "com.xiaomi.mitv.advertise": ("pub", "Publicités Xiaomi"),
    "com.miui.systemAdSolution": ("pub", "Publicités système Xiaomi"),
    "com.mitv.tvhome": ("option", "Accueil Xiaomi PatchWall"),
    "org.droidtv.nettvadvert": ("pub", "Publicités Philips"),
    "org.droidtv.nettvrecommender": ("pub", "Recommandations Philips"),
    "com.hisense.ad": ("pub", "Publicités Hisense"),
    "com.vidaa.ad": ("pub", "Publicités Hisense / VIDAA"),
    "com.nvidia.tvrecommendations": ("pub", "Recommandations NVIDIA Shield"),
    # --- Télémétrie / rapports
    "com.google.android.feedback": ("telemetrie", "Envoi de rapports de retour d'expérience Google"),
    "com.google.android.tv.bugreportsender": ("telemetrie", "Envoi de rapports de bugs Google TV"),
    "com.miui.tv.analytics": ("telemetrie", "Statistiques d'usage Xiaomi"),
    "com.xiaomi.mitv.statistic": ("telemetrie", "Statistiques Xiaomi"),
    "com.tcl.statistic": ("telemetrie", "Statistiques TCL"),
    "com.tcl.logger": ("telemetrie", "Journalisation d'usage TCL"),
    "com.tcl.usercenter.analytics": ("telemetrie", "Statistiques compte TCL"),
    "com.sony.dtv.tvx.analytics": ("telemetrie", "Statistiques Sony"),
    "org.droidtv.statistics": ("telemetrie", "Statistiques Philips"),
    "com.nvidia.feedback": ("telemetrie", "Retours d'expérience NVIDIA"),
    # --- Applis / fonctions que tu pourrais utiliser
    "com.google.android.katniss": ("option", "Assistant Google / recherche vocale (bouton micro)"),
    "com.google.android.apps.mediashell": ("option", "Chromecast intégré (caster depuis ton téléphone)"),
    "com.google.android.backdrop": ("option", "Mode Ambiant / économiseur d'écran photos Google"),
    "com.google.android.apps.tv.dreamx": ("option", "Mode Ambiant Google TV"),
    "com.google.android.youtube.tv": ("option", "YouTube"),
    "com.google.android.youtube.tvmusic": ("option", "YouTube Music"),
    "com.google.android.youtube.tvkids": ("option", "YouTube Kids"),
    "com.google.android.videos": ("option", "Google TV / Play Films"),
    "com.google.android.play.games": ("option", "Google Play Jeux"),
    "com.google.android.tvtutorials": ("option", "Tutoriels Android TV"),
    "com.google.android.marvin.talkback": ("option", "TalkBack (lecteur d'écran, accessibilité)"),
    "com.android.printspooler": ("option", "Service d'impression"),
    "com.netflix.ninja": ("option", "Netflix"),
    "com.amazon.amazonvideo.livingroom": ("option", "Prime Video"),
    "com.disney.disneyplus": ("option", "Disney+"),
    "com.spotify.tv.android": ("option", "Spotify"),
    "com.android.tv": ("option", "TV en direct / chaînes (antenne, TNT)"),
}

# Accueils d'origine : jamais touchés pendant l'allègement. Proposés seulement
# à l'étape « launcher », et seulement si Horizon fonctionne déjà.
STOCK_LAUNCHERS = [
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
    "com.mitv.tvhome",
    "com.tcl.waterfall.overseas",
    "com.sony.dtv.tvlauncher",
    "org.droidtv.home",
    "com.nvidia.tvlauncher",
]

# Jamais proposés : sans eux, la télé ou la télécommande peut ne plus marcher.
PROTECTED_EXACT = {
    "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
    "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
    "com.google.android.tv.remote.service", "com.android.shell", "com.android.packageinstaller",
    "com.google.android.packageinstaller", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.google.android.webview", "com.android.webview",
    "com.google.android.tungsten.setupwraith", "com.google.android.tv.frameworkpackagestubs",
    "com.android.bluetooth", "com.android.externalstorage", "com.android.keychain",
    "com.google.android.ext.services", "com.android.networkstack", "com.google.android.networkstack",
    "com.android.certinstaller", "com.android.wallpaperbackup", "com.android.location.fused",
    LAUNCHER_PKG,
} | set(STOCK_LAUNCHERS)
PROTECTED_WORDS = ("provider", "inputmethod", "keyboard", "ime", "remote", "bluetooth", "hdmi", "cec",
                   "tvinput", "input", "audio", "systemui", "framework", "setupwizard", "setup",
                   "networkstack", "wifi", "telephony", "media", "update", "ota", "firmware",
                   "permission", "security", "keystore", "launcher", "home", "shell")
SUSPECT_WORDS = {"ad", "ads", "adservice", "adsdk", "advert", "advertise", "advertising", "promo",
                 "promos", "promotion", "analytics", "statistic", "statistics", "stat", "stats",
                 "telemetry", "tracker", "tracking", "feedback", "bugreport", "bugreportsender",
                 "logger", "reporter", "marketing", "commercial", "recommend", "recommendation",
                 "recommendations", "recommender", "sponsor", "sponsored"}

ANIMATION_KEYS = ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]


# --------------------------------------------------------------------------- utilitaires


def say(msg=""):
    print(msg, flush=True)


def title(msg):
    say()
    say("━" * 64)
    say("  " + msg)
    say("━" * 64)


def ask(question, default=None):
    hint = " [O/n] " if default is True else " [o/N] " if default is False else " [o/n] "
    while True:
        try:
            a = input(question + hint).strip().lower()
        except EOFError:
            a = ""
        if not a and default is not None:
            return default
        if a in ("o", "oui", "y", "yes"):
            return True
        if a in ("n", "non", "no"):
            return False
        say("  Réponds par o (oui) ou n (non).")


def check_adb():
    if shutil.which(ADB) is None and not Path(ADB).exists():
        say("❌ adb introuvable. Installe « Android SDK Platform-Tools » :")
        say("   https://developer.android.com/tools/releases/platform-tools")
        say("   puis relance (ou indique son chemin : ADB=/chemin/vers/adb python3 tvtool.py ...).")
        sys.exit(1)


def adb_cmd(args):
    cmd = [ADB]
    if SERIAL:
        cmd += ["-s", SERIAL]
    return cmd + list(args)


def adb(*args, timeout=60, binary=False):
    """Commande en lecture seule : exécutée même en --dry-run."""
    p = subprocess.run(adb_cmd(args), capture_output=True, timeout=timeout)
    if binary:
        return p.returncode, p.stdout
    out = (p.stdout + p.stderr).decode("utf-8", "replace").replace("\r", "")
    return p.returncode, out.strip()


def adb_change(*args, timeout=120):
    """Commande qui modifie la télé : seulement affichée en --dry-run."""
    if DRY_RUN:
        say("   [dry-run] " + " ".join(adb_cmd(args)))
        return 0, "Success"
    return adb(*args, timeout=timeout)


def sh(command):
    return adb("shell", command)[1]


def load_state():
    try:
        return json.loads(STATE.read_text())
    except (OSError, ValueError):
        return {}


def save_state(**kw):
    s = load_state()
    s.update(kw)
    STATE.write_text(json.dumps(s, indent=2))


def packages(flag=""):
    out = sh("pm list packages " + flag)
    return {l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")}


def home_activity():
    out = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    lines = [l.strip() for l in out.splitlines() if "/" in l and " " not in l.strip()]
    return lines[-1] if lines else ""


def leanback_apps():
    out = sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
    found = []
    for l in out.splitlines():
        m = re.match(r"^\s*([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\s*$", l)
        if m and m.group(1) not in found:
            found.append(m.group(1))
    if not found:  # vieilles versions d'Android sans « query-activities »
        found = sorted(packages("-3"))
    return found


def is_protected(pkg):
    if pkg in PROTECTED_EXACT:
        return True
    tokens = re.split(r"[._]", pkg.lower())
    return any(w in tokens or (len(w) > 4 and w in pkg.lower()) for w in PROTECTED_WORDS)


def is_suspect(pkg):
    tokens = set(re.split(r"[._]", pkg.lower()))
    return bool(tokens & SUSPECT_WORDS)


# --------------------------------------------------------------------------- appareil


def local_networks():
    nets = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # n'envoie aucun paquet
        nets.add(ipaddress.ip_network(s.getsockname()[0] + "/24", strict=False))
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                nets.add(ipaddress.ip_network(ip + "/24", strict=False))
    except OSError:
        pass
    return [n for n in nets if n.is_private]


def port_open(ip, port=5555, timeout=0.5):
    try:
        with socket.create_connection((str(ip), port), timeout=timeout):
            return True
    except OSError:
        return False


def connected_devices():
    rc, out = adb("devices")
    devs = []
    for l in out.splitlines()[1:]:
        parts = l.split()
        if len(parts) >= 2:
            devs.append((parts[0], parts[1]))
    return devs


def describe(serial):
    global SERIAL
    old, SERIAL = SERIAL, serial
    try:
        model = sh("getprop ro.product.manufacturer").strip() + " " + sh("getprop ro.product.model").strip()
        tv = "leanback" in sh("pm list features")
        version = sh("getprop ro.build.version.release").strip()
        return model.strip(), tv, version
    finally:
        SERIAL = old


def cmd_scan(args):
    check_adb()
    title("Recherche de la télé sur le Wi-Fi")
    nets = local_networks()
    if not nets:
        say("Impossible de déterminer ton réseau local. Utilise : python3 tvtool.py connect <IP de la télé>")
        return []
    hosts = [ip for n in nets for ip in n.hosts()]
    say(f"Je teste le port ADB 5555 sur {len(hosts)} adresses ({', '.join(map(str, nets))})…")
    with ThreadPoolExecutor(max_workers=128) as ex:
        found = [str(ip) for ip, ok in zip(hosts, ex.map(port_open, hosts)) if ok]
    rc, mdns = adb("mdns", "services")
    for m in re.finditer(r"(\d+\.\d+\.\d+\.\d+):(\d+)", mdns if rc == 0 else ""):
        if m.group(1) not in found:
            found.append(m.group(1) + ":" + m.group(2))
    if not found:
        say("\nAucun appareil avec le débogage ADB réseau trouvé. Sur la télé :")
        say("  1. Réglages > Préférences de l'appareil > À propos > clique 7 fois sur « Build »")
        say("  2. Réglages > Préférences de l'appareil > Options pour les développeurs")
        say("     > active « Débogage USB » ET « Débogage réseau / ADB sans fil »")
        say("  3. Note l'IP : Réglages > Réseau et Internet > (ton Wi-Fi)")
        say("  puis : python3 tvtool.py connect <IP>")
        return []
    say(f"Trouvé : {', '.join(found)}")
    return found


def connect(target):
    global SERIAL
    if ":" not in target:
        target += ":5555"
    say(f"Connexion à {target}…")
    rc, out = adb("connect", target, timeout=20)
    say("  " + out)
    say("  👉 Si la télé affiche « Autoriser le débogage ? », coche « Toujours autoriser » puis OK.")
    for _ in range(40):
        state = dict(connected_devices()).get(target)
        if state == "device":
            SERIAL = target
            model, tv, version = describe(target)
            say(f"✅ Connecté : {model} (Android {version}){'' if tv else ' — ⚠ ne semble pas être une Android TV'}")
            save_state(serial=target, model=model)
            return True
        if state == "unauthorized":
            time.sleep(1.5)
            continue
        if state is None:
            adb("connect", target, timeout=20)
        time.sleep(1.5)
    say("❌ Connexion impossible (autorisation refusée ou télé injoignable).")
    return False


def cmd_connect(args):
    check_adb()
    if not connect(args.ip):
        sys.exit(1)


def ensure_device(args=None):
    """Choisit la télé : -s, connexion active, dernière utilisée, ou recherche."""
    global SERIAL
    check_adb()
    if getattr(args, "serial", None):
        SERIAL = args.serial
        if ":" in SERIAL and dict(connected_devices()).get(SERIAL) != "device":
            if not connect(SERIAL):
                sys.exit(1)
        return
    devs = [d for d, s in connected_devices() if s == "device"]
    if len(devs) == 1:
        SERIAL = devs[0]
        return
    last = load_state().get("serial")
    if last and last in devs:
        SERIAL = last
        return
    if last and connect(last):
        return
    found = devs or cmd_scan(args)
    if not found:
        sys.exit(1)
    choice = found[0]
    if len(found) > 1:
        for i, f in enumerate(found, 1):
            say(f"  {i}. {f}")
        while True:
            a = input("Numéro de ta télé : ").strip()
            if a.isdigit() and 1 <= int(a) <= len(found):
                choice = found[int(a) - 1]
                break
    if choice in devs:
        SERIAL = choice
    elif not connect(choice):
        sys.exit(1)


# --------------------------------------------------------------------------- captures


def screenshot(folder, name, go_home=False):
    """Capture l'écran de la télé dans folder/captures/NN-name.png ; renvoie le chemin."""
    if go_home and not DRY_RUN:
        adb("shell", "input keyevent KEYCODE_HOME")
        time.sleep(3)
    d = Path(folder) / "captures"
    d.mkdir(parents=True, exist_ok=True)
    n = len(list(d.glob("*.png"))) + 1
    path = d / f"{n:02d}-{name}.png"
    rc, data = adb("exec-out", "screencap -p", binary=True, timeout=30)
    if rc == 0 and data.startswith(b"\x89PNG"):
        path.write_bytes(data)
        say(f"   📸 capture : {path}")
        return path
    say("   ⚠ capture impossible (certaines télés bloquent la capture sur les vidéos protégées)")
    return None


def cmd_shot(args):
    ensure_device(args)
    screenshot(HERE / "captures-manuelles", args.name or "ecran")


# --------------------------------------------------------------------------- sauvegarde & journal


class Journal:
    def __init__(self, folder):
        self.folder = Path(folder)
        self.path = self.folder / "journal.json"
        self.data = json.loads(self.path.read_text()) if self.path.exists() else {
            "serial": SERIAL, "created": dt.datetime.now().isoformat(timespec="seconds"),
            "disabled_by_tvtool": [], "settings_changed": [], "launcher_installed_by_tvtool": False,
            "home_before": "", "stock_launchers_disabled": [],
        }

    def save(self):
        if DRY_RUN:
            return
        self.path.write_text(json.dumps(self.data, indent=2, ensure_ascii=False))
        write_restore_scripts(self.folder, self.data)

    def add_disabled(self, pkg, key="disabled_by_tvtool"):
        if pkg not in self.data[key]:
            self.data[key].append(pkg)
        self.save()


def latest_backup():
    if not BACKUPS.exists():
        return None
    dirs = sorted(d for d in BACKUPS.iterdir() if (d / "journal.json").exists())
    return dirs[-1] if dirs else None


def cmd_backup(args, quiet=False):
    ensure_device(args)
    title("Sauvegarde de l'état actuel de la télé")
    model = describe(SERIAL)[0].replace(" ", "_") or "tv"
    folder = BACKUPS / (dt.datetime.now().strftime("%Y-%m-%d_%H-%M-%S") + "_" + re.sub(r"[^\w-]", "", model))
    folder.mkdir(parents=True, exist_ok=True)
    files = {
        "paquets-tous.txt": "pm list packages -f",
        "paquets-desactives.txt": "pm list packages -d",
        "paquets-actives.txt": "pm list packages -e",
        "paquets-systeme.txt": "pm list packages -s",
        "paquets-tiers.txt": "pm list packages -3",
        "reglages-global.txt": "settings list global",
        "reglages-secure.txt": "settings list secure",
        "reglages-system.txt": "settings list system",
        "proprietes.txt": "getprop",
        "accueil.txt": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    }
    for name, command in files.items():
        (folder / name).write_text(sh(command) + "\n", encoding="utf-8")
        say(f"   ✔ {name}")
    j = Journal(folder)
    j.data["serial"] = SERIAL
    j.data["home_before"] = home_activity()
    j.data["animation_before"] = {k: sh(f"settings get global {k}").strip() for k in ANIMATION_KEYS}
    # La sauvegarde ne modifie pas la télé : on l'écrit même en --dry-run.
    j.path.write_text(json.dumps(j.data, indent=2, ensure_ascii=False))
    write_restore_scripts(folder, j.data)
    screenshot(folder, "avant-accueil", go_home=True)
    save_state(backup=str(folder))
    say(f"\n✅ Sauvegarde : {folder}")
    say("   (les applis ne sont jamais désinstallées, donc leurs données restent sur la télé)")
    return folder


def current_backup(args):
    """La sauvegarde de cette session ; en crée une si besoin (jamais de modif sans sauvegarde)."""
    b = load_state().get("backup")
    if b and Path(b, "journal.json").exists() and Journal(b).data.get("serial") == SERIAL:
        say(f"Sauvegarde utilisée : {b}")
        return Path(b)
    return cmd_backup(args)


# --------------------------------------------------------------------------- allègement


def disable(pkg, journal):
    rc, out = adb_change("shell", f"pm disable-user --user 0 {pkg}")
    if DRY_RUN:
        return True
    ok = pkg in packages("-d")
    if ok:
        journal.add_disabled(pkg)
        say(f"   ✔ désactivé : {pkg}")
    else:
        say(f"   ✖ refusé par la télé : {pkg} ({out.splitlines()[-1] if out else 'erreur'})")
    return ok


def cmd_debloat(args):
    ensure_device(args)
    folder = current_backup(args)
    j = Journal(folder)
    enabled = packages("-e")
    system = packages("-s")
    title("Allègement — rien n'est désinstallé, tout est réversible")

    present = {p: CATALOG[p] for p in CATALOG if p in enabled}
    groups = {"pub": [], "telemetrie": [], "option": []}
    for p, (cat, desc) in present.items():
        groups[cat].append((p, desc))
    asked = set(present)

    for cat, label in (("pub", "Pubs et promotions"), ("telemetrie", "Télémétrie / statistiques")):
        items = groups[cat]
        if not items:
            say(f"\n{label} : rien de connu trouvé sur ta télé.")
            continue
        say(f"\n{label} trouvés :")
        for p, d in items:
            say(f"   • {d}  ({p})")
        if ask("Désactiver " + ("cet élément ?" if len(items) == 1 else f"ces {len(items)} éléments ?"), default=True):
            if len(items) > 1 and not ask("Tous d'un coup ? (n = je demande pour chacun)", default=True):
                items = [(p, d) for p, d in items if ask(f"   {d} ?", default=True)]
            for p, _ in items:
                disable(p, j)
            screenshot(folder, f"apres-{cat}", go_home=True)

    if groups["option"]:
        say("\nApplis et fonctions que tu pourrais utiliser — je demande pour chacune :")
        chosen = [(p, d) for p, d in groups["option"] if ask(f"   Désactiver « {d} » ({p}) ?", default=False)]
        for p, _ in chosen:
            disable(p, j)
        if chosen:
            screenshot(folder, "apres-applis", go_home=True)

    suspects = sorted(p for p in enabled & system if p not in asked and is_suspect(p) and not is_protected(p))
    if suspects:
        say("\nDétectés automatiquement (le nom évoque pub/statistiques — vérifie avant de dire oui) :")
        chosen = [p for p in suspects if ask(f"   Désactiver {p} ?", default=False)]
        for p in chosen:
            disable(p, j)
        asked |= set(suspects)
        if chosen:
            screenshot(folder, "apres-detectes", go_home=True)

    others = [p for p in leanback_apps() if p in system and p in enabled and p not in asked and not is_protected(p)]
    if others:
        say("\nAutres applis préinstallées visibles sur l'accueil :")
        chosen = [p for p in others if ask(f"   Désactiver {p} ?", default=False)]
        for p in chosen:
            disable(p, j)
        if chosen:
            screenshot(folder, "apres-preinstallees", go_home=True)

    title("Réglages de vitesse")
    current = {k: sh(f"settings get global {k}").strip() for k in ANIMATION_KEYS}
    say("Animations actuelles : " + ", ".join(f"{k.split('_')[0]}={v}" for k, v in current.items()))
    if ask("Accélérer les animations (×0,5) ? La télé paraît nettement plus rapide.", default=True):
        for k in ANIMATION_KEYS:
            if not any(s["key"] == k for s in j.data["settings_changed"]):
                j.data["settings_changed"].append({"ns": "global", "key": k, "old": current[k]})
            adb_change("shell", f"settings put global {k} 0.5")
        j.save()
        ok = DRY_RUN or all(sh(f"settings get global {k}").strip() == "0.5" for k in ANIMATION_KEYS)
        say("   ✔ animations à 0,5" if ok else "   ✖ la télé n'a pas pris le réglage")

    say("\nℹ À faire à la main (pas réglable par ADB de façon fiable) :")
    say("   Réglages > Préférences de l'appareil > Utilisation et diagnostics → désactiver")
    say("   Réglages > Confidentialité > Annonces → « Supprimer l'identifiant publicitaire » / désactiver la personnalisation")
    screenshot(folder, "fin-allegement", go_home=True)
    say(f"\n✅ Allègement terminé. Journal + script de restauration : {folder}")


# --------------------------------------------------------------------------- écran d'accueil


def find_apk(explicit):
    candidates = [explicit] if explicit else []
    candidates += [HERE / "horizon-launcher.apk",
                   HERE / "launcher/app/build/outputs/apk/release/app-release.apk",
                   HERE / "launcher/app/build/outputs/apk/debug/app-debug.apk",
                   Path.home() / "Downloads/horizon-launcher.apk",
                   Path.home() / "Téléchargements/horizon-launcher.apk"]
    for c in candidates:
        if c and Path(c).is_file():
            return Path(c)
    return None


def resumed_package():
    out = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'")
    m = re.search(r"([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+", out)
    return m.group(1) if m else ""


def cmd_launcher(args):
    ensure_device(args)
    folder = current_backup(args)
    j = Journal(folder)
    title("Installation de l'écran d'accueil Horizon")
    apk = find_apk(getattr(args, "apk", None))
    if not apk:
        say("❌ APK introuvable. Télécharge « horizon-launcher » dans l'onglet Actions du dépôt GitHub,")
        say(f"   dézippe-le dans {HERE} ou passe son chemin : python3 tvtool.py launcher --apk CHEMIN")
        sys.exit(1)
    already = LAUNCHER_PKG in packages()
    rc, out = adb_change("install", "-r", str(apk), timeout=300)
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
        say("Une ancienne version d'Horizon signée différemment est installée.")
        if not ask("La remplacer (tes favoris Horizon seront réinitialisés) ?", default=True):
            sys.exit(1)
        adb_change("uninstall", LAUNCHER_PKG)
        rc, out = adb_change("install", str(apk), timeout=300)
    if not DRY_RUN and LAUNCHER_PKG not in packages():
        say("❌ Installation échouée : " + out)
        sys.exit(1)
    if not already:
        j.data["launcher_installed_by_tvtool"] = True
        j.save()
    say("   ✔ Horizon installé")

    apps = [p for p in leanback_apps() if p != LAUNCHER_PKG and p not in STOCK_LAUNCHERS]
    say("\nChoisis les applis du dock, dans l'ordre (ex. 3,1,7). Entrée = choix automatique.")
    for i, p in enumerate(apps, 1):
        desc = CATALOG.get(p, ("", ""))[1]
        say(f"  {i:2d}. {p}{'  — ' + desc if desc else ''}")
    try:
        raw = input("Ton dock : ").strip()
    except EOFError:
        raw = ""
    favs = []
    for part in re.split(r"[,\s]+", raw):
        if part.isdigit() and 1 <= int(part) <= len(apps) and apps[int(part) - 1] not in favs:
            favs.append(apps[int(part) - 1])
    extra = ["--es", "favorites", ",".join(favs)] if favs else []
    adb_change("shell", "am", "start", "-n", LAUNCHER_ACTIVITY, *extra)
    time.sleep(4)
    screenshot(folder, "horizon-premier-lancement")
    if not DRY_RUN and resumed_package() != LAUNCHER_PKG:
        say("⚠ Horizon ne semble pas au premier plan. Regarde la capture ; je n'en fais pas l'accueil par défaut.")
        return

    if not j.data.get("home_before"):
        j.data["home_before"] = home_activity()
        j.save()
    if not ask("\nHorizon s'affiche bien ? En faire l'écran d'accueil (bouton HOME) ?", default=True):
        return
    adb_change("shell", f"cmd package set-home-activity --user 0 {LAUNCHER_ACTIVITY}")
    adb("shell", "input keyevent KEYCODE_HOME")
    time.sleep(3)
    if DRY_RUN or resumed_package() == LAUNCHER_PKG:
        say("   ✔ HOME ouvre maintenant Horizon")
    else:
        stock = [p for p in STOCK_LAUNCHERS if p in packages("-e")]
        say("   La télé force son accueil d'origine sur le bouton HOME (courant sur Google TV).")
        if stock and ask(f"   Désactiver l'accueil d'origine ({', '.join(stock)}) ? Réversible avec restore.", default=False):
            for p in stock:
                adb_change("shell", f"pm disable-user --user 0 {p}")
                if DRY_RUN or p in packages("-d"):
                    j.add_disabled(p, "stock_launchers_disabled")
            adb_change("shell", f"cmd package set-home-activity --user 0 {LAUNCHER_ACTIVITY}")
            adb("shell", "input keyevent KEYCODE_HOME")
            time.sleep(3)
            if DRY_RUN or resumed_package() == LAUNCHER_PKG:
                say("   ✔ HOME ouvre maintenant Horizon")
            else:
                say("   ✖ HOME n'ouvre toujours pas Horizon — je remets l'accueil d'origine.")
                for p in list(j.data["stock_launchers_disabled"]):
                    adb_change("shell", f"pm enable {p}")
                    j.data["stock_launchers_disabled"].remove(p)
                j.save()
    screenshot(folder, "horizon-accueil", go_home=True)
    say("\nAstuces Horizon : appui long (ou MENU) sur une appli du dock pour la déplacer / retirer ;")
    say("« ⋯ Toutes les applis » puis appui long pour ajouter au dock.")


def cmd_wallpapers(args):
    ensure_device(args)
    src = Path(args.folder)
    imgs = [p for p in sorted(src.iterdir()) if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp")]
    if not imgs:
        say("Aucune image .jpg/.png/.webp dans " + str(src))
        sys.exit(1)
    dest = f"/sdcard/Android/data/{LAUNCHER_PKG}/files/wallpapers"
    adb_change("shell", f"mkdir -p {dest}")
    for p in imgs:
        rc, out = adb_change("push", str(p), f"{dest}/{p.name}", timeout=300)
        say(("   ✔ " if rc == 0 else "   ✖ ") + p.name + ("" if rc == 0 else " : " + out))
    say("Les photos remplacent les paysages générés au prochain changement de fond (≤ 45 s).")


# --------------------------------------------------------------------------- restauration


def write_restore_scripts(folder, data):
    pkgs = list(dict.fromkeys(data.get("stock_launchers_disabled", []) + data.get("disabled_by_tvtool", [])))
    lines = []
    for p in pkgs:
        lines.append(f"shell pm enable {p}")
    for s in data.get("settings_changed", []):
        if s["old"] in ("", "null"):
            lines.append(f"shell settings delete {s['ns']} {s['key']}")
        else:
            lines.append(f"shell settings put {s['ns']} {s['key']} {s['old']}")
    if data.get("home_before") and LAUNCHER_PKG not in data["home_before"]:
        lines.append(f"shell cmd package set-home-activity --user 0 {data['home_before']}")
    serial = data.get("serial") or ""
    sh_text = ["#!/bin/sh", "# Restauration générée par tvtool.py — remet la télé comme avant.",
               "# Usage : sh restore.sh [IP:5555]   (Horizon reste installé ; voir la fin du fichier)",
               'ADB="${ADB:-adb}"', f'T="${{1:-{serial}}}"',
               'case "$T" in *:*) "$ADB" connect "$T" >/dev/null;; esac', 'a() { "$ADB" -s "$T" "$@"; }']
    sh_text += ["a " + l for l in lines]
    sh_text += ['a shell input keyevent KEYCODE_HOME',
                f'# Pour aussi désinstaller Horizon :  a uninstall {LAUNCHER_PKG}',
                'echo "Restauration terminée."']
    bat = ["@echo off", "rem Restauration générée par tvtool.py — remet la télé comme avant.",
           "rem Usage : restore.bat [IP:5555]", "if \"%ADB%\"==\"\" set ADB=adb",
           f"set T=%1", f"if \"%T%\"==\"\" set T={serial}", "%ADB% connect %T% >nul 2>&1"]
    bat += ["%ADB% -s %T% " + l for l in lines]
    bat += ["%ADB% -s %T% shell input keyevent KEYCODE_HOME",
            f"rem Pour aussi désinstaller Horizon :  %ADB% -s %T% uninstall {LAUNCHER_PKG}",
            "echo Restauration terminee."]
    (Path(folder) / "restore.sh").write_text("\n".join(sh_text) + "\n", encoding="utf-8")
    (Path(folder) / "restore.bat").write_text("\r\n".join(bat) + "\r\n", encoding="utf-8")
    try:
        os.chmod(Path(folder) / "restore.sh", 0o755)
    except OSError:
        pass


def cmd_restore(args):
    ensure_device(args)
    folder = Path(args.backup) if args.backup else (Path(load_state()["backup"]) if load_state().get("backup") else latest_backup())
    if not folder or not (folder / "journal.json").exists():
        say("❌ Aucune sauvegarde trouvée dans " + str(BACKUPS))
        sys.exit(1)
    j = Journal(folder)
    title(f"Restauration depuis {folder.name}")
    to_enable = list(dict.fromkeys(j.data["stock_launchers_disabled"] + j.data["disabled_by_tvtool"]))
    # Filet de sécurité : tout ce qui était actif au moment de la sauvegarde et ne l'est plus.
    before = folder / "paquets-actives.txt"
    if before.exists():
        was_enabled = {l.split(":", 1)[1].strip() for l in before.read_text().splitlines() if l.startswith("package:")}
        extra = sorted((was_enabled & packages("-d")) - set(to_enable))
        if extra:
            say("Désactivés depuis la sauvegarde mais absents du journal : " + ", ".join(extra))
            if ask("Les réactiver aussi ?", default=True):
                to_enable += extra
    for p in to_enable:
        adb_change("shell", f"pm enable {p}")
        ok = DRY_RUN or p not in packages("-d")
        say(("   ✔ réactivé : " if ok else "   ✖ échec : ") + p)
    for s in j.data["settings_changed"]:
        if s["old"] in ("", "null"):
            adb_change("shell", f"settings delete {s['ns']} {s['key']}")
        else:
            adb_change("shell", f"settings put {s['ns']} {s['key']} {s['old']}")
        say(f"   ✔ réglage {s['key']} remis à {s['old'] if s['old'] not in ('', 'null') else 'sa valeur par défaut'}")
    hb = j.data.get("home_before", "")
    if hb and LAUNCHER_PKG not in hb:
        adb_change("shell", f"cmd package set-home-activity --user 0 {hb}")
        say(f"   ✔ accueil d'origine : {hb}")
    if LAUNCHER_PKG in packages() and ask("Désinstaller aussi Horizon ?", default=bool(j.data["launcher_installed_by_tvtool"])):
        adb_change("uninstall", LAUNCHER_PKG)
        say("   ✔ Horizon désinstallé")
    if not DRY_RUN:
        j.data["disabled_by_tvtool"] = []
        j.data["stock_launchers_disabled"] = []
        j.data["settings_changed"] = []
        j.data["restored"] = dt.datetime.now().isoformat(timespec="seconds")
        j.path.write_text(json.dumps(j.data, indent=2, ensure_ascii=False))
    screenshot(folder, "apres-restauration", go_home=True)
    say("\n✅ Télé restaurée. Compare avec la capture « avant-accueil » du même dossier.")


def cmd_tout(args):
    ensure_device(args)
    cmd_backup(args)
    cmd_debloat(args)
    if ask("\nPasser à l'installation de l'écran d'accueil Horizon ?", default=True):
        cmd_launcher(args)
    b = load_state().get("backup")
    say(f"\nTout est noté dans {b}")
    say(f"Pour tout annuler : python3 tvtool.py restore   (ou {b}/restore.sh / restore.bat)")


# --------------------------------------------------------------------------- main


def main():
    global DRY_RUN
    ap = argparse.ArgumentParser(description="Allège une Android TV via ADB (réversible).")
    ap.add_argument("--dry-run", action="store_true", help="n'exécute aucune commande qui modifie la télé")
    ap.add_argument("-s", "--serial", help="IP:port ou numéro de série de la télé")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("tout").set_defaults(fn=cmd_tout)
    sub.add_parser("scan").set_defaults(fn=cmd_scan)
    p = sub.add_parser("connect"); p.add_argument("ip"); p.set_defaults(fn=cmd_connect)
    sub.add_parser("backup").set_defaults(fn=cmd_backup)
    sub.add_parser("debloat").set_defaults(fn=cmd_debloat)
    p = sub.add_parser("launcher"); p.add_argument("--apk"); p.set_defaults(fn=cmd_launcher)
    p = sub.add_parser("wallpapers"); p.add_argument("folder"); p.set_defaults(fn=cmd_wallpapers)
    p = sub.add_parser("shot"); p.add_argument("name", nargs="?"); p.set_defaults(fn=cmd_shot)
    p = sub.add_parser("restore"); p.add_argument("backup", nargs="?"); p.set_defaults(fn=cmd_restore)
    args = ap.parse_args()
    DRY_RUN = args.dry_run
    try:
        args.fn(args)
    except KeyboardInterrupt:
        say("\nInterrompu. Ce qui a été fait est noté dans le journal ; « restore » l'annule.")


if __name__ == "__main__":
    main()
