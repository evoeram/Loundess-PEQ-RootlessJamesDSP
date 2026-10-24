#!/system/bin/sh
# Boot safety net. Runs before the audio server starts.
# Validates our patched audio configs and quarantines bad ones.
# Marks boot as in-progress so service.sh can clear it.

MODDIR=${0%/*}
[ -n "$MODDIR" ] && [ -d "$MODDIR" ] || exit 0

UUID="f27317f4-c984-4de6-9a90-545759495bf2"
LIB_NAME="jdsp"

# Mark boot as in-progress. service.sh clears this once boot completes.
touch "$MODDIR/.boot_incomplete" 2>/dev/null

# Validate our patched audio configs
find -L "$MODDIR/system" -maxdepth 6 -type f \
  \( -name "audio_effects*.xml" -o -name "audio_effects*.conf" \) 2>/dev/null |
while read -r f; do
  bad=0
  grep -q "$UUID" "$f" || bad=1
  case "$f" in
    *.xml)
      grep -q "name=\"$LIB_NAME\"" "$f" || bad=1
      grep -q "</audio_effects_conf>" "$f" || bad=1
      ;;
    *.conf)
      grep -q "soundfx" "$f" || bad=1
      grep -q "$LIB_NAME {" "$f" || bad=1
      awk '{ n += gsub(/{/,"{") - gsub(/}/,"}"); if (n < 0) exit 1 } END { exit (n != 0) }' "$f" || bad=1
      ;;
  esac
  [ "$(wc -c < "$f" 2>/dev/null || echo 0)" -gt 100 ] || bad=1
  if [ "$bad" = "1" ]; then
    mv "$f" "$f.rejected" 2>/dev/null
    echo "rejected $f (failed validation)" > "$MODDIR/boot.log" 2>&1
  fi
done

exit 0
