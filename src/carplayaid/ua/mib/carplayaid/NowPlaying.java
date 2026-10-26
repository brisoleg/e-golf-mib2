package ua.mib.carplayaid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo;
import de.vw.mib.bap.mqbab2.generated.audiosd.serializer.CurrentStationInfo_Status;

/**
 * CarPlay now playing on the AID audio tab (BAP AudioSD CurrentStationInfo): title / artist / album
 * from the iAP2 driver's PPS object instead of the plain "Apple CarPlay" the factory HMI sends for
 * the CarPlay source.
 *
 * Also starts the CarPlay route guidance poller (ua.mib.rgi.RgiModule) once the HMI is up, so
 * turn-by-turn hints reach the AID after every boot.
 *
 * Split out of BatteryVC.jar (BatteryOverlay) without the e-Golf battery line.
 */
public class NowPlaying {

    private static final String NOW_PLAYING_FILE = "/ramdisk/pps/iap2/nowplaying";
    private static final long REFRESH_MS = 3000;
    private static final long RGI_AUTOSTART_DELAY_MS = 20000;

    private static long nowPlayingModified = -1;
    private static String npTitle = "";
    private static String npArtist = "";
    private static String npAlbum = "";
    private static String npStatus = "";
    private static boolean started = false;

    /** Called once from CurrentStationInfo.init(). */
    public static synchronized void start(final CurrentStationInfo csi) {
        if (started) return;
        started = true;
        Thread t = new Thread("CarPlayAidRefresh") {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(REFRESH_MS);
                        if (reload()) csi.process(-1);
                    } catch (Throwable e) {
                        // never let anything escape into the HMI
                    }
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        Thread rgi = new Thread("RgiAutoStart") {
            public void run() {
                // CarPlay route guidance: start its queue poller once the HMI is up.
                // Reflection keeps now playing working when the RGI classes are absent.
                try {
                    Thread.sleep(RGI_AUTOSTART_DELAY_MS);
                    Class.forName("ua.mib.rgi.RgiModule").getMethod("autoStart", new Class[0])
                            .invoke(null, new Object[0]);
                } catch (Throwable e) {
                    // RGI not installed or failed: ignore
                }
            }
        };
        rgi.setDaemon(true);
        rgi.setPriority(Thread.MIN_PRIORITY);
        rgi.start();
        Thread w = new Thread("CarPlayNowPlaying") {
            public void run() {
                watchNowPlaying(csi);
            }
        };
        w.setDaemon(true);
        w.setPriority(Thread.MIN_PRIORITY);
        w.start();
    }

    /**
     * Blocks on the PPS object (?wait,delta): the first read returns the whole object, later reads
     * return only changed attributes. Track changes reach the cluster at once instead of on the
     * next 3 s poll; elapsed-time-only deltas are ignored.
     */
    private static void watchNowPlaying(CurrentStationInfo csi) {
        byte[] buf = new byte[16384];
        while (true) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(NOW_PLAYING_FILE + "?wait,delta");
                while (true) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    String delta = new String(buf, 0, n, "UTF8");
                    if (delta.indexOf("MediaItemTitle:") >= 0 || delta.indexOf("MediaItemArtist:") >= 0
                            || delta.indexOf("MediaItemAlbumTitle:") >= 0 || delta.indexOf("PlaybackStatus:") >= 0
                            || delta.indexOf("-MediaItem") >= 0) {
                        synchronized (NowPlaying.class) {
                            nowPlayingModified = -1;
                        }
                        if (reload()) csi.process(-1);
                    }
                }
            } catch (Throwable e) {
                // object not there yet (no iPhone) or driver restarted
            } finally {
                try { if (in != null) in.close(); } catch (Throwable e) { }
            }
            try { Thread.sleep(2000); } catch (Throwable e) { }
        }
    }

    /** Called from CurrentStationInfo.setStationInfoForMirrorLink() while CarPlay is the audio source. */
    public static void apply(CurrentStationInfo_Status status) {
        try {
            reload();
            String title, artist, album;
            synchronized (NowPlaying.class) {
                title = npTitle; artist = npArtist; album = npAlbum;
                if (title.length() == 0 || "Stopped".equals(npStatus)) return;
            }
            status.primaryInformation.setContent(title);
            status.pi_Type = 72;
            status.pi_Id = 0;
            status.secondaryInformation.setContent(artist);
            status.si_Type = artist.length() != 0 ? 73 : 0;
            status.tertiaryInformation.setContent(album);
            status.ti_Type = album.length() != 0 ? 74 : 0;
            status.quaternaryInformation.setContent("");
            status.qi_Type = 0;
        } catch (Throwable e) {
            // keep "Apple CarPlay"
        }
    }

    /** @return true if title, artist, album or playback status changed (elapsed time is ignored) */
    private static synchronized boolean reload() {
        File f = new File(NOW_PLAYING_FILE);
        long m = f.exists() ? f.lastModified() : 0;
        if (m == nowPlayingModified) return false;
        nowPlayingModified = m;
        String title = "", artist = "", album = "", status = "";
        if (m != 0) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF8"));
                String s;
                while ((s = r.readLine()) != null) {
                    // PPS attribute line: name:encoding:value (plain text has an empty encoding)
                    int c1 = s.indexOf(':');
                    if (c1 <= 0 || c1 + 1 >= s.length() || s.charAt(c1 + 1) != ':') continue;
                    String name = s.substring(0, c1);
                    String value = s.substring(c1 + 2).trim();
                    if (name.equals("MediaItemTitle")) title = value;
                    else if (name.equals("MediaItemArtist")) artist = value;
                    else if (name.equals("MediaItemAlbumTitle")) album = value;
                    else if (name.equals("PlaybackStatus")) status = value;
                }
            } catch (Throwable e) {
                // keep what was parsed
            } finally {
                try { if (r != null) r.close(); } catch (Throwable e) { }
            }
        }
        boolean changed = !title.equals(npTitle) || !artist.equals(npArtist) || !album.equals(npAlbum)
                || !status.equals(npStatus);
        npTitle = title; npArtist = artist; npAlbum = album; npStatus = status;
        return changed;
    }
}
