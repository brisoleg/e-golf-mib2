# Third-party components and credits

| Component | Where | Author | License |
| --- | --- | --- | --- |
| `CarPlayClusterControls.jar` (steering-wheel zoom / map view for the CarPlay map on the AID) | `payload/zoom/` | [omonob](https://github.com/omonob) — [MHI2-Carplay-Maps](https://github.com/omonob/MHI2-Carplay-Maps), toolbox V7.2.2 | GPL-3.0. Shipped unmodified; the source is in the upstream repository. |
| `ManeuverMapper` (CarPlay maneuver type → VW BAP arrow) | `src/m2/ua/mib/rgi/ManeuverMapper.java`, `payload/nav/rgi/ManeuverMapper.class` | LuKa ([@LuKa_dev](https://github.com/luka-dev)) — [mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi) | Upstream has no license file; copyright notice kept in the source. |
| iAP2 route guidance TLV layout | `src/native/rgi_start.c` | checked against [edzenis/mib2high-carplay-rgi](https://github.com/edzenis/mib2high-carplay-rgi) and LuKa | reference only, no code copied |

The CarPlay map video on the AID itself is omonob's **CarPlay Maps VC** and is not part of this package;
install it from the upstream releases first.

`CarPlayAID.jar` contains a patched copy of the HMI class
`de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo` (compiled from the unit's own `lsd.jxe`
with two added calls, see `src/carplayaid/README.md`). Its source is not published here because it is
Volkswagen / e.solutions code.

Volkswagen, Apple CarPlay, Apple Maps, Google Maps and other names are trademarks of their owners.
This project is not affiliated with or endorsed by any of them.
