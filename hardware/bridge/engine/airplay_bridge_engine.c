/*
 * AirPlay Bridge 送信エンジン
 *
 * Bluetooth(BlueALSA の ALSA デバイス)で受けた 44.1kHz/16bit/2ch の音を、AirPlay 1(RAOP)機器へ送る。
 *
 *   ALSA 取得スレッド ──▶ リングバッファ ──▶ 可変レート変換(libsamplerate)──▶ libraop ──▶ AirPlay 機器
 *
 * - Bluetooth の送り手(プロジェクター)と Pi の時計はわずかにずれる。リングバッファの量が目標値に
 *   とどまるよう変換レートを ±0.5% の範囲で微調整し、ずれを吸収する(溜まりすぎ・枯渇による音切れを防ぐ)。
 * - 最初の音が届いてから AirPlay に接続する(接続後に無音期間があると、最初の音が遅れて捨てられる)。
 * - 送信できない間は短く眠る(cliraop のように CPU を回し続けない)。
 * - 制御: --control の FIFO に "volume N"(0〜100)を書くと音量を変える。
 * - 状態: --status の JSON を 1 秒ごとに更新する。
 */
#include <alsa/asoundlib.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <math.h>
#include <pthread.h>
#include <samplerate.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "raop_client.h"
#include "cross_net.h"
#include "cross_ssl.h"
#include "cross_log.h"

log_level util_loglevel = lERROR;
log_level raop_loglevel = lERROR;
log_level main_log = lINFO;
log_level *loglevel = &main_log;

#define RATE 44100
#define CH 2
#define CHUNK DEFAULT_FRAMES_PER_CHUNK      /* 352 frames */
#define RING_FRAMES (RATE * 3)               /* 3 秒分 */

static struct {
	const char *device, *host, *control, *status, *et;
	int port, latency_ms, volume, target_ms;
} opt = { "default", NULL, NULL, NULL, "0,4", 7000, 600, 40, 120 };

static volatile sig_atomic_t stop_flag = 0;
static void on_signal(int s) { (void) s; stop_flag = 1; }

/* ---- リングバッファ(int16 インタリーブ) ---- */
static int16_t ring[RING_FRAMES * CH];
static size_t r_head = 0, r_tail = 0;      /* frames */
static pthread_mutex_t r_mtx = PTHREAD_MUTEX_INITIALIZER;
static volatile bool capture_alive = true;
static uint64_t stat_underruns = 0, stat_overruns = 0, stat_capture_frames = 0;

/* 受信した音の大きさ(直近約 3 秒の RMS とピーク、dBFS)。無音部分は除いて平均する */
static double lv_sum = 0, lv_peak = 0, lv_rms_db = -120, lv_peak_db = -120;
static size_t lv_n = 0;
static void level_feed(const int16_t *s, size_t frames) {
	for (size_t i = 0; i < frames * CH; i++) {
		double x = s[i] / 32768.0;
		lv_sum += x * x;
		if (fabs(x) > lv_peak) lv_peak = fabs(x);
	}
	lv_n += frames * CH;
	if (lv_n >= (size_t) RATE * CH * 3) {
		double rms = sqrt(lv_sum / lv_n);
		if (rms > 1e-5) {    /* 約 -100dBFS 以下(無音)は更新しない */
			lv_rms_db = 20 * log10(rms);
			lv_peak_db = 20 * log10(lv_peak > 1e-6 ? lv_peak : 1e-6);
		}
		lv_sum = 0; lv_peak = 0; lv_n = 0;
	}
}

static size_t ring_fill_locked(void) { return (r_head + RING_FRAMES - r_tail) % RING_FRAMES; }
static size_t ring_fill(void) { pthread_mutex_lock(&r_mtx); size_t f = ring_fill_locked(); pthread_mutex_unlock(&r_mtx); return f; }

static void ring_push(const int16_t *src, size_t frames) {
	pthread_mutex_lock(&r_mtx);
	for (size_t i = 0; i < frames; i++) {
		if (ring_fill_locked() >= RING_FRAMES - 1) {   /* 満杯: 最も古い音を捨てる */
			r_tail = (r_tail + 1) % RING_FRAMES;
			stat_overruns++;
		}
		memcpy(&ring[r_head * CH], &src[i * CH], sizeof(int16_t) * CH);
		r_head = (r_head + 1) % RING_FRAMES;
	}
	stat_capture_frames += frames;
	level_feed(src, frames);
	pthread_mutex_unlock(&r_mtx);
}

static size_t ring_pop(int16_t *dst, size_t frames) {
	pthread_mutex_lock(&r_mtx);
	size_t n = ring_fill_locked();
	if (n > frames) n = frames;
	for (size_t i = 0; i < n; i++) {
		memcpy(&dst[i * CH], &ring[r_tail * CH], sizeof(int16_t) * CH);
		r_tail = (r_tail + 1) % RING_FRAMES;
	}
	pthread_mutex_unlock(&r_mtx);
	return n;
}

static void ring_drop_to(size_t keep) {
	pthread_mutex_lock(&r_mtx);
	size_t n = ring_fill_locked();
	if (n > keep) r_tail = (r_tail + (n - keep)) % RING_FRAMES;
	pthread_mutex_unlock(&r_mtx);
}

