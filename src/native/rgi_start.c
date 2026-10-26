/*
 * CarPlay iAP2 0x5200 StartRouteGuidanceUpdates.
 * Transport-neutral; verified against edzenis + LuKa TLV shape.
 */
#include "rgi_start.h"

int rgi_start_send(const rgi_start_ops *ops, void *ctx) {
    uint16_t component=0x0010U;
    void *pkt;
    int a, b=-1, c=-1, d=-1, sent=-1;
    if(!ops||!ctx||!ops->pop||!ops->push||!ops->add_num||
       !ops->add_empty||!ops->send)return -1;
    pkt=ops->pop(ctx);
    if(!pkt)return -1;
    /* Component 0x0010 is the ID advertised by the MU1367 identification
     * hook. The request then includes three presence-only parameters.
     */
    a=ops->add_num(ctx,pkt,0x0000,&component,sizeof(component));
    if(a==0)b=ops->add_empty(ctx,pkt,0x0001);
    if(a==0&&b==0)c=ops->add_empty(ctx,pkt,0x0002);
    if(a==0&&b==0&&c==0)d=ops->add_empty(ctx,pkt,0x0003);
    if(a==0&&b==0&&c==0&&d==0)
        sent=ops->send(ctx,pkt,0x5200);
    if(ops->push(ctx,pkt)!=0)return -1;
    return sent;
}
