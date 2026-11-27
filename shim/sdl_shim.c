/*
 * sdl_shim.c - minimal "libSDL2-2.0.so.0" for running OZMA (aarch64 glibc) inside an Android app.
 * Built with aarch64-linux-gnu-gcc (glibc, NOT the NDK). It never touches Android APIs: it talks to the
 * Kotlin side through unix sockets + one shared-memory file in $OZMA_IPC.
 *
 *   video.sock : shim -> app   1 byte per presented frame (index of the shm buffer just written)
 *   audio.sock : shim -> app   16-byte header {freq,format,channels,samples} then raw PCM
 *   input.sock : app  -> shim  8-byte messages {u8 type,u8 code,i16 value,u32 pad}
 *   frames.shm : header(64B) + 2 x (MAX_W*MAX_H*4) RGBA frames
 *
 * Only the ~65 SDL functions OZMA uses are implemented. Best-effort; logs to stderr.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/syscall.h>

#define EXPORT __attribute__((visibility("default")))
#define LOG(...) do { fprintf(stderr, "[shim] " __VA_ARGS__); fputc('\n', stderr); } while (0)

typedef uint8_t u8; typedef uint16_t u16; typedef uint32_t u32; typedef int16_t s16; typedef int32_t s32;

/* ---------- pixel formats (same macro as SDL_pixels.h) ---------- */
#define PF(type, order, layout, bits, bytes) ((1u<<28)|((type)<<24)|((order)<<20)|((layout)<<16)|((bits)<<8)|(bytes))
#define PF_ARGB8888 PF(6,3,6,32,4)
#define PF_XRGB8888 PF(6,1,6,24,4)
#define PF_ABGR8888 PF(6,7,6,32,4)
#define PF_XBGR8888 PF(6,5,6,24,4)
#define PF_RGB565   PF(5,1,5,16,2)

static int bytes_pp(u32 f) { return f & 0xff; }


/* ---------- SIGSYS safety net ----------
 * Android's app seccomp filter traps (SIGSYS) syscalls bionic never uses. Newer glibc probes for a few of them and
 * expects ENOSYS, so we answer ENOSYS ourselves, emulate the legacy rlimit calls, and log every syscall number we see
 * (look for "[shim] SIGSYS" lines in ozma.log). aarch64 only. */
#if defined(__aarch64__)
static volatile int sys_logged;
static void logsys(long nr, const char *what) {
    if (sys_logged++ > 60) return;
    char b[96]; int n = 0; const char *p = "[shim] SIGSYS syscall=";
    while (*p) b[n++] = *p++;
    char d[24]; int k = 0; long v = nr < 0 ? 0 : nr; do { d[k++] = (char)('0' + v % 10); v /= 10; } while (v);
    while (k) b[n++] = d[--k];
    b[n++] = ' '; while (*what && n < 90) b[n++] = *what++;
    b[n++] = '\n'; ssize_t w = write(2, b, (size_t)n); (void)w;
}
static void sigsys_handler(int sig, siginfo_t *si, void *vctx) {
    (void)sig; ucontext_t *uc = (ucontext_t *)vctx; int saved = errno;
    long nr = (long)si->si_syscall, a0 = (long)uc->uc_mcontext.regs[0], a1 = (long)uc->uc_mcontext.regs[1];
    long ret = -ENOSYS; const char *what = "-> ENOSYS";
    if (nr == 163) { long r = syscall(261, 0L, a0, 0L, a1); ret = r < 0 ? -errno : r; what = "getrlimit emulated"; }
    else if (nr == 164) { long r = syscall(261, 0L, a0, a1, 0L); ret = r < 0 ? -errno : r; what = "setrlimit emulated"; }
    else if (nr == 293) what = "rseq -> ENOSYS";
    else if (nr == 435) what = "clone3 -> ENOSYS";
    else if (nr == 439) what = "faccessat2 -> ENOSYS";
    logsys(nr, what);
    uc->uc_mcontext.regs[0] = (unsigned long long)ret; errno = saved;
}
__attribute__((constructor)) static void sigsys_init(void) {
    struct sigaction sa; memset(&sa, 0, sizeof sa); sa.sa_sigaction = sigsys_handler; sa.sa_flags = SA_SIGINFO | SA_NODEFER; sigemptyset(&sa.sa_mask);
    sigaction(SIGSYS, &sa, 0);
}
#endif

