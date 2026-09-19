#!/system/bin/sh
# Magisk module installation script
# Set correct permissions for audio effect library and config files

ui_print "- Installing JamesDSP audio effect library"

# Set permissions for shared libraries
set_perm_recursive "$MODPATH/system/vendor/lib" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/lib64" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/lib/soundfx" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/lib64/soundfx" 0 0 0755 0644

# Audio effect libraries need to be executable
set_perm "$MODPATH/system/vendor/lib/soundfx/libjamesdsp.so" 0 0 0644
set_perm "$MODPATH/system/vendor/lib64/soundfx/libjamesdsp.so" 0 0 0644

# Set permissions for audio_effects config files
set_perm_recursive "$MODPATH/system/etc" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/odm" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/etc" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/vendor/etc/audio" 0 0 0755 0644

ui_print "- Setting file permissions"
ui_print "- Installation complete"
ui_print "- Reboot your device to activate JamesDSP"
