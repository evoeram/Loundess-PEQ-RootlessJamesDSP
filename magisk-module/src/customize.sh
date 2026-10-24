#!/system/bin/sh
# Installs the JamesDSP effect library and registers it with the audio HAL.
#
# Instead of shipping pre-patched XML/conf files that assume a specific
# device layout, this installer searches the LIVE device for audio_effects
# configs, copies the real one, injects JamesDSP entries, validates, and
# only then writes the patched copy to $MODPATH for Magisk overlay.

SKIPUNZIP=0

# --- detect root implementation and Android API --------------------------
API=$(getprop ro.build.version.sdk)
case "$API" in ''|*[!0-9]*) API=0 ;; esac

if [ -d /data/adb/ksu ] || [ -n "$KSU" ]; then
  ROOT_IMPL="KernelSU"
elif [ -d /data/adb/ap ] || [ -n "$APATCH" ]; then
  ROOT_IMPL="APatch"
else
  ROOT_IMPL="Magisk"
fi
ui_print "- Root: $ROOT_IMPL, Android API $API"

# --- clear stale self-disable from a previous version --------------------
if [ -f /data/adb/modules/ainur_jamesdsp/disable ]; then
  rm -f /data/adb/modules/ainur_jamesdsp/disable
  ui_print "- Cleared a previous self-disable"
  if [ -f /data/adb/modules/ainur_jamesdsp/boot.log ]; then
    grep -i "quarantined\|rejected" /data/adb/modules/ainur_jamesdsp/boot.log 2>/dev/null |
      tail -3 | while read -r l; do ui_print "  was: $l"; done
  fi
fi

# --- detect AIDL vs legacy audio effects HAL ------------------------------
# Android 15+ moved effects to AIDL HAL which does not load legacy .so libs.
HAS_AIDL=0
if command -v service >/dev/null 2>&1; then
  service list 2>/dev/null | grep -q "android.hardware.audio.effect.IFactory" && HAS_AIDL=1
fi

HAS_LEGACY_CONFIG=0
for f in /odm/etc/audio_effects.xml /odm/etc/audio_effects.conf \
         /odm/etc/audio_effects_sec.xml /odm/etc/audio_effects_sec.conf \
         /vendor/etc/audio/audio_effects.xml /vendor/etc/audio/audio_effects.conf \
         /vendor/etc/audio_effects.xml /vendor/etc/audio_effects.conf \
         /vendor/etc/audio_effects_sec.xml /vendor/etc/audio_effects_sec.conf \
         /system/vendor/etc/audio_effects.xml /system/vendor/etc/audio_effects.conf \
         /system/vendor/etc/audio_effects_sec.xml /system/vendor/etc/audio_effects_sec.conf \
         /system_ext/etc/audio_effects.xml /system_ext/etc/audio_effects.conf \
         /product/etc/audio_effects.xml /product/etc/audio_effects.conf \
         /system/etc/audio_effects.xml /system/etc/audio_effects.conf; do
  [ -f "$f" ] && HAS_LEGACY_CONFIG=1
done
# Also check Qualcomm SKU paths
for sku in /vendor/etc/audio/sku_*; do
  [ -d "$sku" ] || continue
  for f in "$sku"/audio_effects.xml "$sku"/audio_effects.conf; do
    [ -f "$f" ] && HAS_LEGACY_CONFIG=1
  done
done

if [ "$HAS_AIDL" = "1" ] && [ "$HAS_LEGACY_CONFIG" = "0" ]; then
  ui_print " "
  ui_print "! This device runs the AIDL audio effects HAL and has"
  ui_print "  no legacy effects config. It does not load effect"
  ui_print "  libraries like this one. Android 15 moved effects to"
  ui_print "  AIDL and this engine implements the older interface,"
  ui_print "  so it cannot be found here."
  ui_print "  Rootless mode still works and is unaffected."
  abort "! Aborting rather than installing something inert"
fi

if [ "$HAS_LEGACY_CONFIG" = "0" ]; then
  ui_print " "
  ui_print "! No legacy audio effects config was found on this"
  ui_print "  device, and the AIDL HAL was not detected either."
  ui_print "  There is nowhere to register the engine."
  abort "! Aborting rather than installing something inert"
fi

if [ "$HAS_AIDL" = "1" ]; then
  ui_print "- Note: this device has BOTH the AIDL HAL and a legacy"
  ui_print "  config. Installing against the legacy path; if the"
  ui_print "  effect does not appear, the AIDL HAL is the one in use."