/* ---------- ipc ---------- */
#define MAX_W 1920
#define MAX_H 1080
#define HDR 64
static const char *ipc_dir;
static u8 *shm; static size_t shm_len;
static int video_fd = -1, input_fd = -1;
static u32 g_ticks0;

static u32 now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (u32)(t.tv_sec * 1000u + t.tv_nsec / 1000000u); }

static int connect_sock(const char *name) {
    char path[512]; snprintf(path, sizeof path, "%s/%s", ipc_dir, name);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return -1;
    struct sockaddr_un a; memset(&a, 0, sizeof a); a.sun_family = AF_UNIX; strncpy(a.sun_path, path, sizeof a.sun_path - 1);
    for (int i = 0; i < 100; i++) { if (connect(fd, (struct sockaddr *)&a, sizeof a) == 0) return fd; usleep(50000); }
    LOG("connect %s failed: %s", path, strerror(errno)); close(fd); return -1;
}
static int write_all(int fd, const void *p, size_t n) {
    const u8 *b = p;
    while (n) { ssize_t w = send(fd, b, n, MSG_NOSIGNAL); if (w <= 0) { if (errno == EINTR) continue; return -1; } b += w; n -= (size_t)w; }
    return 0;
}

/* ---------- input state ---------- */
static volatile u32 btn_mask; static volatile s16 axes[6]; static volatile int quit_req;
typedef struct { u32 type; u32 ts; s32 which; u8 a; u8 b; u8 c; u8 d; s16 value; u16 pad; } Ev;
#define EQ 256
static Ev evq[EQ]; static int eq_h, eq_t; static pthread_mutex_t eq_m = PTHREAD_MUTEX_INITIALIZER;
static void push_ev(Ev e) { pthread_mutex_lock(&eq_m); int n = (eq_t + 1) % EQ; if (n != eq_h) { evq[eq_t] = e; eq_t = n; } pthread_mutex_unlock(&eq_m); }
static int joy_events;

#define EV_QUIT 0x100
#define EV_JOYAXIS 0x600
#define EV_JOYBTNDOWN 0x603
#define EV_JOYBTNUP 0x604
#define EV_JOYADDED 0x605
#define EV_CAXIS 0x650
#define EV_CBTNDOWN 0x651
#define EV_CBTNUP 0x652
#define EV_CADDED 0x653

static void *input_thread(void *unused) {
    (void)unused; u8 m[8];
    for (;;) {
        size_t got = 0;
        while (got < 8) {
            ssize_t r = recv(input_fd, m + got, 8 - got, 0);
            if (r <= 0) { quit_req = 1; Ev e; memset(&e, 0, sizeof e); e.type = EV_QUIT; push_ev(e); return 0; }
            got += (size_t)r;
        }
        u8 type = m[0], code = m[1]; s16 val; memcpy(&val, m + 2, 2);
        Ev e; memset(&e, 0, sizeof e); e.ts = now_ms() - g_ticks0; e.which = 0;
        if (type == 1 && code < 15) { /* button: code = SDL_GameControllerButton */
            if (val) btn_mask |= 1u << code; else btn_mask &= ~(1u << code);
            e.type = val ? EV_CBTNDOWN : EV_CBTNUP; e.a = code; e.b = val ? 1 : 0; push_ev(e);
            if (joy_events) { e.type = val ? EV_JOYBTNDOWN : EV_JOYBTNUP; push_ev(e); }
        } else if (type == 2 && code < 6) { /* axis */
            axes[code] = val; e.type = EV_CAXIS; e.a = code; e.value = val; push_ev(e);
            if (joy_events) { e.type = EV_JOYAXIS; push_ev(e); }
        } else if (type == 3) { quit_req = 1; Ev q; memset(&q, 0, sizeof q); q.type = EV_QUIT; push_ev(q); }
    }
}

