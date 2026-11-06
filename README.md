# Crusader: Android Edition

Play your own copies of **Crusader: No Remorse** and **Crusader: No Regret** on an Android phone, with
touch controls built for the games.

- **One launcher for both games.** It shows a card only for the games you have installed, so one game
  is enough and two works too. Tap PLAY and that game boots straight in.
- **Touch HUD made for Crusader.** It has a floating movement stick (push to the rim to run), hold-to-fire,
  roll, crouch, use and search buttons, plus weapon/item/medikit buttons. The controls sit in the black
  bars beside the 4:3 picture, so they don't cover the game. Taps anywhere else act as mouse clicks for
  menus and the inventory.
- **No PC tools needed for the game files.** Copy the Steam/GOG folder to the phone and tap IMPORT GAME. The
  cutscenes are pulled straight out of the `GAME.BIN` disc image.
- **Phone tuning.** This is a lean arm64 build with only the Crusader engine compiled in. It runs in landscape,
  edge to edge around the camera cutout, with a 60 Hz cap and sustained-performance mode. You can turn on
  smooth scaling or high-res mode, and the HUD's size, opacity and handedness are adjustable.
- **Offline.** The app has no internet permission.

> **No game files are included in this repository or in the APK.** You need to own the games
> (they are sold on Steam and GOG). The app only runs data you copy onto your own phone.

---

## How it works

