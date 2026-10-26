package ua.mib.rgi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Date;
import java.util.StringTokenizer;

import de.audi.tghu.dsitrace.extension.IUserFunction;

/**
 * CarPlay route guidance → AID (M2 Java side). DSITracer user function, invoked like cover_test /
 * RgiTestV1. It validates the parse → map → datapool chain, and (once the native mm-ipod hook lands)
 * drives the AID from real RGI frames the hook drops in /dev/shmem.
 *
 * Commands:
 *   feed &lt;hex&gt;      parse one iAP2 control frame (0x5201/0x5202, hex from the 40 40 marker) and apply
 *   clear           clear the CarPlay maneuver from the AID
 *   poll on|off     start/stop reading /dev/shmem/rgi-5201.bin and rgi-5202.bin (native hook output)
 *   status          report state
 *
 * Native hook contract (M1/M2): write the latest complete control frame of each kind, starting at
 * the 0x40 0x40 marker, atomically (tmp + rename) to /dev/shmem/rgi-5201.bin and rgi-5202.bin.
 * Output: /tmp/rgi-test-result.txt
 */
public final class RgiModule implements IUserFunction {

    private static final String REPORT = "/tmp/rgi-test-result.txt";
    private static final String FILE_5201 = "/dev/shmem/rgi-5201.bin";
    private static final String QUEUE_DIR = "/ramdisk/rgi-events";
    private static final String FILE_5202 = "/dev/shmem/rgi-5202.bin";
    private static final long POLL_MS = 250;
    private static final long STALE_MS = 8000;        // silence before checking the iPhone link
    // Apple Maps sends RGI only on change (none while parked), so silence alone is not a stop.
    private static final long MAX_SILENT_MS = 30L * 60 * 1000;
    private static final String IAP2_DEVICE = "/ramdisk/pps/iap2/device";
    // /mnt/ota survives reboots and is writable (/tmp and /ramdisk are not persistent).
    private static final String PERSIST_DIR = "/mnt/ota/system/logs/rgi";
    private static final long PERSIST_MAX = 1024 * 1024;   // per file, one rotated copy kept

    private static RouteGuidanceDriver driver;
    private static Poller poller;

    private static synchronized RouteGuidanceDriver driver() {
        if (driver == null) {
            driver = new RouteGuidanceDriver(new RouteGuidanceDriver.Logger() {
                public void log(String m) { append(m); }
            });
        }
        return driver;
    }

    public void execute(String arguments) throws Exception {
        String command = arguments == null ? "status" : arguments.trim();
        try {
            if (command.startsWith("feed ")) feed(command.substring(5).trim());
            else if (command.equals("clear")) { driver().clear(); write("CLEARED"); }
            else if (command.equals("poll on")) pollOn();
            else if (command.equals("poll off")) pollOff();
            else if (command.equals("status")) write(status());
            else throw new IllegalArgumentException("Use feed <hex> | clear | poll on|off | status");
        } catch (Exception e) { write("REJECTED: " + e); throw e; }
    }

    private void feed(String hex) throws Exception {
        byte[] frame = fromHex(hex);
        RgiParser p = new RgiParser();
        if (!p.parse(frame, 0, frame.length))
            throw new IllegalArgumentException("Malformed frame (msgid=" + Integer.toHexString(p.msgid) + ")");
        if (p.msgid == RgiParser.MSG_UPDATE) driver().applyUpdate(p);
        else if (p.msgid == RgiParser.MSG_MANEUVER) driver().applyManeuver(p);
        else driver().stopRoute();
        write("FED msg=" + Integer.toHexString(p.msgid) + " " + describe(p));
    }

    private static String describe(RgiParser p) {
        if (p.msgid == RgiParser.MSG_MANEUVER) {
            int[] bap = p.hasType && ManeuverMapper.isValidType(p.maneuverType)
                    ? ManeuverMapper.map(p.maneuverType, p.hasExitAngle ? p.exitAngle : 1000,
                        p.hasJunctionType ? p.junctionType : 0, p.hasDrivingSide ? p.drivingSide : 0, p.hasExitAngle)
                    : null;
            return "index=" + p.index + " type=" + p.maneuverType + " angle=" + (p.hasExitAngle ? "" + p.exitAngle : "-")
                    + " side=" + p.drivingSide + " junc=" + p.junctionType
                    + (bap != null ? " -> element=" + bap[0] + " direction=" + bap[1] : " -> (invalid type)");
        }
        return "routeState=" + p.routeState + " distManeuver=" + (p.hasDistManeuver ? "" + p.distManeuverM : "-")
                + "m srcRg=" + (p.hasSourceSupportsRg ? "" + p.sourceSupportsRg : "-")
                + " road=" + (p.currentRoad != null ? p.currentRoad : "-");
    }

    /** Called by BatteryOverlay ~20 s after HMI start, so polling survives reboots. */
    public static void autoStart() {
        append("=== autostart (HMI boot) ===");
        startPolling();
    }

    private static synchronized void startPolling() {
        if (poller == null) { poller = new Poller(); poller.start(); }
    }

    private void pollOn() throws Exception {
        startPolling();
        write("POLL on (ordered queue " + QUEUE_DIR + "; snapshot fallback " + FILE_5201 + ", " + FILE_5202 + ")");
    }

