# e-golf-mib2 — CarPlay on the VW Active Info Display

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/yumiko58185)

Mods for the Volkswagen **MIB2 High (Discover Pro)** head unit that bring more of Apple CarPlay to the
**Active Info Display (AID)** digital cockpit:

| Feature | What you get on the AID |
| --- | --- |
| **Navigation hints** | Turn arrow, distance to the next maneuver, distance and time to destination from **Apple Maps** and **Google Maps** running in CarPlay |
| **Now playing** | Song title, artist and album from CarPlay on the AID audio tab instead of the plain "Apple CarPlay" |
| **Map zoom** | Zoom the CarPlay map on the AID with the steering-wheel ▲/▼ buttons (short press: zoom, long press: map view) |

One installer, one uninstaller, every change backed up. Firmware other than the one listed under
[Tested on](#tested-on) is refused before anything is written.

> [!WARNING]
> This modifies system files of your car's head unit. You do it at your own risk. Read the whole page
> first, keep the backups, and do the first test with the car parked.

## Screenshots

<!-- Put the pictures into docs/screenshots/ and replace the placeholders below. -->

| Navigation hints (Apple Maps) | Navigation hints (Google Maps) |
| --- | --- |
| _screenshot coming soon_ <!-- ![Apple Maps hints on the AID](docs/screenshots/nav-apple-maps.jpg) --> | _screenshot coming soon_ <!-- ![Google Maps hints on the AID](docs/screenshots/nav-google-maps.jpg) --> |

| Now playing | Map zoom |
| --- | --- |
| _screenshot coming soon_ <!-- ![Now playing on the AID](docs/screenshots/now-playing.jpg) --> | _screenshot coming soon_ <!-- ![CarPlay map zoom on the AID](docs/screenshots/map-zoom.jpg) --> |

## Tested on

| | |
| --- | --- |
| Car | Volkswagen e-Golf (2020) |
| Head unit | MIB2 High, Discover Pro 9.2", Harman MMX2 |
| Firmware (train) | `MHI2_ER_VWG13_P4521_2`, MU **1367**, HMI `H29.319.75`, QNX 6.5 ARMv7 (`/ifs/lsd.jxe` = 55,840,541 bytes) |
| Cockpit | Active Info Display 12.3" (omonob profile `MU1367/791`) |
| CarPlay map on the AID | omonob CarPlay Maps VC `V7.2.2-PLAINTEXT-TOOLBOX-1` |
| CarPlay | wired (USB) |
| Navigation apps | Apple Maps, Google Maps |

The installer only accepts MU1367 (checked by the `lsd.jxe` size) with the stock `mm-ipod` and
`ipod-drvr-iap2.so`. The native hook and the launcher also check the SHA-256 of those files at runtime and
stay passive on any mismatch.

> [!NOTE]
> Status of 1.0.0: navigation hints, now playing and map zoom were validated on this car. The one-step
> installer and the standalone `CarPlayAID.jar` are new in this release and were tested on a simulated
> unit file system; please report how they behave on yours.

## Requirements

- MIB2 High with the firmware above and an Active Info Display.
- [**MIB2 Toolbox**](https://github.com/jilleb/mib2-toolbox) by jilleb installed on the unit, and its SD card
  (the one with the `Toolbox` folder). The Green Engineering Menu must be enabled.
- For **map zoom**: omonob's [CarPlay Maps VC](https://github.com/omonob/MHI2-Carplay-Maps) installed
  (it puts the CarPlay map on the AID). Without it the installer skips zoom.
- An iPhone with CarPlay over USB.

Navigation hints and now playing were tested together with CarPlay Maps VC and do not depend on its
files. A root shell (SSH / telnet) is not needed, but works too, see [Install over SSH](#install-over-ssh).

## Install with the MIB2 Toolbox

1. Download this repository (**Code → Download ZIP**) and unpack it.
2. Copy the `Custom` folder from it to the root of your **MIB2 Toolbox SD card**, merging it with the
   `Custom` folder that is already there. You get:
   ```
   Custom/GreenMenu/egolf-carplay.esd
   Custom/GreenMenu/scripts/egolf/        (install.sh, payload/, ...)
   ```
   Keep loose `.sh` files out of `Custom/GreenMenu/scripts/`: the toolbox then copies only those and skips
   folders. Put your own scripts into a subfolder as well.
3. Insert the card (only one SD card in the unit) and unplug the iPhone.
4. Hold the **MENU** button → **Testmode** → **Green Engineering Menu** → **mqbcoding** →
   **Customization** → **GreenMenu** → **Install GreenMenu screens and scripts from Custom/GreenMenu**.
5. Leave the Green Engineering Menu and open it again. There is a new screen **mqbcoding → e-golf-mib2**:

   | Button | What it does |
   | --- | --- |
   | **Install / update** | Installs all three features. It checks everything first and stops without changes if something does not match; the last line must be `INSTALL_OK`. |
   | **Status** | Shows what is installed (read only). |
   | **Navigation hints off / on** | Turns the hints off or on without uninstalling (applies after replugging the iPhone). |
   | **Uninstall** | Removes everything and restores the backups, see [Uninstall](#uninstall). |

6. Press **Install / update**, wait for `INSTALL_OK`, then **reboot the unit**: hold the power button for
   about 10 seconds until it restarts.
7. Plug in the iPhone, start a route in Apple Maps or Google Maps and open the navigation view on the AID.
   The hints appear about 30 s after the unit has booted.

The scripts stay on the unit in `/eso/hmi/engdefs/scripts/egolf/`, so the SD card is not needed afterwards.
Pressing **Install / update** again updates an existing install; to update to a new version, repeat
steps 1–6.

## Install over SSH

If you already have a root shell on the MMX (the main CPU of the unit):

```sh
scp -O -r Custom/GreenMenu/scripts/egolf root@<unit-ip>:/ramdisk/
ssh root@<unit-ip> ksh /ramdisk/egolf/install.sh
```

`/ramdisk` is RAM and is cleared on reboot. Use `-O` (legacy scp protocol) for the unit's old sshd.
`status.sh`, `nav_off.sh`, `nav_on.sh` and `uninstall.sh` in the same folder work the same way.
Optional, before copying: `cd Custom/GreenMenu/scripts/egolf/payload && shasum -a 256 -c SHA256SUMS`.

### What the installer changes

| What | Where | Backup / undo |
| --- | --- | --- |
| `mm-ipod` launcher (stock binary renamed to `mm-ipod.real`) | `/mnt/app/armle/usr/sbin/mm-ipod` | `/mnt/app/carplay_aid_backup/mm-ipod.stock` |
| Native iAP2 hook | `/mnt/app/rgi/librgi_mu1367_m1c.so` | deleted on uninstall |
| Hook switches | `/mnt/app/rgi_enable_preload`, `rgi_allow_send`, `rgi_allow_rx` | deleted on uninstall |
| Route guidance message IDs (`[ident]` section) | `/mnt/system/etc/mm/iap2.cfg` | `/mnt/system/etc/mm/iap2.cfg.pre_rgi` |
| Route guidance → AID (Java) | `/mnt/app/eso/hmi/lsd/jars/ua/mib/rgi/*.class` | deleted on uninstall |
| Now playing + autostart | `/mnt/app/eso/hmi/lsd/jars/CarPlayAID.jar` | deleted on uninstall |
| Map zoom (only if not there yet) | `/mnt/app/eso/hmi/lsd/jars/CarPlayClusterControls.jar` | deleted on uninstall if we installed it |
| Guarded block that loads the jars | `/mnt/app/eso/hmi/lsd/lsd.sh` (between `# >>> e-golf-mib2` and `# <<< e-golf-mib2`) | `/mnt/app/carplay_aid_backup/lsd.sh.orig` |

The patched `lsd.sh` is syntax-checked with `ksh -n` before it replaces the original. Nothing else is
touched: no Wi-Fi, SSH, boot scripts, `dio_manager` or omonob files.

## Uninstall

Press **Uninstall** on the **e-golf-mib2** screen (or run `ksh /eso/hmi/engdefs/scripts/egolf/uninstall.sh`),
then reboot the unit. It removes our block from `lsd.sh`, our jars and classes, the hook and its switches,
restores the stock `mm-ipod` and `iap2.cfg`, and puts back a `BatteryVC.jar` it had moved aside. A
`CarPlayClusterControls.jar` from omonob's own installer stays. The backup folder
`/mnt/app/carplay_aid_backup` is kept; delete it by hand once you are happy.

The Green Engineering Menu screen itself stays so you can reinstall. To remove it too:

```sh
mount -uw /mnt/app
rm -rf /eso/hmi/engdefs/egolf-carplay.esd /eso/hmi/engdefs/scripts/egolf
```

### Manual rollback (if the scripts cannot run)

```sh
mount -uw /mnt/app
mv /mnt/app/armle/usr/sbin/mm-ipod.real /mnt/app/armle/usr/sbin/mm-ipod
cp /mnt/app/carplay_aid_backup/lsd.sh.orig /mnt/app/eso/hmi/lsd/lsd.sh
rm -f /mnt/app/eso/hmi/lsd/jars/CarPlayAID.jar /mnt/app/rgi_enable_preload
mount -uw /mnt/system
cp /mnt/system/etc/mm/iap2.cfg.pre_rgi /mnt/system/etc/mm/iap2.cfg
sync
```

Reboot afterwards. Keep your own copy of the unit's firmware or a full dump before you start.

## Known limitations

- Only **MU1367** with the firmware above. Other firmware is refused.
- Only **wired** CarPlay was tested.
- **Google Maps** sends the turn arrow and the distance to the maneuver only while the car is moving
  (parked it sends distance and time to destination only).
- **Waze** does not send route guidance to the car, so nothing is shown for it.
- Street names and maneuver text are not shown yet, and the arrival time (ETA) circle is not filled.
- CarPlay hints take over the AID only while the built-in navigation is not guiding.
- `BatteryVC.jar` (an older mod from the same author) replaces the same HMI class as `CarPlayAID.jar`;
  the installer moves it aside and the uninstaller puts it back.
- Do not combine with other CarPlay route guidance installers for MIB2 (for example the Audi-oriented
  LuKa / edzenis packages): they change the same files.
- The hook logs to `/dev/shmem/rgi-*.log` and the Java part to `/mnt/ota/system/logs/rgi/`; these logs
  contain your routes and destinations, so do not post them publicly unedited.

## Planned

- **Labels on the AID**: street names and maneuver text from CarPlay.
- **Fix the CarPlay map artifacts on the AID.**
- Album art from CarPlay next to the now playing text (works in tests, not in this package yet).

## How it works

```
iPhone (Apple / Google Maps)
  │ iAP2 over USB: route guidance 0x5201 / 0x5202
  ▼
mm-ipod + native hook ── adds the RouteGuidanceDisplay component to identification,
  │                      asks the iPhone to start (0x5200), copies the frames
  ▼
/ramdisk/rgi-events (ordered queue)
  ▼
HMI Java (ua.mib.rgi) ── parses the frames, maps maneuvers to VW BAP
  ▼
AID: arrow, distance, distance and time to destination
```

Now playing reads the iAP2 driver's PPS object `/ramdisk/pps/iap2/nowplaying` and fills the AID audio tab.
Map zoom forwards the steering-wheel buttons to the CarPlay map stream (omonob). Sources and build notes:
[`src/`](src/README.md). Credits and licenses: [NOTICE.md](NOTICE.md).

## Support

The mods are free. If they are useful to you, you can support further work (street names on the AID,
map fixes, test hardware) on [Ko-fi](https://ko-fi.com/yumiko58185).

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/yumiko58185)

## License

[GPL-3.0](LICENSE) for the code in this repository. `CarPlayClusterControls.jar` is omonob's work under
GPL-3.0; `ManeuverMapper` is LuKa's work, kept with its copyright notice. See [NOTICE.md](NOTICE.md).
