#!/bin/sh
# On-device acceptance for docs/PLAN.md P3.1 (report K8): do the DDC, the
# compander and the stereo widening follow a 44.1 / 48 kHz move?
# Runs golden -3 twice: with the pre-fix binary (tools/golden/build/
# golden_arm64_prefix, engine at 8fa277e - expected FAIL) and the post-fix
# binary (golden_arm64 - expected PASS).
# Results: tools/golden/results/raterefresh_{prefix,postfix}.txt
set -e
export MSYS_NO_PATHCONV=1
wpath() { cygpath -w "$1"; }
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEVDIR=/data/local/tmp/golden
OUT="$REPO/tools/golden/results"

test -x "$ADB" || { echo "adb not found: $ADB" >&2; exit 1; }
test -f "$REPO/tools/golden/build/golden_arm64" || { echo "build first: tools/golden/build_golden.sh" >&2; exit 1; }
test -f "$REPO/tools/golden/build/golden_arm64_prefix" || { echo "prefix binary missing (build from submodule HEAD~ before fixing)" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "no device attached" >&2; exit 1; }

mkdir -p "$OUT"
"$ADB" shell "mkdir -p $DEVDIR"
"$ADB" push "$(wpath "$REPO/tools/golden/build/golden_arm64_prefix")" "$DEVDIR/golden_prefix" >/dev/null
"$ADB" push "$(wpath "$REPO/tools/golden/build/golden_arm64")" "$DEVDIR/golden" >/dev/null
"$ADB" shell "chmod +x $DEVDIR/golden_prefix $DEVDIR/golden"
echo "== pre-fix engine (8fa277e), expected FAIL =="
"$ADB" shell "$DEVDIR/golden_prefix -3" | tee "$OUT/raterefresh_prefix.txt"
echo "== post-fix engine, expected PASS =="
"$ADB" shell "$DEVDIR/golden -3" | tee "$OUT/raterefresh_postfix.txt"
