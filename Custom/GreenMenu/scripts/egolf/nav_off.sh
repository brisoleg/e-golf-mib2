#!/bin/ksh
# Turns the navigation hints off without uninstalling: the launcher starts mm-ipod without the hook.

BASE=${0%/*}
[ "$BASE" = "$0" ] && BASE=.
. "$BASE/lib.sh"

check_mmx
remount_app
: > "$APP/rgi_disable" || fail "cannot create $APP/rgi_disable"
sync
say "NAV_HINTS_OFF"
say "Unplug and replug the iPhone (or reboot) to apply."
