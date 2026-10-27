/*
 * ManeuverMapper - iAP2 RouteGuidanceManeuverUpdate -> MIB2 maneuver datapool row
 * (list 751/838: mainElement + direction), which the HMI forwards as BAP NavSD
 * ManeuverDescriptor (fct 23).
 *
 * Written from primary sources only:
 * - ManeuverType 0-53, junction type and traffic side: Apple CarPlay SDK,
 *   CarPlay.framework/Headers/CPManeuver.h (CPManeuverType, CPJunctionType,
 *   CPTrafficSide carry the same numbering as the iAP2 fields).
 * - mainElement values: field order of
 *   de.vw.mib.bap.mqbab2.generated.navsd.serializer.ManeuverDescriptor_Status$Maneuver_1
 *   (MAIN_ELEMENT_NO_SYMBOL = 0 ... DIRECTION_TO_WAYPOINT = 34), cross-checked with the
 *   literals in mqbab2/mqbpq navsd.functions.ManeuverDescriptor (13 = TURN, 25 = UTURN,
 *   datapool 32/33 are rewritten to 15/16, 34/35/38 are HMI-internal) and with M0 on the car
 *   (13 = arrow, 21 = roundabout). Only datapool values 0-31 are used: above that the
 *   datapool and BAP numbering differ.
 * - direction: 1/256 of a full turn, counter-clockwise from straight ahead
 *   (0 straight, 64 left, 128 back, 192 right), as proven by M0 (64 left, 192 right,
 *   32 slight left) and mqbpq's U-turn mirroring (64 <-> 192).
 * - exit angle: degrees, clockwise positive (live Google Maps frames: RightTurn +90,
 *   LeftTurn -90, RoundaboutExit3 -89).
 */
package ua.mib.rgi;

public class ManeuverMapper {

    // iAP2 / CPManeuverType
    public static final int NO_TURN = 0;
    public static final int LEFT_TURN = 1;
    public static final int RIGHT_TURN = 2;
    public static final int STRAIGHT_AHEAD = 3;
    public static final int U_TURN = 4;
    public static final int FOLLOW_ROAD = 5;
    public static final int ENTER_ROUNDABOUT = 6;
    public static final int EXIT_ROUNDABOUT = 7;
    public static final int OFF_RAMP = 8;
    public static final int ON_RAMP = 9;
    public static final int ARRIVE_END_OF_NAVIGATION = 10;
    public static final int START_ROUTE = 11;
    public static final int ARRIVE_AT_DESTINATION = 12;
    public static final int KEEP_LEFT = 13;
    public static final int KEEP_RIGHT = 14;
    public static final int ENTER_FERRY = 15;
    public static final int EXIT_FERRY = 16;
    public static final int CHANGE_FERRY = 17;
    public static final int START_ROUTE_WITH_U_TURN = 18;
    public static final int U_TURN_AT_ROUNDABOUT = 19;
    public static final int LEFT_TURN_AT_END = 20;
    public static final int RIGHT_TURN_AT_END = 21;
    public static final int HIGHWAY_OFF_RAMP_LEFT = 22;
    public static final int HIGHWAY_OFF_RAMP_RIGHT = 23;
    public static final int ARRIVE_AT_DESTINATION_LEFT = 24;
    public static final int ARRIVE_AT_DESTINATION_RIGHT = 25;
    public static final int U_TURN_WHEN_POSSIBLE = 26;
    public static final int ARRIVE_END_OF_DIRECTIONS = 27;
    public static final int ROUNDABOUT_EXIT_1 = 28;
    public static final int ROUNDABOUT_EXIT_19 = 46;
    public static final int SHARP_LEFT_TURN = 47;
    public static final int SHARP_RIGHT_TURN = 48;
    public static final int SLIGHT_LEFT_TURN = 49;
    public static final int SLIGHT_RIGHT_TURN = 50;
    public static final int CHANGE_HIGHWAY = 51;
    public static final int CHANGE_HIGHWAY_LEFT = 52;
    public static final int CHANGE_HIGHWAY_RIGHT = 53;

    public static final int JUNCTION_ROUNDABOUT = 1;   // 0 = intersection
    public static final int SIDE_LEFT = 1;             // 0 = right-hand traffic

    // datapool / BAP NavSD mainElement
    static final int E_NO_SYMBOL = 0;
    static final int E_ARRIVED = 3;
    static final int E_FOLLOW_STREET = 11;
    static final int E_TURN = 13;
    static final int E_EXIT_RIGHT = 15;
    static final int E_EXIT_LEFT = 16;
    static final int E_FORK_2 = 19;
    static final int E_ROUNDABOUT_TRS_RIGHT = 21;
    static final int E_ROUNDABOUT_TRS_LEFT = 22;
    static final int E_UTURN = 25;
    static final int E_EXIT_ROUNDABOUT_TRS_RIGHT = 26;
    static final int E_EXIT_ROUNDABOUT_TRS_LEFT = 27;

    // direction, 1/256 turn counter-clockwise
    static final int D_STRAIGHT = 0;
    static final int D_SLIGHT_LEFT = 32;
    static final int D_LEFT = 64;
    static final int D_SHARP_LEFT = 96;
    static final int D_BACK = 128;
    static final int D_SHARP_RIGHT = 160;
    static final int D_RIGHT = 192;
    static final int D_SLIGHT_RIGHT = 224;

    public static boolean isValidType(int type) {
        return type >= NO_TURN && type <= CHANGE_HIGHWAY_RIGHT;
    }