    private synchronized void pollOff() throws Exception {
        if (poller != null) { poller.stopPolling(); poller = null; }
        driver().clear();
        write("POLL off; cleared");
    }

    private static String status() {
        RouteGuidanceDriver d = driver();
        return "STATUS active=" + d.isActive() + " nativeGuiding=" + RouteGuidanceDriver.nativeGuiding()
                + " polling=" + (poller != null) + " lastApplyMs=" + d.lastApplyMs();
    }

    /** Feeds the driver from the native queue; clears once the iPhone link is gone. */
    private static final class Poller extends Thread {
        private volatile boolean running = true;
        private byte[] prev5201, prev5202;
        private final RgiQueue queue = new RgiQueue(new File(QUEUE_DIR));
        private long lastFed;
        private final RgiQueue.Listener queueListener = new RgiQueue.RawListener() {
            public void onRaw(byte[] frame) { persist("rgi-frames.log", toHex(frame)); }
            public void onReset(String reason) {
                lastFed = 0;
                driver().clear();
                append("rgi queue reset: " + reason);
            }
            public void onFrame(RgiParser event) {
                if (event.msgid == RgiParser.MSG_UPDATE) driver().applyUpdate(event);
                else if (event.msgid == RgiParser.MSG_MANEUVER) driver().applyManeuver(event);
                else if (event.msgid == RgiParser.MSG_STOP) {
                    driver().stopRoute();
                    lastFed = 0;
                }
            }
        };

        Poller() { setDaemon(true); setName("rgi-poller"); }
        void stopPolling() { running = false; interrupt(); }

        public void run() {
            RgiParser p = new RgiParser();
            while (running) {
                try {
                    boolean fed;
                    if (queue.available()) {
                        // Never mix current ordered events with legacy snapshots.
                        fed = queue.drain(queueListener) > 0;
                    } else {
                        fed = feedFile(FILE_5201, p, true);
                        fed |= feedFile(FILE_5202, p, false);
                    }
                    if (fed) lastFed = System.currentTimeMillis();
                    else if (lastFed != 0) {
                        long silent = System.currentTimeMillis() - lastFed;
                        if (silent > STALE_MS && (silent > MAX_SILENT_MS || !iphoneConnected())) {
                            append("stale: iPhone gone or silent " + silent / 1000 + " s -> clear");
                            driver().clear(); lastFed = 0;
                        }
                    }
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException ie) { break; }
                catch (Throwable t) { append("poller: " + t); try { Thread.sleep(POLL_MS); } catch (Exception e) {} }
            }
        }

        private boolean feedFile(String path, RgiParser p, boolean isUpdate) {
            File f = new File(path);
            // Compare the complete snapshot, not mtime: QNX filesystem timestamp resolution
            // can cause distinct route updates in the same second to be missed.
            byte[] b = read(f);
            if (b == null || !p.parse(b, 0, b.length)) return false;
            if (p.msgid != (isUpdate ? RgiParser.MSG_UPDATE : RgiParser.MSG_MANEUVER)) return false;
            byte[] old = isUpdate ? prev5201 : prev5202;
            if (java.util.Arrays.equals(b, old)) return false;
            if (isUpdate) prev5201 = b; else prev5202 = b;
            if (isUpdate) driver().applyUpdate(p);
            else driver().applyManeuver(p);
            return true;
        }
    }

    /** mm-ipod publishes the authenticated iPhone in its iAP2 PPS device object. */
    static boolean iphoneConnected() {
        byte[] b = read(new File(IAP2_DEVICE));
        return b != null && new String(b).indexOf("Authenticated::true") >= 0;
    }

    private static byte[] read(File f) {
        try {
            long len = f.length();
            if (len <= 0 || len > 65536) return null;
            byte[] b = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            try {
                int n = 0;
                while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
                if (n != b.length) return null;
            } finally { in.close(); }
            return b;
        } catch (Throwable t) { return null; }
    }

    private static byte[] fromHex(String s) {
        StringBuffer clean = new StringBuffer();
        StringTokenizer t = new StringTokenizer(s, " ,:\t");
        while (t.hasMoreTokens()) clean.append(t.nextToken());
        String h = clean.toString();
        if ((h.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; ++i)
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static synchronized void append(String message) {
        try {
            PrintStream out = new PrintStream(new FileOutputStream("/tmp/rgi-module.log", true));
            try { out.println(new Date() + " " + message); } finally { out.close(); }
        } catch (Throwable t) {}
        persist("rgi-module.log", message);
    }

    /** Size-capped append under PERSIST_DIR; never throws into the HMI. */
    static synchronized void persist(String name, String line) {
        try {
            File dir = new File(PERSIST_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, name);
            if (f.length() > PERSIST_MAX) {
                File old = new File(dir, name + ".1");
                old.delete();
                f.renameTo(old);
            }
            PrintStream out = new PrintStream(new FileOutputStream(f.getPath(), true));
            try { out.println(System.currentTimeMillis() + " " + line); } finally { out.close(); }
        } catch (Throwable t) {}
    }

    private static String toHex(byte[] b) {
        StringBuffer s = new StringBuffer(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            s.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 15, 16));
        }
        return s.toString();
    }

    private static void write(String message) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(REPORT));
        try { out.println(message); } finally { out.close(); }
    }
}
