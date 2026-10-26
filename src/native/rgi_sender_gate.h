#ifndef MU1367_RGI_SENDER_GATE_H
#define MU1367_RGI_SENDER_GATE_H
#include <stdint.h>

/* Flags from exact MU1367 stock ipod-drvr-iap2.so SHA:
 * iap2_msg: ctx+0x5c bit 0x02 permits outgoing iAP2 actions.
 * gps_info_send: ctx+0xa4 bit 0x20 and ctx+0xe8 bit 0x01.
 * Before sending a supplemental 0x5200 triggered by 0x2700, require
 * the SAME stock prerequisites that accepted the GPS update.
 * This never writes driver memory or starts a route by itself.
 */
int rgi_sender_gate(uint32_t iap2_flags, uint32_t gps_flags, uint32_t gps_capabilities);
#endif
