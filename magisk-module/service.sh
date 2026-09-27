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

# SELinux: ensure libjamesdsp.so has vendor_file:s0 context after overlay mount.
# audioserver cannot load the library with default system_file:s0 context.
# Повторяем chcon здесь, т.к. KernelSU/SukiSU могут не иметь post-fs-data.sh
# на момент первого старта audioserver (сепolicy.rule применяется с задержкой).
chcon u:object_r:vendor_file:s0 /vendor/lib/soundfx/libjamesdsp.so 2>/dev/null
chcon u:object_r:vendor_file:s0 /vendor/lib64/soundfx/libjamesdsp.so 2>/dev/null

# Перезапуск audioserver для применения новых audio_effects.xml
# killall работает и в Magisk, и в KernelSU/SukiSU/APatch
killall -q audioserver
# На некоторых устройствах audioserver не перезапускается автоматически.
# Запускаем start audioserver как fallback (через setprop для Android 13+).
setprop ctl.restart audioserver 2>/dev/null
)&
