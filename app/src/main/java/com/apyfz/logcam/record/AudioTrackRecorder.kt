package com.apyfz.logcam.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the microphone to AAC and feeds it into the shared [MuxerGate].
 *
 * Deliberately plain: 48 kHz stereo AAC-LC at 192 kbps. Audio is not what this app is for, but a
 * clip with no sound is not usable footage either.
 */
class AudioTrackRecorder(
    private val gate: MuxerGate,
) {

    private var codec: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var track = -1
    private val running = AtomicBoolean(false)
    private val info = MediaCodec.BufferInfo()

    @SuppressLint("MissingPermission")
    fun start() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
            .apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_BYTES)
            }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(BUFFER_BYTES)

        recorder = AudioRecord(
            // CAMCORDER rather than MIC: it selects the microphones oriented with the camera and
            // skips the voice-call processing that MIC applies.
            MediaRecorder.AudioSource.CAMCORDER,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
        ).also { it.startRecording() }

        running.set(true)
        Thread(::pumpLoop, "aac-pump").start()
        Thread(::drainLoop, "aac-drain").start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
    }

    private fun pumpLoop() {
        val encoder = codec ?: return
        val source = recorder ?: return
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (running.get()) {
                val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) continue
                val input = encoder.getInputBuffer(index) ?: continue
                input.clear()
                val read = source.read(buffer, 0, minOf(buffer.size, input.remaining()))
                if (read > 0) {
                    input.put(buffer, 0, read)
                    encoder.queueInputBuffer(index, 0, read, timestampUs(), 0)
                } else {
                    encoder.queueInputBuffer(index, 0, 0, timestampUs(), 0)
                }
            }
            val index = encoder.dequeueInputBuffer(TIMEOUT_US * 10)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index, 0, 0, timestampUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio pump failed", t)
        } finally {
            runCatching { source.stop() }
            runCatching { source.release() }
        }
    }

    private fun drainLoop() {
        val encoder = codec ?: return
        try {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        track = gate.addTrack(encoder.outputFormat)

                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && !isConfig) {
                            gate.write(track, buffer, info)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio drain failed", t)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    /**
     * Timestamps come from the same monotonic clock the camera stamps its frames with, which is
     * what keeps audio and video in sync in the finished file.
     */
    private fun timestampUs(): Long = System.nanoTime() / 1000

    companion object {
        private const val TAG = "AudioTrackRecorder"
        private const val SAMPLE_RATE = 48_000
        private const val BIT_RATE = 192_000
        private const val BUFFER_BYTES = 8192
        private const val TIMEOUT_US = 10_000L
    }
}
