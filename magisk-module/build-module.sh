#!/usr/bin/env bash
# ============================================================================
# Magisk module builder for ainur_jamesdsp (PEQ+Loudness fork)
#
# Packages the module zip from:
#   - libjamesdsp_effect.so built by the app's Gradle/CMake pipeline
#     (target "jamesdsp_effect" in app/src/main/cpp/CMakeLists.txt; renamed to
#     libjamesdsp.so and staged per-ABI into libs/<abi>/, same as release
#     v6.3.10; the installer copies them to the device's soundfx paths)
#   - static templates in magisk-module/src/ (scripts only; the installer
#     patches the device's live audio_effects configs at install time)
#
# Usage:
#   ./build-module.sh
#     -> builds the released configuration: v6.3.14-peq-loudness, code 60314
#
#   ./build-module.sh -v v6.3.15-peq-loudness -c 60315 [-d "release notes"]
#                     [-a /path/to/root-universal-release.apk]
#                     [-D YYYY-MM-DD] [--updatejson]
#
#   -v  module version string (default: v6.3.10-peq-loudness)
#   -c  module versionCode, integer, must be greater than the previous one
#       (default: 114)
#   -d  release notes appended after the base description line. When omitted
#       for the default version, the v6.3.8 HAL-fix notes of the released
#       v6.3.10 are used, so a bare run reproduces the release module.prop
#   -a  path to a prebuilt root *universal* APK; when omitted the script uses
#       the most recent local build, or runs "./gradlew assembleRootFullRelease"
#       if none exists
#   -D  build date stamped into module.prop (default: today; use
#       -D 2026-10-06 to reproduce the released v6.3.10 verbatim)
#   --updatejson     (default) fill module.prop updateJson with the
#                    auto-update URL (releases/latest/download/update.json)
#   --no-updatejson  leave module.prop updateJson empty (like v6.3.10)
#
# Output: magisk-module/ainur_jamesdsp-peq-loudness-<version>.zip and a
# rendered update.json in magisk-module/build/ (attach it to the release as
# an asset; do NOT put it inside the zip — v6.3.10-style modules ship none).
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEFAULT_VERSION="v6.3.14-peq-loudness"
DEFAULT_VERSION_CODE="60314"
DEFAULT_NOTES="v6.3.14: Rootless engine now follows dynamic output sample rates (USB hardware rate and active content rate up to 384 kHz) instead of a fixed 44.1/48 kHz clamp."

VERSION=""
VERSION_CODE=""
NOTES=""
APK=""
BUILD_DATE="$(date +%Y-%m-%d)"
SET_UPDATE_JSON=1
VERSION_EXPLICIT=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        -v) VERSION="$2"; VERSION_EXPLICIT=1; shift 2 ;;
        -c) VERSION_CODE="$2"; shift 2 ;;
        -d) NOTES="$2"; shift 2 ;;
        -a) APK="$2"; shift 2 ;;
        -D) BUILD_DATE="$2"; shift 2 ;;
        --updatejson) SET_UPDATE_JSON=1; shift ;;
        --no-updatejson) SET_UPDATE_JSON=0; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

[[ -z "$VERSION" ]] && VERSION="$DEFAULT_VERSION"
if [[ -z "$VERSION_CODE" ]]; then
    if [[ $VERSION_EXPLICIT -eq 1 ]]; then
        echo "versionCode (-c) is required when -v is given" >&2
        exit 1
    fi
    VERSION_CODE="$DEFAULT_VERSION_CODE"
fi
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
# 2. Stage libjamesdsp.so per ABI as libs/<abi>/
# ---------------------------------------------------------------------------
# Prefer the unstripped CMake output (RelWithDebInfo obj dir): released
# modules ship unstripped libs (debug info kept for crash symbolication).
# The APK copy is AGP-stripped and only used as a CI fallback.
rm -rf "$STAGING"
mkdir -p "$STAGING"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
    DEST="$STAGING/libs/$ABI/libjamesdsp.so"
    mkdir -p "$STAGING/libs/$ABI"
    SRC="$(ls -t "$REPO_ROOT"/app/build/intermediates/cxx/*/*/obj/"$ABI"/libjamesdsp_effect.so 2>/dev/null | head -1 || true)"
    if [[ -n "$SRC" ]]; then
        echo "== $ABI: unstripped CMake output ($(wc -c < "$SRC" | tr -d ' ') bytes)"
    else
        unzip -o -q -j "$APK" "lib/$ABI/libjamesdsp_effect.so" -d "$TMP/$ABI" || {
            echo "ERROR: no CMake obj lib and lib/$ABI/libjamesdsp_effect.so not found in APK" >&2
            exit 1
        }
        SRC="$TMP/$ABI/libjamesdsp_effect.so"
        echo "== $ABI: APK fallback (stripped by AGP)"
    fi
    cp "$SRC" "$DEST"
    # Sanity check: the AudioEffect entry point symbol name must be present
    grep -qa "AELI" "$DEST" || {
        echo "ERROR: AELI (AUDIO_EFFECT_LIBRARY_INFO_SYM) not found in $DEST" >&2
        exit 1
    }
done

# ---------------------------------------------------------------------------
# 3. Copy static templates (scripts only — no audio_effects configs)
# ---------------------------------------------------------------------------
cp "$SCRIPT_DIR"/src/customize.sh "$SCRIPT_DIR"/src/service.sh \
   "$SCRIPT_DIR"/src/uninstall.sh "$SCRIPT_DIR"/src/post-fs-data.sh "$STAGING/"
mkdir -p "$STAGING/common"
cp "$SCRIPT_DIR"/src/common/patch_audio_config.sh "$STAGING/common/"

# ---------------------------------------------------------------------------
# 4. Render module.prop / update.json
# ---------------------------------------------------------------------------
DESC_BASE="JamesDSP with PEQ biquad cascade and loudness correction (Fletcher-Munson) support via AudioEffect API."
if [[ -n "$NOTES" ]]; then
    DESC="$DESC_BASE $NOTES"
elif [[ "$VERSION" == "$DEFAULT_VERSION" ]]; then
    DESC="$DESC_BASE $DEFAULT_NOTES"
else
    DESC="$DESC_BASE"
fi

if [[ $SET_UPDATE_JSON -eq 1 ]]; then
    UPD_VALUE="$UPDATE_JSON_URL"
else
    UPD_VALUE=""
fi

mkdir -p "$SCRIPT_DIR/build"
sed -e "s|{{MODULE_VERSION}}|$VERSION|g" \
    -e "s|{{MODULE_VERSION_CODE}}|$VERSION_CODE|g" \
    -e "s|{{BUILD_DATE}}|$BUILD_DATE|g" \
    -e "s|{{MODULE_DESCRIPTION}}|$DESC|g" \
    -e "s|{{UPDATE_JSON_URL}}|$UPD_VALUE|g" \
    "$SCRIPT_DIR/src/module.prop.template" > "$STAGING/module.prop"

# update.json is a release asset only — it must not end up inside the zip
sed -e "s|{{MODULE_VERSION}}|$VERSION|g" \
    -e "s|{{MODULE_VERSION_CODE}}|$VERSION_CODE|g" \
    -e "s|{{TAG}}|$TAG|g" \
    "$SCRIPT_DIR/src/update.json.template" > "$SCRIPT_DIR/build/update.json"

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

echo ""
echo "== Built: $OUT =="
unzip -l "$OUT"
echo "== Rendered: $SCRIPT_DIR/build/update.json (attach to the release) =="
