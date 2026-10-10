/*
 * A libretro frontend inside Enginehost's sandbox: any libretro core linked
 * into the same library runs as an isolated EngineStepDriven plugin
 * (LibretroPlugin.java beside this file; Enginehost docs/engine-sandbox.md
 * "Engines with a libretro core").
 *
 * A libretro core already has the shape an isolated engine needs: it draws
 * into memory and hands each frame over (video_refresh), produces sound as
 * samples (audio_sample_batch), reads input by polling, and opens its files
 * with ordinary libc calls, which Enginehost's file layer serves
 * (enginehost_vfs_forward.c, linked into the same library). So this file is
 * only the frontend half of the libretro API:
 *
 * - the core runs on its own thread at the frame rate it asks for;
 * - frames go to a store the plugin's step() copies to the host, converted
 *   to ARGB_8888 at the size of the core's first picture (later sizes are
 *   scaled into it, as the host sizes its view once);
 * - sound is resampled to the host's ring rate and written into the ring
 *   (enginehost_audio_ring.c); a core that asks to be called for sound
 *   (RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK) is called whenever the ring has
 *   room;
 * - input is one RetroPad, a pointer and a mouse, set from the host's
 *   actions and taps by the plugin;
 * - core options take their defaults, overridden by what the plugin passes.
 *
 * No GPU (an isolated process cannot open one): a core asking for a
 * hardware context is refused, as RetroArch refuses one it cannot give.
 *
 * Copied verbatim from Enginehost's plugin-native/libretro/; change it there.
 */
#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "enginehost_audio_ring.h"
#include "libretro.h"

#define TAG "enginehost-libretro"
#define LOG(priority, ...) __android_log_print(priority, TAG, __VA_ARGS__)

/* ---- state ---------------------------------------------------------------- */

static char *g_content_path, *g_system_dir, *g_save_dir;
static char g_error[256];

struct option {
    char *key;
    char *value;
};
static struct option *g_options;
static size_t g_option_count;
static int g_options_changed;
static pthread_mutex_t g_option_lock = PTHREAD_MUTEX_INITIALIZER;

static enum retro_pixel_format g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static struct retro_audio_callback g_audio_callback;
static struct retro_frame_time_callback g_frame_time_callback;
static struct retro_keyboard_callback g_keyboard_callback;
static double g_fps = 60.0, g_core_rate = 44100.0;
static volatile int g_stop, g_shutdown;

static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_frame_ready = PTHREAD_COND_INITIALIZER;
static uint32_t *g_frame;
static int g_frame_width, g_frame_height, g_frame_new, g_ended, g_failed;

static struct enginehost_audio_ring g_ring;
static int g_ring_rate;
static double g_resample_position;
static int16_t g_resample_last[2];

static volatile uint32_t g_joypad;          /* bit per RETRO_DEVICE_ID_JOYPAD_* */
static volatile int g_pointer_x, g_pointer_y, g_pointer_down;  /* frame pixels */
static volatile int g_mouse_x, g_mouse_y, g_mouse_buttons, g_mouse_wheel;
static int g_mouse_last_x, g_mouse_last_y;

static uint64_t now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t) t.tv_sec * 1000000u + (uint64_t) t.tv_nsec / 1000u;
}

static void sleep_us(uint64_t us) {
    struct timespec wait = { (time_t) (us / 1000000u), (long) (us % 1000000u) * 1000L };
    while (nanosleep(&wait, &wait) != 0 && errno == EINTR) {}
}

/* ---- options -------------------------------------------------------------- */

static struct option *find_option(const char *key) {
    for (size_t i = 0; i < g_option_count; i++) {
        if (strcmp(g_options[i].key, key) == 0) return &g_options[i];
    }
    return NULL;
}

/* Records a core's default unless the plugin already set the key. */
static void option_default(const char *key, const char *value) {
    if (key == NULL || value == NULL) return;
    pthread_mutex_lock(&g_option_lock);
    if (find_option(key) == NULL) {
        struct option *grown = realloc(g_options, sizeof *g_options * (g_option_count + 1));
        if (grown != NULL) {
            g_options = grown;
            g_options[g_option_count].key = strdup(key);
            g_options[g_option_count].value = strdup(value);
            g_option_count++;
        }
    }
    pthread_mutex_unlock(&g_option_lock);
}

