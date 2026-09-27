#!/system/bin/sh
# post-fs-data.sh — выполняется на ранней стадии загрузки (до запуска audioserver).
#
# Совместимость с KernelSU / SukiSU / APatch:
# В этих root-решениях service.sh может запуститься позже, чем audioserver
# загружает audio_effects. post-fs-data.sh гарантированно отрабатывает
# до старта аудиосистемы, обеспечивая правильный SELinux-контекст.
#
# Magisk также поддерживает post-fs-data.sh (запускается после монтирования /data).

MODDIR=${0%/*}
MODID=ainur_jamesdsp

# SELinux: устанавливаем vendor_file:s0 контекст для libjamesdsp.so
# Без этого audioserver не может загрузить библиотеку (No such file or directory)
chcon u:object_r:vendor_file:s0 /vendor/lib/soundfx/libjamesdsp.so 2>/dev/null
chcon u:object_r:vendor_file:s0 /vendor/lib64/soundfx/libjamesdsp.so 2>/dev/null

# AML (Audio Modification Library) workaround — бинд-маунт odm конфигов
# Выполняем здесь тоже, т.к. KernelSU может не дождаться service.sh
NVBASE=/data/adb
DIR=$MODDIR
[ -d $NVBASE/aml/$MODID ] && DIR=$NVBASE/aml/$MODID
for i in $(find $DIR/system/odm -type f -name "*audio_effects*.conf" -o -name "*audio_effects*.xml" 2>/dev/null); do
  j="$(echo $i | sed "s|$DIR/system||")"
  mount -o bind $i $j 2>/dev/null
done