    /**
     * @param exitAngle degrees, clockwise positive; only used when hasExitAngle
     * @return {mainElement, direction}; {0, 0} (no symbol) for an invalid type
     */
    public static int[] map(int type, int exitAngle, int junctionType, int drivingSide, boolean hasExitAngle) {
        boolean lht = drivingSide == SIDE_LEFT;
        boolean angle = hasExitAngle && exitAngle >= -180 && exitAngle <= 180;
        int rbElement = lht ? E_ROUNDABOUT_TRS_LEFT : E_ROUNDABOUT_TRS_RIGHT;
        if (type >= ROUNDABOUT_EXIT_1 && type <= ROUNDABOUT_EXIT_19)
            return pair(rbElement, angle ? toDirection(exitAngle) : D_STRAIGHT);
        switch (type) {
            case NO_TURN:
            case FOLLOW_ROAD:
            case START_ROUTE:
            case ENTER_FERRY:
            case EXIT_FERRY:
            case CHANGE_FERRY:
                return pair(E_FOLLOW_STREET, D_STRAIGHT);
            case LEFT_TURN:
            case LEFT_TURN_AT_END:
                return turn(D_LEFT, junctionType, rbElement, exitAngle, angle);
            case RIGHT_TURN:
            case RIGHT_TURN_AT_END:
                return turn(D_RIGHT, junctionType, rbElement, exitAngle, angle);
            case STRAIGHT_AHEAD:
                return turn(D_STRAIGHT, junctionType, rbElement, exitAngle, angle);
            case SHARP_LEFT_TURN:
                return turn(D_SHARP_LEFT, junctionType, rbElement, exitAngle, angle);
            case SHARP_RIGHT_TURN:
                return turn(D_SHARP_RIGHT, junctionType, rbElement, exitAngle, angle);
            case SLIGHT_LEFT_TURN:
                return turn(D_SLIGHT_LEFT, junctionType, rbElement, exitAngle, angle);
            case SLIGHT_RIGHT_TURN:
                return turn(D_SLIGHT_RIGHT, junctionType, rbElement, exitAngle, angle);
            case U_TURN:
            case START_ROUTE_WITH_U_TURN:
            case U_TURN_WHEN_POSSIBLE:
                // U-turn sweeps across the oncoming lanes: left in RHT, right in LHT
                return pair(E_UTURN, lht ? D_RIGHT : D_LEFT);
            case U_TURN_AT_ROUNDABOUT:
                return pair(rbElement, D_BACK);
            case ENTER_ROUNDABOUT:
                return pair(rbElement, angle ? toDirection(exitAngle) : D_STRAIGHT);
            case EXIT_ROUNDABOUT:
                return pair(lht ? E_EXIT_ROUNDABOUT_TRS_LEFT : E_EXIT_ROUNDABOUT_TRS_RIGHT,
                        angle ? toDirection(exitAngle) : D_STRAIGHT);
            case OFF_RAMP:
            case CHANGE_HIGHWAY:
                // no side given: ramps leave on the traffic side
                return lht ? pair(E_EXIT_LEFT, D_SLIGHT_LEFT) : pair(E_EXIT_RIGHT, D_SLIGHT_RIGHT);
            case HIGHWAY_OFF_RAMP_LEFT:
            case CHANGE_HIGHWAY_LEFT:
                return pair(E_EXIT_LEFT, D_SLIGHT_LEFT);
            case HIGHWAY_OFF_RAMP_RIGHT:
            case CHANGE_HIGHWAY_RIGHT:
                return pair(E_EXIT_RIGHT, D_SLIGHT_RIGHT);
            case ON_RAMP:
                // the maneuver point is the ramp entry, which leaves on the traffic side;
                // TURN_ON_MAINROAD is the priority-road bend, not a motorway merge
                return pair(E_TURN, lht ? D_SLIGHT_LEFT : D_SLIGHT_RIGHT);
            case KEEP_LEFT:
                return pair(E_FORK_2, D_SLIGHT_LEFT);
            case KEEP_RIGHT:
                return pair(E_FORK_2, D_SLIGHT_RIGHT);
            case ARRIVE_END_OF_NAVIGATION:
            case ARRIVE_AT_DESTINATION:
            case ARRIVE_END_OF_DIRECTIONS:
                return pair(E_ARRIVED, D_STRAIGHT);
            case ARRIVE_AT_DESTINATION_LEFT:
                return pair(E_ARRIVED, D_LEFT);
            case ARRIVE_AT_DESTINATION_RIGHT:
                return pair(E_ARRIVED, D_RIGHT);
            default:
                return pair(E_NO_SYMBOL, D_STRAIGHT);
        }
    }

    /** A plain turn; at a roundabout junction it is drawn as the roundabout glyph. */
    private static int[] turn(int direction, int junctionType, int rbElement, int exitAngle, boolean angle) {
        if (junctionType != JUNCTION_ROUNDABOUT) return pair(E_TURN, direction);
        return pair(rbElement, angle ? toDirection(exitAngle) : direction);
    }

    /** degrees clockwise -> 1/256 turn counter-clockwise, rounded half away from zero */
    static int toDirection(int degreesCw) {
        int v = -degreesCw * 256;
        int d = v >= 0 ? (v + 180) / 360 : -((-v + 180) / 360);
        return d & 0xFF;
    }

    private static int[] pair(int element, int direction) {
        return new int[] { element, direction };
    }
}
