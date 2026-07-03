package com.martin.speechtranslator.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Result of one push-to-talk capture: a complete in-memory WAV file plus stats. */
data class RecordedAudio(
    val wavBytes: ByteArray,
    val durationMs: Long,
    val rms: Double,
)

/**
 * Captures mic audio as 16 kHz mono 16-bit PCM and returns it wrapped in a WAV container.
 * WHY: LiteRT-LM's audio input expects a mono .wav clip (max 30 s), 16 kHz is the
 * standard rate for Gemma speech input.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 30_000L
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_PCM_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_DURATION_MS / 1000).toInt()
    }

    private var record: AudioRecord? = null
    private var readerThread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var recording = false

    /** Caller must hold RECORD_AUDIO permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        pcm.reset()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE) // ~0.5 s buffer headroom
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        record = rec
        recording = true
        rec.startRecording()
        readerThread = Thread {
            val buf = ByteArray(4096)
            while (recording && pcm.size() < MAX_PCM_BYTES) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
            }
        }.also { it.start() }
    }

    fun stop(): RecordedAudio {
        recording = false
        readerThread?.join(1000)
        readerThread = null
        record?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        record = null
        val data = pcm.toByteArray()
        return RecordedAudio(
            wavBytes = wrapInWav(data),
            durationMs = data.size * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE),
            rms = rmsOfPcm16(data),
        )
    }

    private fun rmsOfPcm16(data: ByteArray): Double {
        if (data.size < 2) return 0.0
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        return sqrt(sum / count) / Short.MAX_VALUE
    }

    /** Prepends a standard 44-byte RIFF/WAVE header (PCM, mono, 16-bit, 16 kHz). */
    private fun wrapInWav(pcmData: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        val out = ByteArrayOutputStream(44 + pcmData.size)
        fun writeIntLE(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xFF) }
        fun writeShortLE(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray())
        writeIntLE(36 + pcmData.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(16)              // PCM chunk size
        writeShortLE(1)             // audio format: PCM
        writeShortLE(1)             // channels: mono
        writeIntLE(SAMPLE_RATE)
        writeIntLE(byteRate)
        writeShortLE(BYTES_PER_SAMPLE) // block align
        writeShortLE(16)            // bits per sample
        out.write("data".toByteArray())
        writeIntLE(pcmData.size)
        out.write(pcmData)
        return out.toByteArray()
    }
}
