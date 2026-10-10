/*
 * plugin-native/enginehost_audio_ring.c against a ring laid out the way
 * IsolatedAudioBridge lays it out, with the host's side of it (reading and
 * advancing the read position) done here by hand. Run by run.sh.
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "enginehost_audio_ring.h"

static int g_failures;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); \
        g_failures++; \
    } \
} while (0)

static void put_u32(int fd, off_t offset, uint32_t value) {
    uint8_t bytes[4] = { value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff, value >> 24 };
    if (pwrite(fd, bytes, 4, offset) != 4) abort();
}

static uint32_t get_u32(int fd, off_t offset) {
    uint8_t bytes[4];
    if (pread(fd, bytes, 4, offset) != 4) abort();
    return bytes[0] | bytes[1] << 8 | bytes[2] << 16 | (uint32_t) bytes[3] << 24;
}

int main(void) {
    char path[] = "/tmp/enginehost-ring-XXXXXX";
    int fd = mkstemp(path);
    if (fd < 0) return 2;
    unlink(path);
    const uint32_t capacity = 64; /* 16 frames */
    if (ftruncate(fd, 16 + capacity) != 0) return 2;
    put_u32(fd, 8, capacity);
    /* Positions near the top of uint32, so the writer has to wrap them too. */
    put_u32(fd, 0, 0xFFFFFFC0u);
    put_u32(fd, 4, 0xFFFFFFC0u);

    struct enginehost_audio_ring ring;
    CHECK(enginehost_audio_ring_open(&ring, fd) == 0);
    CHECK(enginehost_audio_ring_free_frames(&ring) == 16);

    int16_t samples[2 * 20];
    for (int i = 0; i < 40; i++) samples[i] = (int16_t) (i + 1);
    CHECK(enginehost_audio_ring_write(&ring, samples, 20) == 16);
    CHECK(enginehost_audio_ring_free_frames(&ring) == 0);
    CHECK(enginehost_audio_ring_write(&ring, samples, 1) == 0);
    CHECK(get_u32(fd, 0) == 0xFFFFFFC0u + 64);

    /* The host plays five frames: the writer gets their room back, after the wrap. */
    int16_t played[10];
    uint32_t read = get_u32(fd, 4);
    uint32_t start = read % capacity;
    if (pread(fd, played, 20, 16 + start) != 20) return 2;
    CHECK(played[0] == 1 && played[9] == 10);
    put_u32(fd, 4, read + 20);
    CHECK(enginehost_audio_ring_buffered_frames(&ring) == 11);
    CHECK(enginehost_audio_ring_write(&ring, samples + 32, 4) == 4);
    /* Frame 17 (samples 33, 34) landed where frame 1 was. */
    int16_t wrapped[2];
    if (pread(fd, wrapped, 4, 16 + start) != 4) return 2;
    CHECK(wrapped[0] == 33 && wrapped[1] == 34);
    enginehost_audio_ring_close(&ring);
    close(fd);
    if (g_failures == 0) printf("audio ring: all checks passed\n");
    return g_failures == 0 ? 0 : 1;
}
