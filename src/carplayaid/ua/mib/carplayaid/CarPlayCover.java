package ua.mib.carplayaid;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;

import de.vw.mib.asl.api.bap.BAPInterfaceProvider;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.kombipictureserver.common.controller.Controller;
import de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationHandle;
import de.vw.mib.bap.mqbab2.common.api.APIFactory;
import de.vw.mib.bap.mqbab2.common.api.adapter.AbstractBAPDataPool;
import de.vw.mib.bap.mqbab2.generated.audiosd.serializer.CurrentStation_Handle_Status;
import org.dsi.ifc.global.ResourceLocator;
import org.dsi.ifc.kombipictureserver.DSIKombiPictureServer;

/**
 * CarPlay album art on the AID audio tab.
 *
 * The factory HMI sends no cover for the CarPlay source (BAP current-station handle -1). For every
 * new track artwork (PPS MediaItemArtworkFileTransferIdentifier) this class
 * 1. runs cpcover, which reads the picture the iAP2 driver already received and writes a
 *    256x256 RGB PNG into the OEM cover cache /var/app/icab/tmp/ (the format the AID shows);
 * 2. publishes a fresh BAP current-station handle for the CarPlay source;
 * 3. answers the cluster's cover request for that handle with the PNG (patched CoverArt) and
 *    also pushes it once, as the cluster does not always ask.
 * A handle set by the factory code (any source other than CarPlay) is never touched.
 *
 * The factory picture server starts its CoverArt use case only if the cluster's cover capability
 * is already known when it enters its running state; after some boots it is not, and no cover
 * (CarPlay or any other source) reaches the AID. publish() then starts that use case itself.
 */
public final class CarPlayCover {

    static final int SOURCE_CARPLAY = 19;
    private static final int COMPONENT_CARPLAY = 6;
    private static final int LCU_AUDIO_SD = 49;
    private static final int STAGE_CURRENT_STATION_HANDLE = 22;
    private static final String PICTURE_SERVER = "de.vw.mib.asl.internal.kombipictureserver.controller.PictureServerController";
    private static final int SEQUENCE_COVER_ART = 1100289536;
    private static final String TOOL = "/mnt/app/carplayaid/cpcover";
    private static final String DIR = "/var/app/icab/tmp/";
    private static final String PREFIX = "egolf_cp_";
    private static final String[] ENV = {
        "LD_LIBRARY_PATH=/mnt/app/armle/lib/dll:/lib:/mnt/app/armle/lib:/mnt/app/root/lib-target",
        "LIBIMG_CFGFILE=/mnt/app/eso/hmi/lsd/img.conf"
    };
    private static final int FETCH_TRIES = 8;
    private static final long FETCH_RETRY_MS = 1000;

    private static DSIKombiPictureServer server;
    private static int handle = -1;        // published handle, -1 = none
    private static String picture;         // PNG for that handle
    private static int lastHandle = 0;     // handles rotate 1..999 so the cluster never reuses a cached picture
    private static String wantedKey = "";  // "<transfer id>/<size>" of the newest artwork in PPS
    private static String doneKey = "";    // artwork the current picture was made from
    private static int sentHandle = -1;    // last handle we put into BAP (ASL thread only)
    private static String state = "idle";  // last step, for diagnostics
    private static Thread worker;

    private CarPlayCover() { }

    /** CoverArt.getKombiPictureServer(): keep the OEM picture-server proxy. */
    public static void setServer(DSIKombiPictureServer s) {
        server = s;
    }

