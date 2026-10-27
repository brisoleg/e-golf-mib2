/*
 * cpcover - current CarPlay / iPhone now-playing artwork as a PNG for the VW AID.
 *
 * Reads the artwork the stock iAP2 driver already received (NowPlaying
 * ArtworkFileTransferIdentifier) through the driver's own control message
 * (_IO_MSG subtype 0x2100, handled by nowplay_getartwork in ipod-drvr-iap2.so),
 * then scales it with the unit's libimg to the format the cluster shows:
 * PNG, 256x256, 24-bit RGB.
 *
 * usage: cpcover [-d /dev/ipod0] [-s 256] [-e expected-bytes] [-r raw-copy] OUT.png
 * prints "OK <artwork bytes> <w>x<h> OUT.png" or "ERR <reason>"; exit 0 on success.
 * -e: the MediaItemArtworkSize from PPS; a different size means the driver still holds the
 *     previous track's picture (exit 13, try again shortly).
 * OUT.png is written atomically (OUT.png.tmp + rename).
 *
 * Build: qcc -Vgcc_ntoarmv7le -O2 -Wall -o cpcover cpcover.c -limg
 * Run with LD_LIBRARY_PATH containing /mnt/app/armle/lib/dll (libimg codecs) and
 * LIBIMG_CFGFILE=/mnt/app/eso/hmi/lsd/img.conf (the unit has no /etc/system/config/img.conf).
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/iomsg.h>
#include <sys/neutrino.h>
#include <img/img.h>

#define ARTWORK_MAX   (2 * 1024 * 1024)
#define SUBTYPE_ARTWORK 0x2100
#define IMG_CFG_DEFAULT "/mnt/app/eso/hmi/lsd/img.conf"

/* Same layout libipod sends: io_msg header plus request fields, 32 bytes. */
struct ipod_req {
    uint16_t type;          /* _IO_MSG */
    uint16_t combine_len;   /* header size */
    uint16_t mgrid;
    uint16_t subtype;
    uint32_t arg8;
    uint32_t size;          /* +0x0c: buffer size, 0 = query total size */
    uint32_t arg10, arg14, arg18, arg1c;
};

/* libipod also sends its 44-byte per-connection options block; mirror its defaults. */
static uint8_t conn_opts[44];

static int ipod_artwork(int coid, void *buf, uint32_t len)
{
    struct ipod_req req;
    iov_t siov[2], riov[2];
    uint32_t count = 0;

    memset(&req, 0, sizeof req);
    req.type = _IO_MSG;
    req.combine_len = sizeof req;
    req.mgrid = 0x13;
    req.subtype = SUBTYPE_ARTWORK;
    req.size = buf ? len : 0;
    SETIOV(&siov[0], &req, sizeof req);
    SETIOV(&siov[1], conn_opts, sizeof conn_opts);
    SETIOV(&riov[0], &count, sizeof count);
    SETIOV(&riov[1], buf, buf ? len : 0);
    if (MsgSendv(coid, siov, 2, riov, buf ? 2 : 1) == -1)
        return -1;
    return (int)count;
}

