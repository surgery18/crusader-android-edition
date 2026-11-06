#!/usr/bin/env bash
# Build Crusader: Android Edition (Linux, macOS or WSL).
#
#   scripts/build.sh                 # build dist/CrusaderAndroidEdition.apk
#   scripts/build.sh --setup-sdk     # first download the Android SDK + NDK into ~/android-sdk
#   scripts/build.sh --install       # build, then adb install to the connected phone
#   scripts/build.sh --work DIR      # where to keep the ScummVM checkout/build (default: ./build)
#
# Steps: fetch ScummVM at the pinned commit -> apply patches/ -> copy overlay/
# -> configure with only the Crusader engine for arm64 -> make -> sign.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$REPO/build"
SETUP_SDK=0
INSTALL=0
ABI="${ABI:-arm64-v8a}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

while [ $# -gt 0 ]; do
	case "$1" in
		--setup-sdk) SETUP_SDK=1 ;;
		--install) INSTALL=1 ;;
		--work) WORK="$2"; shift ;;
		--abi) ABI="$2"; shift ;;
		-h|--help) sed -n '2,12p' "$0"; exit 0 ;;
		*) echo "unknown option $1"; exit 1 ;;
	esac
	shift
done

SCUMMVM_REPO="$(sed -n 's/^repo=//p' "$REPO/scummvm.lock")"
SCUMMVM_COMMIT="$(sed -n 's/^commit=//p' "$REPO/scummvm.lock")"
NDK_VERSION="$(sed -n 's/^ndk=//p' "$REPO/scummvm.lock")"
OBOE_VERSION="$(sed -n 's/^oboe=//p' "$REPO/scummvm.lock")"
CMAKE_VERSION="$(sed -n 's/^cmake=//p' "$REPO/scummvm.lock")"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/$NDK_VERSION}"

say() { printf '\n\033[1;31m==>\033[0m \033[1m%s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- SDK / NDK
if [ "$SETUP_SDK" = 1 ]; then
	say "Installing Android SDK + NDK $NDK_VERSION into $ANDROID_SDK_ROOT"
	case "$(uname -s)" in
		Darwin) CLT_OS=mac ;;
		*) CLT_OS=linux ;;
	esac
	mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
	if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
		tmp="$(mktemp -d)"
		curl -fSL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/commandlinetools-${CLT_OS}-13114758_latest.zip"
		unzip -q "$tmp/clt.zip" -d "$tmp"
		mv "$tmp/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
		rm -rf "$tmp"
	fi
	SDKM="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
	yes | "$SDKM" --licenses >/dev/null 2>&1 || true
	"$SDKM" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION"
fi

[ -f "$ANDROID_NDK_ROOT/source.properties" ] || { echo "Android NDK $NDK_VERSION not found at $ANDROID_NDK_ROOT (run with --setup-sdk)"; exit 1; }
command -v java >/dev/null || { echo "A JDK (17 or newer) is required: install openjdk-17-jdk"; exit 1; }

# ------------------------------------------------------------ Oboe (audio)
# ScummVM's Android mixer links Google's Oboe library, which the NDK does not
# ship. Build it once as a static library for the target ABI.
CMAKE_BIN="$ANDROID_SDK_ROOT/cmake/$CMAKE_VERSION/bin"
[ -x "$CMAKE_BIN/cmake" ] || { echo "SDK CMake $CMAKE_VERSION not found (run with --setup-sdk)"; exit 1; }
DEPS="$WORK/deps/$ABI"
if [ ! -f "$DEPS/lib/liboboe.a" ]; then
	say "Building Oboe $OBOE_VERSION for $ABI"
	rm -rf "$WORK/deps/oboe-src" "$WORK/deps/oboe-build-$ABI"
	git clone -q --depth 1 --branch "$OBOE_VERSION" https://github.com/google/oboe.git "$WORK/deps/oboe-src"
	"$CMAKE_BIN/cmake" -S "$WORK/deps/oboe-src" -B "$WORK/deps/oboe-build-$ABI" -G Ninja \
		-DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
		-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 \
		-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF >/dev/null
	"$CMAKE_BIN/cmake" --build "$WORK/deps/oboe-build-$ABI" >/dev/null
	mkdir -p "$DEPS/lib" "$DEPS/include"
	cp "$(find "$WORK/deps/oboe-build-$ABI" -name liboboe.a | head -1)" "$DEPS/lib/"
	cp -R "$WORK/deps/oboe-src/include/oboe" "$DEPS/include/"
