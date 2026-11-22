package com.localcaption.app.audio

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streams 16 kHz mono 16-bit PCM audio into a standard WAV file for a transcription
 * session, enabling whole-session offline speaker re-diarization afterwards.
 */
class SessionWavRecorder(
    val file: File,
    private val sampleRate: Int = AudioCaptureSource.SAMPLE_RATE,
) : AutoCloseable {

    private val lock = Any()
    private var output: BufferedOutputStream? = null
    private var totalDataBytes: Long = 0L
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        val fos = FileOutputStream(file)
        val bos = BufferedOutputStream(fos, 32 * 1024)
        bos.write(ByteArray(WAV_HEADER_BYTES))
        output = bos
    }

    /** Appends normalized `[-1f, 1f]` mono samples as little-endian 16-bit PCM. */
    fun append(samples: FloatArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            val out = output ?: return
            if (closed) return
            val bytes = ByteArray(samples.size * 2)
            var offset = 0
            for (sample in samples) {
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[offset++] = (pcm and 0xFF).toByte()
                bytes[offset++] = ((pcm ushr 8) and 0xFF).toByte()
            }
            runCatching {
                out.write(bytes)
                totalDataBytes += bytes.size
            }.onFailure {
                Log.w(TAG, "Failed writing session WAV audio", it)
            }
        }
    }

    /** Flushes buffered bytes and patches the 44-byte RIFF/WAVE header. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching {
                output?.flush()
                output?.close()
            }
            output = null
            runCatching { writeWavHeader(file, totalDataBytes, sampleRate) }
                .onFailure { Log.w(TAG, "Failed finalizing WAV header for $file", it) }
        }
    }

    companion object {
        private const val TAG = "SessionWavRecorder"
        private const val RECORDINGS_DIR = "recordings"
        private const val WAV_HEADER_BYTES = 44
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        fun fileForSession(context: Context, sessionId: Long): File {
            val dir = File(context.applicationContext.filesDir, RECORDINGS_DIR).apply { mkdirs() }
            return File(dir, "session_$sessionId.wav")
        }

        /** Reads a 16-bit mono PCM WAV file into normalized `[-1f, 1f]` float samples. */
        fun readWavToFloats(file: File): FloatArray? {
            if (!file.isFile || file.length() <= WAV_HEADER_BYTES) return null
            return runCatching {
                val allBytes = file.readBytes()
                if (allBytes.size <= WAV_HEADER_BYTES) return null
                val dataOffset = findDataChunkOffset(allBytes) ?: WAV_HEADER_BYTES
                val dataBytes = allBytes.size - dataOffset
                val sampleCount = dataBytes / 2
                if (sampleCount <= 0) return null
                val floats = FloatArray(sampleCount)
                val buf = ByteBuffer.wrap(allBytes, dataOffset, sampleCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    floats[i] = buf.short / 32768f
                }
                floats
            }.onFailure {
                Log.w(TAG, "Unable to read WAV file $file", it)
            }.getOrNull()
        }

        private fun findDataChunkOffset(bytes: ByteArray): Int? {
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(bytes, pos + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                if (id == "data") {
                    return pos + 8
                }
                if (size < 0) break
                pos += 8 + size
            }
            return null
        }

        private fun writeWavHeader(file: File, dataBytes: Long, sampleRate: Int) {
            if (!file.isFile) return
            val byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8)
            val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)
            val riffChunkSize = (36L + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val dataChunkSize = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(riffChunkSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM fmt chunk size
            header.putShort(1) // AudioFormat = PCM
            header.putShort(CHANNELS.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(BITS_PER_SAMPLE.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataChunkSize)

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0L)
                raf.write(header.array())
            }
        }
    }
}
