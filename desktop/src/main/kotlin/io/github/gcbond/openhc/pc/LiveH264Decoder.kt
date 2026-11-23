package io.github.gcbond.openhc.pc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
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
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import java.awt.image.BufferedImage

/**
 * 直播用的 H.264 解码：不经过 ffmpeg 的 demuxer（它在阻塞 InputStream 上会卡在探测/跳读），
 * 直接拿 `avcodec` 解按 Annex-B 访问单元切好的字节。
 *
 * 只做 yuv420p → RGB 的转换（手机投屏基本都是这个格式），逐行拷成 ByteArray 再算，避免逐像素 JNI 调用。
 * 单线程使用：[decode] 返回这一帧解出的画面（通常是 1 张）。
 */
internal class LiveH264Decoder : AutoCloseable {
    private val codec = avcodec_find_decoder(AV_CODEC_ID_H264)
        ?: throw IllegalStateException("ffmpeg 里没有 H.264 解码器")
    private val ctx: AVCodecContext = avcodec_alloc_context3(codec)
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var yPlane = ByteArray(0)
    private var uPlane = ByteArray(0)
    private var vPlane = ByteArray(0)
    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(workerCount) { r ->
        Thread(r, "HiCarlink-YUV").apply { isDaemon = true }
    }

    init {
        check(avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) >= 0) { "打开 H.264 解码器失败" }
    }

    /** 喂一个访问单元；返回解出的画面（可能为空）。 */
    fun decode(accessUnit: ByteArray): List<BufferedImage> {
        val result = ArrayList<BufferedImage>(1)
        av_new_packet(packet, accessUnit.size)
        packet.data().position(0).put(accessUnit, 0, accessUnit.size)
        packet.size(accessUnit.size)
        if (avcodec_send_packet(ctx, packet) < 0) {
            av_packet_unref(packet)
            return result
        }
        while (true) {
            if (avcodec_receive_frame(ctx, frame) < 0) break
            val img = toImage(frame)
            if (img != null) result += img
            av_frame_unref(frame)
        }
        av_packet_unref(packet)
        return result
    }

    private fun toImage(f: AVFrame): BufferedImage? {
        val w = f.width()
        val h = f.height()
        if (w <= 0 || h <= 0) return null
        val fmt = f.format()
        if (fmt != AV_PIX_FMT_YUV420P && fmt != AV_PIX_FMT_YUVJ420P) return null
        val yStride = f.linesize(0)
        val uStride = f.linesize(1)
        val vStride = f.linesize(2)
        val ySize = yStride * h
        val uvH = (h + 1) / 2
        val uSize = uStride * uvH
        val vSize = vStride * uvH
        if (yPlane.size < ySize) yPlane = ByteArray(ySize)
        if (uPlane.size < uSize) uPlane = ByteArray(uSize)
        if (vPlane.size < vSize) vPlane = ByteArray(vSize)
        f.data(0).get(yPlane, 0, ySize)
        f.data(1).get(uPlane, 0, uSize)
        f.data(2).get(vPlane, 0, vSize)

        // 直接写 BufferedImage 自己的 int 像素（TYPE_INT_RGB 是单 bank int[]），省一次整帧拷贝。
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = (img.raster.dataBuffer as java.awt.image.DataBufferInt).data
        val chunks = minOf(workerCount, maxOf(1, h / 32))
        if (chunks <= 1) {
            convertRows(0, h, w, yStride, uStride, vStride, out)
        } else {
            val futures = ArrayList<java.util.concurrent.Future<*>>(chunks)
            for (c in 0 until chunks) {
                val y0 = h * c / chunks
                val y1 = h * (c + 1) / chunks
                futures += pool.submit { convertRows(y0, y1, w, yStride, uStride, vStride, out) }
            }
            for (future in futures) {
                try {
                    future.get()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                } catch (e: java.util.concurrent.ExecutionException) {
                    return null
                }
            }
        }
        return img
    }

    /** 把第 [y0, y1) 行从 yuv420p 转成 RGB 写进 [out]；多线程各写各的行，不重叠。 */
    private fun convertRows(
        y0: Int,
        y1: Int,
        w: Int,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        out: IntArray,
    ) {
        for (y in y0 until y1) {
            val yRow = y * yStride
            val uvRow = y / 2
            val uRow = uvRow * uStride
            val vRow = uvRow * vStride
            var idx = y * w
            for (x in 0 until w) {
                val yv = yPlane[yRow + x].toInt() and 0xFF
                val ux = (uPlane[uRow + (x / 2)].toInt() and 0xFF) - 128
                val vx = (vPlane[vRow + (x / 2)].toInt() and 0xFF) - 128
                var r = yv + (1.402 * vx).toInt()
                var g = yv - (0.344136 * ux).toInt() - (0.714136 * vx).toInt()
                var b = yv + (1.772 * ux).toInt()
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[idx++] = (r shl 16) or (g shl 8) or b
            }
        }
    }

    override fun close() {
        runCatching { av_frame_free(frame) }
        runCatching { av_packet_free(packet) }
        runCatching { avcodec_free_context(ctx) }
        pool.shutdownNow()
    }
}