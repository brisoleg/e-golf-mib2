package ua.mib.rgi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Ordered, bounded, file-backed iAP2 RGI transport (QNX/J9 Java 1.4).
 *
 * Producer contract (native mm-ipod): create /ramdisk/rgi-events, replace the
 * ASCII "session" file atomically on each connection, then write complete
 * validated 0x5201/0x5202/0x5203 frames as 10-digit decimal sequence names
 * "0000000001.bin", "0000000002.bin", etc., using temp+rename. Never overwrite
 * a published .bin. The producer must cap pending files to MAX_BACKLOG.
 *
 * Only .bin files are consumed; temp files are ignored. Old-session frames are
 * discarded by the producer BEFORE writing the new session marker. Until a
 * native producer fulfills this contract the current on-car snapshot poller is
 * still a separate legacy compatibility path.
 */
public final class RgiQueue {
    public interface Listener {
        void onFrame(RgiParser frame);
        void onReset(String reason);
    }
    /** Optional: also receives every raw frame (before parsing) for logging. */
    public interface RawListener extends Listener {
        void onRaw(byte[] frame);
    }

    public static final int MAX_BACKLOG = 128;
    private static final int MAX_BATCH = 64;
    private static final int MAX_FRAME = 65535;

    private final File folder;
    private String session;
    private long lastSeq = -1;
    private long received;

    public RgiQueue(File folder) { this.folder = folder; }
    public long received() { return received; }
    public String session() { return session == null ? "" : session; }

    public boolean available() {
        return new File(folder, "session").isFile();
    }

    /** Returns number of complete frames consumed this polling pass. */
    public int drain(Listener sink) {
        if (!available()) return 0;
        String newSession = readSession(new File(folder, "session"));
        if (newSession == null) {
            sink.onReset("invalid queue session");
            return 0;
        }
        if (!newSession.equals(session)) {
            session = newSession;
            lastSeq = -1;
            sink.onReset("session changed");
        }

        String[] names = folder.list();
        if (names == null) return 0;
        Arrays.sort(names);
        int pending = 0;
        for (int i = 0; i < names.length; i++)
            if (isEventName(names[i])) pending++;
        if (pending > MAX_BACKLOG) {
            sink.onReset("RGI queue backlog exceeded");
            return 0;
        }
        int processed = 0;
        for (int i = 0; i < names.length && processed < MAX_BATCH; i++) {
            // The native producer can reset and reuse sequence names when the
            // iPhone reconnects while Java is inside this drain() call.
            // Never consume OR delete files from a changed generation.
            if (!newSession.equals(readSession(new File(folder, "session")))) {
                sink.onReset("session changed during drain");
                session = null; // force a clean generation on the next poll
                lastSeq = -1;
                return processed;
            }
            String name = names[i];
            if (!isEventName(name)) continue;
            long seq;
            try { seq = Long.parseLong(name.substring(0, 10)); }
            catch (NumberFormatException nfe) { continue; }
            File event = new File(folder, name);
            if (seq <= lastSeq) {
                // Already consumed or left by a previous poll. Only remove if
                // it still belongs to this same session generation.
                if (newSession.equals(readSession(new File(folder, "session"))))
                    event.delete();
                continue;
            }
            if (lastSeq >= 0 && seq != lastSeq + 1)
                sink.onReset("sequence gap (" + lastSeq + " -> " + seq + ")");
            lastSeq = seq;
            byte[] data = readBounded(event, MAX_FRAME);
            if (data != null && sink instanceof RawListener) ((RawListener) sink).onRaw(data);
            RgiParser parsed = new RgiParser();
            if (data == null || !parsed.parse(data, 0, data.length)) {
                sink.onReset("invalid RGI queue frame " + name);
            } else {
                sink.onFrame(parsed);
                received++;
                processed++;
            }
            // A session may change inside Listener.onFrame (or another
            // mm-ipod process). Never delete the new session's same-name file.
            if (!newSession.equals(readSession(new File(folder, "session")))) {
                sink.onReset("session changed while processing frame");
                session = null;
                lastSeq = -1;
                return processed;
            }
            // Producer's atomic rename means no partial ready file is visible.
            event.delete();
        }
        return processed;
    }

    private static boolean isEventName(String name) {
        if (name == null || name.length() != 14 || !name.endsWith(".bin"))
            return false;
        for (int i = 0; i < 10; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String readSession(File f) {
        byte[] b = readBounded(f, 64);
        if (b == null || b.length < 1) return null;
        int end = b.length;
        while (end > 0 && (b[end - 1] == '\n' || b[end - 1] == '\r')) end--;
        if (end < 1 || end > 48) return null;
        for (int i = 0; i < end; i++) {
            int c = b[i] & 0xff;
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
                  (c >= 'A' && c <= 'Z') || c == '_' || c == '-')) return null;
        }
        try { return new String(b, 0, end, "US-ASCII"); }
        catch (Exception e) { return null; }
    }

    private static byte[] readBounded(File file, int max) {
        try {
            long len = file.length();
            if (len <= 0 || len > max) return null;
            byte[] b = new byte[(int) len];
            FileInputStream in = new FileInputStream(file);
            try {
                int at = 0;
                while (at < b.length) {
                    int count = in.read(b, at, b.length - at);
                    if (count < 0) return null;
                    if (count == 0) return null;
                    at += count;
                }
                // Strictly guard against file growth while being read.
                if (in.read() != -1) return null;
                return b;
            } finally { in.close(); }
        } catch (IOException ioe) { return null; }
    }
}
