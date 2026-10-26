#ifndef MU1367_RGI_EVENT_STORE_H
#define MU1367_RGI_EVENT_STORE_H
#include <stddef.h>
#include <stdint.h>

#define RGI_EVENTS_ROOT "/ramdisk/rgi-events"
#define RGI_EVENT_MAX_FRAME 65535U
#define RGI_EVENT_MAX_BACKLOG 128U

typedef struct {
    char folder[256];
    unsigned long long next_sequence;
    int initialized;
} rgi_event_store;

/* Source-only portable POSIX implementation. No global driver hooks. */
int rgi_events_init(rgi_event_store *state, const char *root);
int rgi_events_publish(rgi_event_store *state, const uint8_t *frame, size_t n);
int rgi_events_valid(const uint8_t *frame, size_t n);

#endif
