package app.auralynx.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.auralynx.core.common.DefaultDispatcher
import app.auralynx.core.dsp.Levels
import app.auralynx.core.dsp.SpectralNoiseReducer
import app.auralynx.core.dsp.VoiceEnhancer
import app.auralynx.core.dsp.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/** Offline processing and export of recordings. */
class AudioPostProcessor @Inject constructor(@DefaultDispatcher private val cpu: CoroutineDispatcher) {

    /** Writes a processed copy next to [input] and returns it. The original is kept. */
    suspend fun process(input: File, reduceNoise: Boolean, enhanceVoice: Boolean, normalize: Boolean): File = withContext(cpu) {
        val audio = input.inputStream().use { Wav.read(it) }
        var samples = audio.samples
        if (reduceNoise) samples = SpectralNoiseReducer().process(samples)
        if (enhanceVoice) VoiceEnhancer(audio.sampleRate.toDouble()).process(samples)
        if (normalize) Levels.normalize(samples, -1.0)
        val out = File(input.parentFile, input.nameWithoutExtension + "-processed.wav")
        out.outputStream().use { Wav.write(it, Wav.Audio(audio.sampleRate, audio.channels, samples)) }
        out
    }

    /** Encodes a WAV file to AAC-LC in an MPEG-4 container using the platform encoder. */
    suspend fun exportAac(input: File, output: File, bitrate: Int = 128_000): File = withContext(cpu) {
        val audio = input.inputStream().use { Wav.read(it) }
        val pcm = Wav.toPcm16(audio.samples)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, audio.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var offset = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        val bytesPerSecond = audio.sampleRate * audio.channels * 2
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(i)!!
                        val n = minOf(buf.remaining(), pcm.size - offset)
                        val pts = offset * 1_000_000L / bytesPerSecond
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            buf.order(ByteOrder.LITTLE_ENDIAN).put(pcm, offset, n)
                            codec.queueInputBuffer(i, 0, n, pts, 0); offset += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (track >= 0) muxer.stop() }; muxer.release()
        }
        output
    }
}
