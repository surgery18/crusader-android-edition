#!/usr/bin/env python3
"""Copy your own Crusader game files onto an Android phone for Crusader: Android Edition.

    python tools/install_games.py                 # auto-find Steam / GOG installs
    python tools/install_games.py "D:/Games/Crusader No Remorse"
    python tools/install_games.py --stage-only out/  # just prepare folders, no adb

What it does, per game folder:
  1. identifies the game from USECODE/EUSECODE.FLX (No Remorse or No Regret),
  2. copies STATIC, USECODE, SOUND (and FLICS if present) into a staging folder,
  3. if FLICS is missing, extracts it from the GAME.BIN / .iso disc image,
  4. pushes the folder with adb to
     /sdcard/Android/data/io.github.crusaderandroid.edition/files/games/<remorse|regret>

Only the Python standard library is needed (plus adb on PATH, or --adb PATH).
Nothing here downloads or ships game data: it only moves files you already own.
"""

import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile

PACKAGE = "io.github.crusaderandroid.edition"
REMOTE_ROOT = f"/sdcard/Android/data/{PACKAGE}/files/games"

# md5 of the first 5000 bytes of USECODE/EUSECODE.FLX (same table as the app)
KNOWN = {
    "0a0f64507adc4f280129c735ee9cad42": ("remorse", "v1.21 (GOG / Steam)"),
    "73b413b1ef291c4512f16c719ad746f3": ("remorse", "v1.10 CD"),
    "3fb211f4adfd80595078afc85bdfe7b4": ("remorse", "CD"),
    "8c74327e30088ce93f08a15a7f85b3ce": ("remorse", "v1.01 (unpatched)"),
    "efbd33d6a5e8f14e9c57f963c3fbe939": ("remorse", "French"),
    "36a16d70c97d0379f1133cc743c31313": ("remorse", "Spanish"),
    "a8b5c421c5d74be8c69fcd4fecadd1dd": ("remorse", "Spanish fan translation"),
    "1bb360156b7240a1f05eb9bda01c54db": ("regret", "v1.06 (GOG / Steam)"),
    "1824d9725de45a8b49f058c12c6cf5c3": ("regret", "German v1.07NV"),
    "f5906654047ed1dab75760da6426ecfa": ("regret", "Spanish"),
}
TITLES = {"remorse": "Crusader: No Remorse", "regret": "Crusader: No Regret"}
COPY_DIRS = ("STATIC", "USECODE", "SOUND", "FLICS")
COPY_FILES = ("CRUSADER.EXE", "REGRET.EXE", "CRUSADER.CFG")


def child(d, name):
    """Case-insensitive lookup of a direct child."""
    if not d or not os.path.isdir(d):
        return None
    p = os.path.join(d, name)
    if os.path.exists(p):
        return p
    for n in os.listdir(d):
        if n.lower() == name.lower():
            return os.path.join(d, n)
    return None


def identify(d):
    usecode = child(child(d, "USECODE"), "EUSECODE.FLX")
    if not usecode or not child(d, "STATIC"):
        return None
    with open(usecode, "rb") as f:
        md5 = hashlib.md5(f.read(5000)).hexdigest()
    if md5 in KNOWN:
        return KNOWN[md5]
    if child(d, "REGRET.EXE"):
        return ("regret", "unrecognised version")
    if child(d, "CRUSADER.EXE"):
        return ("remorse", "unrecognised version")
    return None


def default_locations():
    roots = []
    if os.name == "nt":
        for base in (os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
                     os.environ.get("ProgramFiles", r"C:\Program Files")):
            roots += [os.path.join(base, "Steam", "steamapps", "common"),
                      os.path.join(base, "GOG Galaxy", "Games")]
        roots.append(r"C:\GOG Games")
    else:
        home = os.path.expanduser("~")
        roots += [os.path.join(home, ".steam", "steam", "steamapps", "common"),
                  os.path.join(home, ".local", "share", "Steam", "steamapps", "common"),
                  os.path.join(home, "Library", "Application Support", "Steam", "steamapps", "common"),
                  os.path.join(home, "GOG Games")]
    found = []
    for r in roots:
        if not os.path.isdir(r):
            continue
        for n in sorted(os.listdir(r)):
            if "crusader" in n.lower():
                found.append(os.path.join(r, n))
    return found


# ---------------------------------------------------------------- ISO 9660

