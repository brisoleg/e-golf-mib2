package ua.mib.rgi;

/**
 * Parses an iAP2 route-guidance control frame (0x5201 RouteGuidanceUpdate / 0x5202
 * RouteGuidanceManeuverUpdate / 0x5203 StopRouteGuidanceUpdates) into AID fields. Frame layout, from
 * the start-of-control marker: [0x40 0x40][u16 frameLen BE][u16 msgid BE][params...],
 * each param [u16 len BE (incl. 4-byte header)][u16 id BE][value]. Field IDs are from
 * carplay_rgi/upstream/luka/docs/rgd/rgd-tlv.md (verified against accessoryd 23G71).
 *
 * Numbers are big-endian. Validation is strict: a malformed frame yields valid=false and
 * nothing is applied, matching luka's rgd_message_valid.
 */
public final class RgiParser {

    public static final int MSG_UPDATE = 0x5201;
    public static final int MSG_MANEUVER = 0x5202;
    public static final int MSG_STOP = 0x5203;
    private static final int MAX_ORDER = 16;

    public boolean valid;
    public int msgid;

    /* 0x5201 */
    public boolean hasRouteState;
    public int routeState;
    public boolean hasManeuverState;
    public int maneuverState;
    public String currentRoad;
    public String destination;
    public boolean hasDistDest;
    public int distDestM;
    public boolean hasDistManeuver;
    public int distManeuverM;
    public boolean hasTimeRemaining;
    public int timeRemainingS;
    public boolean hasManeuverOrder;
    public int[] maneuverOrder = new int[MAX_ORDER];
    public int maneuverOrderCount;
    public boolean hasManeuverCount;
    public int maneuverCount;
    public boolean hasVisibleInApp;
    public int visibleInApp;
    public boolean hasSourceSupportsRg;
    public int sourceSupportsRg;

    /* 0x5202 */
    public boolean hasIndex;
    public int index;
    public String afterRoad;
    public boolean hasType;
    public int maneuverType;
    public boolean hasDrivingSide;
    public int drivingSide;
    public boolean hasJunctionType;
    public int junctionType;
    public boolean hasExitAngle;
    public int exitAngle;

    public void reset() {
        valid = false; msgid = 0;
        hasRouteState = hasManeuverState = hasDistDest = hasDistManeuver = hasTimeRemaining = false;
        hasManeuverOrder = hasManeuverCount = hasVisibleInApp = hasSourceSupportsRg = false;
        maneuverOrderCount = 0;
        hasIndex = hasType = hasDrivingSide = hasJunctionType = hasExitAngle = false;
        currentRoad = destination = afterRoad = null;
        routeState = maneuverState = distDestM = distManeuverM = timeRemainingS = maneuverCount = 0;
        visibleInApp = sourceSupportsRg = index = maneuverType = drivingSide = 0;
        junctionType = exitAngle = 0;
    }

    /** Parse one control frame starting at the 0x40 0x40 marker. */
    public boolean parse(byte[] f, int off, int len) {
        reset();
        if (f == null || off < 0 || off > f.length || len < 6 || len > f.length - off) return false;
        if ((f[off] & 0xff) != 0x40 || (f[off + 1] & 0xff) != 0x40) return false;
        int frameLen = u16(f, off + 2);
        if (frameLen != len) return false;  // no silent trailing bytes or truncated frames
        msgid = u16(f, off + 4);
        int p = off + 6;
        int end = off + frameLen;
        while (p + 4 <= end) {
            int plen = u16(f, p);
            int pid = u16(f, p + 2);
            if (plen < 4 || p + plen > end) return false;
            if (!fieldLengthValid(msgid, pid, plen - 4)) return false;
            int vOff = p + 4;
            int vLen = plen - 4;
            if (msgid == MSG_UPDATE) field5201(pid, f, vOff, vLen);
            else if (msgid == MSG_MANEUVER) field5202(pid, f, vOff, vLen);
            p += plen;
        }
        if (p != end) return false;
        valid = (msgid == MSG_UPDATE || msgid == MSG_MANEUVER || msgid == MSG_STOP);
        return valid;
    }