/* ---------- types ---------- */
typedef struct { u32 format; int w, h, refresh_rate; void *driverdata; } DisplayMode;
typedef struct { const char *name; u32 flags; u32 num_texture_formats; u32 texture_formats[16]; int max_texture_width, max_texture_height; } RendererInfo;
typedef struct { int x, y, w, h; } Rect;
typedef struct { u8 data[16]; } GUID;
typedef struct { int w, h; } Window;
typedef struct { int lw, lh; u32 *canvas; int cw, ch; int buf; } Renderer;
typedef struct { u32 format; int w, h, bpp; u8 *px; } Texture;
typedef struct { u32 flags; void *format; int w, h, pitch; void *pixels; void *userdata; int locked; void *list_blitmap; Rect clip; void *map; int refcount; } Surface;
typedef struct { FILE *f; } RWops;
typedef struct { int idx; int naxes, nbuttons, nhats; u32 bmask; s16 ax[8]; int virt; } Joy;

static char err[256] = "";
static int vid_w = 1024, vid_h = 768;

/* ---------- core ---------- */
EXPORT int SDL_Init(u32 flags) {
    (void)flags;
    if (shm) return 0;
    ipc_dir = getenv("OZMA_IPC"); if (!ipc_dir) { snprintf(err, sizeof err, "OZMA_IPC not set"); return -1; }
    if (getenv("OZMA_W")) vid_w = atoi(getenv("OZMA_W"));
    if (getenv("OZMA_H")) vid_h = atoi(getenv("OZMA_H"));
    joy_events = getenv("OZMA_SHIM_JOYEVENTS") != NULL;
    g_ticks0 = now_ms();
    char p[512]; snprintf(p, sizeof p, "%s/frames.shm", ipc_dir);
    int fd = open(p, O_RDWR); if (fd < 0) { snprintf(err, sizeof err, "open shm: %s", strerror(errno)); return -1; }
    shm_len = HDR + 2u * MAX_W * MAX_H * 4u;
    u8 *m = mmap(0, shm_len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); close(fd);
    if (m == MAP_FAILED) { snprintf(err, sizeof err, "mmap shm"); return -1; }
    shm = m;
    video_fd = connect_sock("video.sock"); input_fd = connect_sock("input.sock");
    if (video_fd < 0 || input_fd < 0) { snprintf(err, sizeof err, "ipc connect failed"); return -1; }
    pthread_t t; pthread_create(&t, 0, input_thread, 0); pthread_detach(t);
    Ev e; memset(&e, 0, sizeof e); e.type = EV_CADDED; e.which = 0; push_ev(e); e.type = EV_JOYADDED; push_ev(e);
    LOG("init ok, display %dx%d", vid_w, vid_h);
    return 0;
}
EXPORT void SDL_Quit(void) { }
EXPORT const char *SDL_GetError(void) { return err; }
EXPORT void SDL_free(void *p) { free(p); }
EXPORT void SDL_GetVersion(u8 *v) { v[0] = 2; v[1] = 30; v[2] = 0; }
EXPORT u32 SDL_GetTicks(void) { return now_ms() - g_ticks0; }
EXPORT uint64_t SDL_GetPerformanceCounter(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }
EXPORT uint64_t SDL_GetPerformanceFrequency(void) { return 1000000000ull; }
EXPORT void SDL_Delay(u32 ms) { struct timespec t = { ms / 1000, (long)(ms % 1000) * 1000000L }; nanosleep(&t, 0); }
EXPORT const char *SDL_GetCurrentVideoDriver(void) { return "ozma-android"; }
EXPORT const char *SDL_GetCurrentAudioDriver(void) { return "ozma-android"; }
EXPORT const char *SDL_GetKeyName(s32 key) { static char b[8]; if (key >= 32 && key < 127) { b[0] = (char)(key >= 'a' && key <= 'z' ? key - 32 : key); b[1] = 0; return b; } return ""; }