fi
export CPPFLAGS="${CPPFLAGS:-} -I$DEPS/include"
export LDFLAGS="${LDFLAGS:-} -L$DEPS/lib"

# ------------------------------------------------------------ ScummVM source
SRC="$WORK/scummvm"
say "ScummVM source @ ${SCUMMVM_COMMIT:0:12}"
mkdir -p "$WORK"
if [ ! -d "$SRC/.git" ]; then
	git init -q "$SRC"
	git -C "$SRC" remote add origin "$SCUMMVM_REPO"
fi
if ! git -C "$SRC" cat-file -e "$SCUMMVM_COMMIT^{commit}" 2>/dev/null; then
	git -C "$SRC" fetch --depth 1 origin "$SCUMMVM_COMMIT"
fi
git -C "$SRC" checkout -q -f "$SCUMMVM_COMMIT"
git -C "$SRC" clean -q -fdx -e /build-android/ -e /build-android

say "Applying patches and overlay"
for p in "$REPO"/patches/*.patch; do
	echo "   $(basename "$p")"
	git -C "$SRC" apply --whitespace=nowarn "$p"
done
cp -R "$REPO/overlay/." "$SRC/"

# ------------------------------------------------------------------- build
OUT="$SRC/build-android"
mkdir -p "$OUT"
cd "$OUT"
if [ ! -f config.mk ] || [ "$REPO/scummvm.lock" -nt config.mk ] || [ "$0" -nt config.mk ]; then
	say "Configuring (Crusader engine only, $ABI, release)"
	../configure \
		--host="android-$ABI" \
		--disable-all-engines --enable-engine=ultima8 \
		--enable-release --disable-debug \
		--disable-cloud --disable-libcurl --disable-sdlnet \
		--disable-mt32emu --disable-tts --disable-fluidsynth \
		--enable-optimizations
fi

say "Compiling with $JOBS jobs"
# Ship only the data files the Crusader engine uses (ultima8.dat, not the 16 MB
# ultima.dat of Ultima 1/4/6), one GUI theme for the in-game menu, no
# translations / Mac fonts / launcher icons. Saves ~24 MB of APK.
rm -rf android_project/mainAssets/src/main/assets
make -j"$JOBS" androidrelease \
	DIST_FILES_ENGINEDATA="../dists/engine-data/encoding.dat ../dists/engine-data/ultima8.dat" \
	DIST_FILES_THEMES="../gui/themes/scummremastered.zip"

# ------------------------------------------------------------------- sign
say "Signing"
BT="$(ls -d "$ANDROID_SDK_ROOT"/build-tools/* | sort -V | tail -1)"
KS="$REPO/keystore/crusader-android-edition.jks"
if [ ! -f "$KS" ]; then
	mkdir -p "$REPO/keystore"
	keytool -genkeypair -v -keystore "$KS" -alias crusader -keyalg RSA -keysize 2048 -validity 10000 \
		-storepass crusader -keypass crusader -dname "CN=Crusader Android Edition local build" >/dev/null
	echo "   created a local signing key: $KS (keep it to be able to update the app later)"
fi
mkdir -p "$REPO/dist"
APK="$REPO/dist/CrusaderAndroidEdition.apk"
"$BT/zipalign" -p -f 4 ScummVM-release-unsigned.apk "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:crusader --key-pass pass:crusader --out "$APK" "$OUT/aligned.apk"
rm -f "$OUT/aligned.apk" "$APK.idsig"
say "Built $APK ($(du -h "$APK" | cut -f1))"

if [ "$INSTALL" = 1 ]; then
	ADB="${ADB:-$(command -v adb || echo "$ANDROID_SDK_ROOT/platform-tools/adb")}"
	say "Installing on the phone"
	"$ADB" install -r "$APK"
fi