    private void field5201(int id, byte[] f, int o, int n) {
        switch (id) {
            case 0x01: routeState = num(f, o, n); hasRouteState = true; break;
            case 0x02: maneuverState = num(f, o, n); hasManeuverState = true; break;
            case 0x03: currentRoad = str(f, o, n); break;
            case 0x04: destination = str(f, o, n); break;
            case 0x06: timeRemainingS = num(f, o, n); hasTimeRemaining = true; break;
            case 0x07: distDestM = num(f, o, n); hasDistDest = true; break;
            case 0x0A: distManeuverM = num(f, o, n); hasDistManeuver = true; break;
            case 0x0D:
                hasManeuverOrder = true;
                maneuverOrderCount = Math.min(n / 2, MAX_ORDER);
                for (int i = 0; i < maneuverOrderCount; i++) maneuverOrder[i] = u16(f, o + i * 2);
                break;
            case 0x0E: maneuverCount = num(f, o, n); hasManeuverCount = true; break;
            case 0x0F: visibleInApp = num(f, o, n); hasVisibleInApp = true; break;
            case 0x14: sourceSupportsRg = num(f, o, n); hasSourceSupportsRg = true; break;
            default: break;
        }
    }

    private void field5202(int id, byte[] f, int o, int n) {
        switch (id) {
            case 0x01: index = num(f, o, n); hasIndex = true; break;
            case 0x03: maneuverType = num(f, o, n); hasType = true; break;
            case 0x04: afterRoad = str(f, o, n); break;
            case 0x08: drivingSide = num(f, o, n); hasDrivingSide = true; break;
            case 0x09: junctionType = num(f, o, n); hasJunctionType = true; break;
            case 0x0B: exitAngle = sig16(f, o, n); hasExitAngle = true; break;
            default: break;
        }
    }


    /** A per-event snapshot. Never enqueue an object that a polling thread will parse into again. */
    public RgiParser copy() {
        RgiParser c = new RgiParser();
        c.valid = valid; c.msgid = msgid;
        c.hasRouteState = hasRouteState; c.routeState = routeState;
        c.hasManeuverState = hasManeuverState; c.maneuverState = maneuverState;
        c.currentRoad = currentRoad; c.destination = destination;
        c.hasDistDest = hasDistDest; c.distDestM = distDestM;
        c.hasDistManeuver = hasDistManeuver; c.distManeuverM = distManeuverM;
        c.hasTimeRemaining = hasTimeRemaining; c.timeRemainingS = timeRemainingS;
        c.hasManeuverOrder = hasManeuverOrder; c.maneuverOrderCount = maneuverOrderCount;
        System.arraycopy(maneuverOrder, 0, c.maneuverOrder, 0, MAX_ORDER);
        c.hasManeuverCount = hasManeuverCount; c.maneuverCount = maneuverCount;
        c.hasVisibleInApp = hasVisibleInApp; c.visibleInApp = visibleInApp;
        c.hasSourceSupportsRg = hasSourceSupportsRg; c.sourceSupportsRg = sourceSupportsRg;
        c.hasIndex = hasIndex; c.index = index; c.afterRoad = afterRoad;
        c.hasType = hasType; c.maneuverType = maneuverType;
        c.hasDrivingSide = hasDrivingSide; c.drivingSide = drivingSide;
        c.hasJunctionType = hasJunctionType; c.junctionType = junctionType;
        c.hasExitAngle = hasExitAngle; c.exitAngle = exitAngle;
        return c;
    }

    /**
     * Reject malformed *known* scalar TLVs rather than interpreting an absent value as zero.
     * Unknown TLVs remain forward compatible with newer iOS versions.
     */
    private static boolean fieldLengthValid(int msg, int id, int n) {
        if (msg == MSG_UPDATE) {
            switch (id) {
                case 0x01: case 0x02: case 0x0F: case 0x14: return n == 1;
                case 0x05: case 0x06: return n == 8;   // ETA / time remaining, seconds
                case 0x07: case 0x0A: return n == 4;
                case 0x0D: return (n & 1) == 0;
                case 0x0E: return n == 2;
                default: return true;
            }
        }
        if (msg == MSG_MANEUVER) {
            switch (id) {
                case 0x01: return n == 2;
                case 0x03: case 0x08: case 0x09: return n == 1;
                case 0x0B: return n == 2;
                default: return true;
            }
        }
        return true;
    }

    private static int u16(byte[] f, int o) {
        return ((f[o] & 0xff) << 8) | (f[o + 1] & 0xff);
    }

    /** Big-endian unsigned integer of 1..4 bytes; longer values keep the low 32 bits. */
    private static int num(byte[] f, int o, int n) {
        int v = 0;
        for (int i = 0; i < n; ++i) v = (v << 8) | (f[o + i] & 0xff);
        return v;
    }

    private static int sig16(byte[] f, int o, int n) {
        if (n < 2) return num(f, o, n);
        int v = u16(f, o);
        return (v >= 0x8000) ? v - 0x10000 : v;
    }

    private static String str(byte[] f, int o, int n) {
        int len = n;
        while (len > 0 && f[o + len - 1] == 0) len--;   // trim trailing NUL
        try { return new String(f, o, len, "UTF-8"); }
        catch (Exception e) { return new String(f, o, len); }
    }
}