/* "Description; first|second|third": the first value is the default (libretro v0 options). */
static void option_from_v0(const struct retro_variable *variable) {
    const char *values = strchr(variable->value, ';');
    if (values == NULL) return;
    values++;
    while (*values == ' ') values++;
    char first[256];
    size_t n = strcspn(values, "|");
    if (n >= sizeof first) n = sizeof first - 1;
    memcpy(first, values, n);
    first[n] = '\0';
    option_default(variable->key, first);
}

static void option_from_v1(const struct retro_core_option_definition *definitions) {
    for (; definitions != NULL && definitions->key != NULL; definitions++) {
        const char *value = definitions->default_value;
        if (value == NULL) value = definitions->values[0].value;
        option_default(definitions->key, value);
    }
}

static void option_from_v2(const struct retro_core_options_v2 *options) {
    if (options == NULL) return;
    for (const struct retro_core_option_v2_definition *d = options->definitions; d != NULL && d->key != NULL; d++) {
        const char *value = d->default_value;
        if (value == NULL) value = d->values[0].value;
        option_default(d->key, value);
    }
}

static bool get_variable(struct retro_variable *variable) {
    pthread_mutex_lock(&g_option_lock);
    struct option *option = find_option(variable->key);
    variable->value = option != NULL ? option->value : NULL;
    pthread_mutex_unlock(&g_option_lock);
    return variable->value != NULL;
}

/* ---- callbacks the core calls ---------------------------------------------- */

static void log_printf(enum retro_log_level level, const char *format, ...) {
    static const int priorities[] = { ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR };
    va_list args;
    va_start(args, format);
    __android_log_vprint(priorities[level <= RETRO_LOG_ERROR ? level : RETRO_LOG_ERROR], "libretro-core", format, args);
    va_end(args);
}

static bool environment(unsigned command, void *data) {
    switch (command & ~RETRO_ENVIRONMENT_EXPERIMENTAL) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE & ~RETRO_ENVIRONMENT_EXPERIMENTAL:
            ((struct retro_log_callback *) data)->log = log_printf;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format format = *(const enum retro_pixel_format *) data;
            if (format != RETRO_PIXEL_FORMAT_XRGB8888 && format != RETRO_PIXEL_FORMAT_RGB565
                && format != RETRO_PIXEL_FORMAT_0RGB1555) return false;
            g_pixel_format = format;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **) data = g_system_dir;
            return g_system_dir != NULL;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **) data = g_save_dir;
            return g_save_dir != NULL;
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY & ~RETRO_ENVIRONMENT_EXPERIMENTAL:
            *(const char **) data = g_system_dir;
            return g_system_dir != NULL;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *) data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS & ~RETRO_ENVIRONMENT_EXPERIMENTAL:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS & ~RETRO_ENVIRONMENT_EXPERIMENTAL:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_ROTATION:
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            /* Frames carry their own size; the store scales them into the first one's. */
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *message = data;
            if (message != NULL && message->msg != NULL) LOG(ANDROID_LOG_INFO, "core message: %s", message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SHUTDOWN:
            g_shutdown = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK: {
            const struct retro_audio_callback *callback = data;
            g_audio_callback = callback != NULL ? *callback : (struct retro_audio_callback) { 0 };
            return true;
        }
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK: {
            const struct retro_frame_time_callback *callback = data;
            g_frame_time_callback = callback != NULL ? *callback : (struct retro_frame_time_callback) { 0 };
            return true;
        }
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: {
            const struct retro_keyboard_callback *callback = data;
            g_keyboard_callback = callback != NULL ? *callback : (struct retro_keyboard_callback) { 0 };
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            for (const struct retro_variable *v = data; v != NULL && v->key != NULL; v++) option_from_v0(v);
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *(unsigned *) data = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            option_from_v1(data);
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            const struct retro_core_options_intl *intl = data;
            if (intl != NULL) option_from_v1(intl->us);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
            option_from_v2(data);
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            const struct retro_core_options_v2_intl *intl = data;
            if (intl != NULL) option_from_v2(intl->us);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            return get_variable(data);
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *) data = g_options_changed != 0;
            g_options_changed = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS & ~RETRO_ENVIRONMENT_EXPERIMENTAL:
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *) data = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *(int *) data = 3;
            return true;
        default:
            /* Including SET_HW_RENDER and GET_VFS_INTERFACE: no GPU here, and
               the core's own stdio is what the file layer serves. */
            return false;
    }
}