    /**
     * Cluster cover request (or the OEM reacting to a handle change), ASL thread.
     * @return true if it was our CarPlay handle and the PNG was sent
     */
    public static boolean answer(DSIKombiPictureServer s, long h, int n, int source) {
        String p;
        synchronized (CarPlayCover.class) {
            if (source != SOURCE_CARPLAY || n != 0 || picture == null || h != handle) return false;
            p = picture;
        }
        try {
            s.responseCoverArt(h, 0, SOURCE_CARPLAY, 1, new ResourceLocator(p));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** NowPlaying: artwork id/size from PPS (empty when the track has no artwork). */
    public static void artworkChanged(String transferId, String size) {
        String key = transferId.length() == 0 ? "" : transferId + "/" + size;
        synchronized (CarPlayCover.class) {
            if (key.equals(wantedKey)) return;
            wantedKey = key;
            if (worker == null) {
                cleanup(null);
                worker = new Thread("CarPlayCover") {
                    public void run() { work(); }
                };
                worker.setDaemon(true);
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.start();
            }
            CarPlayCover.class.notifyAll();
        }
    }

    /** NowPlaying refresh tick (every few seconds): put our handle back if the HMI reset it. */
    public static void tick() {
        synchronized (CarPlayCover.class) {
            if (picture == null) return;
        }
        onAsl(new Runnable() {
            public void run() { publish(false); }
        });
    }

    private static void work() {
        while (true) {
            String key;
            try {
                synchronized (CarPlayCover.class) {
                    while (wantedKey.equals(doneKey)) CarPlayCover.class.wait();
                    key = wantedKey;
                }
                if (key.length() == 0) {
                    synchronized (CarPlayCover.class) { doneKey = key; picture = null; }
                    continue;
                }
                fetch(key);
            } catch (Throwable e) {
                try { Thread.sleep(FETCH_RETRY_MS); } catch (Throwable t) { }
            }
        }
    }

    /** Runs cpcover until the driver holds the artwork PPS announced, then publishes it. */
    private static void fetch(String key) throws Exception {
        String size = key.substring(key.indexOf('/') + 1);
        int h;
        synchronized (CarPlayCover.class) {
            h = lastHandle % 999 + 1;
            lastHandle = h;
        }
        String png = DIR + PREFIX + h + ".png";
        for (int i = 0; i < FETCH_TRIES; i++) {
            synchronized (CarPlayCover.class) {
                if (!key.equals(wantedKey)) return; // a newer track arrived meanwhile
            }
            if (run(new String[] { TOOL, "-e", size, png })) {
                synchronized (CarPlayCover.class) {
                    if (!key.equals(wantedKey)) { new File(png).delete(); return; }
                    picture = png;
                    handle = h;
                    doneKey = key;
                }
                onAsl(new Runnable() {
                    public void run() { publish(true); }
                });
                cleanup(png);
                return;
            }
            Thread.sleep(FETCH_RETRY_MS);
        }
        synchronized (CarPlayCover.class) {
            if (key.equals(wantedKey)) doneKey = key; // give up on this picture
        }
    }

    private static boolean run(String[] cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd, ENV);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            return p.waitFor() == 0 && line != null && line.startsWith("OK ");
        } catch (Throwable e) {
            return false;
        } finally {
            if (p != null) {
                try { p.getInputStream().close(); } catch (Throwable e) { }
                try { p.getOutputStream().close(); } catch (Throwable e) { }
                try { p.getErrorStream().close(); } catch (Throwable e) { }
            }
        }
    }

    /** Deletes our PNGs except keep (null = all). */
    private static void cleanup(String keep) {
        try {
            String[] names = new File(DIR).list();
            for (int i = 0; names != null && i < names.length; i++) {
                String path = DIR + names[i];
                if (names[i].startsWith(PREFIX) && !path.equals(keep)) new File(path).delete();
            }
        } catch (Throwable e) {
            // cache directory not there
        }
    }

    /**
     * ASL thread: while CarPlay plays, make the BAP current-station handle ours and send the PNG.
     * @param push send the picture even if the handle was already ours (new artwork)
     */
    private static void publish(boolean push) {
        try {
            int h;
            String p;
            synchronized (CarPlayCover.class) {
                h = handle;
                p = picture;
            }
            if (p == null) return;
            if (APIFactory.getAPIFactory().getSoundService().getCurrentAudioComponent() != COMPONENT_CARPLAY) {
                state = "not CarPlay";
                return;
            }
            if (server == null) startCoverArt();
            CurrentStationHandle stage = handleStage();
            CurrentStation_Handle_Status last = (CurrentStation_Handle_Status) read(stage, "lastCurrentStationHandleStatus");
            boolean ours = last.fsgHandle == h;
            if (!ours && last.fsgHandle != -1 && last.fsgHandle != sentHandle) { // factory handle
                state = "handle " + last.fsgHandle + " owned by HMI";
                return;
            }
            if (!ours) {
                CurrentStation_Handle_Status status = new CurrentStation_Handle_Status();
                status.fsgHandle = h;
                status.fsgHandle_absolutePosition = 0;
                stage.sendCurrentStationHandleStatus(status);
                sentHandle = h;
            }
            if ((push || !ours) && server != null)
                server.responseCoverArt(h, 0, SOURCE_CARPLAY, 1, new ResourceLocator(p));
            state = "handle " + h + (server != null ? " sent " : " no picture server ") + p;
        } catch (Throwable e) {
            // HMI not ready or layout differs: no cover, nothing else affected
            state = "publish failed: " + e;
        }
    }

    /** Runs the factory CoverArt sequence (what the picture server does when it starts). */
    private static void startCoverArt() {
        java.util.HashMap targets = ServiceManager.eventMain.getEventDispatcher().getTargets();
        java.util.Iterator it = targets.keySet().iterator();
        while (it.hasNext()) {
            Object t = ServiceManager.eventMain.getEventDispatcher().getTarget(((Integer) it.next()).intValue());
            if (t != null && t.getClass().getName().equals(PICTURE_SERVER)) {
                ((Controller) t).getSequence(SEQUENCE_COVER_ART).executeSequence();
                return;
            }
        }
    }

    private static CurrentStationHandle handleStage() throws Exception {
        Object lcu = new BAPInterfaceProvider(ServiceManager.bundleContext).getBapDispatcher()
                .getLogicalControlUnit(LCU_AUDIO_SD);
        AbstractBAPDataPool pool = (AbstractBAPDataPool) read(lcu, "_dataPool");
        return (CurrentStationHandle) pool.getStage(STAGE_CURRENT_STATION_HANDLE);
    }

    private static Object read(Object owner, String name) throws Exception {
        Class c = owner.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(owner);
                if (v == null) throw new IllegalStateException(name);
                return v;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void onAsl(Runnable r) {
        try {
            ServiceManager.aslThreadSwitch.enqueue(r);
        } catch (Throwable e) {
            // HMI not up yet
        }
    }
}
