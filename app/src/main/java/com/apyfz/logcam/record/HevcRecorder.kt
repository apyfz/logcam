package com.apyfz.logcam.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.apyfz.logcam.camera.RecordingProfile
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware HEVC Main10 encoder writing an MP4, fed by the camera through a Surface.
 *
 * Surface input means frames travel sensor to ISP to encoder without ever being copied into app
 * memory, which is what makes 4K30 10-bit sustainable on battery rather than a thermal event.
 */
class HevcRecorder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val profile: RecordingProfile,
    private val orientationHint: Int,
    private val withAudio: Boolean,
) {

    private lateinit var codec: MediaCodec
    private lateinit var gate: MuxerGate
    private var audio: AudioTrackRecorder? = null

    private var track = -1
    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    private val finished = CountDownLatch(1)

    /** The surface the camera renders into. Valid between [prepare] and the end of [stop]. */
    lateinit var inputSurface: Surface
        private set

    private val info = MediaCodec.BufferInfo()

    @Volatile
    var frameCount: Long = 0L
        private set

    private var firstPtsUs = -1L
    private var lastPtsUs = 0L

    /** Recorded duration in milliseconds, from frame timestamps rather than the wall clock. */
    val durationMs: Long
        get() = if (firstPtsUs < 0) 0 else (lastPtsUs - firstPtsUs) / 1000

    fun prepare(output: FileDescriptor) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
            .apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )

                // 10-bit is the whole point. An 8-bit log file bands the instant a LUT stretches it.
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                )
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
                )

                // Written into the bitstream as CICP, so the file describes itself to an editor
                // rather than leaving the importer to guess.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, profile.colorStandard)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, profile.colorTransfer)
                setInteger(MediaFormat.KEY_COLOR_RANGE, profile.colorRange)
            }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()

        gate = MuxerGate(output, orientationHint, expectedTracks = if (withAudio) 2 else 1)
        if (withAudio) audio = AudioTrackRecorder(gate)
    }

    fun start() {
        check(!started.get()) { "recorder already started" }
        codec.start()
        started.set(true)
        audio?.start()
        Thread(::drainLoop, "hevc-drain").start()
    }

    /**
     * Ends the recording and finalises the container, blocking until the file is closed.
     *
     * `signalEndOfInputStream` lets the encoder flush the frames still in flight; stopping the codec
     * outright would truncate the tail of the take. Waiting for [finished] matters because the
     * caller closes the file descriptor next, and doing that under the muxer produces a corrupt MP4.
     */
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        if (!started.get()) {
            cleanUp()
            return
        }
        audio?.stop()
        runCatching { codec.signalEndOfInputStream() }
        if (!finished.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "encoder did not drain within 5s; finalising anyway")
            cleanUp()
        }
    }

    private fun drainLoop() {
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        track = gate.addTrack(codec.outputFormat)

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && !isConfig && gate.write(track, buffer, info)) {
                            frameCount++
                            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                            lastPtsUs = info.presentationTimeUs
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "encoder drain failed", t)
        } finally {
            cleanUp()
        }
    }

    private fun cleanUp() {
        if (!cleanedUp.compareAndSet(false, true)) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        gate.release()
        runCatching { inputSurface.release() }
        finished.countDown()
    }

    companion object {
        private const val TAG = "HevcRecorder"
        private const val TIMEOUT_US = 10_000L
    }
}