static inline uint32_t to_argb(const void *row, int x) {
    switch (g_pixel_format) {
        case RETRO_PIXEL_FORMAT_XRGB8888:
            return ((const uint32_t *) row)[x] | 0xFF000000u;
        case RETRO_PIXEL_FORMAT_RGB565: {
            uint32_t p = ((const uint16_t *) row)[x];
            uint32_t r = (p >> 11) & 0x1F, g = (p >> 5) & 0x3F, b = p & 0x1F;
            return 0xFF000000u | (r << 19 | (r >> 2) << 16) | (g << 10 | (g >> 4) << 8) | (b << 3 | b >> 2);
        }
        default: {
            uint32_t p = ((const uint16_t *) row)[x];
            uint32_t r = (p >> 10) & 0x1F, g = (p >> 5) & 0x1F, b = p & 0x1F;
            return 0xFF000000u | (r << 19 | (r >> 2) << 16) | (g << 11 | (g >> 2) << 8) | (b << 3 | b >> 2);
        }
    }
}

static void video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data == NULL || data == RETRO_HW_FRAME_BUFFER_VALID || width == 0 || height == 0) return;
    pthread_mutex_lock(&g_frame_lock);
    if (g_frame == NULL) {
        g_frame = malloc((size_t) width * height * 4);
        if (g_frame == NULL) {
            pthread_mutex_unlock(&g_frame_lock);
            return;
        }
        g_frame_width = (int) width;
        g_frame_height = (int) height;
        LOG(ANDROID_LOG_INFO, "first frame %ux%u", width, height);
    }
    for (int y = 0; y < g_frame_height; y++) {
        unsigned sy = (unsigned) g_frame_height == height ? (unsigned) y : (unsigned) ((uint64_t) y * height / (unsigned) g_frame_height);
        const uint8_t *row = (const uint8_t *) data + (size_t) sy * pitch;
        uint32_t *out = g_frame + (size_t) y * (size_t) g_frame_width;
        if ((unsigned) g_frame_width == width) {
            for (int x = 0; x < g_frame_width; x++) out[x] = to_argb(row, x);
        } else {
            for (int x = 0; x < g_frame_width; x++) out[x] = to_argb(row, (int) ((uint64_t) x * width / (unsigned) g_frame_width));
        }
    }
    g_frame_new = 1;
    pthread_cond_broadcast(&g_frame_ready);
    pthread_mutex_unlock(&g_frame_lock);
}

/* Linear resampling from the core's rate to the ring's, carried across calls. */
static size_t audio_batch(const int16_t *data, size_t frames) {
    if (g_ring.base == NULL || frames == 0) return frames;
    double step = g_core_rate / (double) g_ring_rate;
    int16_t out[1024 * 2];
    size_t produced = 0;
    for (;;) {
        double position = g_resample_position;
        if (position >= (double) frames) break;
        size_t index = (size_t) position;
        double t = position - (double) index;
        const int16_t *b = data + index * 2;
        const int16_t *a = index == 0 ? g_resample_last : b - 2;
        /* position counts from the sample before data[0] (the last of the previous call). */
        out[produced * 2] = (int16_t) (a[0] + (b[0] - a[0]) * t);
        out[produced * 2 + 1] = (int16_t) (a[1] + (b[1] - a[1]) * t);
        produced++;
        g_resample_position += step;
        if (produced == 1024) {
            enginehost_audio_ring_write(&g_ring, out, (uint32_t) produced);
            produced = 0;
        }
    }
    if (produced > 0) enginehost_audio_ring_write(&g_ring, out, (uint32_t) produced);
    g_resample_position -= (double) frames;
    g_resample_last[0] = data[(frames - 1) * 2];
    g_resample_last[1] = data[(frames - 1) * 2 + 1];
    return frames;
}