fi

# --- architecture detection and library staging ---------------------------
# Detect primary and secondary ABI for multi-arch staging.
# Many Qualcomm/OPLUS devices run a 32-bit audio HAL process that loads
# effect .so from /vendor/lib/soundfx and /odm/lib/soundfx even on arm64
# systems. We must stage both 32-bit and 64-bit .so to cover both.
case "$ARCH" in
  arm64) ABI64="arm64-v8a";   ABI32="armeabi-v7a" ;;
  x64)   ABI64="x86_64";      ABI32="x86"         ;;
  arm)   ABI64="";            ABI32="armeabi-v7a" ;;
  x86)   ABI64="";            ABI32="x86"         ;;
  *)     abort "! Unsupported architecture: $ARCH" ;;
esac

ui_print "- Architecture: $ARCH"

# Stage the engine .so to every soundfx path the audio HAL may load from.
# Covers: system/lib, system/lib64, vendor/lib, vendor/lib64,
#         odm/lib, odm/lib64 (OPLUS Dolby libs load from odm/lib/soundfx).
# SELinux: audioserver / audio HAL require vendor_file:s0 for .so in
# /vendor/lib*/soundfx/ and /odm/lib*/soundfx/. Without this, AudioFlinger
# reports "Libraries NOT loaded" and the effect is not registered.
stage_so() {
  local abi="$1" libdir="$2" dst="$3"
  local src="$MODPATH/libs/$abi/libjamesdsp.so"
  [ -f "$src" ] || { ui_print "  (no $abi build — skipping $libdir/soundfx)"; return 0; }
  mkdir -p "$dst"
  cp "$src" "$dst/libjamesdsp.so" || abort "! Could not stage the engine into $dst"
  set_perm "$dst" 0 0 0755
  set_perm "$dst/libjamesdsp.so" 0 0 0644
  chcon u:object_r:vendor_file:s0 "$dst/libjamesdsp.so" 2>/dev/null ||
    ui_print "  (could not set vendor_file context on $dst)"
  ui_print "  staged $abi → $dst"
}

ui_print "- Staging engine to all soundfx paths:"
if [ -n "$ABI64" ]; then
  stage_so "$ABI64" lib64 "$MODPATH/system/lib64/soundfx"
  stage_so "$ABI64" lib64 "$MODPATH/system/vendor/lib64/soundfx"
  stage_so "$ABI64" lib64 "$MODPATH/system/odm/lib64/soundfx"
fi
if [ -n "$ABI32" ]; then
  stage_so "$ABI32" lib   "$MODPATH/system/lib/soundfx"
  stage_so "$ABI32" lib   "$MODPATH/system/vendor/lib/soundfx"
  stage_so "$ABI32" lib   "$MODPATH/system/odm/lib/soundfx"
fi
rm -rf "$MODPATH/libs"
ui_print "- Engine staged to all soundfx paths"

# --- patch the device's real audio config ---------------------------------
. "$MODPATH/common/patch_audio_config.sh"

# Check for Audio Modification Library / PIXAML
if [ -d /data/adb/modules/aml ] || [ -d /data/adb/modules/AML ] ||
   [ -d /data/adb/modules/pixaml ] || [ -d /data/adb/modules/PIXAML ]; then
  ui_print "- Audio Modification Library detected"
  ui_print "  Leaving the merge to AML; both mods can coexist"
  write_effect_fragment
else
  patch_audio_config || abort "! Aborting rather than installing something inert"
fi

ui_print "- Reboot to load the engine"
ui_print " "
ui_print "  If the device fails to boot, boot into Safe Mode,"
ui_print "  which disables every module, or connect it and run:"
case "$ROOT_IMPL" in
  KernelSU) ui_print "    adb wait-for-device shell su -c 'ksud module disable ainur_jamesdsp'" ;;
  APatch)   ui_print "    adb wait-for-device shell su -c 'apd module disable ainur_jamesdsp'" ;;
  *)        ui_print "    adb wait-for-device shell su -c 'touch /data/adb/modules/ainur_jamesdsp/disable'" ;;
esac
ui_print "  (su over adb has to be granted in the manager already.)"
ui_print " "
ui_print "  This module also disables itself after two boots that"
ui_print "  do not complete, so a loop caused by this alone should"
ui_print "  clear itself by the third boot."
