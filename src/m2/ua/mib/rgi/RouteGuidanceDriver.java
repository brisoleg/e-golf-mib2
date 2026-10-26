package ua.mib.rgi;

import de.vw.mib.asl.api.navbap.ASLNavBAPFactory;
import de.vw.mib.asl.framework.internal.framework.GenericCollector;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.bap.mqbab2.common.api.APIFactory;
import generated.de.vw.mib.asl.internal.ListManager;

/**
 * Applies parsed CarPlay route guidance to the AID (datapool entries proven in M0: route state 732,
 * maneuver lists 751/838, distance 741, street 738). Every applied frame re-asserts the full current
 * state in one shot, so the cluster output never desyncs from this driver's view (self-healing, the
 * way native navigation continuously drives the same entries). No internal-mirror dedup.
 *
 * Ownership: takes over only from an idle state (native route state 0 while we are not active); once
 * active we own 732 until we clear, and clearing resets the cluster. While we own it the BAP route
 * state echoes our own write, so a factory route started mid-CarPlay is not detected.
 */
public final class RouteGuidanceDriver {

    private static final int DP_RG_STATE = 732;
    private static final int DP_STREET = 738;
    private static final int LIST_MANEUVERS = 751;
    private static final int LIST_MANEUVERS_2 = 838;

    private boolean active;
    /** A 5202 event alone must never seize VW BAP navigation ownership. */
    private boolean routeConfirmed;
    private int mainElement, direction, distManeuverM;
    private int distDestM = -1, timeRemainingS = -1;
    private String street = "";
    private boolean haveManeuver;
    private int activeIndex = -1;
    private final java.util.Hashtable maneuvers = new java.util.Hashtable();
    private long lastApplyMs;

    public interface Logger { void log(String message); }
    private final Logger logger;
    public RouteGuidanceDriver(Logger logger) { this.logger = logger; }

    public long lastApplyMs() { return lastApplyMs; }
    public boolean isActive() { return active; }

    public static boolean nativeGuiding() {
        try { return APIFactory.getAPIFactory().getNavigationService().getRouteGuidanceState() != 0; }
        catch (Throwable t) { return false; }
    }
    /** Called only on the ASL thread, never from the poller's IO thread. */
    private boolean blockedByNative() {
        // Once CarPlay owns the cluster, the BAP route state reflects our own 732 write
        // (seen on the car), so the native check only guards taking ownership.
        if (active) return false;
        if (!nativeGuiding()) return false;
        if (routeConfirmed || !maneuvers.isEmpty()) {
            resetLocalState();
            log("CarPlay guidance relinquished to active VW native navigation");
        }
        return true;
    }

    /** Clear local CarPlay state WITHOUT touching shared VW BAP properties. */
    private void resetLocalState() {
        active = false;
        routeConfirmed = false;
        haveManeuver = false;
        activeIndex = -1;
        maneuvers.clear();
        mainElement = 0;
        direction = 0;
        distManeuverM = 0;
        distDestM = -1;
        timeRemainingS = -1;
        street = "";
    }

    public void stopRoute() { clear(); }

    public void applyUpdate(final RgiParser u) {
        final RgiParser snapshot = u.copy();
        enqueue(new Runnable() { public void run() { runUpdate(snapshot); } });
    }
    public void applyManeuver(final RgiParser m) {
        final RgiParser snapshot = m.copy();
        enqueue(new Runnable() { public void run() { runManeuver(snapshot); } });
    }
    public void clear() {
        enqueue(new Runnable() { public void run() { runClear(); } });
    }
    private void enqueue(Runnable r) {
        try { ServiceManager.aslThreadSwitch.enqueue(r); }
        catch (Throwable t) { log("enqueue failed: " + t); }
    }
    private void log(String m) { if (logger != null) logger.log(m); }

