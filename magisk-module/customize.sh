#!/system/bin/sh
# Magisk module installation script
# Совместимость: Magisk, KernelSU, SukiSU, APatch
# Set correct permissions for audio effect library and config files

ui_print "- Installing JamesDSP audio effect library"

# Детекция root-метода (для совместимости с KernelSU/SukiSU/APatch)
KSU=false
if [ -f /data/adb/ksu ]; then
  KSU=true
  ui_print "- Detected KernelSU/SukiSU"
elif [ -f /data/adb/ap ]; then
  KSU=true
  ui_print "- Detected APatch"
fi

# Set permissions for shared libraries
set_perm_recursive "$MODPATH/system/vendor/lib" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/lib64" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/lib/soundfx" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/lib64/soundfx" 0 0 0755 0644

# Audio effect libraries need to be executable
set_perm "$MODPATH/system/vendor/lib/soundfx/libjamesdsp.so" 0 0 0644
set_perm "$MODPATH/system/vendor/lib64/soundfx/libjamesdsp.so" 0 0 0644

# SELinux: audioserver requires vendor_file:s0 context for .so in /vendor/lib*/soundfx/
# Without this, AudioFlinger fails with "No such file or directory" and the effect is not registered.
# chcon работает в Magisk, KernelSU, SukiSU и APatch (все поддерживают эту функцию в customize.sh)
chcon u:object_r:vendor_file:s0 "$MODPATH/system/vendor/lib/soundfx/libjamesdsp.so"
chcon u:object_r:vendor_file:s0 "$MODPATH/system/vendor/lib64/soundfx/libjamesdsp.so"

# Set permissions for audio_effects config files
set_perm_recursive "$MODPATH/system/etc" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/odm" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/etc" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/etc/audio" 0 0 0755 0644

# Для KernelSU/SukiSU/APatch: подтверждаем, что sepolicy.rule будет применён
# (Magisk применяет автоматически, KSU/APatch — через своё ядро)
if [ "$KSU" = true ]; then
  if [ -f "$MODPATH/sepolicy.rule" ]; then
    ui_print "- SELinux rules will be applied via sepolicy.rule"
  fi
fi

ui_print "- Setting file permissions"
ui_print "- Installation complete"
ui_print "- Reboot your device to activate JamesDSP"
