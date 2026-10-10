/*
 * See enginehost_audio_ring.h. The host reads the ring with pread on the
 * same shared file (IsolatedAudioBridge), so the data goes in before the
 * write position moves, and the position is published with release order.
 */
#include "enginehost_audio_ring.h"

#include <errno.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>

#define HEADER_SIZE 16
#define OFFSET_WRITE 0
#define OFFSET_READ 4
#define OFFSET_CAPACITY 8
#define FRAME_BYTES 4

static uint32_t *header_word(const struct enginehost_audio_ring *ring, size_t offset) {
    return (uint32_t *) (void *) (ring->base + offset);
}

int enginehost_audio_ring_open(struct enginehost_audio_ring *ring, int fd) {
    memset(ring, 0, sizeof *ring);
    struct stat info;
    if (fd < 0 || fstat(fd, &info) != 0) return -EBADF;
    if (info.st_size <= HEADER_SIZE) return -EINVAL;
    void *base = mmap(NULL, (size_t) info.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) return -errno;
    ring->base = base;
    ring->mapped = (size_t) info.st_size;
    uint32_t capacity = __atomic_load_n(header_word(ring, OFFSET_CAPACITY), __ATOMIC_ACQUIRE);
    if (capacity == 0 || capacity > ring->mapped - HEADER_SIZE || capacity % FRAME_BYTES != 0) {
        enginehost_audio_ring_close(ring);
        return -EINVAL;
    }
    ring->capacity = capacity;
    return 0;
}

uint32_t enginehost_audio_ring_buffered_frames(const struct enginehost_audio_ring *ring) {
    if (ring->base == NULL) return 0;
    uint32_t write = __atomic_load_n(header_word(ring, OFFSET_WRITE), __ATOMIC_RELAXED);
    uint32_t read = __atomic_load_n(header_word(ring, OFFSET_READ), __ATOMIC_ACQUIRE);
    uint32_t used = write - read;
    if (used > ring->capacity) used = ring->capacity;
    return used / FRAME_BYTES;
}

uint32_t enginehost_audio_ring_free_frames(const struct enginehost_audio_ring *ring) {
    if (ring->base == NULL) return 0;
    return ring->capacity / FRAME_BYTES - enginehost_audio_ring_buffered_frames(ring);
}

uint32_t enginehost_audio_ring_write(struct enginehost_audio_ring *ring, const int16_t *stereo, uint32_t frames) {
    uint32_t room = enginehost_audio_ring_free_frames(ring);
    if (frames > room) frames = room;
    if (frames == 0) return 0;
    uint32_t write = __atomic_load_n(header_word(ring, OFFSET_WRITE), __ATOMIC_RELAXED);
    uint32_t bytes = frames * FRAME_BYTES;
    uint32_t start = write % ring->capacity;
    uint32_t first = ring->capacity - start;
    uint8_t *data = ring->base + HEADER_SIZE;
    const uint8_t *source = (const uint8_t *) stereo;
    if (bytes <= first) {
        memcpy(data + start, source, bytes);
    } else {
        memcpy(data + start, source, first);
        memcpy(data, source + first, bytes - first);
    }
    __atomic_store_n(header_word(ring, OFFSET_WRITE), write + bytes, __ATOMIC_RELEASE);
    return frames;
}

void enginehost_audio_ring_close(struct enginehost_audio_ring *ring) {
    if (ring->base != NULL) munmap(ring->base, ring->mapped);
    memset(ring, 0, sizeof *ring);
}
