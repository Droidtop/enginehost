/*
 * Writing sound into an isolated runtime's audio ring
 * (EngineHost.isolatedAudioBuffer() in plugin-api; docs/engine-sandbox.md
 * "Audio"). An isolated process cannot open an audio output, so the host
 * plays what the engine writes here: 16-bit stereo PCM at
 * EngineHost.isolatedAudioSampleRate(), behind a 16-byte header of write
 * position, read position, capacity and a reserved word (little-endian
 * uint32 each). Positions are byte counts that only grow; this side only
 * ever advances the write position.
 *
 * Copied verbatim from Enginehost's plugin-native/ directory into each
 * plugin that needs it; change it there.
 */
#ifndef ENGINEHOST_AUDIO_RING_H
#define ENGINEHOST_AUDIO_RING_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct enginehost_audio_ring {
    uint8_t *base;
    size_t mapped;
    uint32_t capacity;
};

/* Maps the ring behind `fd` (the descriptor stays the caller's). 0, or a negative errno. */
int enginehost_audio_ring_open(struct enginehost_audio_ring *ring, int fd);

/* Whole stereo frames the host has not played yet, and room for more. */
uint32_t enginehost_audio_ring_buffered_frames(const struct enginehost_audio_ring *ring);
uint32_t enginehost_audio_ring_free_frames(const struct enginehost_audio_ring *ring);

/* Writes up to `frames` interleaved stereo frames; answers how many fitted. */
uint32_t enginehost_audio_ring_write(struct enginehost_audio_ring *ring, const int16_t *stereo, uint32_t frames);

void enginehost_audio_ring_close(struct enginehost_audio_ring *ring);

#ifdef __cplusplus
}
#endif

#endif
