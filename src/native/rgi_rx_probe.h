#ifndef MU1367_RGI_RX_PROBE_H
#define MU1367_RGI_RX_PROBE_H
#include <stddef.h>
#include <stdint.h>

/* Scan a single complete iAP2 FF5A link packet (header length 9) for a
 * 0x5201/0x5202 control frame at offset 9. A broken/fragmented RGI packet
 * must not reach the AID. 1=valid; 0=not RGI; -1=RGI but invalid/incomplete.
 */
int rgi_rx_probe(const uint8_t *transport, size_t n,
                 const uint8_t **control, size_t *control_len, unsigned *msgid);
/* 1D00 marks a new inbound Identify transaction/session (if present on
 * this MU1367 receive callsite). Return 1 for a complete matching packet.
 */
int rgi_rx_identify_start(const uint8_t *transport, size_t n);
int rgi_rx_peek_msgid(const uint8_t *transport, size_t n,
                      unsigned int *msgid, int *complete);
#endif
