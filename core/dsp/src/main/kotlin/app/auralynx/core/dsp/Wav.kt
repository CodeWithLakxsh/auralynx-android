package app.auralynx.core.dsp

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV encoder/decoder (RIFF, little-endian). */
object Wav {
    data class Audio(val sampleRate: Int, val channels: Int, val samples: FloatArray)

    fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        b.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    fun write(out: OutputStream, audio: Audio) {
        out.write(header(audio.sampleRate, audio.channels, audio.samples.size * 2))
        out.write(toPcm16(audio.samples))
    }

    fun toPcm16(samples: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    fun fromPcm16(bytes: ByteArray, length: Int = bytes.size): FloatArray {
        val b = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(length / 2) { b.short / 32768f }
    }

    fun read(input: InputStream): Audio {
        val data = DataInputStream(input).readBytes()
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WAVE") { "Not a WAV file" }
        var pos = 12; var channels = 1; var rate = 0; var bits = 16
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4); val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                require(b.getShort(pos + 8).toInt() == 1) { "Only PCM WAV is supported" }
                channels = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                require(bits == 16) { "Only 16-bit WAV is supported" }
                val len = minOf(size, data.size - pos - 8)
                return Audio(rate, channels, fromPcm16(data.copyOfRange(pos + 8, pos + 8 + len)))
            }
            pos += 8 + size + (size and 1)
        }
        error("WAV file has no data chunk")
    }
}
