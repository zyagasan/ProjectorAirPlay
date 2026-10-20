// libraop の OpenSSL 依存の代替実装。
// AirMac Express(暗号化なし・パスワードなし)への PCM 送信では RAND_bytes しか使わない。
// それ以外(AppleTV ペアリング用の EVP_*、パスワード認証用の MD5、RSA 暗号化)は未対応のスタブ。
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>
#include "cross_log.h"

log_level raop_loglevel = lINFO;
log_level util_loglevel = lWARN;

int RAND_bytes(unsigned char *buf, int num) {
    int fd = open("/dev/urandom", O_RDONLY);
    int got = 0;
    if (fd >= 0) {
        while (got < num) {
            ssize_t r = read(fd, buf + got, num - got);
            if (r <= 0) break;
            got += (int) r;
        }
        close(fd);
    }
    return got == num ? 1 : 0;
}

static void unsupported(const char *name) {
    __android_log_print(ANDROID_LOG_ERROR, "raopjni", "unsupported OpenSSL call: %s", name);
}

unsigned char *MD5(const unsigned char *d, size_t n, unsigned char *md) { unsupported("MD5"); return NULL; }

void *EVP_PKEY_new_raw_private_key(int t, void *e, const unsigned char *k, size_t l) { unsupported(__func__); return NULL; }
void *EVP_PKEY_new_raw_public_key(int t, void *e, const unsigned char *k, size_t l) { unsupported(__func__); return NULL; }
int EVP_PKEY_get_raw_private_key(const void *p, unsigned char *k, size_t *l) { unsupported(__func__); return 0; }
int EVP_PKEY_get_raw_public_key(const void *p, unsigned char *k, size_t *l) { unsupported(__func__); return 0; }
void EVP_PKEY_free(void *p) {}
void *EVP_PKEY_CTX_new(void *p, void *e) { unsupported(__func__); return NULL; }
int EVP_PKEY_derive_init(void *c) { return 0; }
int EVP_PKEY_derive_set_peer(void *c, void *p) { return 0; }
int EVP_PKEY_derive(void *c, unsigned char *k, size_t *l) { return 0; }
void EVP_PKEY_CTX_free(void *c) {}
void *EVP_MD_CTX_new(void) { unsupported(__func__); return NULL; }
int EVP_DigestSignInit(void *c, void **p, const void *t, void *e, void *k) { return 0; }
int EVP_DigestSign(void *c, unsigned char *s, size_t *sl, const unsigned char *d, size_t dl) { return 0; }
void EVP_MD_CTX_free(void *c) {}

void *RSA_new(void) { unsupported(__func__); return NULL; }
int RSA_set0_key(void *r, void *n, void *e, void *d) { return 0; }
int RSA_public_encrypt(int fl, const unsigned char *f, unsigned char *t, void *r, int p) { return -1; }
void RSA_free(void *r) {}
void *BN_bin2bn(const unsigned char *s, int l, void *ret) { unsupported(__func__); return NULL; }

// ALAC は使わない(PCM のみ)
int pcm_to_alac(void *c, unsigned char *in, int frames, unsigned char **out, int *size) { return 0; }
int pcm_to_alac_raw(unsigned char *in, int frames, unsigned char **out, int *size, int bsize) { return 0; }
void *alac_create_encoder(int mf, int sr, int ss, int ch) { return NULL; }
void alac_delete_encoder(void *c) {}
