# CarPlayAID.jar

Prepended to the HMI boot class path by the guarded block that `install.sh` adds to `lsd.sh`.

| Class | Source |
| --- | --- |
| `ua.mib.carplayaid.NowPlaying` | `ua/mib/carplayaid/NowPlaying.java` |
| `de.audi.tghu.dsitrace.extension.IUserFunction` | `de/audi/tghu/dsitrace/extension/IUserFunction.java` — same signature as the DSITracer interface that `ua.mib.rgi.RgiModule` implements, so the module also loads when DSITracer is off (stock) |
| `de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo` | the HMI's own class (decompiled from `lsd.jxe`, H29.319.75), with the two calls below; not published |

Changes to `CurrentStationInfo`:

```java
// end of init(BAPStageInitializer), before computeCurrentStationInfoStatus(...)
ua.mib.carplayaid.NowPlaying.start(this);

// end of setStationInfoForMirrorLink(CurrentStationInfo_Status), i.e. CarPlay is the audio source
if (this.getSmartphoneIntegrationService().getSmarphoneIntegrationActiveConnectionType() == 2) {
    ua.mib.carplayaid.NowPlaying.apply(currentStationInfo_Status);
}
```

`NowPlaying` reads `/ramdisk/pps/iap2/nowplaying` (title, artist, album, playback state) and fills the
AID audio tab lines with them instead of "Apple CarPlay". About 20 s after HMI start it calls
`ua.mib.rgi.RgiModule.autoStart()` by reflection, so route guidance runs after every boot and the jar
still works when the navigation part is not installed.