/* ---------- video ---------- */
EXPORT int SDL_GetCurrentDisplayMode(int i, DisplayMode *m) { (void)i; m->format = PF_ARGB8888; m->w = vid_w; m->h = vid_h; m->refresh_rate = 60; m->driverdata = 0; return 0; }
EXPORT void *SDL_CreateWindow(const char *t, int x, int y, int w, int h, u32 fl) { (void)t; (void)x; (void)y; (void)fl; Window *wn = calloc(1, sizeof *wn); wn->w = w > 0 ? w : vid_w; wn->h = h > 0 ? h : vid_h; return wn; }
EXPORT void SDL_DestroyWindow(void *w) { free(w); }
EXPORT u32 SDL_GetWindowFlags(void *w) { (void)w; return 0x1 | 0x4 | 0x200 | 0x400; }
EXPORT int SDL_SetWindowFullscreen(void *w, u32 f) { (void)w; (void)f; return 0; }
EXPORT void *SDL_CreateRenderer(Window *w, int idx, u32 fl) {
    (void)idx; (void)fl; Renderer *r = calloc(1, sizeof *r); r->cw = w ? w->w : vid_w; r->ch = w ? w->h : vid_h;
    if (r->cw > MAX_W) r->cw = MAX_W;
    if (r->ch > MAX_H) r->ch = MAX_H;
    r->canvas = calloc((size_t)r->cw * (size_t)r->ch, 4); return r;
}
EXPORT void SDL_DestroyRenderer(void *r) { Renderer *x = r; if (x) { free(x->canvas); free(x); } }
EXPORT int SDL_GetRendererInfo(void *r, RendererInfo *i) {
    (void)r; memset(i, 0, sizeof *i); i->name = "software"; i->flags = 1 | 4; i->num_texture_formats = 2;
    i->texture_formats[0] = PF_ARGB8888; i->texture_formats[1] = PF_XRGB8888; i->max_texture_width = i->max_texture_height = 4096; return 0;
}
EXPORT int SDL_RenderSetLogicalSize(void *r, int w, int h) {
    Renderer *x = r; x->lw = w; x->lh = h;
    if (w > 0 && h > 0 && w <= MAX_W && h <= MAX_H && (w != x->cw || h != x->ch)) { free(x->canvas); x->cw = w; x->ch = h; x->canvas = calloc((size_t)w * (size_t)h, 4); }
    return 0;
}
EXPORT void *SDL_CreateTexture(void *r, u32 fmt, int access, int w, int h) {
    (void)r; (void)access; Texture *t = calloc(1, sizeof *t); t->format = fmt; t->w = w; t->h = h; t->bpp = bytes_pp(fmt);
    if (t->bpp != 2 && t->bpp != 4) t->bpp = 4;
    t->px = calloc((size_t)w * (size_t)h, (size_t)t->bpp); return t;
}
EXPORT void SDL_DestroyTexture(void *t) { Texture *x = t; if (x) { free(x->px); free(x); } }
EXPORT int SDL_UpdateTexture(void *t, const Rect *rc, const void *pixels, int pitch) {
    Texture *x = t; Rect all = { 0, 0, x->w, x->h }; const Rect *q = rc ? rc : &all;
    for (int y = 0; y < q->h; y++)
        memcpy(x->px + ((size_t)(q->y + y) * (size_t)x->w + (size_t)q->x) * (size_t)x->bpp, (const u8 *)pixels + (size_t)y * (size_t)pitch, (size_t)q->w * (size_t)x->bpp);
    return 0;
}
/* convert one source pixel to RGBA bytes packed little-endian (R | G<<8 | B<<16 | A<<24) */
static inline u32 conv(u32 fmt, const u8 *p) {
    u32 v;
    if (fmt == PF_ARGB8888 || fmt == PF_XRGB8888) { memcpy(&v, p, 4); return ((v >> 16) & 0xff) | (v & 0xff00) | ((v & 0xff) << 16) | 0xff000000u; }
    if (fmt == PF_ABGR8888 || fmt == PF_XBGR8888) { memcpy(&v, p, 4); return v | 0xff000000u; }
    if (fmt == PF_RGB565) { u16 s; memcpy(&s, p, 2); u32 r = (s >> 11) & 31, g = (s >> 5) & 63, b = s & 31; return ((r << 3) | (r >> 2)) | (((g << 2) | (g >> 4)) << 8) | (((b << 3) | (b >> 2)) << 16) | 0xff000000u; }
    memcpy(&v, p, 4); return v | 0xff000000u;
}
EXPORT int SDL_RenderClear(void *r) { Renderer *x = r; size_t n = (size_t)x->cw * (size_t)x->ch; for (size_t i = 0; i < n; i++) x->canvas[i] = 0xff000000u; return 0; }
EXPORT int SDL_RenderCopy(void *r, void *t, const Rect *src, const Rect *dst) {
    Renderer *x = r; Texture *tx = t; Rect s = { 0, 0, tx->w, tx->h }, d = { 0, 0, x->cw, x->ch };
    if (src) s = *src;
    if (dst) d = *dst;
    if (s.w <= 0 || s.h <= 0 || d.w <= 0 || d.h <= 0) return 0;
    for (int dy = 0; dy < d.h; dy++) {
        int oy = d.y + dy; if (oy < 0 || oy >= x->ch) continue;
        int sy = s.y + (int)((int64_t)dy * s.h / d.h); if (sy < 0 || sy >= tx->h) continue;
        for (int dx = 0; dx < d.w; dx++) {
            int ox = d.x + dx; if (ox < 0 || ox >= x->cw) continue;
            int sx = s.x + (int)((int64_t)dx * s.w / d.w); if (sx < 0 || sx >= tx->w) continue;
            x->canvas[(size_t)oy * (size_t)x->cw + (size_t)ox] = conv(tx->format, tx->px + ((size_t)sy * (size_t)tx->w + (size_t)sx) * (size_t)tx->bpp);
        }
    }
    return 0;
}
EXPORT void SDL_RenderPresent(void *r) {
    Renderer *x = r; if (!shm) return;
    x->buf ^= 1; size_t fb = (size_t)MAX_W * MAX_H * 4;
    memcpy(shm + HDR + (size_t)x->buf * fb, x->canvas, (size_t)x->cw * (size_t)x->ch * 4);
    u32 hd[2] = { (u32)x->cw, (u32)x->ch }; memcpy(shm, hd, 8);
    u8 b = (u8)x->buf; write_all(video_fd, &b, 1);
}

