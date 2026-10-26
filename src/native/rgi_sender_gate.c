#include "rgi_sender_gate.h"

int rgi_sender_gate(uint32_t iap2_flags,uint32_t gps_flags,uint32_t capabilities) {
    return (iap2_flags & 0x00000002U) != 0U &&
           (gps_flags & 0x00000020U) != 0U &&
           (capabilities & 0x00000001U) != 0U;
}
