/* Standalone SHA-256 for firmware identity verification; no crypto library dependency. */
#include "sha256.h"
#include <stdio.h>
#include <string.h>
static const uint32_t k[64]={
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
#define R(x,n) (((x)>>(n))|((x)<<(32-(n))))
typedef struct {uint32_t h[8]; uint8_t b[64]; uint64_t total; size_t used;} ctx;
static void block(ctx *c,const uint8_t *p){uint32_t w[64],a,b,d,e,f,g,h,t1,t2,z; uint32_t cc;int i;
 for(i=0;i<16;i++)w[i]=((uint32_t)p[i*4]<<24)|((uint32_t)p[i*4+1]<<16)|((uint32_t)p[i*4+2]<<8)|p[i*4+3];
 for(i=16;i<64;i++)w[i]=(R(w[i-2],17)^R(w[i-2],19)^(w[i-2]>>10))+w[i-7]+(R(w[i-15],7)^R(w[i-15],18)^(w[i-15]>>3))+w[i-16];
 a=c->h[0];b=c->h[1];cc=c->h[2];d=c->h[3];e=c->h[4];f=c->h[5];g=c->h[6];h=c->h[7];
 for(i=0;i<64;i++){t1=h+(R(e,6)^R(e,11)^R(e,25))+((e&f)^(~e&g))+k[i]+w[i];t2=(R(a,2)^R(a,13)^R(a,22))+((a&b)^(a&cc)^(b&cc));h=g;g=f;f=e;e=d+t1;d=cc;cc=b;b=a;a=t1+t2;}
 c->h[0]+=a;c->h[1]+=b;c->h[2]+=cc;c->h[3]+=d;c->h[4]+=e;c->h[5]+=f;c->h[6]+=g;c->h[7]+=h;z=0;(void)z;
}
static void update(ctx *c,const uint8_t *p,size_t n){size_t m;c->total+=n;while(n){m=64-c->used;if(m>n)m=n;memcpy(c->b+c->used,p,m);c->used+=m;p+=m;n-=m;if(c->used==64){block(c,c->b);c->used=0;}}}
int rgi_sha256_file(const char *path,uint8_t out[32]){ctx c;FILE *fp;uint8_t chunk[4096];size_t n;uint64_t bits;int i;memset(&c,0,sizeof(c));
 {static const uint32_t init[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};memcpy(c.h,init,sizeof(init));}
 fp=fopen(path,"rb");if(!fp)return -1;while((n=fread(chunk,1,sizeof(chunk),fp))!=0)update(&c,chunk,n);if(ferror(fp)){fclose(fp);return -1;}fclose(fp);bits=c.total*8;
 {uint8_t pad=0x80,zero=0;update(&c,&pad,1);while(c.used!=56)update(&c,&zero,1);}
 for(i=7;i>=0;i--){uint8_t x=(uint8_t)(bits>>(8*i));update(&c,&x,1);}for(i=0;i<8;i++){out[4*i]=(uint8_t)(c.h[i]>>24);out[4*i+1]=(uint8_t)(c.h[i]>>16);out[4*i+2]=(uint8_t)(c.h[i]>>8);out[4*i+3]=(uint8_t)c.h[i];}return 0;}
