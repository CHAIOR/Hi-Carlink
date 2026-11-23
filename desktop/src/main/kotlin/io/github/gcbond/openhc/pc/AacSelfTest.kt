package io.github.gcbond.openhc.pc

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * 声音链路自检：
 * 1. 生成 1 秒 1kHz 正弦波（16kHz 单声道 16 位）；
 * 2. 用 ffmpeg 的 AAC 编码器编成 ADTS；
 * 3. 按 RTP 载荷的样子逐帧喂给 [LiveAacDecoder]；
 * 4. 解出来的 PCM 写成 WAV，统计采样数（应当接近 16000）。
 */
fun main() {
    val rate = 16000
    val channels = 1
    val total = rate
    val pcm = ShortArray(total)
    for (i in 0 until total) {
        pcm[i] = (sin(2 * PI * 1000.0 * i / rate) * 12000).toInt().toShort()
    }

    val adts = ByteArrayOutputStream()
    val rec = FFmpegFrameRecorder(adts, 0, 0, channels)
    rec.format = "adts"
    rec.setAudioCodec(AV_CODEC_ID_AAC)
    rec.setSampleRate(rate)
    rec.setAudioChannels(channels)
    rec.setAudioBitrate(64000)
    rec.start()
    var off = 0
    while (off < total) {
        val n = minOf(1024, total - off)
        val buf = ShortBuffer.allocate(n)
        buf.put(pcm, off, n)
        buf.rewind()
        rec.recordSamples(rate, channels, buf)
        off += n
    }
    rec.stop()
    rec.release()
    val encoded = adts.toByteArray()
    println("编码完成：${encoded.size} 字节 ADTS")

    val frames = ArrayList<ByteArray>()
    var i = 0
    while (i + 7 <= encoded.size) {
        val head = AacConfig.parseAdts(encoded, i, encoded.size - i) ?: break
        frames += encoded.copyOfRange(i, i + head.frameLength)
        i += head.frameLength
    }
    println("切成 ${frames.size} 个 AAC 帧")

    LiveAacDecoder(rate, channels).use { dec ->
        val pcmOut = ByteArrayOutputStream()
        for (f in frames) {
            val chunk = dec.decode(f)
            if (chunk.isNotEmpty()) pcmOut.write(chunk)
        }
        val out = pcmOut.toByteArray()
        val samples = out.size / 2
        println("解出 ${out.size} 字节 PCM，约 $samples 个采样（编码前 $total 个）")
        writeWav(File("aac-selftest.wav"), out, dec.sampleRate, dec.channelCount)
        println("已写出 aac-selftest.wav（${dec.sampleRate}Hz ${dec.channelCount} 声道）")
        if (samples > total * 0.9) println("AAC 解码自检通过") else println("AAC 解码自检失败：采样数太少")
    }
}

private fun writeWav(file: File, pcm: ByteArray, rate: Int, channels: Int) {
    val dataLen = pcm.size
    val byteRate = rate * channels * 2
    val buffer = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray())
    buffer.putInt(36 + dataLen)
    buffer.put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(channels.toShort())
    buffer.putInt(rate)
    buffer.putInt(byteRate)
    buffer.putShort((channels * 2).toShort())
    buffer.putShort(16)
    buffer.put("data".toByteArray())
    buffer.putInt(dataLen)
    buffer.put(pcm)
    file.writeBytes(buffer.array())
}