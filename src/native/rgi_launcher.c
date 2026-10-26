/*
 * QNX 6.5 mm-ipod-only launcher candidate. NOT AN INSTALLER.
 *
 * The build MUST provide the real (unmodified) mm-ipod and library paths.
 * This executable is disabled by default: it simply execv()s the original
 * mm-ipod with identical argv and inherited environment unless explicitly
 * opted in. /mnt/app/rgi_disable takes precedence over enable.
 *
 * Never install into a boot path until exact launcher path and physical
 * rollback procedure have been independently checked on the real MU1367.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifndef RGI_HOST_AUDIT
#include "rgi_stock_binary.h"
#endif

#ifndef RGI_LAUNCH_REAL
#error "Define RGI_LAUNCH_REAL with the verified absolute stock mm-ipod path"
#endif
#ifndef RGI_LAUNCH_SO
#error "Define RGI_LAUNCH_SO with the verified absolute native RGI .so path"
#endif
#ifndef RGI_LAUNCH_ENABLE
#define RGI_LAUNCH_ENABLE "/mnt/app/rgi_enable_preload"
#endif
#ifndef RGI_LAUNCH_DISABLE
#define RGI_LAUNCH_DISABLE "/mnt/app/rgi_disable"
#endif

#define MAX_PRELOAD_CHARS 4096

static int elf_compatible(const char *path) {
    unsigned char header[20];
    int fd=open(path,O_RDONLY);
    ssize_t n;
    if(fd<0)return 0;
    n=read(fd,header,sizeof(header));
    (void)close(fd);
    if(n!=(ssize_t)sizeof(header) ||
       header[0]!=0x7f || header[1]!='E' || header[2]!='L' ||
       header[3]!='F' || header[5]!=1 || header[16]!=3 ||
       header[17]!=0) return 0; /* little endian ELF ET_DYN */
#ifndef RGI_HOST_AUDIT
    /* Only QNX 6.5 ARM 32-bit preload artifacts may reach mm-ipod. */
    return header[4]==1 && header[18]==40 && header[19]==0;
#else
    /* Host mock tests use an ordinary 64-bit Linux shared object. */
    return (header[4]==2 && header[18]==62 && header[19]==0) ||
           (header[4]==1 && header[18]==40 && header[19]==0);
#endif
}
static int already_listed(const char *preloads,const char *lib) {
    size_t wanted=strlen(lib);
    const char *s=preloads;
    if(!s)return 0;
    while(*s) {
        const char *end=strchr(s,':');
        size_t length=end?(size_t)(end-s):strlen(s);
        if(length==wanted && memcmp(s,lib,wanted)==0)return 1;
        if(!end)break;
        s=end+1;
    }
    return 0;
}
/*
 * Defensive kill switch: an older launcher invocation may already have placed
 * our exact library path in inherited LD_PRELOAD. Default OFF / disable must
 * remove that entry rather than simply skipping another append.
 *
 * Preserve all unrelated preloads, including omonob's existing entries.
 * Never delete or rewrite files on disk.
 */
static void drop_inherited_rgi(void) {
    const char *old=getenv("LD_PRELOAD");
    const char *lib=RGI_LAUNCH_SO;
    char result[MAX_PRELOAD_CHARS+1];
    size_t used=0, expected=strlen(lib);
    const char *at=old;
    int removed=0;
    if(!old || !*old || !already_listed(old,lib)) return;
    while(*at) {
        const char *end=strchr(at,':');
        size_t length=end ? (size_t)(end-at) : strlen(at);
        if(length==expected && memcmp(at,lib,expected)==0) {
            removed=1;
        } else if(length>0) {
            if(used+length+(used?1:0)>MAX_PRELOAD_CHARS) return;
            if(used) result[used++]=':';
            memcpy(result+used,at,length);
            used+=length;
        }
        if(!end)break;
        at=end+1;
    }
    if(removed) {
        result[used]=0;
        if(used) (void)setenv("LD_PRELOAD",result,1);
        else (void)unsetenv("LD_PRELOAD");
    }
}

static void prepare_preload(const char *real) {
    const char *old;
    const char *lib=RGI_LAUNCH_SO;
    size_t old_len,lib_len;
    char merged[MAX_PRELOAD_CHARS+1];
    struct stat real_stat;
    if(access(RGI_LAUNCH_ENABLE,F_OK)!=0 ||
       access(RGI_LAUNCH_DISABLE,F_OK)==0) {
        drop_inherited_rgi();
        return;
    }
    if(lib[0]!='/' || real[0]!='/' ||
       stat(real,&real_stat)!=0 ||
       (real_stat.st_mode&(S_ISUID|S_ISGID))!=0 ||
       !elf_compatible(lib))return;
#ifndef RGI_HOST_AUDIT
    if(!rgi_stock_binary_matches(real))return;
#endif
    old=getenv("LD_PRELOAD");
    if(!old)old="";
    if(already_listed(old,lib))return;
    old_len=strlen(old);
    lib_len=strlen(lib);
    if(old_len+lib_len+(old_len?1:0)>MAX_PRELOAD_CHARS)return;
    if(old_len) {
        memcpy(merged,old,old_len);
        merged[old_len]=':';
        memcpy(merged+old_len+1,lib,lib_len+1);
    } else {
        memcpy(merged,lib,lib_len+1);
    }
    /* A failure to set the environment leaves the stock path unchanged. */
    (void)setenv("LD_PRELOAD",merged,1);
}
int main(int argc,char **argv) {
    const char *real=RGI_LAUNCH_REAL;
    (void)argc;
    if(real[0]!='/'||real[1]==0 || !argv || !argv[0] ||
       access(real,X_OK)!=0) {
        fprintf(stderr,"RGI launcher: verified original mm-ipod not executable\n");
        return 127;
    }
    prepare_preload(real);
    /* Preserve original argv[0], USB arguments and any existing LD_PRELOAD. */
    execv(real,argv);
    fprintf(stderr,"RGI launcher: stock mm-ipod exec failed (errno=%d)\n",errno);
    return 127;
}
