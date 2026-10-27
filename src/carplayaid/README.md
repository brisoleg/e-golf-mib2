# CarPlayAID.jar

Prepended to the HMI boot class path by the guarded block that `install.sh` adds to `lsd.sh`.

| Class | Source |
| --- | --- |
| `ua.mib.carplayaid.NowPlaying` | `ua/mib/carplayaid/NowPlaying.java` |
| `ua.mib.carplayaid.CarPlayCover` | `ua/mib/carplayaid/CarPlayCover.java` |
| `de.audi.tghu.dsitrace.extension.IUserFunction` | `de/audi/tghu/dsitrace/extension/IUserFunction.java` — same signature as the DSITracer interface that `ua.mib.rgi.RgiModule` implements, so the module also loads when DSITracer is off (stock) |
| `de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo` | the HMI's own class (decompiled from `lsd.jxe`, H29.319.75), with the two calls below; not published |
| `de.vw.mib.asl.internal.kombipictureserver.usecaces.CoverArt` | the HMI's own class, with the three calls below; not published |

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

Changes to `CoverArt` (the cockpit picture server's cover use case):

```java
// getKombiPictureServer(), right after the DSI proxy is created
ua.mib.carplayaid.CarPlayCover.setServer(this._kombiPictureServer);

// start of dsiKombiPictureServerIndicationCoverArt(long handle, int n, int source): the cockpit asks for a cover
if (ua.mib.carplayaid.CarPlayCover.answer(this.getKombiPictureServer(), handle, n, source)) return;

// start of updateBapAudioSd(BapAudioSdService, int n): the current-station handle changed
if (n == 22 && this.getBapActiveSourceType() == 19 && ua.mib.carplayaid.CarPlayCover.answer(
        this.getKombiPictureServer(), <current station handle>, 0, 19)) return;
```

`answer()` only takes over the CarPlay source (19) and only for the handle `CarPlayCover` published;
everything else stays with the factory code, which for CarPlay would otherwise answer "no picture".

`CarPlayCover` runs `/mnt/app/carplayaid/cpcover` (see `../cpcover/`) when PPS announces new artwork
(`MediaItemArtworkFileTransferIdentifier`), then, on the HMI's ASL thread, sets a fresh BAP AudioSD
current-station handle (1–999, so the cockpit never shows a cached picture) while the factory handle is
`-1`, and sends the PNG with `DSIKombiPictureServer.responseCoverArt(handle, 0, 19, 1, url)`. A handle set
by the factory code for any other source is left alone. On some boots the factory picture server does not
start its cover use case (the cockpit's cover capability is not known yet when it starts); `CarPlayCover`
then runs that use case's own start sequence.
