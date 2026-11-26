@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.reader

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Playing Opus on tablets whose Android has no Opus decoder (the Onyx NoteAir5C has none): an audio renderer for
 * the player with Concentus (a Java port of libopus) inside, modelled on Media3's own libopus extension.
 * The player keeps using Android's decoder where there is one; this one is only chosen when there is not.
 */

/** Opus packets to 16-bit PCM at 48 kHz, mono or stereo. */
class ConcentusOpusDecoder(initializationData: List<ByteArray>, initialInputBufferSize: Int) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        // empty slots: SimpleDecoder fills them with createInputBuffer() and createOutputBuffer()
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(16) as Array<DecoderInputBuffer>),
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<SimpleDecoderOutputBuffer>(16) as Array<SimpleDecoderOutputBuffer>)
    ) {

    val channelCount: Int
    private val preSkipSamples: Int
    private val seekPreRollSamples: Int
    private var decoder: org.concentus.OpusDecoder
    private var skipSamples: Int
    private val pcm: ShortArray

    init {
        val header = initializationData.firstOrNull() ?: throw DecoderException("No Opus header")
        if (header.size < 19) throw DecoderException("Invalid Opus header")
        channelCount = header[9].toInt() and 0xff
        if (channelCount !in 1..2 || (header[18].toInt() and 0xff) != 0 && channelCount > 2) throw DecoderException("Opus with $channelCount channels is not supported")
        preSkipSamples = if (initializationData.size == 3) nanosToSamples(initializationData[1]) else (header[10].toInt() and 0xff) or ((header[11].toInt() and 0xff) shl 8)
        seekPreRollSamples = if (initializationData.size == 3) nanosToSamples(initializationData[2]) else 3840
        skipSamples = preSkipSamples
        decoder = org.concentus.OpusDecoder(SAMPLE_RATE, channelCount)
        pcm = ShortArray(5760 * channelCount)   // the longest packet: 120 ms at 48 kHz
        setInitialInputBufferSize(initialInputBufferSize)
    }

    private fun nanosToSamples(b: ByteArray): Int =
        (ByteBuffer.wrap(b).order(ByteOrder.nativeOrder()).long * SAMPLE_RATE / C.NANOS_PER_SECOND).toInt()

    override fun getName() = "concentus-opus"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("Unexpected Opus decode error", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        if (reset) {
            decoder.resetState()
            // at the start, the header's pre-skip; after a seek, the pre-roll
            skipSamples = if (inputBuffer.timeUs == 0L) preSkipSamples else seekPreRollSamples
        }
        val data = inputBuffer.data ?: return null
        val packet = ByteArray(data.limit()).also { data.position(0); data.get(it) }
        val samples = try {
            decoder.decode(packet, 0, packet.size, pcm, 0, 5760, false)
        } catch (t: Throwable) {
            return DecoderException("Opus decode error: ${t.message}", t)
        }
        val bytes = samples * channelCount * 2
        val out = outputBuffer.init(inputBuffer.timeUs, bytes).order(ByteOrder.nativeOrder())
        out.asShortBuffer().put(pcm, 0, samples * channelCount)
        out.position(0); out.limit(bytes)
        if (skipSamples > 0) {
            val skipBytes = skipSamples * channelCount * 2
            if (bytes <= skipBytes) {
                skipSamples -= samples
                outputBuffer.shouldBeSkipped = true
                out.position(bytes)
            } else {
                skipSamples = 0
                out.position(skipBytes)
            }
        }
        return null
    }

    companion object { const val SAMPLE_RATE = 48_000 }
}

/** The renderer the player uses for Opus when Android's decoders cannot. */
class ConcentusOpusRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<ConcentusOpusDecoder>(handler, listener, sink) {

    override fun getName() = "ConcentusOpusRenderer"

    override fun supportsFormatInternal(format: Format): Int = when {
        !MimeTypes.AUDIO_OPUS.equals(format.sampleMimeType, ignoreCase = true) -> C.FORMAT_UNSUPPORTED_TYPE
        format.channelCount > 2 -> C.FORMAT_UNSUPPORTED_SUBTYPE
        !sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, format.channelCount, ConcentusOpusDecoder.SAMPLE_RATE)) -> C.FORMAT_UNSUPPORTED_SUBTYPE
        format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
        else -> C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): ConcentusOpusDecoder =
        ConcentusOpusDecoder(format.initializationData, if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else 960 * 6)

    override fun getOutputFormat(decoder: ConcentusOpusDecoder): Format =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, decoder.channelCount, ConcentusOpusDecoder.SAMPLE_RATE)
}

/** Android's own renderers first, then the Opus one, which the player only picks when they cannot play the file. */
class AudiobookRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out.add(ConcentusOpusRenderer(eventHandler, eventListener, audioSink))
    }
}