/* ---------- surfaces / rwops (only used for screenshots) ---------- */
EXPORT void *SDL_RWFromFile(const char *file, const char *mode) { FILE *f = fopen(file, mode); if (!f) { snprintf(err, sizeof err, "fopen failed"); return 0; } RWops *r = calloc(1, sizeof *r); r->f = f; return r; }
EXPORT void *SDL_CreateRGBSurfaceWithFormatFrom(void *pixels, int w, int h, int depth, int pitch, u32 fmt) {
    (void)depth; Surface *s = calloc(1, sizeof *s); s->w = w; s->h = h; s->pitch = pitch; s->pixels = pixels; s->userdata = (void *)(uintptr_t)fmt; s->refcount = 1; return s;
}
EXPORT void SDL_FreeSurface(void *s) { free(s); }
EXPORT int SDL_SaveBMP_RW(Surface *s, RWops *rw, int freedst) {
    if (!s || !rw) return -1;
    u32 fmt = (u32)(uintptr_t)s->userdata; int w = s->w, h = s->h; u32 rowb = ((u32)w * 3 + 3) & ~3u; u32 sz = 54 + rowb * (u32)h;
    u8 hd[54]; memset(hd, 0, sizeof hd); hd[0] = 'B'; hd[1] = 'M';
    u32 off = 54, dib = 40; memcpy(hd + 2, &sz, 4); memcpy(hd + 10, &off, 4); memcpy(hd + 14, &dib, 4); memcpy(hd + 18, &w, 4); memcpy(hd + 22, &h, 4); hd[26] = 1; hd[28] = 24;
    fwrite(hd, 1, 54, rw->f); u8 *row = calloc(1, rowb);
    for (int y = h - 1; y >= 0; y--) {
        for (int x = 0; x < w; x++) { u32 c = conv(fmt, (u8 *)s->pixels + (size_t)y * (size_t)s->pitch + (size_t)x * (size_t)bytes_pp(fmt)); row[x * 3] = (c >> 16) & 255; row[x * 3 + 1] = (c >> 8) & 255; row[x * 3 + 2] = c & 255; }
        fwrite(row, 1, rowb, rw->f);
    }
    free(row); if (freedst) { fclose(rw->f); free(rw); } return 0;
}

/* ---------- audio ---------- */
typedef void (*AudioCb)(void *, u8 *, int);
typedef struct { int freq; u16 format; u8 channels, silence; u16 samples, padding; u32 size; AudioCb callback; void *userdata; } AudioSpec;
static AudioSpec aspec; static int audio_fd = -1; static volatile int audio_run, audio_open_flag; static pthread_t audio_th;

