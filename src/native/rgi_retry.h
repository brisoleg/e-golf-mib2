#ifndef RGI_RETRY_H
#define RGI_RETRY_H
#include <stdint.h>

typedef struct {
    unsigned int attempts;
    unsigned int generation;
    uint64_t last_ms;
    int received;
    int inflight;
} rgi_retry_state;

void rgi_retry_reset(rgi_retry_state *s);
void rgi_retry_note_rx(rgi_retry_state *s, unsigned int msgid);
int rgi_retry_claim(rgi_retry_state *s, uint64_t now, unsigned int *generation);
int rgi_retry_valid(const rgi_retry_state *s, unsigned int generation);
void rgi_retry_complete(rgi_retry_state *s, unsigned int generation);

#endif
