#!/bin/sh
# Run the golden harness on the connected device and pull outputs.
# Usage: tools/golden/run_golden.sh <tag>
#   <tag> labels the local result directory, e.g. "baseline", "post-K1".
# Results: tools/golden/results/<tag>/{golden_a.wav,golden_b.wav,hashes.txt}
set -e
# MSYS path conversion turns /data/local/tmp/... into a Windows path before
# adb sees it; disable it for every adb invocation and convert the local
# paths explicitly with cygpath.
export MSYS_NO_PATHCONV=1
wpath() { cygpath -w "$1"; }
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
TAG="${1:-run}"
DEVDIR=/data/local/tmp/golden
OUT="$REPO/tools/golden/results/$TAG"

test -x "$ADB" || { echo "adb not found: $ADB" >&2; exit 1; }
test -f "$REPO/tools/golden/build/golden_arm64" || { echo "build first: tools/golden/build_golden.sh" >&2; exit 1; }

"$ADB" get-state >/dev/null 2>&1 || { echo "no device attached" >&2; exit 1; }

mkdir -p "$OUT"
"$ADB" shell "mkdir -p $DEVDIR"
"$ADB" push "$(wpath "$REPO/tools/golden/build/golden_arm64")" "$DEVDIR/golden" >/dev/null
"$ADB" push "$(wpath "$REPO/app/src/main/cpp/libjamesdsp/Main/CLI/Butterworth.vdc")" "$DEVDIR/Butterworth.vdc" >/dev/null
"$ADB" shell "chmod +x $DEVDIR/golden"
"$ADB" shell "$DEVDIR/golden 1 $DEVDIR/golden_a.wav $DEVDIR/Butterworth.vdc && $DEVDIR/golden 3 $DEVDIR/golden_b.wav $DEVDIR/Butterworth.vdc && $DEVDIR/golden 11 $DEVDIR/golden_a_notube.wav $DEVDIR/Butterworth.vdc && $DEVDIR/golden 13 $DEVDIR/golden_b_notube.wav $DEVDIR/Butterworth.vdc" | grep -Ev "^Shit" | tee "$OUT/hashes.txt"
"$ADB" pull "$DEVDIR/golden_a.wav" "$(wpath "$OUT/golden_a.wav")" >/dev/null
"$ADB" pull "$DEVDIR/golden_b.wav" "$(wpath "$OUT/golden_b.wav")" >/dev/null
"$ADB" pull "$DEVDIR/golden_a_notube.wav" "$(wpath "$OUT/golden_a_notube.wav")" >/dev/null
"$ADB" pull "$DEVDIR/golden_b_notube.wav" "$(wpath "$OUT/golden_b_notube.wav")" >/dev/null
echo "OK: tools/golden/results/$TAG"