static void *audio_thread(void *unused) {
    (void)unused; u8 *buf = calloc(1, aspec.size);
    while (audio_open_flag) {
        if (!audio_run) { usleep(5000); continue; }
        memset(buf, aspec.silence, aspec.size);
        if (aspec.callback) aspec.callback(aspec.userdata, buf, (int)aspec.size);
        if (write_all(audio_fd, buf, aspec.size) < 0) break; /* blocks: paced by AudioTrack on the Android side */
    }
    free(buf); return 0;
}
EXPORT u32 SDL_OpenAudioDevice(const char *dev, int iscapture, const AudioSpec *want, AudioSpec *got, int allowed) {
    (void)dev; (void)allowed;
    if (iscapture || audio_open_flag) { snprintf(err, sizeof err, "audio: capture/second device unsupported"); return 0; }
    AudioSpec a = *want;
    if (a.format != 0x8010 && a.format != 0x8120) a.format = 0x8010; /* S16LSB or F32LSB only */
    if (a.channels < 1 || a.channels > 2) a.channels = 2;
    if (a.freq <= 0) a.freq = 48000;
    if (a.samples == 0) a.samples = 1024;
    int bps = a.format == 0x8120 ? 4 : 2; a.size = (u32)a.samples * a.channels * (u32)bps; a.silence = 0;
    audio_fd = connect_sock("audio.sock"); if (audio_fd < 0) { snprintf(err, sizeof err, "audio ipc"); return 0; }
    u32 h[4] = { (u32)a.freq, a.format, a.channels, a.samples }; write_all(audio_fd, h, 16);
    aspec = a; if (got) *got = a;
    audio_open_flag = 1; audio_run = 0; pthread_create(&audio_th, 0, audio_thread, 0);
    LOG("audio open %d Hz fmt 0x%x ch %d samples %d", a.freq, a.format, a.channels, a.samples); return 2;
}
EXPORT void SDL_PauseAudioDevice(u32 dev, int pause) { (void)dev; audio_run = !pause; }
EXPORT void SDL_CloseAudioDevice(u32 dev) {
    (void)dev; if (!audio_open_flag) return; audio_open_flag = 0;
    if (audio_fd >= 0) shutdown(audio_fd, SHUT_RDWR);
    pthread_join(audio_th, 0); close(audio_fd); audio_fd = -1;
}

/* ---------- events ---------- */
EXPORT int SDL_PollEvent(void *ev) {
    int got = 0; if (!ev) return 0;
    pthread_mutex_lock(&eq_m);
    if (eq_h != eq_t) { Ev e = evq[eq_h]; eq_h = (eq_h + 1) % EQ; memset(ev, 0, 56); memcpy(ev, &e, sizeof e); got = 1; }
    pthread_mutex_unlock(&eq_m);
    return got;
}

/* ---------- joystick / game controller: index 0 = the touch pad; more via SDL_JoystickAttachVirtual ---------- */
#define MAXJ 8
static Joy joys[MAXJ]; static int njoys = 1;
static const char *PAD_NAME = "OZMA Touch Pad";
static GUID pad_guid(void) { GUID g; memset(&g, 0, sizeof g); g.data[0] = 0x03; memcpy(g.data + 4, "OZMA", 4); g.data[8] = 0x01; return g; }
static Joy *J(void *p) { return (Joy *)p; }
static void joy_init0(void) { static int done; if (done) return; done = 1; joys[0].idx = 0; joys[0].naxes = 6; joys[0].nbuttons = 15; joys[0].nhats = 0; joys[0].virt = 0; }