static void audio_sample(int16_t left, int16_t right) {
    int16_t frame[2] = { left, right };
    audio_batch(frame, 1);
}

static void input_poll(void) {}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    switch (device) {
        case RETRO_DEVICE_JOYPAD:
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) g_joypad;
            return id < 16 ? (int16_t) ((g_joypad >> id) & 1) : 0;
        case RETRO_DEVICE_POINTER: {
            if (index > 0) return 0;
            int width = g_frame_width > 0 ? g_frame_width : 1, height = g_frame_height > 0 ? g_frame_height : 1;
            switch (id) {
                case RETRO_DEVICE_ID_POINTER_X: return (int16_t) (g_pointer_x * 0xFFFF / width - 0x7FFF);
                case RETRO_DEVICE_ID_POINTER_Y: return (int16_t) (g_pointer_y * 0xFFFF / height - 0x7FFF);
                case RETRO_DEVICE_ID_POINTER_PRESSED: return (int16_t) g_pointer_down;
                case RETRO_DEVICE_ID_POINTER_COUNT: return (int16_t) (g_pointer_down ? 1 : 0);
                default: return 0;
            }
        }
        case RETRO_DEVICE_MOUSE:
            switch (id) {
                case RETRO_DEVICE_ID_MOUSE_X: {
                    int dx = g_mouse_x - g_mouse_last_x;
                    g_mouse_last_x = g_mouse_x;
                    return (int16_t) dx;
                }
                case RETRO_DEVICE_ID_MOUSE_Y: {
                    int dy = g_mouse_y - g_mouse_last_y;
                    g_mouse_last_y = g_mouse_y;
                    return (int16_t) dy;
                }
                case RETRO_DEVICE_ID_MOUSE_LEFT: return (int16_t) (g_mouse_buttons & 1);
                case RETRO_DEVICE_ID_MOUSE_RIGHT: return (int16_t) ((g_mouse_buttons >> 1) & 1);
                case RETRO_DEVICE_ID_MOUSE_WHEELUP: {
                    int up = g_mouse_wheel > 0;
                    if (up) g_mouse_wheel--;
                    return (int16_t) up;
                }
                case RETRO_DEVICE_ID_MOUSE_WHEELDOWN: {
                    int down = g_mouse_wheel < 0;
                    if (down) g_mouse_wheel++;
                    return (int16_t) down;
                }
                default: return 0;
            }
        default:
            return 0;
    }
}

/* ---- the core's thread ---------------------------------------------------- */

static void finish(int failed) {
    pthread_mutex_lock(&g_frame_lock);
    g_ended = 1;
    g_failed = failed;
    pthread_cond_broadcast(&g_frame_ready);
    pthread_mutex_unlock(&g_frame_lock);
}

