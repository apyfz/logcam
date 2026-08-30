package com.apyfz.logcam.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Owns the [MediaMuxer] and the rule that governs it: no sample may be written until every track
 * has been added, and the muxer may only be started once.
 *
 * Two encoders finish configuring at different, unpredictable moments. Left to coordinate directly
 * they either start the muxer twice or drop the samples that arrive before the slower one is ready.
 * Putting the rule in one place makes both encoders' jobs trivial — declare a track, then write.
 */
class MuxerGate(
    output: FileDescriptor,
    orientationHint: Int,
    private val expectedTracks: Int,
) {

    private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        .apply { setOrientationHint(orientationHint) }

    private var addedTracks = 0
    private var started = false
    private var released = false

    /** Registers a track and starts the muxer once all expected tracks have arrived. */
    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        check(!started) { "cannot add a track after the muxer has started" }
        val index = muxer.addTrack(format)
        addedTracks++
        if (addedTracks == expectedTracks) {
            muxer.start()
            started = true
        }
        return index
    }

    /**
     * Writes a sample, or drops it if the muxer is not running yet.
     *
     * Dropping is correct rather than lossy: samples produced before every track exists cannot be
     * written to an MP4 at all, and they are the first few frames of a take that has not visibly
     * begun. Returns whether the sample was written.
     */
    @Synchronized
    fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo): Boolean {
        if (!started || released || track < 0 || info.size <= 0) return false
        muxer.writeSampleData(track, buffer, info)
        return true
    }

    /** Finalises the container. Safe to call more than once and after a failed start. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
