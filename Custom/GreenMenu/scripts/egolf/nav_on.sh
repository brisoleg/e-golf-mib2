#!/bin/ksh
# Turns the navigation hints back on after nav_off.sh.

BASE=${0%/*}
[ "$BASE" = "$0" ] && BASE=.
. "$BASE/lib.sh"

check_mmx
[ -e "$APP/rgi_enable_preload" ] || fail "navigation hints are not installed; run install.sh"
remount_app
rm -f "$APP/rgi_disable"
sync
say "NAV_HINTS_ON"
say "Unplug and replug the iPhone (or reboot) to apply."
