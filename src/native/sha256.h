#ifndef RGI_SHA256_H
#define RGI_SHA256_H
#include <stdint.h>
#include <stddef.h>
int rgi_sha256_file(const char *path, uint8_t out[32]);
#endif
