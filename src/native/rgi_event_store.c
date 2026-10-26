/*
 * Portable bounded event queue for HARMAN CarPlay RGI (QNX 6.5 and POSIX hosts).
 * Intentionally contains NO LD_PRELOAD, driver-patching or boot-time behavior.
 *
 * The Java RgiQueue consumes immutable 10-digit .bin files in lexicographic
 * order and resets on an atomic session marker change or a sequence gap.
 */
#include "rgi_event_store.h"
#include <errno.h>
#include <pthread.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* Serialize producer updates: memcpy observers may run on different QNX threads. */
static pthread_mutex_t publish_lock = PTHREAD_MUTEX_INITIALIZER;

static int event_name(const char *s) {
    unsigned i;
    if (!s || strlen(s) != 14 || strcmp(s+10, ".bin")) return 0;
    for (i=0;i<10;i++) if (s[i]<'0'||s[i]>'9') return 0;
    return 1;
}
static int path_join(char *dst, size_t cap, const char *dir, const char *name) {
    int n;
    if (!dst || !dir || !name) return -1;
    n=snprintf(dst,cap,"%s/%s",dir,name);
    return n<0||(size_t)n>=cap?-1:0;
}
static int write_all(int fd, const uint8_t *buf, size_t n) {
    size_t off=0;
    while(off<n) {
        ssize_t amount=write(fd,buf+off,n-off);
        if(amount<0 && errno==EINTR)continue;
        if(amount<=0)return -1;
        off+=(size_t)amount;
    }
    return 0;
}
static int atomic_file(const char *folder, const char *name,
                       const uint8_t *data, size_t n) {
    char tmpname[72],tmp[360],end[360];
    int fd,ret=-1;
    int k=snprintf(tmpname,sizeof(tmpname),".tmp-%ld-%s",(long)getpid(),name);
    if(k<0||(size_t)k>=sizeof(tmpname)||
       path_join(tmp,sizeof(tmp),folder,tmpname)<0||
       path_join(end,sizeof(end),folder,name)<0)return -1;
    fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC,0600);
    if(fd<0)return -1;
    if(write_all(fd,data,n)==0)ret=0;
    if(close(fd)!=0)ret=-1;
    if(ret==0&&rename(tmp,end)==0)return 0;
    unlink(tmp);
    return -1;
}
int rgi_events_valid(const uint8_t *f,size_t n) {
    size_t p;
    unsigned id;
    if(!f||n<6||n>RGI_EVENT_MAX_FRAME||f[0]!=0x40||f[1]!=0x40)return 0;
    if((((unsigned)f[2]<<8)|f[3])!=n)return 0;
    id=((unsigned)f[4]<<8)|f[5];
    if(id!=0x5201&&id!=0x5202&&id!=0x5203)return 0;
    for(p=6;p<n;) {
        size_t len;
        if(n-p<4)return 0;
        len=((size_t)f[p]<<8)|f[p+1];
        if(len<4||len>n-p)return 0;
        p+=len;
    }
    return p==n;
}
static int init_locked(rgi_event_store *state,const char *folder) {
    DIR *d;
    struct dirent *ent;
    struct timespec ts;
    struct stat st;
    char marker[96];
    char full[360];
    int n;
    if(!state||!folder||strlen(folder)>=sizeof(state->folder))return -1;
    memset(state,0,sizeof(*state));
    strcpy(state->folder,folder);
    if(mkdir(folder,0700)!=0&&errno!=EEXIST)return -1;
    if(stat(folder,&st)!=0||!S_ISDIR(st.st_mode))return -1;
    /* Clean only owned event files, never unrelated data. The producer must be
     * the sole owner of this private path. Do not share it with other hooks.
     */
    d=opendir(folder);
    if(!d)return -1;
    while((ent=readdir(d))!=NULL) {
        if(event_name(ent->d_name)&&
           path_join(full,sizeof(full),folder,ent->d_name)==0)
            (void)unlink(full);
    }
    closedir(d);
    if(clock_gettime(CLOCK_REALTIME,&ts)!=0)return -1;
    n=snprintf(marker,sizeof(marker),"pid%ld_t%ld_n%ld\n",
               (long)getpid(),(long)ts.tv_sec,(long)ts.tv_nsec);
    if(n<=0||(size_t)n>=sizeof(marker))return -1;
    if(atomic_file(folder,"session",(const uint8_t *)marker,(size_t)n)!=0)return -1;
    state->next_sequence=1;
    state->initialized=1;
    return 0;
}
static int publish_locked(rgi_event_store *state,const uint8_t *f,size_t n) {
    char name[24],oldname[24],old[360];
    unsigned long long seq;
    int k;
    if(!state||!state->initialized||!rgi_events_valid(f,n))return -1;
    seq=state->next_sequence;
    if(seq==0||seq>9999999999ULL)return -1;
    k=snprintf(name,sizeof(name),"%010llu.bin",seq);
    if(k!=14)return -1;
    if(atomic_file(state->folder,name,f,n)!=0)return -1;
    state->next_sequence++;
    /* Cap disk/memory use if the Java consumer has not yet been started.
     * Missing earlier events cause a sequence-gap reset in the consumer.
     */
    if(seq>RGI_EVENT_MAX_BACKLOG) {
        k=snprintf(oldname,sizeof(oldname),"%010llu.bin",
                   seq-RGI_EVENT_MAX_BACKLOG);
        if(k==14&&path_join(old,sizeof(old),state->folder,oldname)==0)
            (void)unlink(old);
    }
    return 0;
}

/* Queue reset and packet publication share this lock. An iAP2 1D00 reconnect
 * may reinitialize a queue while the driver has more than one RX worker.
 */
int rgi_events_init(rgi_event_store *state,const char *folder) {
    int rc;
    if(pthread_mutex_lock(&publish_lock)!=0)return -1;
    rc=init_locked(state,folder);
    (void)pthread_mutex_unlock(&publish_lock);
    return rc;
}

int rgi_events_publish(rgi_event_store *state,const uint8_t *frame,size_t length) {
    int rc;
    if(pthread_mutex_lock(&publish_lock)!=0)return -1;
    rc=publish_locked(state,frame,length);
    (void)pthread_mutex_unlock(&publish_lock);
    return rc;
}
