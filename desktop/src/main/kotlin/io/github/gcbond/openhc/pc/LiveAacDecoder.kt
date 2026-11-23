package io.github.gcbond.openhc.pc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
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
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

/**
 * AAC 解码（avcodec 直解）：手机把声音编成 AAC 时用。
 *
 * 两个踩过的原生内存坑（都会让 JVM 直接崩，不是抛异常）：
 * 1. extradata 必须用 `av_malloc` 分配 —— `avcodec_free_context` 会用 ffmpeg 自己的 `av_free` 释放它，
 *    传 Java 分配的内存进去等于让 ffmpeg 释放野指针；
 * 2. 采样数据按指针偏移读（`getFloat`），不要用 `asByteBuffer()`：JavaCPP 的容量不可靠，会越界。
 */
internal class LiveAacDecoder(
    sampleRate: Int,
    channels: Int,
) : AutoCloseable {
    private val codec = avcodec_find_decoder(AV_CODEC_ID_AAC)
        ?: throw IllegalStateException("ffmpeg 里没有 AAC 解码器")
    private val ctx: AVCodecContext = avcodec_alloc_context3(codec)
    private val frame: AVFrame = av_frame_alloc()
    private var extradataRaw: Pointer? = null
    private var configSet = false
    private var outputRate = sampleRate
    private var outputChannels = channels

    val sampleRate: Int get() = if (ctx.sample_rate() > 0) ctx.sample_rate() else outputRate
    val channelCount: Int get() = if (ctx.ch_layout().nb_channels() > 0) ctx.ch_layout().nb_channels() else outputChannels

    init {
        ctx.sample_rate(sampleRate)
        setExtradata(AacConfig.audioSpecificConfig(sampleRate, channels))
        check(avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) >= 0) { "打开 AAC 解码器失败" }
    }

    private fun setExtradata(bytes: ByteArray) {
        extradataRaw?.let { org.bytedeco.ffmpeg.global.avutil.av_free(it) }
        extradataRaw = null
        val raw = org.bytedeco.ffmpeg.global.avutil.av_malloc(bytes.size.toLong())
            ?: throw IllegalStateException("给 AAC 解码器分配 extradata 失败")
        val view = BytePointer(raw)
        view.put(bytes, 0, bytes.size)
        extradataRaw = raw
        ctx.extradata(view)
        ctx.extradata_size(bytes.size)
        configSet = true
    }

    /** 喂一个载荷（可带 ADTS 头），返回 16 位小端交错 PCM；没解出东西时返回空数组。 */
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
        val pkt = av_packet_alloc()
        try {
            av_new_packet(pkt, size)
            pkt.data().position(0).put(payload, from, size)
            pkt.size(size)
            if (avcodec_send_packet(ctx, pkt) < 0) return ByteArray(0)
        } finally {
            av_packet_unref(pkt)
            av_packet_free(pkt)
        }
        while (true) {
            if (avcodec_receive_frame(ctx, frame) < 0) break
            out.write(convertFrame(frame))
            av_frame_unref(frame)
        }
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
                        val v = f.data(c).getFloat((i * 4).toLong())
                        val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                        out[o++] = (s and 0xFF).toByte()
                        out[o++] = ((s shr 8) and 0xFF).toByte()
                    }
                }
            }
            AV_SAMPLE_FMT_FLT -> {
                val plane = f.data(0)
                for (i in 0 until n * ch) {
                    val v = plane.getFloat((i * 4).toLong())
                    val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
                    out[o++] = (s and 0xFF).toByte()
                    out[o++] = ((s shr 8) and 0xFF).toByte()
                }
            }
            AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P -> {
                if (fmt == AV_SAMPLE_FMT_S16) {
                    f.data(0).get(out, 0, out.size)
                } else {
                    for (i in 0 until n) {
                        for (c in 0 until ch) {
                            val plane = f.data(c)
                            out[o++] = plane.get((i * 2).toLong())
                            out[o++] = plane.get((i * 2 + 1).toLong())
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
        runCatching { avcodec_free_context(ctx) }
        extradataRaw = null
    }
}