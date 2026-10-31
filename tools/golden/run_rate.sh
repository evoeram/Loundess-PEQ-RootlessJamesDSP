#!/bin/sh
# On-device acceptance for docs/PLAN.md Phase 3:
#  - 'golden -3' (report K8, port 57ce68f/ed58602): the DDC, the compander
#    and the stereo widening follow a 44.1 / 48 kHz move. Prefix engine at
#    8fa277e (pre-P3.1) is expected to FAIL, post-fix to PASS.
#  - 'golden -4' (port 2675a58/P3.4): stream rates below 44.1 kHz no longer
#    overrun the ASRC scratch. Prefix engine is expected to FAIL, post-fix
#    to PASS.
# Runs each mode with tools/golden/build/golden_arm64_prefix and
# tools/golden/build/golden_arm64.
# Results: tools/golden/results/raterefresh_{prefix,postfix}.txt,
#          tools/golden/results/asrcup_{prefix,postfix}.txt
set -e
export MSYS_NO_PATHCONV=1
wpath() { cygpath -w "$1"; }
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEVDIR=/data/local/tmp/golden
OUT="$REPO/tools/golden/results"

test -x "$ADB" || { echo "adb not found: $ADB" >&2; exit 1; }
test -f "$REPO/tools/golden/build/golden_arm64" || { echo "build first: tools/golden/build_golden.sh" >&2; exit 1; }
test -f "$REPO/tools/golden/build/golden_arm64_prefix" || { echo "prefix binary missing (build from submodule 8fa277e)" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "no device attached" >&2; exit 1; }

mkdir -p "$OUT"
"$ADB" shell "mkdir -p $DEVDIR"
"$ADB" push "$(wpath "$REPO/tools/golden/build/golden_arm64_prefix")" "$DEVDIR/golden_prefix" >/dev/null
"$ADB" push "$(wpath "$REPO/tools/golden/build/golden_arm64")" "$DEVDIR/golden" >/dev/null
"$ADB" shell "chmod +x $DEVDIR/golden_prefix $DEVDIR/golden"
echo "== rate refresh, pre-fix engine (8fa277e), expected FAIL =="
"$ADB" shell "$DEVDIR/golden_prefix -3" | grep -Ev "^Shit" | tee "$OUT/raterefresh_prefix.txt"
echo "== rate refresh, post-fix engine, expected PASS =="
"$ADB" shell "$DEVDIR/golden -3" | grep -Ev "^Shit" | tee "$OUT/raterefresh_postfix.txt"
echo "== ASRC scratch, pre-fix engine (8fa277e), expected FAIL =="
"$ADB" shell "$DEVDIR/golden_prefix -4" | grep -Ev "^Shit" | tee "$OUT/asrcup_prefix.txt"
echo "== ASRC scratch, post-fix engine, expected PASS =="
"$ADB" shell "$DEVDIR/golden -4" | grep -Ev "^Shit" | tee "$OUT/asrcup_postfix.txt"