The original games are DOS programs. Instead of emulating DOS, this project uses the
[ScummVM](https://www.scummvm.org) **Ultima 8 / Crusader engine**. That engine is an open-source
reimplementation of the Crusader engine, made by reverse-engineering (decompiling) the original
executables. It started as the Pentagram project and is now maintained in ScummVM. It reads the original
data files (`STATIC/`, `USECODE/`, `SOUND/`, `FLICS/`) and runs them natively on ARM, which is faster and
lighter than DOSBox on a phone.

This repository doesn't fork all of ScummVM. It keeps a **small patch set on top of one pinned ScummVM commit**:

| Path | What it is |
|---|---|
| `scummvm.lock` | ScummVM git URL + the exact commit the patches apply to, and the NDK version |
| `patches/0001-crusader-android-edition.patch` | ~70 changed lines in ScummVM: JNI bridge for HUD actions, app id, manifest (our launcher, landscape, no INTERNET), HUD hook in `ScummVMActivity` |
| `overlay/backends/platform/android/crusader-bridge.*` | Native side: turns HUD presses like `"ATTACK"` into the engine's keymapper actions on the engine thread |
| `overlay/backends/platform/android/org/crusader/edition/` | Java: `LauncherActivity` (game picker), `GameLibrary` (detection), `CrusaderHud` (touch controls), `GameImporter` + `IsoExtractor` (import / cutscene extraction), `EngineConfig` (settings → `scummvm.ini`), `HudHost` (phone tuning) |
| `overlay/dists/android/res/` | App icon (original art) and strings |
| `scripts/build.sh` | Fetch ScummVM → apply patches → copy overlay → configure Crusader-only → build → sign |
| `scripts/build-windows.ps1` | The same build, run through WSL on Windows |
| `tools/install_games.py` | Finds your Steam/GOG installs, extracts the cutscenes and pushes everything to the phone with adb |

---

## 1. Build the APK

You need about 6 GB of free disk space and an internet connection for the first build. The build downloads
ScummVM, the Android NDK and Gradle.

### Linux / macOS / WSL

```bash
sudo apt install build-essential pkg-config git curl unzip zip openjdk-17-jdk-headless python3   # Debian/Ubuntu
```

```bash
bash scripts/build.sh --setup-sdk
```

`--setup-sdk` installs the Android SDK + NDK into `~/android-sdk`, so you only need it the first time. If you
already have them, set `ANDROID_SDK_ROOT` and `ANDROID_NDK_ROOT` (NDK version from `scummvm.lock`) and run
`bash scripts/build.sh`.

The result is `dist/CrusaderAndroidEdition.apk`. The first build takes 5–15 minutes; rebuilds are much faster.

### Windows

Install WSL with Ubuntu once (`wsl --install -d Ubuntu` in an admin PowerShell, then reboot). Then:

```bash
powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -SetupSdk
```

Leave out `-SetupSdk` on later builds.

The first build creates a signing key in `keystore/`. **Keep that folder.** Android only accepts an update
signed with the same key; if you lose it, uninstall the app before installing the new build (export your
saves first, see below).

## 2. Install it on the phone

Turn on **Developer options → USB debugging** on the phone (Settings → About phone → tap *Build number*
7 times), connect it with USB and run:

```bash
adb install -r dist/CrusaderAndroidEdition.apk
```

Without adb, copy the APK to the phone and open it in the Files app, allowing "install unknown apps" when
asked.

**Open the app once.** It creates its storage folder on first launch.

## 3. Add your game files

You can add **either game or both**. The launcher only lists what it finds.

### Option A: from the phone (no PC tools)

1. Copy the game folder from your PC to the phone, for example into `Download/`. Use the whole Steam or GOG
   folder (`Crusader No Remorse`, `Crusader No Regret`, ...).
2. In the app tap **IMPORT GAME** and pick that folder. If you pick a folder that holds both games, both are
   imported.
3. The app copies only what the engine needs and extracts the cutscenes from `GAME.BIN`. You can then
   delete the copy in `Download/`.

### Option B: from the PC with the install script (USB + adb)

```bash
python tools/install_games.py
```

The script finds Steam/GOG installs on its own. Pass folders explicitly if yours are elsewhere:

```bash
python tools/install_games.py "D:/Games/Crusader No Remorse" "D:/Games/Crusader No Regret"
```

### Option C: copy by hand

Copy the files into the app's folder on the phone. Over USB in Windows Explorer this shows up as
`Internal storage\Android\data\io.github.crusaderandroid.edition\files\games\`:

```
games/
  remorse/            <- Crusader: No Remorse
    STATIC/  USECODE/  SOUND/  FLICS/
  regret/             <- Crusader: No Regret
    STATIC/  USECODE/  SOUND/  FLICS/
```

The folder names don't matter, because each game is recognised by its `USECODE/EUSECODE.FLX`. If you have
no `FLICS` folder, copy `GAME.BIN` in as well and tap **EXTRACT CUTSCENES** on the game's card.

**Supported versions:** the GOG/Steam releases (No Remorse 1.21, No Regret 1.06) are the main target.
Other versions the ScummVM engine knows (CD 1.01/1.10, French, Spanish, German) are recognised too.

## 4. Play

| Control | Does |
|---|---|
| Left stick | Move. Directions are screen-relative. Push to the outer ring to **run** |
| **FIRE** | Hold to shoot (draws your weapon) |
| **ROLL** | Hold together with a direction to roll / jump |
| **DUCK** | Crouch on/off |
| **FIND** / **USE** | Cycle nearby usable things / use the selected one (doors, switches, terminals) |
| **WPN** / **ITEM** / **USE ITEM** / **MED** | Next weapon / next item / use item / use medikit |
| **MENU** / **SAVE** / **LOAD** | Game menu, save screen, load screen |
| **KEYS** | On-screen keyboard (for naming saves) |
| **HUD** | Hide the controls. Tap the faint HUD button to bring them back |
| Tap the picture | Mouse click (menus, inventory, dialogs) |
| Back gesture | Engine menu (options, quit to the launcher) |

Bluetooth controllers and keyboards work too.

**SETTINGS** in the launcher let you change: HUD opacity/size, left-handed layout, vibration, smooth vs.
sharp pixels, high-res mode (1024×768), camera follows the Silencer, battery saver (frame skipping), and
skip intro.

**Saves** are stored in `Android/data/io.github.crusaderandroid.edition/files/saves/`. Copy that folder to
back them up.

## Let Claude set it up for you

If you use [Claude Code](https://claude.com/claude-code), open a terminal in this folder, start `claude`
and paste the prompt below. It builds the app, installs it and copies your games over.

```text
Set up Crusader: Android Edition from this repository on my Android phone.

1. Read README.md. Build the APK: on Windows run
   `powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -SetupSdk` (WSL Ubuntu;
   install it with `wsl --install -d Ubuntu` first if `wsl -l` shows no distro);
   on Linux/macOS run `bash scripts/build.sh --setup-sdk`. If the build fails, read the error,
   fix the cause (missing package, SDK path, download hiccup) and retry. Do not change the
   patches unless the error is in them.
2. Check `adb devices -l` shows my phone. If not, tell me how to enable USB debugging and wait.
3. Install with `adb install -r dist/CrusaderAndroidEdition.apk`, then start it once with
   `adb shell monkey -p io.github.crusaderandroid.edition 1` so it creates its storage folder.
4. Find my Crusader games (Steam: steamapps/common/Crusader No Remorse and Crusader No Regret;
   GOG: GOG Games or GOG Galaxy/Games). I may own only one of them - that is fine.
   Run `python tools/install_games.py` (pass the folders explicitly if it does not find them).
5. Tell me which games were installed and that I should tap RESCAN or reopen the app.
Never commit, upload or share the game files anywhere.
```

## Troubleshooting

- **"No Crusader games installed yet" after copying.** Check that the folder contains `STATIC` and
  `USECODE` directly, not one level deeper. Then tap RESCAN.
- **Cutscenes are missing.** The card says "Cutscenes: still inside GAME.BIN": tap EXTRACT CUTSCENES. If it
  says "missing", copy the game's `GAME.BIN` into the game folder.
- **The character keeps walking after a phone call.** This shouldn't happen, because the HUD releases
  every control when the app loses focus. If it does, tap the stick once.
- **Slow or choppy on an older phone.** Turn on *Battery saver* and turn off *High resolution mode* in
  SETTINGS.
- **Logs.** Run `adb logcat -s ScummVM` and look for lines mentioning `Crusader HUD`.

## Legal

Crusader: Android Edition is a fan project. It is not affiliated with or endorsed by Electronic Arts or
Origin Systems. *Crusader*, *Crusader: No Remorse* and *Crusader: No Regret* are trademarks of their
respective owners. This repository contains **no** game data, executables or artwork from the games. The
icon is original artwork.

The engine is [ScummVM](https://www.scummvm.org). Its Crusader support comes from the ScummVM Ultima team
and the Pentagram project. ScummVM is licensed under the GNU GPL v3 or later, so this project (patches,
overlay sources, scripts) is released under the **GNU GPL v3 or later** as well. See [LICENSE](LICENSE).
The APK also statically links Google's [Oboe](https://github.com/google/oboe) audio library (Apache 2.0),
which the build compiles from source.

**Don't commit game files.** `.gitignore` blocks the usual game file types (`*.FLX`, `*.DAT`, `GAME.BIN`,
`STATIC/`, `FLICS/`, ...). Keep it that way.