class Iso:
    def __init__(self, path):
        self.f = open(path, "rb")
        for ss, hdr in ((2048, 0), (2352, 16)):
            self.ss, self.hdr = ss, hdr
            pvd = self.sector(16)
            if pvd[0] == 1 and pvd[1:6] == b"CD001":
                self.root = self.record(pvd, 156)
                return
        raise ValueError(f"{path} is not an ISO 9660 image")

    def sector(self, lba):
        self.f.seek(lba * self.ss + self.hdr)
        return self.f.read(2048)

    @staticmethod
    def record(b, o):
        ln = b[o]
        if ln == 0:
            return None
        lba = struct.unpack_from("<I", b, o + 2)[0]
        size = struct.unpack_from("<I", b, o + 10)[0]
        is_dir = bool(b[o + 25] & 2)
        nl = b[o + 32]
        raw = b[o + 33:o + 33 + nl]
        if nl == 1 and raw in (b"\0", b"\1"):
            name = "." if raw == b"\0" else ".."
        else:
            name = raw.decode("latin-1").split(";")[0].rstrip(".")
        return (name, lba, size, is_dir)

    def listdir(self, entry):
        out = []
        for s in range((entry[2] + 2047) // 2048):
            sec = self.sector(entry[1] + s)
            o = 0
            while o < 2048 and sec[o]:
                r = self.record(sec, o)
                if r and r[0] not in (".", ".."):
                    out.append(r)
                o += sec[o]
        return out

    def find(self, name):
        for e in self.listdir(self.root):
            if e[0].lower() == name.lower():
                return e
        return None

    def merge_missing(self, game_dir):
        """Copy files from the disc's game folders that game_dir lacks."""
        jobs = []
        for d in ("STATIC", "SOUND", "USECODE", "FLICS"):
            e = self.find(d)
            if not e or not e[3]:
                continue
            local = child(game_dir, d) or os.path.join(game_dir, d)
            for f in self.listdir(e):
                if not f[3] and not child(local, f[0]):
                    jobs.append((f, os.path.join(local, f[0].upper())))
        self._copy(jobs)
        return len(jobs)

    def _copy(self, jobs):
        total = sum(f[2] for f, _ in jobs) or 1
        done = 0
        for (fname, lba, size, _), path in jobs:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as out:
                remaining = size
                while remaining:
                    if self.ss == 2048:
                        self.f.seek(lba * 2048)
                        chunk = self.f.read(min(remaining, 1 << 20))
                        lba += (len(chunk) + 2047) // 2048
                    else:
                        chunk = self.sector(lba)[:remaining]
                        lba += 1
                    out.write(chunk)
                    remaining -= len(chunk)
                    done += len(chunk)
            print(f"\r    from disc: {100 * done // total:3d}%  {fname:<14}", end="", flush=True)
        if jobs:
            print()


# ------------------------------------------------------------------- main

def stage(src, game_id, dest):
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    for d in COPY_DIRS:
        p = child(src, d)
        if p and os.path.isdir(p):
            shutil.copytree(p, os.path.join(dest, d))
    for f in COPY_FILES:
        p = child(src, f)
        if p and os.path.isfile(p):
            shutil.copy2(p, os.path.join(dest, f))
    disc = child(src, "GAME.BIN")
    if not disc:
        for n in os.listdir(src):
            p = os.path.join(src, n)
            if n.lower().endswith((".iso", ".bin")) and os.path.getsize(p) > 50 << 20:
                disc = p
                break
    if disc:
        # Steam/GOG installs keep part of the game only on the disc (cutscenes,
        # music, No Regret's level data). Fill in what the folder lacks; never
        # overwrite installed files, those are the patched versions.
        print(f"    adding the files that only exist on {os.path.basename(disc)}")
        n = Iso(disc).merge_missing(dest)
        open(os.path.join(dest, ".cae-disc-merged"), "w").close()
        print(f"    {n} files added")
    else:
        print("    no disc image found: cutscenes and music may be missing")
    if not child(child(dest, "STATIC"), "FIXED.DAT"):
        print("    WARNING: STATIC/FIXED.DAT is missing, the game cannot start. Put GAME.BIN in the game folder.")


def adb(args, adb_path):
    return subprocess.run([adb_path] + args, check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folders", nargs="*", help="game folders (default: search Steam/GOG locations)")
    ap.add_argument("--adb", default=shutil.which("adb") or "adb", help="path to adb")
    ap.add_argument("--serial", help="adb device serial (adb devices -l)")
    ap.add_argument("--stage-only", metavar="DIR", help="only build the phone-ready folders in DIR")
    a = ap.parse_args()

    folders = a.folders or default_locations()
    games = {}
    for f in folders:
        g = identify(f)
        if g:
            games.setdefault(g[0], (f, g[1]))
        else:
            print(f"skip: {f} (not a Crusader game folder)")
    if not games:
        print("No Crusader games found. Pass the game folder(s) on the command line.")
        return 1

    adb_args = ["-s", a.serial] if a.serial else []
    if not a.stage_only:
        # The app must have created its own storage folder first, otherwise a
        # folder made by adb may not be readable by the app on Android 11+.
        r = subprocess.run([a.adb] + adb_args + ["shell", "ls", f"/sdcard/Android/data/{PACKAGE}/files"],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print("Install the app and open it once on the phone first, then run this again.")
            print((r.stderr or r.stdout).strip())
            return 1

    staging_root = a.stage_only or tempfile.mkdtemp(prefix="crusader-stage-")
    try:
        for gid, (src, ver) in sorted(games.items()):
            print(f"{TITLES[gid]} ({ver})\n    from {src}")
            dest = os.path.join(staging_root, gid)
            stage(src, gid, dest)
            if a.stage_only:
                print(f"    staged in {dest}")
                continue
            remote = f"{REMOTE_ROOT}/{gid}"
            print(f"    pushing to {remote}")
            adb(adb_args + ["shell", "mkdir", "-p", REMOTE_ROOT], a.adb)
            adb(adb_args + ["shell", "rm", "-rf", remote], a.adb)
            adb(adb_args + ["push", dest, REMOTE_ROOT + "/"], a.adb)
    finally:
        if not a.stage_only:
            shutil.rmtree(staging_root, ignore_errors=True)
    if not a.stage_only:
        print("Done. Open Crusader on the phone (tap RESCAN if it was already open).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
