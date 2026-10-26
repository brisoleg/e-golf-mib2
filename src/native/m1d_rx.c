/*
 * EXPERIMENTAL MU1367 RGI receive tap for opt-in M1d build.
 *
 * Interposes memcpy only inside an explicitly mm-ipod-scoped LD_PRELOAD.
 * The exact driver SHA/table identity MUST have been verified in m1c.c before
 * rgi_rx_arm is called. RX return address 0x125e8 is a static candidate, NOT
 * yet verified in a live vehicle. Fail closed if it never matches.
 *
 * Never install globally; never modify network, HMI or other processes.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "mu1367_offsets.h"
#include "rgi_event_store.h"
#include "rgi_rx_probe.h"

typedef void *(*copy_fn)(void *, const void *, size_t);
static copy_fn real_copy;
static volatile int resolving_copy;
static uintptr_t armed_base;
static rgi_event_store queue;
static unsigned long capture_calls;
static unsigned long packets;
static unsigned long rejected;
static int rx_enabled;
static unsigned int seen_flags;

extern void rgi_m1c_note_rx(unsigned int msgid);
extern void rgi_m1c_identify_restart(void);

static void log_rx(const char *reason) {
    FILE *f=fopen("/dev/shmem/rgi-m1d-rx.log","a");
    if(f) { fprintf(f,"M1D %s calls=%lu packets=%lu reject=%lu\n",
                    reason,capture_calls,packets,rejected);
            fclose(f); }
}
static void *copy_fallback(void *out,const void *in,size_t n) {
    volatile unsigned char *d=(volatile unsigned char*)out;
    const volatile unsigned char *s=(const volatile unsigned char*)in;
    size_t i;
    for(i=0;i<n;i++) d[i]=s[i];
    return out;
}
static copy_fn resolve_copy(void) {
    if(real_copy)return real_copy;
    if(resolving_copy)return NULL;
    resolving_copy=1;
    real_copy=(copy_fn)dlsym(RTLD_NEXT,"memcpy");
    resolving_copy=0;
    return real_copy;
}
void rgi_rx_arm(void *base) {
    if(!base||rx_enabled||access("/mnt/app/rgi_disable",F_OK)==0||
       access("/mnt/app/rgi_allow_rx",F_OK)!=0){
        log_rx("rx_opt_in_absent_or_disabled");
        return;
    }
    /* Only a driver that m1c verified may arm this observer. */
    if(rgi_events_init(&queue,RGI_EVENTS_ROOT)!=0){
        log_rx("queue_init_failed");
        return;
    }
    armed_base=(uintptr_t)base;
    __sync_synchronize();
    rx_enabled=1;
    log_rx("rx_armed_verified_driver");
}
void *memcpy(void *dst,const void *src,size_t n) {
    uintptr_t caller=(uintptr_t)__builtin_return_address(0);
    copy_fn fn=resolve_copy();
    void *ret=fn?fn(dst,src,n):copy_fallback(dst,src,n);
    if(rx_enabled&&armed_base!=0&&
       (caller&~(uintptr_t)1)==(armed_base+(uintptr_t)MU1367_RX_MEMCPY_RA)) {
        /* Live soft-disarm on the verified RX callsite. The library remains
         * loaded, but publishes no more packets until a fresh process start.
         * Avoid a filesystem access on unrelated high-frequency memcpy calls.
         */
        if(access("/mnt/app/rgi_disable",F_OK)==0 ||
           access("/mnt/app/rgi_allow_rx",F_OK)!=0) {
            rx_enabled=0;
            __sync_synchronize();
            log_rx("rx_soft_disarmed_via_flag");
            return ret;
        }
        const unsigned char *frame=NULL;
        size_t flen=0;
        unsigned id=0;
        int verdict;
        capture_calls++;
        {
            unsigned int msgid=0;
            int complete=0;
            unsigned int bit=0;
            const char *tag=NULL;
            if(rgi_rx_peek_msgid((const unsigned char*)dst,n,&msgid,&complete)) {
                if(msgid==0x1d00){bit=1;tag="iphone_ident_1d00";}
                else if(msgid==0x1d02){bit=2;tag="iphone_ident_accepted_1d02";}
                else if(msgid==0x1d03){bit=4;tag="iphone_ident_rejected_1d03";}
                else if(msgid==0x5201){bit=8;tag="iphone_rgi_5201";}
                else if(msgid==0x5202){bit=16;tag="iphone_rgi_5202";}
                else if(msgid==0x5204){bit=32;tag="iphone_rgi_5204";}
                if(bit && complete && !(seen_flags & bit)) {
                    seen_flags |= bit;
                    log_rx(tag);
                } else if(bit && !complete && !(seen_flags & 64U)) {
                    seen_flags |= 64U;
                    log_rx("iphone_control_fragment_seen");
                }
            }
        }
        if(rgi_rx_identify_start((const unsigned char*)dst,n)) {
            if(rgi_events_init(&queue,RGI_EVENTS_ROOT)==0) {
                rgi_m1c_identify_restart();
                log_rx("1d00_new_session_queue_reset");
            } else {
                __sync_synchronize();
                rx_enabled=0;
                log_rx("1d00_session_reset_failed_disarmed");
                return ret;
            }
        }
        verdict=rgi_rx_probe((const unsigned char*)dst,n,&frame,&flen,&id);
        if(verdict==1) {
            if(rgi_events_publish(&queue,frame,flen)==0) {
                packets++;
                rgi_m1c_note_rx(id);
                if(packets==1)log_rx("first_rgi_published");
            } else {
                rejected++;
                if(rejected==1)log_rx("queue_write_failed");
            }
        } else if(verdict<0) {
            rejected++;
            if(rejected==1)log_rx("partial_or_bad_frame");
        }
        if(capture_calls==1)log_rx("candidate_rx_callsite_seen");
    }
    return ret;
}
