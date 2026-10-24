#!/usr/bin/env bash
# ============================================================================
# Magisk module builder for ainur_jamesdsp (PEQ+Loudness fork)
#
# Packages the module zip from:
#   - libjamesdsp_effect.so built by the app's Gradle/CMake pipeline
#     (target "jamesdsp_effect" in app/src/main/cpp/CMakeLists.txt; renamed to
#     libjamesdsp.so inside the module, same as manual releases up to v6.3.6)
#   - static templates in magisk-module/src/ (scripts + audio_effects configs)
#
# Usage:
#   ./build-module.sh -v v6.3.7-peq-loudness -c 111 [-d "release notes"]
#                     [-a /path/to/root-universal-release.apk] [--no-updatejson]
#
#   -v  module version string, e.g. v6.3.7-peq-loudness (required)
#   -c  module versionCode, integer, must be greater than the previous one
#   -d  release notes appended to the module description
#   -a  path to a prebuilt root *universal* APK; when omitted the script uses
#       the most recent local build, or runs "./gradlew assembleRootFullRelease"
#       if none exists
#   --no-updatejson  leave module.prop updateJson empty (auto-update disabled)
#
# Output: magisk-module/ainur_jamesdsp-peq-loudness-<version>.zip and a
# rendered update.json in magisk-module/build/ (attach it to the release —
# module.prop points auto-update checks at releases/latest/download/update.json).
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION=""
VERSION_CODE=""
NOTES=""
APK=""
SET_UPDATE_JSON=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        -v) VERSION="$2"; shift 2 ;;
        -c) VERSION_CODE="$2"; shift 2 ;;
        -d) NOTES="$2"; shift 2 ;;
        -a) APK="$2"; shift 2 ;;
        --no-updatejson) SET_UPDATE_JSON=0; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

[[ -n "$VERSION" && -n "$VERSION_CODE" ]] || {
    grep '^# *Usage' -A 12 "$0" | sed 's/^# \{0,2\}//' >&2
    exit 1
}
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || { echo "versionCode must be an integer" >&2; exit 1; }

TAG="$VERSION"   # release tag convention: tag name == module version
ZIP_NAME="ainur_jamesdsp-peq-loudness-${VERSION}.zip"
STAGING="$SCRIPT_DIR/build/staging"
# Stable auto-update pointer: GitHub serves the newest release's asset here
UPDATE_JSON_URL="https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP/releases/latest/download/update.json"

# ---------------------------------------------------------------------------
# 1. Locate or build the root universal APK
# ---------------------------------------------------------------------------
if [[ -z "$APK" ]]; then
    APK="$(ls -t "$REPO_ROOT"/app/build/outputs/apk/rootFull/release/*universal-release.apk 2>/dev/null | head -1 || true)"
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
    echo "== No prebuilt APK found, building assembleRootFullRelease =="
    (cd "$REPO_ROOT" && ./gradlew assembleRootFullRelease \
        -x uploadCrashlyticsSymbolFileRootFullRelease)
    APK="$(ls -t "$REPO_ROOT"/app/build/outputs/apk/rootFull/release/*universal-release.apk | head -1)"
fi
echo "== Using APK: $APK =="

# ---------------------------------------------------------------------------
# 2. Extract libjamesdsp_effect.so per ABI and strip it
# ---------------------------------------------------------------------------
STRIP="$(command -v llvm-strip || true)"
NDK_DIR="${ANDROID_NDK_HOME:-$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018}"
[[ -z "$STRIP" && -x "$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" ]] && \
    STRIP="$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"

rm -rf "$STAGING"
mkdir -p "$STAGING"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/arm64" "$TMP/arm32"
unzip -o -q -j "$APK" "lib/arm64-v8a/libjamesdsp_effect.so"   -d "$TMP/arm64"
unzip -o -q -j "$APK" "lib/armeabi-v7a/libjamesdsp_effect.so" -d "$TMP/arm32"

install -D "$TMP/arm64/libjamesdsp_effect.so" "$STAGING/system/vendor/lib64/soundfx/libjamesdsp.so"
install -D "$TMP/arm32/libjamesdsp_effect.so" "$STAGING/system/vendor/lib/soundfx/libjamesdsp.so"
[[ -n "$STRIP" ]] && "$STRIP" \
    "$STAGING/system/vendor/lib64/soundfx/libjamesdsp.so" \
    "$STAGING/system/vendor/lib/soundfx/libjamesdsp.so"

# Sanity check: the AudioEffect entry point symbol name must be present
grep -qa "AELI" "$STAGING/system/vendor/lib64/soundfx/libjamesdsp.so" || {
    echo "ERROR: AELI (AUDIO_EFFECT_LIBRARY_INFO_SYM) not found in built lib" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 3. Copy static templates
# ---------------------------------------------------------------------------
mkdir -p "$STAGING/system"
cp -r "$SCRIPT_DIR/src/system/." "$STAGING/system/"
cp "$SCRIPT_DIR/src/customize.sh" "$SCRIPT_DIR/src/service.sh" \
   "$SCRIPT_DIR/src/uninstall.sh" "$SCRIPT_DIR/src/changelog.md" "$STAGING/"

# ---------------------------------------------------------------------------
# 4. Render module.prop / update.json
# ---------------------------------------------------------------------------
DATE="$(date +%Y-%m-%d)"
DESC="JamesDSP with PEQ biquad cascade and loudness correction (Fletcher-Munson) support via AudioEffect API. Based on RootlessJamesDSP fork Loundess-PEQ."
[[ -n "$NOTES" ]] && DESC="$DESC $VERSION: $NOTES"

if [[ $SET_UPDATE_JSON -eq 1 ]]; then
    UPD_VALUE="$UPDATE_JSON_URL"
else
    UPD_VALUE=""
fi

sed -e "s|{{MODULE_VERSION}}|$VERSION|g" \
    -e "s|{{MODULE_VERSION_CODE}}|$VERSION_CODE|g" \
    -e "s|{{BUILD_DATE}}|$DATE|g" \
    -e "s|{{MODULE_DESCRIPTION}}|$DESC|g" \
    -e "s|{{UPDATE_JSON_URL}}|$UPD_VALUE|g" \
    "$SCRIPT_DIR/src/module.prop.template" > "$STAGING/module.prop"

sed -e "s|{{MODULE_VERSION}}|$VERSION|g" \
    -e "s|{{MODULE_VERSION_CODE}}|$VERSION_CODE|g" \
    -e "s|{{TAG}}|$TAG|g" \
    "$SCRIPT_DIR/src/update.json.template" > "$STAGING/update.json"

# ---------------------------------------------------------------------------
# 5. Zip
# ---------------------------------------------------------------------------
OUT="$SCRIPT_DIR/$ZIP_NAME"
rm -f "$OUT"
if command -v zip >/dev/null 2>&1; then
    (cd "$STAGING" && zip -q -r -X "$OUT" .)
else
    python - "$STAGING" "$OUT" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, src))
PY
fi

mv "$STAGING/update.json" "$SCRIPT_DIR/build/update.json"

echo ""
echo "== Built: $OUT =="
unzip -l "$OUT"
echo "== Rendered: $SCRIPT_DIR/build/update.json (attach to the release) =="
