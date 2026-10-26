/* MU1367 M1c SOURCE CANDIDATE. NEVER install without QNX/vehicle validation.
 * Adds optional (file-gated) 0x5200 sender after STOCK 0x2700 MsgReply.
 * Still no RX observer or bridge; M1c is not feature-complete.
 * Fail-closed exact stock SHA256 and runtime table / callback checks.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <time.h>
#include <pthread.h>
#include "mu1367_offsets.h"
#include "sha256.h"
#include "rgi_start.h"
#include "rgi_sender_gate.h"
#include "rgi_retry.h"
#ifdef RGI_WITH_RX
extern void rgi_rx_arm(void *driver_base);
#endif

typedef void *(*dlopen_fn)(const char *,int);
typedef int (*builder_fn)(void *,void *);
typedef int (*addblob_fn)(void *,void *,uint16_t,const void *,size_t);
typedef struct {const char *name;uint32_t flags;const void *params;} message;
typedef struct {const char *name;const message *messages;uint32_t count;} group;
/* Genuine QNX ARMv7 build MUST retain the exact 32-bit ABI checks.
 * Host-only CI syntax audit has 64-bit pointers and never runs this .so.
 */
#ifndef RGI_HOST_AUDIT
typedef char ptr_is_4[(sizeof(void*)==4)?1:-1];
typedef char msg_is_12[(sizeof(message)==12)?1:-1];
typedef char group_is_12[(sizeof(group)==12)?1:-1];
#endif
static const uint8_t expected_sha[32]={0x71,0x49,0xa3,0xe7,0x5b,0x1b,0x19,0xe8,0x90,0x21,0x84,0x17,0x59,0xf4,0x20,0xa6,0xec,0xed,0x45,0x6b,0x6c,0x69,0x7a,0x3d,0xd1,0xf0,0xe4,0x36,0xab,0xea,0x0b,0x8c};
/* Preserve upstream's stock-compatible flags: index 0 and 3 remain empty;
 * 0x5200 is sent through link_send_message on the same verified link.
 */