EXPORT int SDL_NumJoysticks(void) { joy_init0(); return njoys; }
EXPORT const char *SDL_JoystickNameForIndex(int i) { (void)i; return PAD_NAME; }
EXPORT const char *SDL_JoystickName(void *j) { (void)j; return PAD_NAME; }
EXPORT GUID SDL_JoystickGetDeviceGUID(int i) { (void)i; return pad_guid(); }
EXPORT GUID SDL_JoystickGetGUID(void *j) { (void)j; return pad_guid(); }
EXPORT void SDL_JoystickGetGUIDString(GUID g, char *out, int n) {
    static const char h[] = "0123456789abcdef";
    if (n < 33) { if (n > 0) out[0] = 0; return; }
    for (int i = 0; i < 16; i++) { out[i * 2] = h[g.data[i] >> 4]; out[i * 2 + 1] = h[g.data[i] & 15]; }
    out[32] = 0;
}
EXPORT int SDL_IsGameController(int i) { return i == 0; }
EXPORT char *SDL_GameControllerMappingForDeviceIndex(int i) {
    if (i != 0) return 0;
    char g[40]; SDL_JoystickGetGUIDString(pad_guid(), g, sizeof g); char *m = malloc(512);
    snprintf(m, 512, "%s,%s,a:b0,b:b1,x:b2,y:b3,back:b4,guide:b5,start:b6,leftstick:b7,rightstick:b8,leftshoulder:b9,rightshoulder:b10,dpup:b11,dpdown:b12,dpleft:b13,dpright:b14,leftx:a0,lefty:a1,rightx:a2,righty:a3,lefttrigger:a4,righttrigger:a5,platform:Linux,", g, PAD_NAME);
    return m;
}
EXPORT void *SDL_GameControllerOpen(int i) { joy_init0(); return i == 0 ? (void *)&joys[0] : 0; }
EXPORT void SDL_GameControllerClose(void *c) { (void)c; }
EXPORT int SDL_GameControllerAddMapping(const char *m) { (void)m; return 0; }
EXPORT int SDL_GameControllerAddMappingsFromRW(RWops *rw, int freerw) { if (rw && freerw) { fclose(rw->f); free(rw); } return 0; }
EXPORT u8 SDL_GameControllerGetButton(void *c, int b) { (void)c; return (b >= 0 && b < 15) ? (u8)((btn_mask >> b) & 1) : 0; }
EXPORT s16 SDL_GameControllerGetAxis(void *c, int a) { (void)c; return (a >= 0 && a < 6) ? axes[a] : 0; }
EXPORT void *SDL_JoystickOpen(int i) { joy_init0(); return (i >= 0 && i < njoys) ? (void *)&joys[i] : 0; }
EXPORT void SDL_JoystickClose(void *j) { (void)j; }
EXPORT void SDL_JoystickUpdate(void) { }
EXPORT s32 SDL_JoystickInstanceID(void *j) { return J(j)->idx; }
EXPORT s32 SDL_JoystickGetDeviceInstanceID(int i) { return i; }
EXPORT int SDL_JoystickNumAxes(void *j) { return J(j)->naxes; }
EXPORT int SDL_JoystickNumButtons(void *j) { return J(j)->nbuttons; }
EXPORT int SDL_JoystickNumHats(void *j) { return J(j)->nhats; }
EXPORT s16 SDL_JoystickGetAxis(void *j, int a) { Joy *x = J(j); if (a < 0 || a >= x->naxes) return 0; return x->virt ? x->ax[a] : (a < 6 ? axes[a] : 0); }
EXPORT int SDL_JoystickGetAxisInitialState(void *j, int a, s16 *s) { (void)j; (void)a; if (s) *s = 0; return 0; }
EXPORT int SDL_JoystickAttachVirtual(int type, int na, int nb, int nh) {
    (void)type; joy_init0(); if (njoys >= MAXJ) return -1;
    Joy *x = &joys[njoys]; memset(x, 0, sizeof *x); x->idx = njoys; x->naxes = na > 8 ? 8 : na; x->nbuttons = nb > 32 ? 32 : nb; x->nhats = nh; x->virt = 1;
    return njoys++;
}
EXPORT int SDL_JoystickSetVirtualAxis(void *j, int a, s16 v) { Joy *x = J(j); if (a >= 0 && a < 8) x->ax[a] = v; return 0; }
EXPORT int SDL_JoystickSetVirtualButton(void *j, int b, u8 v) { Joy *x = J(j); if (b >= 0 && b < 32) { if (v) x->bmask |= 1u << b; else x->bmask &= ~(1u << b); } return 0; }
EXPORT int SDL_JoystickSetVirtualHat(void *j, int h, u8 v) { (void)j; (void)h; (void)v; return 0; }
