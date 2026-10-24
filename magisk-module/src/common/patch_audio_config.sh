#!/system/bin/sh
# Registers the JamesDSP effect with the device's audio configuration.
#
# Instead of shipping pre-patched XML/conf files that assume a specific
# device layout, this script searches the LIVE device for all
# audio_effects.xml and audio_effects.conf files, copies the real one,
# injects the JamesDSP entries with awk, validates the result, and only
# then writes it to $MODPATH for Magisk overlay.
#
# This handles Samsung (_sec variants), Qualcomm SKU paths, ODM configs,
# and any other vendor-specific layout without breaking existing effects.

EFFECT_UUID="f27317f4-c984-4de6-9a90-545759495bf2"
LIB_NAME="jdsp"
EFFECT_NAME="jamesdsp"

# Where the legacy effects loader actually looks, in its own order.
# Covers /odm, /vendor, /system, system_ext, product, and Qualcomm SKU paths.
find_configs() {
  for f in \
    /odm/etc/audio_effects.xml \
    /odm/etc/audio_effects.conf \
    /vendor/etc/audio/audio_effects.xml \
    /vendor/etc/audio/audio_effects.conf \
    /vendor/etc/audio_effects.xml \
    /vendor/etc/audio_effects.conf \
    /system/vendor/etc/audio_effects.xml \
    /system/vendor/etc/audio_effects.conf \
    /system_ext/etc/audio_effects.xml \
    /system_ext/etc/audio_effects.conf \
    /product/etc/audio_effects.xml \
    /product/etc/audio_effects.conf \
    /system/etc/audio_effects.xml \
    /system/etc/audio_effects.conf
  do
    [ -f "$f" ] && echo "$f"
  done
  # Samsung-specific: devices may use audio_effects_sec.xml etc.
  for f in \
    /odm/etc/audio_effects_sec.xml \
    /odm/etc/audio_effects_sec.conf \
    /vendor/etc/audio_effects_sec.xml \
    /vendor/etc/audio_effects_sec.conf \
    /system/vendor/etc/audio_effects_sec.xml \
    /system/vendor/etc/audio_effects_sec.conf
  do
    [ -f "$f" ] && echo "$f"
  done
  # Qualcomm SKU-specific paths
  for sku in /vendor/etc/audio/sku_*; do
    [ -d "$sku" ] || continue
    for f in "$sku"/audio_effects.xml "$sku"/audio_effects.conf; do
      [ -f "$f" ] && echo "$f"
    done
  done
}

