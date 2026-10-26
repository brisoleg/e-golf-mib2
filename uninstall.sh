#!/bin/ksh
# e-golf-mib2 uninstaller: puts the unit back to the state before install.sh.
# Run on the MMX as root:  ksh /path/to/e-golf-mib2/uninstall.sh
# Reboot the unit afterwards.

BASE=${0%/*}
[ "$BASE" = "$0" ] && BASE=.
. "$BASE/lib.sh"

say "e-golf-mib2 $VERSION uninstaller"
need_tools awk grep cp mv rm chmod ls mount
check_mmx

STAGE=$RAMDISK/carplay_aid.$$
rm -rf "$STAGE"
mkdir -p "$STAGE" || fail "cannot create $STAGE"
lsd_strip "$LSD" > "$STAGE/lsd.new" || fail "read lsd.sh"
/bin/ksh -n "$STAGE/lsd.new" >/dev/null 2>&1 || fail "lsd.sh without our block failed the ksh syntax check; nothing changed"

remount_app

say "-- lsd.sh"
if grep -q "^$MARK_BEGIN" "$LSD"; then
    lsd_activate "$STAGE/lsd.new"
    say "   our block removed"
else
    say "   no block of ours"
fi

say "-- now playing"
rm -f "$JARS/CarPlayAID.jar"
if [ -e "$BACKUP/BatteryVC.jar" ] && [ ! -e "$JARS/BatteryVC.jar" ]; then
    mv "$BACKUP/BatteryVC.jar" "$JARS/BatteryVC.jar" && say "   BatteryVC.jar restored"
fi

say "-- steering-wheel map zoom"
if [ -e "$BACKUP/ZOOM_JAR_OURS" ]; then
    rm -f "$JARS/CarPlayClusterControls.jar" "$BACKUP/ZOOM_JAR_OURS"
    say "   CarPlayClusterControls.jar removed"
else
    say "   not installed by us, left as is"
fi

say "-- navigation hints (RGI)"
# Stop the hook first: the launcher reads these on the next mm-ipod start.
rm -f "$APP/rgi_enable_preload" "$APP/rgi_allow_send" "$APP/rgi_allow_rx"
if [ -e "$MMIPOD.real" ]; then
    s=`fsize "$MMIPOD.real"`
    if [ "$s" = "$MMIPOD_SIZE_STOCK" ]; then
        mv "$MMIPOD.real" "$MMIPOD" || fail "restore stock mm-ipod"
        say "   stock mm-ipod restored"
    else
        say "   WARNING: $MMIPOD.real is $s bytes, not stock; left in place (launcher stays default-off)"
    fi
elif [ -e "$BACKUP/mm-ipod.stock" ] && [ "`fsize "$MMIPOD"`" != "$MMIPOD_SIZE_STOCK" ]; then
    install_file "$BACKUP/mm-ipod.stock" "$MMIPOD" 755
    say "   stock mm-ipod restored from backup"
fi
rm -f "$RGI_SO"
rmdir "$RGI_DIR" 2>/dev/null
for c in $M2_CLASSES; do
    rm -f "$M2_DIR/$c"
done
rmdir "$M2_DIR" "$JARS/ua/mib" "$JARS/ua" 2>/dev/null
if grep -q "recvmsgs=0x5201" "$IAP2" 2>/dev/null && [ -s "$IAP2.pre_rgi" ]; then
    remount_system
    install_file "$IAP2.pre_rgi" "$IAP2" 644
    say "   iap2.cfg restored from iap2.cfg.pre_rgi"
fi

rm -f "$BACKUP/VERSION"
rm -rf "$STAGE"
sync
say ""
say "UNINSTALL_OK"
say "Backups stay in $BACKUP (lsd.sh.orig, mm-ipod.stock); delete the folder by hand if you like."
say "Reboot the unit (hold the power button ~10 s)."
