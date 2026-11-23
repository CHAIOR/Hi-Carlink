package io.github.gcbond.openhc.pc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.javacpp.BytePointer

/**
 * AAC 解码（avcodec 直解）：手机把声音编成 AAC 时用。
 *
 * 一包 RTP 载荷可能带 ADTS 头，也可能带；首次见到 ADTS 头时按它重建 `AudioSpecificConfig` 作为 extradata
 * （AAC 解码器需要它才能解没有 ADTS 的裸帧）。输出统一是 16 位小端交错 PCM，直接进 Java Sound。
 */
internal class LiveAacDecoder(
    sampleRate: Int,
    channels: Int,
) : AutoCloseable {
    private val codec = avcodec_find_decoder(AV_CODEC_ID_AAC)
        ?: throw IllegalStateException("ffmpeg 里没有 AAC 解码器")
    private val ctx: AVCodecContext = avcodec_alloc_context3(codec)
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var extradata: BytePointer? = null
    private var configSet = false
    private var outputRate = sampleRate
    private var outputChannels = channels
    private val layout = AVChannelLayout()

    /** 解出来一帧对应的采样数与声道（播放端用来开数据线）。 */
    val sampleRate: Int get() = if (ctx.sample_rate() > 0) ctx.sample_rate() else outputRate
    val channelCount: Int get() = if (ctx.ch_layout().nb_channels() > 0) ctx.ch_layout().nb_channels() else outputChannels

    init {
        ctx.sample_rate(sampleRate)
        av_channel_layout_default(layout, channels)
        ctx.ch_layout(layout)
        setExtradata(AacConfig.audioSpecificConfig(sampleRate, channels))
        check(avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) >= 0) { "打开 AAC 解码器失败" }
    }

    private fun setExtradata(bytes: ByteArray) {
        val ptr = BytePointer(bytes.size.toLong())
        ptr.put(bytes, 0, bytes.size)
        ptr.position(0)
        extradata = ptr
        ctx.extradata(ptr)
        ctx.extradata_size(bytes.size)
        configSet = true
    }

    /** 喂一个载荷（可带 ADTS 头），返回 16 位小端交错 PCM；没有解出东西时返回空数组。 */
    fun decode(payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val adts = AacConfig.parseAdts(payload, offset, length)
        val from: Int
        val to: Int
        if (adts != null) {
            if (!configSet || adts.sampleRate != outputRate || adts.channelConfig != outputChannels) {
                outputRate = adts.sampleRate
                outputChannels = if (adts.channelConfig in 1..2) adts.channelConfig else 2
                setExtradata(adts.audioSpecificConfig())
            }
            from = offset + adts.headerLength
            to = offset + adts.frameLength
        } else {
            from = offset
            to = offset + length
        }
        val size = to - from
        if (size <= 0) return ByteArray(0)

        val out = java.io.ByteArrayOutputStream()
        av_new_packet(packet, size)
        packet.data().position(0).put(payload, from, size)
        packet.size(size)
        if (avcodec_send_packet(ctx, packet) < 0) {
            av_packet_unref(packet)
            return ByteArray(0)
        }
        while (true) {
            if (avcodec_receive_frame(ctx, frame) < 0) break
            out.write(convertFrame(frame))
            av_frame_unref(frame)
        }
        av_packet_unref(packet)
        return out.toByteArray()
    }

    private fun convertFrame(f: AVFrame): ByteArray {
        val ch = if (f.ch_layout().nb_channels() > 0) f.ch_layout().nb_channels() else channelCount
        val n = f.nb_samples()
        if (ch <= 0 || n <= 0) return ByteArray(0)
        val fmt = f.format()
        val out = ByteArray(n * ch * 2)
        var o = 0
        when (fmt) {
            AV_SAMPLE_FMT_FLTP -> {
                for (i in 0 until n) {
                    for (c in 0 until ch) {
                        val v = f.data(c).asByteBuffer().asFloatBuffer().get(i)
                        val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                        out[o++] = (s and 0xFF).toByte()
                        out[o++] = ((s shr 8) and 0xFF).toByte()
                    }
                }
            }
            AV_SAMPLE_FMT_FLT -> {
                val fb = f.data(0).asByteBuffer().asFloatBuffer()
                for (i in 0 until n * ch) {
                    val s = (fb.get(i) * 32767f).toInt().coerceIn(-32768, 32767)
                    out[o++] = (s and 0xFF).toByte()
                    out[o++] = ((s shr 8) and 0xFF).toByte()
                }
            }
            AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P -> {
                // 16 位：planar 时逐声道取，交错时直接拷。
                if (fmt == AV_SAMPLE_FMT_S16) {
                    f.data(0).get(out, 0, out.size)
                } else {
                    for (i in 0 until n) {
                        for (c in 0 until ch) {
                            val p = f.data(c)
                            val lo = p.get((i * 2).toLong()).toInt() and 0xFF
                            val hi = p.get((i * 2 + 1).toLong()).toInt() and 0xFF
                            out[o++] = lo.toByte()
                            out[o++] = hi.toByte()
                        }
                    }
                }
            }
            else -> return ByteArray(0)
        }
        return out
    }

    override fun close() {
        runCatching { av_frame_free(frame) }
        runCatching { av_packet_free(packet) }
        runCatching { avcodec_free_context(ctx) }
    }
}