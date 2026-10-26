#include "rgi_retry.h"

void rgi_retry_reset(rgi_retry_state *s) {
    if (!s) return;
    s->attempts = 0;
    s->last_ms = 0;
    s->received = 0;
    s->inflight = 0;
    s->generation++;
    if (!s->generation) s->generation = 1;
}

void rgi_retry_note_rx(rgi_retry_state *s, unsigned int id) {
    if (s && (id == 0x5201 || id == 0x5202 || id == 0x5204))
        s->received = 1;
}

int rgi_retry_claim(rgi_retry_state *s, uint64_t now, unsigned int *generation) {
    uint64_t interval;
    if (!s || !now || s->received || s->inflight || s->attempts >= 30) return 0;
    interval = s->attempts < 5 ? 2000ULL : 30000ULL;
    if (s->attempts && (now < s->last_ms ||
        now - s->last_ms < interval)) return 0;
    s->inflight = 1;
    s->attempts++;
    s->last_ms = now;
    if (generation) *generation = s->generation;
    return 1;
}

int rgi_retry_valid(const rgi_retry_state *s, unsigned int generation) {
    return s && !s->received && s->generation == generation;
}

void rgi_retry_complete(rgi_retry_state *s, unsigned int generation) {
    if (s && s->generation == generation) s->inflight = 0;
}