static void *core_main(void *unused) {
    (void) unused;
    retro_set_environment(environment);
    retro_set_video_refresh(video_refresh);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();

    struct retro_game_info game = { .path = g_content_path };
    if (!retro_load_game(&game)) {
        snprintf(g_error, sizeof g_error, "the core could not load %s", g_content_path);
        LOG(ANDROID_LOG_ERROR, "%s", g_error);
        retro_deinit();
        finish(1);
        return NULL;
    }
    struct retro_system_av_info av = { 0 };
    retro_get_system_av_info(&av);
    if (av.timing.fps > 1.0) g_fps = av.timing.fps;
    if (av.timing.sample_rate > 1.0) g_core_rate = av.timing.sample_rate;
    LOG(ANDROID_LOG_INFO, "loaded %s: %ux%u at %.2f fps, sound at %.0f Hz into %d Hz",
        g_content_path, av.geometry.base_width, av.geometry.base_height, g_fps, g_core_rate, g_ring_rate);

    const uint64_t period = (uint64_t) (1000000.0 / g_fps);
    uint64_t next = now_us(), last = next;
    /* The ring is kept about a twentieth of a second ahead of the host. */
    const uint32_t ahead = g_ring_rate > 0 ? (uint32_t) (g_ring_rate / 20) : 0;
    while (!g_stop && !g_shutdown) {
        uint64_t now = now_us();
        if (g_frame_time_callback.callback != NULL) {
            retro_usec_t delta = (retro_usec_t) (now - last);
            if (delta <= 0) delta = g_frame_time_callback.reference;
            g_frame_time_callback.callback(delta);
        }
        last = now;
        retro_run();
        if (g_audio_callback.callback != NULL) {
            for (int i = 0; i < 16 && enginehost_audio_ring_buffered_frames(&g_ring) < ahead; i++) {
                g_audio_callback.callback();
            }
        }
        next += period;
        now = now_us();
        if (next > now) sleep_us(next - now);
        else if (now - next > 250000u) next = now; /* fell far behind: do not race to catch up */
    }
    retro_unload_game();
    retro_deinit();
    finish(0);
    return NULL;
}

/* ---- JNI ----------------------------------------------------------------- */

