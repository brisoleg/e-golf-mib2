#!/bin/sh
# Offline structural gate for MU1367 QNX 6.5 ARMv7 artifacts.
# This DOES NOT prove runtime ABI compatibility, loading safety or vehicle readiness.
set -eu

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
[ "$#" -eq 2 ] || fail 'usage: verify-qnx-elf.sh m1c|m1d|launcher PATH'
kind=$1
file=$2
case "$kind" in m1c|m1d|launcher) ;; *) fail "unknown artifact kind: $kind" ;; esac
[ -f "$file" ] || fail "file not found: $file"
[ -s "$file" ] || fail "empty file: $file"

# QNX 6.5 SDK installations may expose a target-prefixed readelf.
if command -v ntoarmv7-readelf >/dev/null 2>&1; then
    reader=ntoarmv7-readelf
elif command -v readelf >/dev/null 2>&1; then
    reader=readelf
else
    fail 'no readelf found (install binutils or use QNX SDK target readelf)'
fi

hdr=$($reader -h "$file" 2>/dev/null) || fail 'invalid ELF header'
printf '%s\n' "$hdr" | grep -Eq 'Class:[[:space:]]+ELF32([[:space:]]|$)' || fail 'not ELF32'
printf '%s\n' "$hdr" | grep -Eq 'Data:[[:space:]]+2.s complement, little endian' || fail 'not little-endian'
printf '%s\n' "$hdr" | grep -Eq 'Machine:[[:space:]]+ARM([[:space:]]|$)' || fail 'not ARM'
case "$kind" in
    m1c|m1d)
        printf '%s\n' "$hdr" | grep -Eq 'Type:[[:space:]]+DYN([[:space:]]|$)' || fail 'preload must be an ELF shared object (DYN)'
        ;;
    launcher)
        printf '%s\n' "$hdr" | grep -Eq 'Type:[[:space:]]+(EXEC|DYN)([[:space:]]|$)' || fail 'launcher is neither EXEC nor PIE/DYN'
        ;;
esac

info=$($reader -d "$file" 2>/dev/null) || fail 'cannot inspect dynamic dependencies'
printf '%s\n' "$info" | grep -Eq '\(NEEDED\).*[[]libc\.so\.3[]]' || fail 'missing QNX 6.5 libc.so.3 dependency'
if printf '%s\n' "$info" | grep -Eq '\(NEEDED\).*[[](libc\.so\.[456]|ld-linux[^]]*|libc\.musl[^]]*)[]]'; then
    fail 'incompatible Linux or newer-QNX libc dependency'
fi
if $reader -V "$file" 2>/dev/null | grep -Eq 'GLIBC_[0-9]'; then
    fail 'GLIBC symbol version dependency detected'
fi

if [ "$kind" != launcher ]; then
    # SDP 6.5 binutils 2.19 readelf has no --dyn-syms; -s -D prints the same dynamic symbols.
    syms=$($reader -W --dyn-syms "$file" 2>/dev/null) || syms=$($reader -W -s -D "$file" 2>/dev/null) \
        || fail 'cannot inspect dynamic symbols'
    printf '%s\n' "$syms" | grep -Eq 'FUNC[[:space:]]+GLOBAL[[:space:]]+DEFAULT[[:space:]]+[[:digit:]]+[[:space:]]+dlopen([[:space:]]|$)' || fail 'missing exported dlopen interposer'
    if [ "$kind" = m1d ]; then
        printf '%s\n' "$syms" | grep -Eq 'FUNC[[:space:]]+GLOBAL[[:space:]]+DEFAULT[[:space:]]+[[:digit:]]+[[:space:]]+memcpy([[:space:]]|$)' || fail 'missing exported memcpy observer'
    fi
fi
printf 'PASS: %s passed offline ELF structure checks (%s)\n' "$kind" "$file"
printf 'NOTE: not a live QNX ABI, firmware, recovery or vehicle validation. DO NOT INSTALL based on this check alone.\n'