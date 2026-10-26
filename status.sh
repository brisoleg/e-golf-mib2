#!/bin/ksh
# e-golf-mib2 status: read only, changes nothing.
# Run on the MMX:  ksh /path/to/e-golf-mib2/status.sh

BASE=${0%/*}
[ "$BASE" = "$0" ] && BASE=.
. "$BASE/lib.sh"

show() {
    if [ -f "$2" ]; then echo "  [x] $1 (${2#$R}, `fsize "$2"` bytes)"
    elif [ -e "$2" ]; then echo "  [x] $1 (${2#$R})"
    else echo "  [ ] $1 (${2#$R})"; fi
}

echo "e-golf-mib2 status (installed version: `cat "$BACKUP/VERSION" 2>/dev/null || echo none`)"
echo "Firmware: lsd.jxe `fsize "$JXE"` bytes (MU1367 = $JXE_SIZE_MU1367)"
echo "Navigation hints:"
show "mm-ipod (launcher 11231 bytes, stock 40297)" "$MMIPOD"
show "stock mm-ipod" "$MMIPOD.real"
show "native hook" "$RGI_SO"
show "Java module" "$M2_DIR/RgiModule.class"
for f in rgi_enable_preload rgi_allow_send rgi_allow_rx rgi_disable; do show "flag $f" "$APP/$f"; done
grep -q "recvmsgs=0x5201" "$IAP2" 2>/dev/null && echo "  [x] iap2.cfg route guidance IDs" || echo "  [ ] iap2.cfg route guidance IDs"
echo "Now playing:"
show "CarPlayAID.jar" "$JARS/CarPlayAID.jar"
show "BatteryVC.jar (conflicts, must be absent)" "$JARS/BatteryVC.jar"
echo "Map zoom:"
show "omonob CarPlay Maps VC" "$OMONOB_DIR"
show "CarPlayClusterControls.jar" "$JARS/CarPlayClusterControls.jar"
echo "lsd.sh:"
grep -q "^$MARK_BEGIN" "$LSD" && echo "  [x] our block present" || echo "  [ ] our block absent"
echo "Runtime:"
[ -r $SHMEM/rgi-m1b.log ] && echo "  hook log: `grep -c 5200_sent $SHMEM/rgi-m1b.log` x 5200_sent" || echo "  hook log: none (iPhone not connected since boot?)"
[ -r $SHMEM/rgi-m1d-rx.log ] && echo "  rx log: `grep -c iphone_rgi_520 $SHMEM/rgi-m1d-rx.log` x RGI frames" || echo "  rx log: none"
for f in `ls $R/mnt/ota/system/logs/rgi 2>/dev/null`; do echo "  persistent log: /mnt/ota/system/logs/rgi/$f"; done
exit 0