static char *dup_string(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    char *copy = utf != NULL ? strdup(utf) : NULL;
    if (utf != NULL) (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

static jlong JNICALL native_start(JNIEnv *env, jclass cls, jstring content, jstring system_dir, jstring save_dir,
                                  jobjectArray option_keys, jobjectArray option_values, jint ring_fd, jint ring_rate) {
    (void) cls;
    if (g_content_path != NULL) {
        snprintf(g_error, sizeof g_error, "a core is already running in this process");
        return 0;
    }
    g_content_path = dup_string(env, content);
    g_system_dir = dup_string(env, system_dir);
    g_save_dir = dup_string(env, save_dir);
    jsize options = option_keys != NULL ? (*env)->GetArrayLength(env, option_keys) : 0;
    for (jsize i = 0; i < options; i++) {
        jstring key = (*env)->GetObjectArrayElement(env, option_keys, i);
        jstring value = (*env)->GetObjectArrayElement(env, option_values, i);
        char *k = dup_string(env, key), *v = dup_string(env, value);
        option_default(k, v);
        free(k);
        free(v);
    }
    if (ring_fd >= 0 && ring_rate > 0) {
        int result = enginehost_audio_ring_open(&g_ring, ring_fd);
        if (result == 0) g_ring_rate = ring_rate;
        else LOG(ANDROID_LOG_WARN, "the host's audio ring did not map (%d); the game plays silent", result);
    }
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setstacksize(&attributes, 8u * 1024u * 1024u);
    pthread_t thread;
    int result = pthread_create(&thread, &attributes, core_main, NULL);
    pthread_attr_destroy(&attributes);
    if (result != 0) {
        snprintf(g_error, sizeof g_error, "could not start the core's thread (%d)", result);
        return 0;
    }
    pthread_detach(thread);
    return 1;
}

static jstring JNICALL native_error(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, g_error[0] ? g_error : "the core could not start");
}

/* The host asks once; the picture's size is the first frame's. Short of the host's own minute. */
static int wait_first_frame(void) {
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 50;
    pthread_mutex_lock(&g_frame_lock);
    while (g_frame == NULL && !g_ended) {
        if (pthread_cond_timedwait(&g_frame_ready, &g_frame_lock, &deadline) == ETIMEDOUT) break;
    }
    int ready = g_frame != NULL;
    pthread_mutex_unlock(&g_frame_lock);
    return ready;
}

static jint JNICALL native_width(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls; (void) handle;
    return wait_first_frame() ? g_frame_width : 0;
}

static jint JNICALL native_height(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls; (void) handle;
    return wait_first_frame() ? g_frame_height : 0;
}

static jint JNICALL native_step(JNIEnv *env, jclass cls, jlong handle, jintArray pixels) {
    (void) cls; (void) handle;
    pthread_mutex_lock(&g_frame_lock);
    if (!g_frame_new) {
        int ended = g_ended;
        pthread_mutex_unlock(&g_frame_lock);
        return ended ? -1 : 0;
    }
    jsize size = g_frame_width * g_frame_height;
    int copied = (*env)->GetArrayLength(env, pixels) >= size;
    if (copied) (*env)->SetIntArrayRegion(env, pixels, 0, size, (const jint *) g_frame);
    g_frame_new = 0;
    int rows = g_frame_height;
    pthread_mutex_unlock(&g_frame_lock);
    return copied ? rows : 0;
}

static void JNICALL native_joypad(JNIEnv *env, jclass cls, jlong handle, jint id, jboolean down) {
    (void) env; (void) cls; (void) handle;
    if (id < 0 || id >= 16) return;
    if (down) __atomic_or_fetch(&g_joypad, 1u << id, __ATOMIC_RELAXED);
    else __atomic_and_fetch(&g_joypad, ~(1u << id), __ATOMIC_RELAXED);
}

static void JNICALL native_pointer(JNIEnv *env, jclass cls, jlong handle, jint x, jint y, jboolean held) {
    (void) env; (void) cls; (void) handle;
    g_pointer_x = x;
    g_pointer_y = y;
    g_pointer_down = held ? 1 : 0;
    g_mouse_x = x;
    g_mouse_y = y;
    if (held) g_mouse_buttons |= 1; else g_mouse_buttons &= ~1;
}

static void JNICALL native_mouse(JNIEnv *env, jclass cls, jlong handle, jint button, jboolean down) {
    (void) env; (void) cls; (void) handle;
    if (button < 0 || button > 1) return;
    if (down) g_mouse_buttons |= 1 << button; else g_mouse_buttons &= ~(1 << button);
}

static void JNICALL native_wheel(JNIEnv *env, jclass cls, jlong handle, jint steps) {
    (void) env; (void) cls; (void) handle;
    g_mouse_wheel += steps;
}

static void JNICALL native_key(JNIEnv *env, jclass cls, jlong handle, jint keycode, jint character, jboolean down) {
    (void) env; (void) cls; (void) handle;
    if (g_keyboard_callback.callback != NULL) {
        g_keyboard_callback.callback(down ? true : false, (unsigned) keycode, (uint32_t) character, 0);
    }
}

static void JNICALL native_stop(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls; (void) handle;
    g_stop = 1;
}

static const JNINativeMethod g_methods[] = {
    { "nativeStart", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;II)J",
      (void *) native_start },
    { "nativeError", "()Ljava/lang/String;", (void *) native_error },
    { "nativeWidth", "(J)I", (void *) native_width },
    { "nativeHeight", "(J)I", (void *) native_height },
    { "nativeStep", "(J[I)I", (void *) native_step },
    { "nativeJoypad", "(JIZ)V", (void *) native_joypad },
    { "nativePointer", "(JIIZ)V", (void *) native_pointer },
    { "nativeMouse", "(JIZ)V", (void *) native_mouse },
    { "nativeWheel", "(JI)V", (void *) native_wheel },
    { "nativeKey", "(JIIZ)V", (void *) native_key },
    { "nativeStop", "(J)V", (void *) native_stop },
};

/*
 * The isolated runtime binds natives through this (Enginehost's
 * isolated_native_bridge.c), handing over the bundle's entry point class, a
 * subclass of LibretroPlugin; the natives live on LibretroPlugin itself.
 */
__attribute__((visibility("default")))
void enginehost_register_natives(JNIEnv *env, jclass cls) {
    jint count = (jint) (sizeof g_methods / sizeof g_methods[0]);
    for (jclass c = cls; c != NULL; c = (*env)->GetSuperclass(env, c)) {
        if ((*env)->RegisterNatives(env, c, g_methods, count) == 0) return;
        (*env)->ExceptionClear(env);
    }
    LOG(ANDROID_LOG_ERROR, "no LibretroPlugin above the entry point class to bind natives to");
}
