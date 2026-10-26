# Shared paths and helpers for install.sh / uninstall.sh / status.sh. Sourced, not executed.
# Target: VW MIB2 High (MHI2) MU1367, QNX 6.5 MMX shell (SSH or telnet as root).

VERSION="1.0.0"

# Non-interactive SSH on the MMX has a minimal PATH; the usual tools live under /mnt/app/armle.
PATH=$PATH:/mnt/app/armle/bin:/mnt/app/armle/usr/bin:/mnt/app/armle/sbin:/mnt/app/armle/usr/sbin
export PATH

# Some units have only gawk.
if command -v awk >/dev/null 2>&1; then
    AWK=awk
else
    AWK=/mnt/app/armle/usr/bin/gawk
fi

# Host tests set CARPLAY_AID_TEST_ROOT to a fake filesystem; on the unit it is empty.
R=${CARPLAY_AID_TEST_ROOT:-}
APP=$R/mnt/app
JXE=$R/ifs/lsd.jxe
LSD=$APP/eso/hmi/lsd/lsd.sh
JARS=$APP/eso/hmi/lsd/jars
SBIN=$APP/armle/usr/sbin
MMIPOD=$SBIN/mm-ipod
DRIVER=$APP/armle/lib/dll/ipod-drvr-iap2.so
IAP2=$R/mnt/system/etc/mm/iap2.cfg
SYSTEM=$R/mnt/system
RAMDISK=$R/ramdisk
SHMEM=$R/dev/shmem
RGI_DIR=$APP/rgi
# The launcher has this path compiled in; the file is the M1d build (sender + receiver).
RGI_SO=$RGI_DIR/librgi_mu1367_m1c.so
M2_DIR=$JARS/ua/mib/rgi
OMONOB_DIR=$APP/carplaymaps
BACKUP=$APP/carplay_aid_backup

# Known stock MU1367 sizes (no sha256 on the unit; the launcher and the hook check SHA-256 at runtime).
JXE_SIZE_MU1367=55840541
MMIPOD_SIZE_STOCK=40297
DRIVER_SIZE_STOCK=739925

MARK_BEGIN="# >>> e-golf-mib2"
MARK_END="# <<< e-golf-mib2"
IAP2_MARK="# e-golf-mib2: CarPlay route guidance message IDs (iap2.cfg.pre_rgi = original)"

M2_CLASSES="ManeuverMapper.class RgiModule.class RgiModule\$1.class RgiModule\$2.class RgiModule\$Poller.class
RgiParser.class RgiQueue.class RgiQueue\$Listener.class RgiQueue\$RawListener.class
RouteGuidanceDriver.class RouteGuidanceDriver\$1.class RouteGuidanceDriver\$2.class RouteGuidanceDriver\$3.class
RouteGuidanceDriver\$Logger.class"

say() { echo "$*"; }
fail() { echo "FAIL: $*"; exit 1; }

# File size in bytes, empty if missing.
fsize() {
    [ -e "$1" ] || { echo ""; return; }
    set -- `ls -ln "$1" 2>/dev/null`
    echo "$5"
}

need_tools() {
    [ -x "$AWK" ] || command -v "$AWK" >/dev/null 2>&1 || fail "neither awk nor gawk found"
    for t in "$@"; do
        command -v "$t" >/dev/null 2>&1 || fail "required tool '$t' not found in PATH ($PATH)"
    done
}

check_mmx() {
    [ -s "$JXE" ] && [ -d "$APP/eso/hmi/lsd" ] || fail "run this on the MMX (main unit) shell, not on RCC"
    s=`fsize "$JXE"`
    [ "$s" = "$JXE_SIZE_MU1367" ] || fail "unsupported firmware: lsd.jxe is $s bytes, only MU1367 ($JXE_SIZE_MU1367) is supported"
    [ -s "$LSD" ] || fail "$LSD not found"
}

remount_app() {
    [ -n "$R" ] || mount -uw "$APP" >/dev/null 2>&1
    probe="$APP/.carplay_aid_probe.$$"
    : > "$probe" 2>/dev/null || fail "$APP is read-only (mount -uw $APP failed)"
    rm -f "$probe"
}

remount_system() {
    [ -n "$R" ] || mount -uw "$SYSTEM" >/dev/null 2>&1
    probe=$SYSTEM/etc/mm/.carplay_aid_probe.$$
    : > "$probe" 2>/dev/null || fail "$SYSTEM is read-only (mount -uw /mnt/system failed)"
    rm -f "$probe"
}

# Copies $1 to $2 through a temporary file, so $2 is never half-written.
install_file() {
    cp "$1" "$2.new" || fail "copy $1 -> $2.new"
    chmod "$3" "$2.new"
    mv "$2.new" "$2" || fail "activate $2"
}

# Prints lsd.sh without our block.
lsd_strip() {
    $AWK -v b="$MARK_BEGIN" -v e="$MARK_END" '
        index($0, b) == 1 { skip = 1; next }
        $0 == e { skip = 0; next }
        !skip { print }
    ' "$1"
}

# Checks a candidate lsd.sh and puts it in place, keeping the file mode.
lsd_activate() {
    /bin/ksh -n "$1" >/dev/null 2>&1 || fail "patched lsd.sh failed the ksh syntax check; nothing changed"
    cp "$LSD" "$LSD.carplay_aid.new" || fail "stage lsd.sh"
    cat "$1" > "$LSD.carplay_aid.new" || fail "stage lsd.sh"
    mv "$LSD.carplay_aid.new" "$LSD" || fail "activate lsd.sh"
}