static const char *sniff_ext(const uint8_t *b, int n)
{
    if (n >= 4 && b[0] == 0xFF && b[1] == 0xD8 && b[n - 2] == 0xFF && b[n - 1] == 0xD9)
        return "jpg";
    if (n >= 8 && b[0] == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
        return "png";
    return NULL;
}

static int write_all(const char *path, const void *b, int n)
{
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    if (fwrite(b, 1, n, f) != (size_t)n) { fclose(f); unlink(path); return -1; }
    return fclose(f);
}

int main(int argc, char **argv)
{
    const char *dev = "/dev/ipod0", *raw = NULL, *out;
    unsigned side = 256;
    long expected = -1;
    char path[512], src[512], tmp[512];
    int opt, coid, total, got, n;
    uint8_t *art;
    const char *ext;
    img_lib_t ilib;
    img_t img;

    while ((opt = getopt(argc, argv, "d:s:e:r:")) != -1) {
        switch (opt) {
        case 'd': dev = optarg; break;
        case 's': side = (unsigned)atoi(optarg); break;
        case 'r': raw = optarg; break;
        case 'e': expected = atol(optarg); break;
        default: goto usage;
        }
    }
    if (optind != argc - 1 || side < 16 || side > 1024) {
usage:
        fprintf(stderr, "usage: cpcover [-d /dev/ipod0] [-s 256] [-e expected-bytes] [-r raw-copy] OUT.png\n");
        return 2;
    }
    out = argv[optind];
    conn_opts[0x24] = 1;

    /* iPod file-system mode exposes the control node; in CarPlay mode the driver's
       resource manager is only /dev/ipodN itself. */
    snprintf(path, sizeof path, "%s/.FS_info./control", dev);
    coid = open(path, O_RDWR);
    if (coid == -1) {
        snprintf(path, sizeof path, "%s", dev);
        coid = open(path, O_RDONLY);
    }
    if (coid == -1) { printf("ERR open %s: %s\n", path, strerror(errno)); return 3; }

    total = ipod_artwork(coid, NULL, 0);
    if (total <= 0) {
        printf("ERR no artwork (%s)\n", total < 0 ? strerror(errno) : "size 0");
        close(coid);
        return 4;
    }
    if (expected >= 0 && total != expected) {
        printf("ERR stale %d bytes, expected %ld\n", total, expected);
        close(coid);
        return 13;
    }
    if (total > ARTWORK_MAX) { printf("ERR artwork too big %d\n", total); close(coid); return 4; }
    art = malloc(total);
    if (!art) { printf("ERR no memory\n"); close(coid); return 5; }

    /* The driver keeps a read cursor and rewinds it at the end; a short first read means
       an earlier reader stopped half-way, so read the rest, then the whole picture again. */
    got = ipod_artwork(coid, art, total);
    if (got >= 0 && got < total) got = ipod_artwork(coid, art, total);
    close(coid);
    if (got != total) {
        printf("ERR read %d of %d (%s)\n", got, total, got < 0 ? strerror(errno) : "short");
        return 6;
    }
    ext = sniff_ext(art, total);
    if (!ext) { printf("ERR unknown image format\n"); return 7; }

    snprintf(src, sizeof src, "%s.src.%s", out, ext);
    if (write_all(src, art, total) != 0) { printf("ERR write %s: %s\n", src, strerror(errno)); return 8; }
    if (raw) write_all(raw, art, total);
    free(art);

    if (!getenv("LIBIMG_CFGFILE")) setenv("LIBIMG_CFGFILE", IMG_CFG_DEFAULT, 1);
    if (img_lib_attach(&ilib) != IMG_ERR_OK) { printf("ERR img_lib_attach\n"); unlink(src); return 9; }
    if (img_codec_list_byext(ilib, "png", NULL, 0) == 0)
        img_cfg_read(ilib, getenv("LIBIMG_CFGFILE"));

    memset(&img, 0, sizeof img);
    img.format = IMG_FMT_RGB888;
    img.w = side;
    img.h = side;
    img.flags = IMG_FORMAT | IMG_W | IMG_H;
    n = img_load_resize_file(ilib, src, NULL, &img);
    unlink(src);
    if (n != IMG_ERR_OK) { printf("ERR decode %d\n", n); img_lib_detach(ilib); return 10; }

    snprintf(tmp, sizeof tmp, "%s.tmp.png", out);
    n = img_write_file(ilib, tmp, NULL, &img);
    free(img.access.direct.data);
    img_lib_detach(ilib);
    if (n != IMG_ERR_OK) { printf("ERR encode %d\n", n); unlink(tmp); return 11; }
    if (rename(tmp, out) != 0) { printf("ERR rename: %s\n", strerror(errno)); unlink(tmp); return 12; }

    printf("OK %d %ux%u %s\n", total, img.w, img.h, out);
    return 0;
}
