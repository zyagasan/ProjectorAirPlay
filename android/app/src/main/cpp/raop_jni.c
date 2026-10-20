// libraop(RAOP/AirPlay 1 送信)を Kotlin から使うための薄い JNI ラッパー。
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <android/log.h>
#include "raop_client.h"

#define TAG "raopjni"
#define CHUNK_FRAMES DEFAULT_FRAMES_PER_CHUNK
#define CHUNK_BYTES (CHUNK_FRAMES * 4)

typedef struct {
    struct raopcl_s *cl;
    uint8_t buf[CHUNK_BYTES];
    int fill;
    volatile int stop;
} ctx_t;

JNIEXPORT jlong JNICALL
Java_com_zyagasan_projectorairplay_RaopNative_open(JNIEnv *env, jobject thiz, jstring host, jint port,
                                                   jint latencyFrames, jint volume, jstring et) {
    const char *h = (*env)->GetStringUTFChars(env, host, NULL);
    const char *e = et ? (*env)->GetStringUTFChars(env, et, NULL) : NULL;
    ctx_t *c = calloc(1, sizeof(ctx_t));
    struct in_addr any = { INADDR_ANY }, peer;
    jlong result = 0;

    if (!inet_aton(h, &peer)) goto done;
    char etbuf[16] = "";
    if (e) strncpy(etbuf, e, sizeof(etbuf) - 1);

    c->cl = raopcl_create(any, 0, 0, NULL, NULL, RAOP_PCM, CHUNK_FRAMES, latencyFrames, RAOP_CLEAR, false,
                          NULL, NULL, etbuf, NULL, NULL, 44100, 16, 2, raopcl_float_volume(volume));
    if (!c->cl) { __android_log_print(ANDROID_LOG_ERROR, TAG, "raopcl_create failed"); goto done; }
    if (!raopcl_connect(c->cl, peer, (uint16_t) port, true)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "raopcl_connect failed %s:%d", h, port);
        raopcl_destroy(c->cl);
        c->cl = NULL;
        goto done;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "connected %s:%d latency=%u frames", h, port, raopcl_latency(c->cl));
    result = (jlong)(intptr_t) c;
done:
    (*env)->ReleaseStringUTFChars(env, host, h);
    if (e) (*env)->ReleaseStringUTFChars(env, et, e);
    if (!result) free(c);
    return result;
}

// 44.1kHz/16bit/2ch の PCM を渡す。1 チャンク分たまるごとに送出し、送出可能になるまで待つ(実時間ペース)。
// 返り値: 0=継続可, -1=停止または送信失敗
JNIEXPORT jint JNICALL
Java_com_zyagasan_projectorairplay_RaopNative_send(JNIEnv *env, jobject thiz, jlong handle,
                                                   jbyteArray data, jint off, jint len) {
    ctx_t *c = (ctx_t *)(intptr_t) handle;
    if (!c || !c->cl) return -1;
    jbyte *src = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    int pos = off, end = off + len, rc = 0;
    while (pos < end && !c->stop) {
        int n = CHUNK_BYTES - c->fill;
        if (n > end - pos) n = end - pos;
        memcpy(c->buf + c->fill, src + pos, n);
        c->fill += n;
        pos += n;
        if (c->fill == CHUNK_BYTES) {
            while (!raopcl_accept_frames(c->cl) && !c->stop) usleep(1000);
            if (c->stop) break;
            uint64_t playtime;
            if (!raopcl_send_chunk(c->cl, c->buf, CHUNK_FRAMES, &playtime)) { rc = -1; break; }
            c->fill = 0;
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, data, src, JNI_ABORT);
    return c->stop ? -1 : rc;
}

JNIEXPORT void JNICALL
Java_com_zyagasan_projectorairplay_RaopNative_requestStop(JNIEnv *env, jobject thiz, jlong handle) {
    ctx_t *c = (ctx_t *)(intptr_t) handle;
    if (c) c->stop = 1;
}

JNIEXPORT void JNICALL
Java_com_zyagasan_projectorairplay_RaopNative_close(JNIEnv *env, jobject thiz, jlong handle) {
    ctx_t *c = (ctx_t *)(intptr_t) handle;
    if (!c) return;
    c->stop = 1;
    if (c->cl) {
        raopcl_disconnect(c->cl);
        raopcl_destroy(c->cl);
    }
    free(c);
}

JNIEXPORT jint JNICALL
Java_com_zyagasan_projectorairplay_RaopNative_latencyMs(JNIEnv *env, jobject thiz, jlong handle) {
    ctx_t *c = (ctx_t *)(intptr_t) handle;
    if (!c || !c->cl) return 0;
    return (jint)(raopcl_latency(c->cl) * 1000 / 44100);
}

JNIEXPORT void JNICALL
Java_com_zyagasan_projectorairplay_RaopNative_setVolume(JNIEnv *env, jobject thiz, jlong handle, jint volume) {
    ctx_t *c = (ctx_t *)(intptr_t) handle;
    if (c && c->cl) raopcl_set_volume(c->cl, raopcl_float_volume(volume));
}
