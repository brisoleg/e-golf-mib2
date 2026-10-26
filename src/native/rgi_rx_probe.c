#include "rgi_rx_probe.h"
#include "rgi_event_store.h"

int rgi_rx_probe(const uint8_t *b,size_t n,const uint8_t **out,
                 size_t *out_len,unsigned *msgid) {
    size_t available,len;
    unsigned id;
    if(!b||!out||!out_len||!msgid||n<15||n>65535U||
       b[0]!=0xff||b[1]!=0x5a)return 0;
    b+=9;
    available=n-9;
    if(b[0]!=0x40||b[1]!=0x40)return 0;
    len=((size_t)b[2]<<8)|b[3];
    id=((unsigned)b[4]<<8)|b[5];
    if(id!=0x5201&&id!=0x5202)return 0;
    if(len<6||len>available||!rgi_events_valid(b,len))return -1;
    *out=b; *out_len=len; *msgid=id;
    return 1;
}

/* Exact complete Identify-start control message on the candidate RX tap. */
int rgi_rx_identify_start(const uint8_t *transport, size_t n) {
    const uint8_t *p;
    size_t payload, frame_length;
    if(!transport||n<15||n>65535U||
       transport[0]!=0xff||transport[1]!=0x5a)return 0;
    p=transport+9;
    payload=n-9;
    if(p[0]!=0x40||p[1]!=0x40)return 0;
    frame_length=((size_t)p[2]<<8)|p[3];
    return (frame_length>=6 && frame_length<=payload &&
            p[4]==0x1d && p[5]==0x00) ? 1 : 0;
}

int rgi_rx_peek_msgid(const uint8_t *transport, size_t n,
                      unsigned int *msgid, int *complete) {
    const uint8_t *p;
    size_t len;
    if (!transport || !msgid || !complete || n < 15 ||
        transport[0] != 0xff || transport[1] != 0x5a) return 0;
    p = transport + 9;
    if (p[0] != 0x40 || p[1] != 0x40) return 0;
    len = ((size_t)p[2] << 8) | p[3];
    if (len < 6) return 0;
    *msgid = ((unsigned int)p[4] << 8) | p[5];
    *complete = len <= n - 9 ? 1 : 0;
    return 1;
}