/* ---- ALSA 取得スレッド ---- */
static void *capture_thread(void *arg) {
	(void) arg;
	snd_pcm_t *pcm = NULL;
	int err;
	if ((err = snd_pcm_open(&pcm, opt.device, SND_PCM_STREAM_CAPTURE, 0)) < 0) {
		fprintf(stderr, "capture: open %s: %s\n", opt.device, snd_strerror(err));
		capture_alive = false;
		return NULL;
	}
	if ((err = snd_pcm_set_params(pcm, SND_PCM_FORMAT_S16_LE, SND_PCM_ACCESS_RW_INTERLEAVED, CH, RATE, 1, 200000)) < 0) {
		fprintf(stderr, "capture: set_params: %s\n", snd_strerror(err));
		snd_pcm_close(pcm);
		capture_alive = false;
		return NULL;
	}
	int16_t buf[441 * CH];   /* 10ms */
	while (!stop_flag) {
		snd_pcm_sframes_t n = snd_pcm_readi(pcm, buf, 441);
		if (n == -EPIPE || n == -ESTRPIPE || n == -EINTR) { snd_pcm_recover(pcm, (int) n, 1); continue; }
		if (n < 0) { fprintf(stderr, "capture: read: %s\n", snd_strerror((int) n)); break; }
		if (n > 0) ring_push(buf, (size_t) n);
	}
	snd_pcm_close(pcm);
	capture_alive = false;
	return NULL;
}

/* ---- libsamplerate の入力コールバック: リングから取り出す。足りなければ無音で埋める ---- */
static float cb_buf[CHUNK * CH * 2];
static long src_input(void *data, float **audio) {
	(void) data;
	int16_t tmp[CHUNK * CH];
	size_t want = CHUNK;
	size_t got = ring_pop(tmp, want);
	if (got < want) {
		memset(&tmp[got * CH], 0, (want - got) * CH * sizeof(int16_t));
		if (got == 0) stat_underruns++;
	}
	for (size_t i = 0; i < want * CH; i++) cb_buf[i] = tmp[i] / 32768.0f;
	*audio = cb_buf;
	return (long) want;
}

