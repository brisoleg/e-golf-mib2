# Third-party components and credits

| Component | Where | Author | License |
| --- | --- | --- | --- |
| `CarPlayClusterControls.jar` (steering-wheel zoom / map view for the CarPlay map on the AID) | `Custom/GreenMenu/scripts/egolf/payload/zoom/` | [omonob](https://github.com/omonob) — [MHI2-Carplay-Maps](https://github.com/omonob/MHI2-Carplay-Maps), toolbox V7.2.2 | GPL-3.0. Shipped unmodified; the source is in the upstream repository. |
| iAP2 route guidance TLV layout | `src/native/rgi_start.c` | checked against [edzenis/mib2high-carplay-rgi](https://github.com/edzenis/mib2high-carplay-rgi) and LuKa | reference only, no code copied |
| QNX 6.5 ARMv7 toolchain (build only, not shipped) | used to build `src/native` | LuKa — [qnx65-armv7-toolchain](https://github.com/luka-dev/qnx65-armv7-toolchain) | see upstream |
| Green Engineering Menu install method (not shipped) | `Custom/GreenMenu/` layout for the toolbox GreenMenu import | jilleb — [mib2-toolbox](https://github.com/jilleb/mib2-toolbox) | MIT |

`ManeuverMapper` (CarPlay maneuver type → VW cockpit arrow) is written for this project from Apple's
public CarPlay SDK header (`CPManeuver.h`) and the HMI's own NavSD classes; it contains no third-party code.

The CarPlay map video on the AID itself is omonob's **CarPlay Maps VC** and is not part of this package;
install it from the upstream releases first.

`CarPlayAID.jar` contains patched copies of the HMI classes
`de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo` and
`de.vw.mib.asl.internal.kombipictureserver.usecaces.CoverArt` (compiled from the unit's own `lsd.jxe`
with a few added calls, see `src/carplayaid/README.md`). Their source is not published here because it is
Volkswagen / e.solutions code.

`cpcover` uses the unit's own QNX image library (`libimg`) at run time; nothing of it is shipped.

Volkswagen, Apple CarPlay, Apple Maps, Google Maps and other names are trademarks of their owners.
This project is not affiliated with or endorsed by any of them.