static const message rgi_msgs[5]={{0,0,0},{"RouteGuidanceUpdate",2,0},{"RouteGuidanceManeuverUpdate",2,0},{0,0,0},{"RouteGuidanceLaneGuidance",2,0}};
static const char group_name[]="Route Guidance";
/* Payload copied from upstream edzenis/native/ident_hook.c, unchanged. */
static const uint8_t component[82]={
0x00,0x06,0x00,0x00,0x00,0x10,0x00,0x22,0x00,0x01,
0x52,0x6f,0x75,0x74,0x65,0x47,0x75,0x69,0x64,0x61,0x6e,0x63,
0x65,0x44,0x69,0x73,0x70,0x6c,0x61,0x79,0x43,0x6f,0x6d,0x70,
0x6f,0x6e,0x65,0x6e,0x74,0x00,
0x00,0x06,0x00,0x02,0x01,0x00,0x00,0x06,0x00,0x03,0x01,0x00,
0x00,0x06,0x00,0x04,0x01,0x00,0x00,0x06,0x00,0x05,0x01,0x00,
0x00,0x06,0x00,0x06,0x00,0x06,0x00,0x06,0x00,0x07,0x01,0x00,
0x00,0x06,0x00,0x08,0x00,0x06};
typedef int (*iap2_msg_fn)(void *,void *,void *,void *,void *);
typedef int (*addnum_fn)(void *,void *,uint16_t,const void *,size_t);
typedef int (*addempty_fn)(void *,void *,uint16_t);
typedef void *(*sendpop_fn)(void *);
typedef int (*sendpush_fn)(void *,void *);
typedef int (*linksend_fn)(void *,void *,uint16_t);
static builder_fn original;static addblob_fn addblob;
static iap2_msg_fn original_iap2;
static iap2_msg_fn *msg_slot;
static addnum_fn addnum;
static addempty_fn addempty;
static sendpop_fn pop;
static sendpush_fn push;
static linksend_fn sendmsg;
static volatile int installed;
static volatile int gps_seen;
static volatile int gps_ready_seen;
static volatile int gps_not_ready_seen;
static pthread_mutex_t tx_lock=PTHREAD_MUTEX_INITIALIZER;
static rgi_retry_state retry={0,1,0,0,0};
static void log_event(const char *s){FILE *f=fopen("/dev/shmem/rgi-m1b.log","a");if(f){fprintf(f,"MU1367_M1B %s\n",s);fclose(f);}}
void rgi_m1c_note_rx(unsigned int msgid) {
 pthread_mutex_lock(&tx_lock);
 rgi_retry_note_rx(&retry,msgid);
 pthread_mutex_unlock(&tx_lock);
}
void rgi_m1c_identify_restart(void) {
 pthread_mutex_lock(&tx_lock);
 rgi_retry_reset(&retry);
 pthread_mutex_unlock(&tx_lock);
 log_event("rgi_session_rearmed_after_1d00");
}
static int wrapped_builder(void *ctx,void *pkt){int result,rc;
 if(!original||!addblob)return 0;
 result=original(ctx,pkt);
 /* MU1367 returns 1 for success and 0 for failure (not K2161 -1). */
 if(result!=1)return result;
 rc=addblob(ctx,pkt,0x001e,component,sizeof(component));
 if(rc!=0){log_event("component_append_failed");return 0;}
 log_event("component_append_ok");return result;
}
static uint64_t now_ms(void){
 struct timespec t;
 if(clock_gettime(CLOCK_MONOTONIC,&t)!=0)return 0;
 return (uint64_t)t.tv_sec*1000ULL+(uint64_t)t.tv_nsec/1000000ULL;
}
static int send_start(void *ctx){
 rgi_start_ops ops;
 int rc;
 ops.pop=pop; ops.push=push; ops.add_num=addnum;
 ops.add_empty=addempty; ops.send=sendmsg;
 rc=rgi_start_send(&ops,ctx);
 log_event(rc==0?"5200_sent":"5200_failed");
 return rc;
}
static int wrapped_iap2(void *ctx,void *ctp,void *msg,void *handle,void *extra){
 unsigned int msgid=msg?((unsigned int)((const uint8_t*)msg)[6]|((unsigned int)((const uint8_t*)msg)[7]<<8)):0xffffU;
 int rc=original_iap2?original_iap2(ctx,ctp,msg,handle,extra):-1;
 uint64_t now;
 if(msgid==0x2700 && !gps_seen){ gps_seen=1; log_event("gps_2700_observed"); }
 int claimed=0;
 unsigned int generation=0;
 /* MU1367 iap2_msg 0x2700 normally sends stock MsgReply before returning.
  * This is a source-level candidate: actual successful GPS MsgReply must
  * still be confirmed on the car before enabling sends.
  */
 if(!installed||msgid!=0x2700||!ctx||access("/mnt/app/rgi_disable",F_OK)==0||
    access("/mnt/app/rgi_allow_send",F_OK)!=0)return rc;
 /* Exact MU1367 driver data-state gates, independently checked in
  * native/re/ipod-drvr-iap2.decomp.c:
  * iap2_msg checks +0x5c, gps_info_send checks +0xa4 and +0xe8.
  * Do not send RGI on an inactive link or a rejected GPS-data request.
  */
 if(!rgi_sender_gate(*(const uint32_t *)((const uint8_t *)ctx+0x5c),
                     *(const uint32_t *)((const uint8_t *)ctx+0xa4),
                     *(const uint32_t *)((const uint8_t *)ctx+0xe8))) {
    if(!gps_not_ready_seen){gps_not_ready_seen=1;log_event("gps_2700_rgi_send_blocked_not_ready");}
    return rc;
 }
 if(!gps_ready_seen){gps_ready_seen=1;log_event("gps_2700_rgi_sender_ready");}
 now=now_ms();
 if(now==0)return rc;
 pthread_mutex_lock(&tx_lock);
 claimed=rgi_retry_claim(&retry,now,&generation);
 pthread_mutex_unlock(&tx_lock);

 /* IMPORTANT: never call stock packet-pool/link functions under tx_lock.
  * They can synchronously run callbacks that call rgi_m1c_note_rx(), and
  * holding tx_lock here would deadlock mm-ipod's dispatch thread.
  */
 if(claimed){
    int send_rc;
    pthread_mutex_lock(&tx_lock);
    if(!rgi_retry_valid(&retry,generation)||
       access("/mnt/app/rgi_disable",F_OK)==0||
       access("/mnt/app/rgi_allow_send",F_OK)!=0){
        rgi_retry_complete(&retry,generation);
        pthread_mutex_unlock(&tx_lock);
        return rc;
    }
    pthread_mutex_unlock(&tx_lock);
    send_rc=send_start(ctx);
    pthread_mutex_lock(&tx_lock);
    rgi_retry_complete(&retry,generation);
    pthread_mutex_unlock(&tx_lock);
    if(send_rc!=0)log_event("5200_retry_backoff_retained");
 }
 return rc;
}
static int validate(uint8_t *b){group *t=(group*)(b+MU1367_CTRL_MSG_TABLE);const message *np;builder_fn builder;
 if((uintptr_t)&t[0x52]!=(uintptr_t)(b+MU1367_GROUP52_SLOT))return 0;
 if(t[0x50].name!=(const char*)(b+MU1367_GROUP50_NAME)||t[0x50].messages!=(const message*)(b+MU1367_GROUP50_MESSAGES)||t[0x50].count!=4)return 0;
 if(t[0x68].name!=(const char*)(b+MU1367_GROUP68_NAME)||t[0x68].messages!=(const message*)(b+MU1367_GROUP68_MESSAGES)||t[0x68].count!=4)return 0;
 np=t[0x50].messages;
 if(np[0].name!=(const char*)(b+MU1367_NP_START_NAME)||np[0].flags!=1||np[0].params!=(void*)(b+MU1367_NP_START_PARAMS))return 0;
 if(np[1].name!=(const char*)(b+MU1367_NP_UPDATE_NAME)||np[1].flags!=2||np[1].params!=(void*)(b+MU1367_NP_UPDATE_PARAMS))return 0;
 if(t[0x52].name||t[0x52].messages||t[0x52].count)return 0;
 if(*(uint32_t*)(b+MU1367_IDENT_ID)!=0x16)return 0;
 builder=*(builder_fn*)(b+MU1367_IDENT_BUILDER);
 if(builder!=(builder_fn)(b+MU1367_GPS_BUILDER))return 0;
 return 1;
}
static void install_inner(void *handle,const char *path){uint8_t actual[32];void *sym1,*sym2;uint8_t *b;group *slot;builder_fn *builder_slot;iap2_msg_fn expected;
 /* All validation must complete before the FIRST write. */
 if(installed)return;
 if(access("/mnt/app/rgi_disable",F_OK)==0){log_event("off_switch");return;}
 if(!path)return;
 sym1=dlsym(handle,"ipod_module");sym2=dlsym(handle,"packet_addparam_blob");
 if(!sym1||!sym2||(uintptr_t)sym1<MU1367_IPOD_MODULE||(uintptr_t)sym2<MU1367_ADDPARAM_BLOB){log_event("symbols_fail");return;}
 b=(uint8_t*)((uintptr_t)sym1-MU1367_IPOD_MODULE);
 if((uintptr_t)sym2!=(uintptr_t)(b+MU1367_ADDPARAM_BLOB)){log_event("base_mismatch");return;}
 {
    Dl_info image;
    const char *loaded_path;
    const char *tail;
    if(dladdr(sym1,&image)==0||!image.dli_fname||
       image.dli_fbase!=(void*)b){log_event("loaded_image_path_fail");return;}
    loaded_path=image.dli_fname;
    if(loaded_path[0]!='/'){
       if(path[0]!='/'||strcmp(loaded_path,strrchr(path,'/')+1)!=0){
          log_event("loaded_image_absolute_path_needed");return;
       }
       loaded_path=path;
    }
    tail=strrchr(loaded_path,'/');
    if(!tail||strcmp(tail+1,"ipod-drvr-iap2.so")!=0){
       log_event("loaded_image_name_fail");return;
    }
    if(rgi_sha256_file(loaded_path,actual)!=0||memcmp(actual,expected_sha,32)!=0){
       log_event("sha256_fail");return;
    }
 }
 if(!validate(b)){log_event("layout_fail");return;}
 /* The callback table was verified in the same MU1367 driver decompilation.
  * Require exact original pointer before any code pointer is replaced.
  */
 expected=(iap2_msg_fn)(b+MU1367_IAP2_MSG);
 msg_slot=(iap2_msg_fn*)(b+MU1367_IAP2_MSG_SLOT);
 if(*msg_slot!=expected ||
    *(void**)(b+MU1367_IAP2_CLOSE_SLOT)!=(void*)(b+MU1367_IAP2_CLOSE)){
    log_event("callback_layout_fail");return;
 }
 slot=(group*)(b+MU1367_GROUP52_SLOT);builder_slot=(builder_fn*)(b+MU1367_IDENT_BUILDER);
 original=*builder_slot;addblob=(addblob_fn)(b+MU1367_ADDPARAM_BLOB);
 /* Builder first, then publish group count last, as upstream does. */
 *builder_slot=wrapped_builder;
 if(*builder_slot!=wrapped_builder){log_event("builder_write_fail");return;}
 slot->name=group_name;slot->messages=rgi_msgs;__sync_synchronize();slot->count=5;__sync_synchronize();
 if(slot->name!=group_name||slot->messages!=rgi_msgs||slot->count!=5){
  slot->count=0;__sync_synchronize();slot->messages=0;slot->name=0;
  if(*builder_slot==wrapped_builder)*builder_slot=original;
  log_event("readback_fail");return;
 }
 /* Wire helper addresses are accepted only after full driver SHA and table checks. */
 original_iap2=expected;
 addnum=(addnum_fn)(b+MU1367_ADDPARAM_NUM);
 addempty=(addempty_fn)(b+MU1367_ADDPARAM_NODATA);
 pop=(sendpop_fn)(b+MU1367_SENDLIST_POP);
 push=(sendpush_fn)(b+MU1367_SENDLIST_PUSH);
 sendmsg=(linksend_fn)(b+MU1367_LINK_SEND_MESSAGE);
 *msg_slot=wrapped_iap2;
 if(*msg_slot!=wrapped_iap2){
    *msg_slot=expected;
    slot->count=0;__sync_synchronize();slot->messages=0;slot->name=0;
    if(*builder_slot==wrapped_builder)*builder_slot=original;
    log_event("callback_write_fail_rolled_back");return;
 }
 __sync_synchronize();
 installed=1;
 log_event("ident_group52_and_sender_ready");
 log_event("5200_opt_in_requires_rgi_allow_send");
#ifdef RGI_WITH_RX
 rgi_rx_arm(b);
#endif
}
static pthread_mutex_t install_lock=PTHREAD_MUTEX_INITIALIZER;
static void install(void *handle,const char *path){
 pthread_mutex_lock(&install_lock);
 if(!installed)install_inner(handle,path);
 pthread_mutex_unlock(&install_lock);
}
void *dlopen(const char *path,int mode){static dlopen_fn real;void *h;
 if(!real)real=(dlopen_fn)dlsym(RTLD_NEXT,"dlopen");
 if(!real)return NULL;
 h=real(path,mode);
 if(h&&path&&strstr(path,"ipod-drvr-iap2.so"))install(h,path);
 return h;
}
