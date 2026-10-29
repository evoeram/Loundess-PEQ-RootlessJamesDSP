#!/bin/sh
# Push every .vdc from both repositories to the device and run the parser
# fuzz mode of the golden harness (docs/PLAN.md, acceptance for report K4).
# Files are renamed to sequential safe names: corpus names contain spaces
# and parentheses that the device shell would choke on.
# Usage: tools/golden/fuzz_vdc.sh <tag>   (tag: prefix|postfix ...)
set -e
export MSYS_NO_PATHCONV=1
wpath() { cygpath -w "$1"; }
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
DEVDIR=/data/local/tmp/golden
CORPUS="$REPO/tools/golden/build/vdc_corpus"

rm -rf "$CORPUS"
mkdir -p "$CORPUS"
find "C:/dev/RootlessViPER4Android" "C:/dev/Loundess-PEQ-RootlessJamesDSP/app/src/main/assets/DDC" -name "*.vdc" 2>/dev/null | sort | while read -r f; do
	i=$((i + 1))
	cp "$f" "$CORPUS/vdc_$(printf '%04d' "$i").vdc"
done
echo "corpus: $(ls "$CORPUS" | wc -l) files"

"$ADB" shell "rm -rf $DEVDIR/vdc_corpus"
"$ADB" push "$(wpath "$REPO/tools/golden/build/golden_arm64")" "$DEVDIR/golden" >/dev/null
"$ADB" shell "chmod +x $DEVDIR/golden"
"$ADB" push "$(wpath "$CORPUS")" "$DEVDIR/vdc_corpus" >/dev/null

# Build the quoted file list on the device side to keep the command short.
"$ADB" shell "cd $DEVDIR/vdc_corpus && ls | sed \"s|^|$DEVDIR/vdc_corpus/|\" > $DEVDIR/vdc_list.txt"
"$ADB" shell "$DEVDIR/golden -1 \$(cat $DEVDIR/vdc_list.txt)" | grep -Ev "^Shit" | tee "$REPO/tools/golden/results/vdc_fuzz_$1.txt" | sort | uniq -c | sort -rn | head -10
