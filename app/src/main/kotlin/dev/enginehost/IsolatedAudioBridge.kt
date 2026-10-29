package dev.enginehost

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The isolated launch's audio, end to end (docs/engine-sandbox.md
 * "Audio"). An isolated process cannot reach AudioFlinger to open its
 * own output -- dq-sandbox-03 found CatSystem2 hanging there forever,
 * not merely failing, which is why [IsolatedRuntimeHost]'s own launch
 * now has a timeout regardless of this fix. The host opens the real
 * output instead and reads what the isolated side renders into a shared
 * ring buffer it never gets to see as a real audio device at all.
 *
 * Layout, matching the plugin-api contract on [EngineHost.isolatedAudioBuffer]:
 * a 16-byte header (write position, read position, capacity, reserved,
 * each a little-endian uint32) followed by [RING_CAPACITY] bytes of ring
 * data. The plugin only ever advances the write position; this class
 * only ever advances the read position. Both are byte offsets that only
 * increase, addressed into the ring by `% capacity`, which stays continuous
 * across the 32-bit counters' wrap only for a power-of-two capacity: the
 * native side refuses any other (plugin-native/enginehost_audio_ring.c), and
 * [RING_CAPACITY] is checked to be one.
 */
internal class IsolatedAudioBridge private constructor(
    private val ownedFd: FileDescriptor,
    val sampleRate: Int,
) {
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile private var playing = false

    /** A descriptor the isolated service can pass on to its plugin; a fresh dup each call, closed by whoever receives it. */
    fun pluginSideBuffer(): ParcelFileDescriptor = ParcelFileDescriptor.dup(ownedFd)

    /**
     * pread/pwrite straight on [ownedFd], not a mapped ByteBuffer: a
     * Java NIO FileChannel's read/write capability comes from how it was
     * opened (FileInputStream -> read-only, FileOutputStream -> write-
     * only), never from the underlying fd's own O_RDWR mode, so
     * FileChannel.map(READ_WRITE, ...) on a channel built either way
     * throws NonWritableChannelException regardless of memfd_create's
     * own mode (dq-sandbox-04, API 34). Os.pread/pwrite operate on the
     * fd directly at the syscall level and are unaffected by that Java
     * wrapper distinction.
     */
    private fun readHeaderInt(offset: Int): Int {
        val bytes = ByteArray(4)
        preadFully(offset, bytes, 0, 4)
        return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).int
    }

    private fun writeHeaderInt(offset: Int, value: Int) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()
        pwriteFully(offset, bytes, 0, 4)
    }

    private fun preadFully(offset: Int, dst: ByteArray, dstOff: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = Os.pread(ownedFd, dst, dstOff + done, length - done, (offset + done).toLong())
            if (n <= 0) break
            done += n
        }
    }

    private fun pwriteFully(offset: Int, src: ByteArray, srcOff: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = Os.pwrite(ownedFd, src, srcOff + done, length - done, (offset + done).toLong())
            if (n <= 0) break
            done += n
        }
    }

    fun startPlayback() {
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBufferBytes, RING_CAPACITY)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        // Prime the track with real silence before play(): a MODE_STREAM
        // track started with nothing ever written has no guarantee its
        // native buffer holds zeros, and on this device it doesn't -- the
        // burst of static heard at every launch was that uninitialized
        // buffer being played back before the isolated side had written a
        // single frame. Writing a full silent buffer (sized to the track's
        // own buffer, not the possibly-negative getMinBufferSize error
        // code) first forces the HAL to actually mix zeros for however long
        // it takes the ring reader below to catch up with real audio.
        track.write(ByteArray(bufferBytes), 0, bufferBytes)
        track.play()
        audioTrack = track
        playing = true
        val thread = Thread({
            val chunk = ByteArray(4096)
            // An empty ring backs off, 1 ms doubling to IDLE_MAX_MS, and the first sound after silence
            // starts it over. A fixed 5 ms sleep spun the reader 200 times a second through every
            // silence, a whole menu's worth (Droidtop/tracker#768).
            var idleMs = 1L
            while (playing) {
                val writePos = readHeaderInt(OFFSET_WRITE_POS)
                val readPos = readHeaderInt(OFFSET_READ_POS)
                val available = writePos - readPos
                if (available <= 0) {
                    Thread.sleep(idleMs)
                    idleMs = minOf(idleMs * 2, IDLE_MAX_MS)
                    continue
                }
                idleMs = 1L
                val toRead = minOf(available, chunk.size)
                val start = readPos.toRingOffset()
                if (start + toRead <= RING_CAPACITY) {
                    preadFully(HEADER_SIZE + start, chunk, 0, toRead)
                } else {
                    val first = RING_CAPACITY - start
                    preadFully(HEADER_SIZE + start, chunk, 0, first)
                    preadFully(HEADER_SIZE, chunk, first, toRead - first)
                }
                track.write(chunk, 0, toRead)
                writeHeaderInt(OFFSET_READ_POS, readPos + toRead)
            }
        }, "enginehost-isolated-audio")
        playbackThread = thread
        thread.start()
    }

    private fun Int.toRingOffset(): Int {
        val offset = this % RING_CAPACITY
        return if (offset < 0) offset + RING_CAPACITY else offset
    }

    fun close() {
        playing = false
        playbackThread?.let { runCatching { it.join(500) } }
        playbackThread = null
        audioTrack?.let { runCatching { it.stop() }; runCatching { it.release() } }
        audioTrack = null
        runCatching { Os.close(ownedFd) }
    }

    companion object {
        private const val HEADER_SIZE = 16
        private const val OFFSET_WRITE_POS = 0
        private const val OFFSET_READ_POS = 4
        private const val OFFSET_CAPACITY = 8
        private const val RING_CAPACITY = 32 * 1024

        /** The longest an empty audio ring waits before looking again; about one AudioTrack period. */
        private const val IDLE_MAX_MS = 16L
        private const val TOTAL_SIZE = HEADER_SIZE + RING_CAPACITY
        private const val TAG = "enginehost-isolated-audio"

        init {
            check(RING_CAPACITY and (RING_CAPACITY - 1) == 0) { "The audio ring's capacity must be a power of two" }
        }

        /**
         * Null when this device cannot give the host a usable output
         * rate, is older than API 30 ([Os.memfd_create] has no Java
         * binding below it -- same gate [ownedCopy] in
         * IsolatedRuntimeService.kt uses for the same reason), or the
         * shared region itself cannot be made -- a game still plays,
         * silently, exactly as when no audio device is available today.
         */
        fun create(): IsolatedAudioBridge? {
            if (Build.VERSION.SDK_INT < 30) return null
            return try {
                val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                    .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                val fd = Os.memfd_create("enginehost-isolated-audio", 0)
                Os.ftruncate(fd, TOTAL_SIZE.toLong())
                val bridge = IsolatedAudioBridge(fd, rate)
                bridge.writeHeaderInt(OFFSET_WRITE_POS, 0)
                bridge.writeHeaderInt(OFFSET_READ_POS, 0)
                bridge.writeHeaderInt(OFFSET_CAPACITY, RING_CAPACITY)
                bridge
            } catch (e: Exception) {
                Log.w(TAG, "could not set up isolated audio; the game will play silently", e)
                null
            }
        }

        private const val DEFAULT_SAMPLE_RATE = 48_000
    }
}