# Magisk mirrors module files from $MODPATH/system, so /vendor/etc/x has to be
# written to $MODPATH/system/vendor/etc/x.
module_path_for() {
  case "$1" in
    /system/*) echo "$MODPATH$1" ;;
    *)         echo "$MODPATH/system$1" ;;
  esac
}

# Strip our own previous entries so re-running is idempotent.
# Handles both library and effect entries in XML and conf formats.
strip_ours() {
  case "$1" in
    *.conf)
      # Skip whole blocks that mention our library or effect name.
      awk -v lib="$LIB_NAME" -v eff="$EFFECT_NAME" '
        skip { if ($0 ~ /^  \}/) skip = 0; next }
        $0 ~ "^  (" lib "|jamesdsp|" eff ") \{" { skip = 1; next }
        { print }
      ' "$1"
      ;;
    *)
      # XML entries are one line each; anchored to our tags.
      grep -v "<effect .*uuid=\"$EFFECT_UUID\"" "$1" |
        grep -v "<library name=\"$LIB_NAME\"" |
        grep -v "<library name=\"jamesdsp\"" |
        grep -v "<effect name=\"$EFFECT_NAME\""
      ;;
  esac
}

validate_xml() {
  grep -q "$EFFECT_UUID" "$1" &&
  grep -q "name=\"$LIB_NAME\"" "$1" &&
  grep -q "</audio_effects_conf>" "$1" &&
  grep -q "</libraries>" "$1" &&
  grep -q "</effects>" "$1" &&
  [ "$(wc -c < "$1")" -gt 200 ]
}

validate_conf() {
  grep -q "$EFFECT_UUID" "$1" &&
  grep -q "$LIB_NAME {" "$1" &&
  grep -q "soundfx" "$1" &&
  # Braces must balance and never close below zero.
  awk '{ n += gsub(/{/,"{") - gsub(/}/,"}"); if (n < 0) exit 1 } END { exit (n != 0) }' "$1" &&
  [ "$(wc -c < "$1")" -gt 100 ]
}

# Patch XML: insert library after <libraries> and effect after <effects>.
patch_xml() {
  strip_ours "$1" | awk -v lib="        <library name=\"$LIB_NAME\" path=\"libjamesdsp.so\"/>" \
      -v eff="        <effect name=\"$EFFECT_NAME\" library=\"$LIB_NAME\" uuid=\"$EFFECT_UUID\"/>" '
    { print }
    /<libraries>/ && !l { print lib; l=1 }
    /<effects>/   && !e { print eff; e=1 }
    END { if (!l || !e) exit 3 }
  ' > "$2"
}

# Patch .conf: insert library block after libraries { and effect block after effects {.
# Path must be /system/lib/soundfx/ (not lib64) — AOSP checkLibraryPath() requires it.
patch_conf() {
  strip_ours "$1" | awk -v lib="  $LIB_NAME {\n    path /system/lib/soundfx/libjamesdsp.so\n  }" \
      -v eff="  $EFFECT_NAME {\n    library $LIB_NAME\n    uuid $EFFECT_UUID\n  }" '
    { print }
    /^libraries[[:space:]]*\{/ && !l { printf "%s\n", lib; l=1 }
    /^effects[[:space:]]*\{/   && !e { printf "%s\n", eff; e=1 }
    END { if (!l || !e) exit 3 }
  ' > "$2"
}

patch_audio_config() {
  found=0
  seen=""
  for cfg in $(find_configs); do
    # /vendor is a symlink into /system/vendor on pre-Treble and Samsung,
    # so deduplicate by real path to avoid double-patching one file.
    real="$(readlink -f "$cfg" 2>/dev/null || echo "$cfg")"
    case " $seen " in *" $real "*) continue ;; esac
    seen="$seen $real"

    target="$(module_path_for "$cfg")"
    mkdir -p "$(dirname "$target")"

    # Generate beside the real name; move into place only after validation.
    tmp="$target.tmp"
    ok=
    case "$cfg" in
      *.xml)  patch_xml  "$cfg" "$tmp" && validate_xml  "$tmp" && ok=1 ;;
      *.conf) patch_conf "$cfg" "$tmp" && validate_conf "$tmp" && ok=1 ;;
    esac

    if [ "$ok" = "1" ]; then
      mv -f "$tmp" "$target"
      # Set correct SELinux context for vendor/odm configs.
      case "$target" in
        */system/vendor/*|*/system/odm/*)
          [ "${API:-0}" -ge 29 ] &&
            set_perm "$target" 0 0 0644 u:object_r:vendor_configs_file:s0 ||
            set_perm "$target" 0 0 0644
          ;;
        *) set_perm "$target" 0 0 0644 ;;
      esac
      ui_print "- Registered in $cfg"
      # Remember which files we patched for OTA staleness detection.
      echo "$cfg $(wc -c < "$cfg") $(wc -l < "$cfg")" >> "$MODPATH/.source_config"
      found=1
      # Continue patching all configs — devices with 32-bit audio HALs may
      # load effects from /vendor/etc AND /odm/etc configs simultaneously.
      # The effect UUID is the same in all; AudioFlinger deduplicates by UUID.
    else
      rm -f "$tmp"
      ui_print "! Generated config for $cfg failed validation, skipped"
    fi
  done
  if [ "$found" != "1" ]; then
    ui_print "! No audio config patched; the effect will not load"
    return 1
  fi
  return 0
}

# For AML (Audio Modification Library) compatibility.
# AML sources this file and calls patch_cfgs with the device's configs.
write_effect_fragment() {
  cat > "$MODPATH/aml.sh" << AMLEOF
#!/system/bin/sh
# Sourced by Audio Modification Library while it merges audio mods.
patch_cfgs $EFFECT_NAME $EFFECT_UUID $LIB_NAME /system/lib/soundfx/libjamesdsp.so
AMLEOF
  set_perm "$MODPATH/aml.sh" 0 0 0755
  ui_print "- Wrote aml.sh for the library to merge"
}
