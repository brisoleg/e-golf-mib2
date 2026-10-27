#!/bin/ksh
# e-golf-mib2 installer: CarPlay route guidance, now playing, album art and map zoom on the VW AID.
# Run from the GEM (MQBCoding -> e-golf-mib2) or on the MMX as root:  ksh /path/to/egolf/install.sh
# Safe to run again (it updates an existing install). Reboot the unit afterwards.

BASE=${0%/*}
[ "$BASE" = "$0" ] && BASE=.
. "$BASE/lib.sh"
P=$BASE/payload

say "e-golf-mib2 $VERSION installer"
need_tools grep cp mv rm mkdir chmod ls mount
check_mmx

for f in nav/librgi_mu1367_m1d.so nav/rgi-mm-ipod-launcher music/CarPlayAID.jar music/cpcover zoom/CarPlayClusterControls.jar; do
    [ -s "$P/$f" ] || fail "payload file $f missing; copy the whole folder"
done
for c in $M2_CLASSES; do
    [ -s "$P/nav/rgi/$c" ] || fail "payload file nav/rgi/$c missing; copy the whole folder"
done

# ---- preflight: decide everything before the first write ----
DRV_SIZE=`fsize "$DRIVER"`
[ "$DRV_SIZE" = "$DRIVER_SIZE_STOCK" ] || fail "ipod-drvr-iap2.so is $DRV_SIZE bytes, expected stock $DRIVER_SIZE_STOCK"

if [ -e "$MMIPOD.real" ]; then
    s=`fsize "$MMIPOD.real"`
    [ "$s" = "$MMIPOD_SIZE_STOCK" ] || fail "$MMIPOD.real is $s bytes, expected stock $MMIPOD_SIZE_STOCK"
    LAUNCHER_MODE=update
else
    s=`fsize "$MMIPOD"`
    [ "$s" = "$MMIPOD_SIZE_STOCK" ] || fail "$MMIPOD is $s bytes, expected stock $MMIPOD_SIZE_STOCK (another mod?)"
    LAUNCHER_MODE=fresh
fi

if grep -q "recvmsgs=0x5201" "$IAP2" 2>/dev/null; then
    IAP2_MODE=present
elif grep -q '^\[ident\]' "$IAP2" 2>/dev/null; then
    fail "$IAP2 already has an [ident] section from something else; merge it by hand"
else
    [ -s "$IAP2" ] || fail "$IAP2 not found"
    IAP2_MODE=append
fi

if [ -e "$JARS/BatteryVC.jar" ] && [ -e "$BACKUP/BatteryVC.jar" ]; then
    fail "both $JARS/BatteryVC.jar and $BACKUP/BatteryVC.jar exist; remove one by hand"
fi

if [ -d "$OMONOB_DIR" ]; then
    ZOOM=yes
else
    ZOOM=no
fi

STAGE=$RAMDISK/carplay_aid.$$
rm -rf "$STAGE"
mkdir -p "$STAGE" || fail "cannot create $STAGE"
lsd_strip "$LSD" > "$STAGE/lsd.base" || fail "read lsd.sh"
CPCC_REFS=`grep -c 'CarPlayClusterControls.jar' "$STAGE/lsd.base"`

{
    echo "$MARK_BEGIN (run uninstall.sh or delete this block to undo)"
    echo '# CarPlayAID.jar: CarPlay now playing and album art on the AID audio tab + route guidance autostart'
    echo 'if [[ -f "$BASE_DIR/lsd/jars/CarPlayAID.jar" ]]; then'
    echo '    BOOTCLASSPATH="$BOOTCLASSPATH -Xbootclasspath/p:$BASE_DIR/lsd/jars/CarPlayAID.jar"'
    echo 'fi'
    if [ "$ZOOM" = yes ] && [ "$CPCC_REFS" = 0 ]; then
        echo '# CarPlayClusterControls.jar (omonob, GPL-3.0): steering-wheel zoom for the CarPlay map on the AID'
        echo 'if [[ -f "$BASE_DIR/lsd/jars/CarPlayClusterControls.jar" ]]; then'
        echo '    BOOTCLASSPATH="$BOOTCLASSPATH -Xbootclasspath/p:$BASE_DIR/lsd/jars/CarPlayClusterControls.jar"'
        echo 'fi'
    fi
    echo "$MARK_END"
} > "$STAGE/block"

# Insert before the J9 launch: the first "export LD_PRELOAD=" after the BOOTCLASSPATH definition.
$AWK -v blk="$STAGE/block" '
    /^BOOTCLASSPATH=-Xbootclasspath\/a:/ { bcp = 1 }
    bcp && !done && /^export LD_PRELOAD=/ {
        while ((getline l < blk) > 0) print l
        done = 1
    }
    { print }
    END { if (!done) exit 9 }
' "$STAGE/lsd.base" > "$STAGE/lsd.new" || fail "unsupported lsd.sh layout (J9 launch line not found); nothing changed"
/bin/ksh -n "$STAGE/lsd.new" >/dev/null 2>&1 || fail "patched lsd.sh failed the ksh syntax check; nothing changed"

# ---- write ----
remount_app
mkdir -p "$BACKUP" || fail "cannot create $BACKUP"
[ -e "$BACKUP/lsd.sh.orig" ] || cp "$LSD" "$BACKUP/lsd.sh.orig" || fail "backup lsd.sh"

say "-- navigation hints (RGI)"
if [ "$LAUNCHER_MODE" = fresh ]; then
    [ -e "$BACKUP/mm-ipod.stock" ] || cp "$MMIPOD" "$BACKUP/mm-ipod.stock" || fail "backup mm-ipod"
    cp "$P/nav/rgi-mm-ipod-launcher" "$MMIPOD.new" || fail "stage launcher"
    chmod 755 "$MMIPOD.new"
    mv "$MMIPOD" "$MMIPOD.real" || fail "move stock mm-ipod to mm-ipod.real"
    mv "$MMIPOD.new" "$MMIPOD" || { mv "$MMIPOD.real" "$MMIPOD"; fail "activate launcher (stock mm-ipod restored)"; }
    say "   mm-ipod launcher installed (stock binary kept as mm-ipod.real)"
else
    install_file "$P/nav/rgi-mm-ipod-launcher" "$MMIPOD" 755
    say "   mm-ipod launcher updated (mm-ipod.real already stock)"
fi
mkdir -p "$RGI_DIR" || fail "cannot create $RGI_DIR"
install_file "$P/nav/librgi_mu1367_m1d.so" "$RGI_SO" 755
rm -f "$APP/rgi_disable"
: > "$APP/rgi_enable_preload"
: > "$APP/rgi_allow_send"
: > "$APP/rgi_allow_rx"
mkdir -p "$M2_DIR" || fail "cannot create $M2_DIR"
for c in $M2_CLASSES; do
    install_file "$P/nav/rgi/$c" "$M2_DIR/$c" 644
done
if [ "$IAP2_MODE" = append ]; then
    remount_system
    [ -e "$IAP2.pre_rgi" ] || cp "$IAP2" "$IAP2.pre_rgi" || fail "backup iap2.cfg"
    cp "$IAP2" "$STAGE/iap2.cfg" || fail "stage iap2.cfg"
    {
        echo ""
        echo "$IAP2_MARK"
        echo "[ident]"
        echo "recvmsgs=0x5201,0x5202,0x5204"
        echo "sendmsgs=0x5200,0x5203"
    } >> "$STAGE/iap2.cfg"
    install_file "$STAGE/iap2.cfg" "$IAP2" 644
    say "   iap2.cfg: route guidance IDs added (original kept as iap2.cfg.pre_rgi)"
else
    say "   iap2.cfg: route guidance IDs already present"
fi

say "-- now playing and album art (CarPlayAID.jar)"
if [ -e "$JARS/BatteryVC.jar" ]; then
    # BatteryVC.jar replaces the same HMI class (CurrentStationInfo); only one of them can be active.
    mv "$JARS/BatteryVC.jar" "$BACKUP/BatteryVC.jar" || fail "move BatteryVC.jar aside"
    say "   BatteryVC.jar moved to $BACKUP (uninstall.sh puts it back)"
fi
install_file "$P/music/CarPlayAID.jar" "$JARS/CarPlayAID.jar" 644
mkdir -p "$COVER_DIR" || fail "cannot create $COVER_DIR"
install_file "$P/music/cpcover" "$COVER_TOOL" 755
say "   album art tool installed ($COVER_TOOL)"

say "-- steering-wheel map zoom"
if [ "$ZOOM" = no ]; then
    say "   skipped: omonob CarPlay Maps VC ($OMONOB_DIR) not installed, there is no CarPlay map on the AID to zoom"
elif [ -e "$JARS/CarPlayClusterControls.jar" ] && [ ! -e "$BACKUP/ZOOM_JAR_OURS" ]; then
    say "   CarPlayClusterControls.jar already installed (omonob), left as is"
else
    install_file "$P/zoom/CarPlayClusterControls.jar" "$JARS/CarPlayClusterControls.jar" 644
    : > "$BACKUP/ZOOM_JAR_OURS"
    say "   CarPlayClusterControls.jar installed"
fi

say "-- lsd.sh"
lsd_activate "$STAGE/lsd.new"
say "   guarded block added (original kept as $BACKUP/lsd.sh.orig)"

echo "$VERSION" > "$BACKUP/VERSION"
rm -rf "$STAGE"
sync
say ""
say "INSTALL_OK"
say "Reboot the unit (hold the power button ~10 s), then plug in the iPhone."