static uint64_t now_ms(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void write_status(bool connected, double fill_ms, double ratio) {
	if (!opt.status) return;
	char tmp[512];
	snprintf(tmp, sizeof tmp, "%s.tmp", opt.status);
	FILE *f = fopen(tmp, "w");
	if (!f) return;
	fprintf(f, "{\"connected\":%s,\"fill_ms\":%.1f,\"target_ms\":%d,\"ratio_ppm\":%.0f,\"underruns\":%llu,"
	        "\"overruns\":%llu,\"volume\":%d,\"latency_ms\":%d,\"capture_alive\":%s,\"captured_s\":%.1f,"
	        "\"level_rms_db\":%.1f,\"level_peak_db\":%.1f}\n",
	        connected ? "true" : "false", fill_ms, opt.target_ms, (ratio - 1.0) * 1e6,
	        (unsigned long long) stat_underruns, (unsigned long long) stat_overruns, opt.volume, opt.latency_ms,
	        capture_alive ? "true" : "false", stat_capture_frames / (double) RATE, lv_rms_db, lv_peak_db);
	fclose(f);
	rename(tmp, opt.status);
}

static void poll_control(int fd, struct raopcl_s *cl) {
	if (fd < 0) return;
	char buf[256];
	ssize_t n = read(fd, buf, sizeof buf - 1);
	if (n <= 0) return;
	buf[n] = 0;
	for (char *line = strtok(buf, "\n"); line; line = strtok(NULL, "\n")) {
		int v;
		if (sscanf(line, "volume %d", &v) == 1) {
			opt.volume = v < 0 ? 0 : v > 100 ? 100 : v;
			if (cl) raopcl_set_volume(cl, raopcl_float_volume(opt.volume));
			fprintf(stderr, "volume -> %d\n", opt.volume);
		}
	}
}

static void usage(void) {
	fprintf(stderr, "usage: airplay-bridge-engine --host IP [--port 7000] [--et 0,4] [--device ALSA] "
	                "[--latency-ms 600] [--volume 40] [--target-ms 120] [--control FIFO] [--status FILE]\n");
}

int main(int argc, char *argv[]) {
	static struct option lo[] = {
		{ "device", 1, 0, 'd' }, { "host", 1, 0, 'h' }, { "port", 1, 0, 'p' }, { "et", 1, 0, 'e' },
		{ "latency-ms", 1, 0, 'l' }, { "volume", 1, 0, 'v' }, { "target-ms", 1, 0, 't' },
		{ "control", 1, 0, 'c' }, { "status", 1, 0, 's' }, { 0, 0, 0, 0 } };
	int c;
	while ((c = getopt_long(argc, argv, "", lo, NULL)) != -1) {
		switch (c) {
		case 'd': opt.device = optarg; break;
		case 'h': opt.host = optarg; break;
		case 'p': opt.port = atoi(optarg); break;
		case 'e': opt.et = optarg; break;
		case 'l': opt.latency_ms = atoi(optarg); break;
		case 'v': opt.volume = atoi(optarg); break;
		case 't': opt.target_ms = atoi(optarg); break;
		case 'c': opt.control = optarg; break;
		case 's': opt.status = optarg; break;
		default: usage(); return 2;
		}
	}
	if (!opt.host) { usage(); return 2; }
	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);
	signal(SIGPIPE, SIG_IGN);

	netsock_init();
	cross_ssl_load();

	int ctl_fd = -1;
	if (opt.control) {
		mkfifo(opt.control, 0660);
		ctl_fd = open(opt.control, O_RDWR | O_NONBLOCK);   /* O_RDWR: 書き手がいなくても EOF にならない */
	}

	pthread_t th;
	pthread_create(&th, NULL, capture_thread, NULL);

	/* 最初の音が目標量だけ溜まるまで待つ */
	size_t target = (size_t) opt.target_ms * RATE / 1000;
	while (!stop_flag && capture_alive && ring_fill() < target) {
		poll_control(ctl_fd, NULL);
		write_status(false, ring_fill() * 1000.0 / RATE, 1.0);
		usleep(20000);
	}
	if (stop_flag || !capture_alive) goto out_nocl;

	struct in_addr any = { INADDR_ANY }, peer;
	if (!inet_aton(opt.host, &peer)) { fprintf(stderr, "bad host %s\n", opt.host); goto out_nocl; }
	char et[16];
	snprintf(et, sizeof et, "%s", opt.et);
	int lat_frames = opt.latency_ms * RATE / 1000 - RAOP_LATENCY_MIN;
	if (lat_frames < RAOP_LATENCY_MIN) lat_frames = RAOP_LATENCY_MIN;

	struct raopcl_s *cl = raopcl_create(any, 0, 0, NULL, NULL, RAOP_PCM, CHUNK, lat_frames, RAOP_CLEAR, false,
	                                    NULL, NULL, et, NULL, NULL, RATE, 16, CH, raopcl_float_volume(opt.volume));
	if (!cl || !raopcl_connect(cl, peer, (uint16_t) opt.port, true)) {
		fprintf(stderr, "airplay: connect %s:%d failed\n", opt.host, opt.port);
		if (cl) raopcl_destroy(cl);
		goto out_nocl;
	}
	fprintf(stderr, "airplay: connected %s:%d latency=%u ms\n", opt.host, opt.port,
	        raopcl_latency(cl) * 1000 / RATE);
	ring_drop_to(target);   /* 接続までに溜まった分は捨てる(恒久的な遅延にしない) */

	int err;
	SRC_STATE *src = src_callback_new(src_input, SRC_SINC_FASTEST, CH, &err, NULL);
	if (!src) { fprintf(stderr, "src: %s\n", src_strerror(err)); raopcl_destroy(cl); goto out_nocl; }

	float out_f[CHUNK * CH];
	uint8_t out_pcm[CHUNK * CH * 2];
	double avg_fill = target, ratio = 1.0;
	uint64_t last_status = 0;
	while (!stop_flag && capture_alive) {
		poll_control(ctl_fd, cl);
		if (!raopcl_accept_frames(cl)) { usleep(2000); continue; }

		/* 溜まり量を目標に保つ: 多ければ少し速く消費し(ratio<1)、少なければ遅くする */
		size_t fill = ring_fill();
		if (fill > (size_t) RATE) { ring_drop_to(target); fill = target; }     /* 1 秒以上は異常: 捨てる */
		avg_fill = avg_fill * 0.995 + fill * 0.005;
		double err_s = (avg_fill - target) / RATE;
		double corr = err_s * 0.02;                     /* 50ms ずれて 0.1% */
		if (corr > 0.005) corr = 0.005;
		if (corr < -0.005) corr = -0.005;
		ratio = 1.0 / (1.0 + corr);

		long got = src_callback_read(src, ratio, CHUNK, out_f);
		if (got < CHUNK) memset(&out_f[got * CH], 0, (CHUNK - got) * CH * sizeof(float));
		for (int i = 0; i < CHUNK * CH; i++) {
			long v = lrintf(out_f[i] * 32768.0f);
			if (v > 32767) v = 32767;
			if (v < -32768) v = -32768;
			out_pcm[i * 2] = (uint8_t) (v & 0xff);
			out_pcm[i * 2 + 1] = (uint8_t) ((v >> 8) & 0xff);
		}
		uint64_t playtime;
		if (!raopcl_send_chunk(cl, out_pcm, CHUNK, &playtime)) { fprintf(stderr, "airplay: send failed\n"); break; }

		uint64_t t = now_ms();
		if (t - last_status >= 1000) { last_status = t; write_status(true, avg_fill * 1000.0 / RATE, ratio); }
	}

	src_delete(src);
	raopcl_disconnect(cl);
	raopcl_destroy(cl);
out_nocl:
	stop_flag = 1;
	pthread_join(th, NULL);
	write_status(false, 0, 1.0);
	cross_ssl_free();
	netsock_close();
	return capture_alive ? 0 : 1;
}
