package com.friend.ios.amuless

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Decodes an Amuless `.bin` recording to a 16 kHz mono 16-bit WAV.
 *
 * `.bin` layout: repeated `[uint32 little-endian length][Opus packet]`; each packet is 20 ms
 * (`opus_fs320`, 16 kHz, mono). Decoding uses Android's built-in Opus decoder (MediaCodec),
 * so no native library is bundled.
 */
object OpusBinDecoder {
    private const val TAG = "Amuless.Opus"
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_US = 20_000L
    private const val MAX_PACKET = 4_000

    /** Returns the decoded duration in milliseconds (0 when the file holds no audio). */
    fun decodeToWav(bin: File, wav: File): Long {
        val codec = MediaCodec.createDecoderByType("audio/opus")
        val resampler = Resampler(SAMPLE_RATE)
        var pcmBytes = 0L
        RandomAccessFile(wav, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(44)) // WAV header, patched at the end
            val sink = { shorts: ShortArray, count: Int ->
                val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) buf.putShort(shorts[i])
                out.write(buf.array(), 0, count * 2)
                pcmBytes += count * 2L
            }
            try {
                codec.configure(opusFormat(), null, null, 0)
                codec.start()
                BufferedInputStream(FileInputStream(bin), 1 shl 16).use { input ->
                    decodeStream(codec, input, resampler, sink)
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
            }
            writeWavHeader(out, pcmBytes)
        }
        return pcmBytes / 2 * 1000 / SAMPLE_RATE
    }

    private fun opusFormat(): MediaFormat {
        val format = MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1)
        // csd-0: OpusHead, csd-1: pre-skip (ns), csd-2: seek pre-roll (ns)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray(Charsets.US_ASCII))
        head.put(1) // version
        head.put(1) // channels
        head.putShort(0) // pre-skip
        head.putInt(SAMPLE_RATE) // input sample rate
        head.putShort(0) // output gain
        head.put(0) // channel mapping family
        format.setByteBuffer("csd-0", ByteBuffer.wrap(head.array()))
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0L).also { it.flip() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).also { it.flip() })
        return format
    }

    private fun decodeStream(
        codec: MediaCodec,
        input: BufferedInputStream,
        resampler: Resampler,
        sink: (ShortArray, Int) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        var inputDone = false
        var outputDone = false
        val header = ByteArray(4)
        var packet = ByteArray(MAX_PACKET)

        // Never block on one side while the other has work: feed every free input buffer, drain every
        // ready output buffer, and only sleep briefly when neither moved.
        while (!outputDone) {
            var progressed = false
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                progressed = true
                val size = readPacket(input, header, packet)
                if (size == EOF) {
                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else if (size == SKIP) {
                    codec.queueInputBuffer(index, 0, 0, pts, 0)
                } else {
                    val buf = codec.getInputBuffer(index)!!
                    buf.clear()
                    buf.put(packet, 0, size)
                    codec.queueInputBuffer(index, 0, size, pts, 0)
                    pts += FRAME_US
                }
            }
            while (!outputDone) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out >= 0) {
                    progressed = true
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(out)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = ShortArray(info.size / 2)
                        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        val (res, n) = resampler.process(shorts)
                        sink(res, n)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                    val f = codec.outputFormat
                    resampler.setInputRate(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    Log.i(TAG, "decoder output format: $f")
                } else {
                    break
                }
            }
            if (!progressed && !outputDone) Thread.sleep(2)
        }
    }

    private const val EOF = -1
    private const val SKIP = -2

    /** Reads one length-prefixed packet into [packet]. EOF on clean end or a truncated tail. */
    private fun readPacket(input: BufferedInputStream, header: ByteArray, packet: ByteArray): Int {
        try {
            readFully(input, header, 4)
        } catch (_: EOFException) {
            return EOF
        }
        val len = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8) or
            ((header[2].toInt() and 0xff) shl 16) or ((header[3].toInt() and 0xff) shl 24)
        if (len < 0 || len > packet.size) return EOF // corrupt: stop at the last good frame
        if (len == 0) return SKIP
        return try {
            readFully(input, packet, len)
            len
        } catch (_: EOFException) {
            EOF
        }
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun writeWavHeader(out: RandomAccessFile, dataBytes: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        out.seek(0)
        out.write(b.array())
    }

    /**
     * The Android Opus decoder always outputs 48 kHz. Windowed-sinc low-pass + decimation to 16 kHz
     * (an integer ratio); other rates pass through a linear resampler.
     */
    private class Resampler(private val targetRate: Int) {
        private var inputRate = 48_000
        private var history = ShortArray(0)
        private var phase = 0.0
        private val taps = 31
        private var kernel = buildKernel(inputRate)

        fun setInputRate(rate: Int) {
            if (rate != inputRate) {
                inputRate = rate
                kernel = buildKernel(rate)
                history = ShortArray(0)
                phase = 0.0
            }
        }

        private fun buildKernel(rate: Int): FloatArray {
            val cutoff = 0.45 * targetRate / rate // fraction of the input rate
            val k = FloatArray(taps)
            val mid = (taps - 1) / 2.0
            var sum = 0.0
            for (i in 0 until taps) {
                val x = i - mid
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val window = 0.54 - 0.46 * kotlin.math.cos(2 * PI * i / (taps - 1))
                k[i] = (sinc * window).toFloat()
                sum += k[i]
            }
            for (i in 0 until taps) k[i] = (k[i] / sum).toFloat()
            return k
        }

        fun process(input: ShortArray): Pair<ShortArray, Int> {
            if (inputRate == targetRate) return Pair(input, input.size)
            val combined = ShortArray(history.size + input.size)
            history.copyInto(combined)
            input.copyInto(combined, history.size)
            val step = inputRate.toDouble() / targetRate
            val out = ShortArray((input.size / step).toInt() + 2)
            var n = 0
            var pos = phase
            while (pos + taps <= combined.size) {
                val base = pos.toInt()
                var acc = 0f
                for (t in 0 until taps) acc += kernel[t] * combined[base + t]
                out[n++] = acc.toInt().coerceIn(-32768, 32767).toShort()
                pos += step
            }
            val consumed = pos.toInt().coerceAtMost(combined.size)
            history = combined.copyOfRange(consumed, combined.size)
            phase = pos - consumed
            return Pair(out, n)
        }
    }
}
