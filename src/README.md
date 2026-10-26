# Sources

The binaries in `Custom/GreenMenu/scripts/egolf/payload/` are built from these sources; `payload/SHA256SUMS` lists their hashes.

| Folder | Builds | Runs in |
| --- | --- | --- |
| `native/` | `librgi_mu1367_m1d.so` (iAP2 hook) and `rgi-mm-ipod-launcher` | `mm-ipod` (QNX 6.5, ARMv7) |
| `m2/` | `ua/mib/rgi/*.class` (route guidance → AID) | HMI Java VM (IBM J9, Java 1.4 class files) |
| `carplayaid/` | `CarPlayAID.jar` (now playing + route guidance autostart) | HMI Java VM |

## Native (`native/`)

Needs a QNX SDP 6.5 ARMv7 toolchain. It was built in Docker with
[luka-dev/qnx65-armv7-toolchain](https://github.com/luka-dev/qnx65-armv7-toolchain) (`base-4.9`, GCC 4.9):

```sh
cd src/native
Q="qcc -Vgcc_ntoarmv7le -O2 -Wall -Wextra -I."
# hook: identification + 0x5200 sender + 0x5201/0x5202 receiver
$Q -fPIC -shared -DRGI_WITH_RX -fno-builtin-memcpy -o librgi_mu1367_m1d.so \
   m1c.c rgi_start.c rgi_sender_gate.c rgi_retry.c m1d_rx.c rgi_event_store.c rgi_rx_probe.c sha256.c -lc
# launcher: default-off exec wrapper around the stock mm-ipod
$Q -DRGI_LAUNCH_REAL='"/mnt/app/armle/usr/sbin/mm-ipod.real"' \
   -DRGI_LAUNCH_SO='"/mnt/app/rgi/librgi_mu1367_m1c.so"' \
   -o rgi-mm-ipod-launcher rgi_launcher.c rgi_stock_binary.c sha256.c
sh ./verify-qnx-elf.sh m1d ./librgi_mu1367_m1d.so
```

The hook checks the SHA-256 of the loaded `ipod-drvr-iap2.so` and the stock table layout before it writes
anything; on any mismatch it stays passive. The launcher checks the SHA-256 of the stock `mm-ipod` and
only adds the hook to `LD_PRELOAD` when `/mnt/app/rgi_enable_preload` exists and `/mnt/app/rgi_disable`
does not. The hook is loaded from `/mnt/app/rgi/librgi_mu1367_m1c.so` (path compiled into the launcher).

## Java (`m2/`, `carplayaid/`)

Compile with [ecj](https://download.eclipse.org/eclipse/downloads/) for Java 1.4 against the HMI classes
(`lsd.jar`, extracted from the unit's `/ifs/lsd.jxe`, not included here):

```sh
java -jar ecj.jar -source 1.4 -target 1.4 -nowarn -encoding UTF-8 \
     -classpath lsd.jar -d out m2/ua/mib/rgi/*.java carplayaid/de/audi/tghu/dsitrace/extension/IUserFunction.java
```

See `carplayaid/README.md` for `CarPlayAID.jar`.
