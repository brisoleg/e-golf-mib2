#ifndef MU1367_RGI_START_H
#define MU1367_RGI_START_H
#include <stddef.h>
#include <stdint.h>

typedef struct {
    void *(*pop)(void *ctx);
    int (*push)(void *ctx, void *packet);
    int (*add_num)(void *ctx, void *packet, uint16_t id, const void *value, size_t size);
    int (*add_empty)(void *ctx, void *packet, uint16_t id);
    int (*send)(void *ctx, void *packet, uint16_t msgid);
} rgi_start_ops;

/* Portable, mockable 0x5200 packet construction. On all paths a packet
 * returned by pop() is returned to push(), including failed TLV encodes.
 * Returns 0 only when every TLV and the transmission succeeded.
 */
int rgi_start_send(const rgi_start_ops *ops, void *ctx);
#endif
