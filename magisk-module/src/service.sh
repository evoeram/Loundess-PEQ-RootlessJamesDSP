#!/system/bin/sh
MODDIR=${0%/*}
MODID=ainur_jamesdsp
NVBASE=/data/adb
(
# AML (Audio Modification Library) workaround
DIR=$MODDIR
[ -d $NVBASE/aml/$MODID ] && DIR=$NVBASE/aml/$MODID
for i in $(find $DIR/system/odm -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" 2>/dev/null); do
  j="$(echo $i | sed "s|$DIR/system||")"
  mount -o bind $i $j
done

# SELinux: ensure libjamesdsp.so has vendor_file:s0 context after Magisk overlay mount.
# audioserver cannot load the library with default system_file:s0 context.
chcon u:object_r:vendor_file:s0 /vendor/lib/soundfx/libjamesdsp.so 2>/dev/null
chcon u:object_r:vendor_file:s0 /vendor/lib64/soundfx/libjamesdsp.so 2>/dev/null

killall -q audioserver
)&