    private void runUpdate(RgiParser u) {
        try {
            if (blockedByNative()) { log("update blocked: native guiding"); return; }
            boolean rgOn = (!u.hasSourceSupportsRg || u.sourceSupportsRg != 0)
                    && (!u.hasRouteState || (u.routeState != 0 && u.routeState != 2));
            if (!rgOn) { log("update rgOff -> clear"); runClear(); return; }
            // iAP2 route states 1 (route set), 5 (rerouting) and 6 (proceed to route) are
            // all live CarPlay guidance; a parked car off the route reports only 6.
            if (u.hasRouteState && (u.routeState == 1 || u.routeState == 5 || u.routeState == 6))
                routeConfirmed = true;
            if (!routeConfirmed) {
                log("update ignored: waiting for routeState 1/5/6");
                return;
            }
            if (u.hasManeuverOrder) {
                int newIndex = u.maneuverOrderCount > 0 ? u.maneuverOrder[0] : -1;
                if (newIndex != activeIndex) {
                    activeIndex = newIndex;
                    haveManeuver = false;
                    mainElement = 0; direction = 0;
                }
                // Apple Maps opens a route with an empty order list before the real one;
                // keep the cached 0x5202 maneuvers so the next order can show its arrow.
                if (newIndex >= 0) {
                    Integer key = new Integer(newIndex);
                    int[] cached = (int[]) maneuvers.get(key);
                    if (cached != null) { mainElement = cached[0]; direction = cached[1]; haveManeuver = true; }
                }
            }
            // Google Maps rarely repeats the order list (0x0D); fall back to the cached
            // maneuver for the current index instead of waiting for the next list.
            if (!haveManeuver && activeIndex >= 0) {
                int[] cached = (int[]) maneuvers.get(new Integer(activeIndex));
                if (cached != null) { mainElement = cached[0]; direction = cached[1]; haveManeuver = true; }
            }
            active = true;
            if (u.hasDistManeuver) distManeuverM = u.distManeuverM;
            if (u.hasDistDest) distDestM = u.distDestM;
            if (u.hasTimeRemaining) timeRemainingS = u.timeRemainingS;
            if (u.currentRoad != null) street = u.currentRoad;
            assertAll();
            lastApplyMs = now();
            log("update applied dist=" + distManeuverM + " street=" + street);
        } catch (Throwable t) { log("applyUpdate failed: " + t); }
    }

    private void runManeuver(RgiParser m) {
        try {
            if (blockedByNative()) { log("maneuver blocked: native guiding"); return; }
            // Cache future maneuvers too; the iOS indices are not always zero.
            if (!m.hasType || !ManeuverMapper.isValidType(m.maneuverType)) { log("maneuver invalid type=" + m.maneuverType); return; }
            int[] bap = ManeuverMapper.map(m.maneuverType, m.hasExitAngle ? m.exitAngle : 1000,
                    m.hasJunctionType ? m.junctionType : 0, m.hasDrivingSide ? m.drivingSide : 0, m.hasExitAngle);
            int index = m.hasIndex ? m.index : 0;
            maneuvers.put(new Integer(index), new int[] {bap[0], bap[1]});
            if (activeIndex < 0) activeIndex = index;
            if (index != activeIndex) { log("maneuver cached index=" + index); return; }
            if (!routeConfirmed) {
                log("maneuver cached pending confirmed CarPlay route");
                return;
            }
            mainElement = bap[0];
            direction = bap[1];
            haveManeuver = true;
            active = true;
            assertAll();
            lastApplyMs = now();
            log("maneuver applied element=" + mainElement + " direction=" + direction);
        } catch (Throwable t) { log("applyManeuver failed: " + t); }
    }

    private void runClear() {
        try {
            boolean wasActive = active;
            resetLocalState();
            // Only clear what we own; the route state is ours while active.
            if (!wasActive) return;
            writeManeuver(0, 0);
            writeDistance(0);
            writeDestination(-1, -1);
            writeStreet("");
            ServiceManager.aslPropertyManager.valueChangedInteger(DP_RG_STATE, 0);
            log("cleared");
        } catch (Throwable t) { log("clear failed: " + t); }
    }

    /** write the whole current state at once, in the M0-proven order: state, maneuver, distance, street */
    private void assertAll() {
        ServiceManager.aslPropertyManager.valueChangedInteger(DP_RG_STATE, active ? 1 : 0);
        writeManeuver(haveManeuver ? mainElement : 0, haveManeuver ? direction : 0);
        writeDistance(distManeuverM);
        writeStreet(street);
        writeDestination(distDestM, timeRemainingS);
    }

    private void writeManeuver(int element, int dir) {
        GenericCollector row = new GenericCollector();
        row.setIntItem(0, element);
        row.setIntItem(1, dir);
        row.setIntItem(2, 0);
        row.setStringItem(3, "");
        GenericCollector[] rows = new GenericCollector[] { row };
        ListManager.getGenericASLList(LIST_MANEUVERS).updateList(rows);
        ListManager.getGenericASLList(LIST_MANEUVERS_2).updateList(rows);
    }
    private void writeDistance(int meters) {
        ASLNavBAPFactory.getNavBAPApi().updateBapDistanceToNextManeuver(meters, 0, false, 0);
    }
    /** Same calls as native navigation (HsmTargetBap.updateDistanceTime); ETA = clock + time left. */
    private void writeDestination(int meters, int seconds) {
        if (meters >= 0) ASLNavBAPFactory.getNavBAPApi().updateBapDistanceToDestination(meters, 255, true);
        if (seconds >= 0) {
            int minutes = (seconds + 30) / 60;
            ASLNavBAPFactory.getNavBAPApi().updateBapTimeToDestination2(minutes / 60, minutes % 60, 0);
        } else {
            ASLNavBAPFactory.getNavBAPApi().updateBapTimeToDestination2(-1, -1, 0);
        }
    }
    private void writeStreet(String s) {
        ServiceManager.aslPropertyManager.valueChangedString(DP_STREET, s == null ? "" : s);
    }
    private static long now() { return System.currentTimeMillis(); }
}
