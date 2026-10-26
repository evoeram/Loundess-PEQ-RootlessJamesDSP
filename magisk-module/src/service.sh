#!/system/bin/sh
# Runs late in boot. Sets SELinux context on the .so after Magisk overlay
# and restarts audioserver to pick up the effect.
# Also clears the boot watchdog marker if boot succeeded.

MODDIR=${0%/*}
MODID=ainur_jamesdsp
NVBASE=/data/adb
(
# AML (Audio Modification Library) workaround
DIR=$MODDIR
[ -d $NVBASE/aml/$MODID ] && DIR=$NVBASE/aml/$MODID

# Re-mount audio_effects configs from our module if AML redirected them
for i in $(find $DIR/system -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" 2>/dev/null); do
  j="$(echo $i | sed "s|$DIR/system||")"
  [ -n "$j" ] && mount -o bind "$i" "$j" 2>/dev/null
done

# SELinux: ensure libjamesdsp.so has vendor_file:s0 context after Magisk overlay.
# audioserver / audio HAL cannot load the library with default system_file:s0 context.
# Cover all paths: system/lib, system/lib64, vendor/lib, vendor/lib64,
# odm/lib, odm/lib64 (OPLUS devices load effects from odm/lib/soundfx).
for L in lib lib64; do
  for P in /system/$L/soundfx /vendor/$L/soundfx /odm/$L/soundfx \
           /system/vendor/$L/soundfx /system/odm/$L/soundfx; do
    [ -f "$P/libjamesdsp.so" ] && chcon u:object_r:vendor_file:s0 "$P/libjamesdsp.so" 2>/dev/null
  done
done

# Restart audioserver and audio HAL to pick up the new effect
killall -q audioserver
killall -q android.hardware.audio.service
sleep 1
start audioserver
start vendor.audio-hal 2>/dev/null

# SELinux diagnostics: enforcement state and denials that mention our effect.
# Overwritten every boot so it cannot grow and never affects boot behaviour.
{
  echo "enforce=$(getenforce 2>/dev/null)"
  dmesg 2>/dev/null | grep -iE "avc: +denied" | grep -iE "jamesdsp|soundfx"
} > "$MODDIR/selinux.log" 2>/dev/null

# Clear boot watchdog marker if boot completed successfully
if [ "$(getprop sys.boot_completed)" = "1" ]; then
  rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes" 2>/dev/null
fi
)&
