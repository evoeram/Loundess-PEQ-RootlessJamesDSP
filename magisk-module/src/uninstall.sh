#!/system/bin/sh
# Uninstall script: remove our patched config overlays.
# The real device configs are untouched (we only ever wrote to $MODPATH),
# so removing the module restores the originals automatically via Magisk.
# This just cleans up any stray state files.

MODDIR=${0%/*}

# Remove boot watchdog state
rm -f "$MODDIR/.boot_incomplete" "$MODDIR/.boot_strikes" "$MODDIR/.source_config" "$MODDIR/boot.log"

# Remove AML fragment if we wrote one
rm -f "$MODDIR/aml.sh"

echo "- JamesDSP module removed; original audio config restored"
