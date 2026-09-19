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
killall -q audioserver
)&
