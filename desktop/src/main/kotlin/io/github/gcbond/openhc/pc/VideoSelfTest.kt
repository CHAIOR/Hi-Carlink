package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.hisight.HiSightInput
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * 视频管线自检：用 ffmpeg 现场编一段 H.264（带帧号和时间），再按真实收帧的路径（[AccessUnitSink] → 解码线程）喂进
 * [VideoWindow]，几秒后抓窗口画面存成 PNG。用来在没手机的情况下证明「解码 → 上屏」这一段是通的。
 */
fun main() {
    val outPng = Paths.get(if (System.getProperty("openhc.snapshot") != null) System.getProperty("openhc.snapshot") else "video-selftest.png")
    val width = 1280
    val height = 720
    val recorderOut = ByteArrayOutputStream(4 shl 20)
    val recorder = FFmpegFrameRecorder(recorderOut, width, height, 0)
    recorder.format = "h264"
    recorder.frameRate = 30.0
    recorder.videoBitrate = 2_000_000
    recorder.gopSize = 15
    recorder.start()
    val converter = Java2DFrameConverter()
    val color = Color(0x10, 0x40, 0x80)
    for (i in 0 until 60) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
        g.drawString("Hi Carlink PC", 120, 300)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 64)
        g.drawString("frame %03d".format(i), 120, 420)
        g.dispose()
        recorder.record(converter.convert(img))
    }
    recorder.stop()
    recorder.release()
    val clip = recorderOut.toByteArray()
    println("自检：编码完成 ${clip.size} 字节 H.264")

    val window = VideoWindow(width, height, { _: HiSightInput -> }, {})
    window.show()
    // 按 NAL 起始码切开逐个喂：和真实收帧一样，不把一帧从中间截断。
    val bounds = ArrayList<Int>()
    var i = 0
    while (i + 3 < clip.size) {
        if (clip[i].toInt() == 0 && clip[i + 1].toInt() == 0 && clip[i + 2].toInt() == 1) {
            bounds += i
            i += 3
        } else {
            i++
        }
    }
    Thread {
        Thread.sleep(1200)
        // 循环喂 4 秒：模拟真实直播流，数据一直不断，解码器不会因为流断在等待上。
        val until = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < until) {
            for (k in bounds.indices) {
                val start = bounds[k]
                val end = if (k + 1 < bounds.size) bounds[k + 1] else clip.size
                window.feed(clip, start, end - start)
                Thread.sleep(5)
            }
        }
    }.start()

    Thread.sleep(4000)
    val img = window.snapshot()
    if (img != null) {
        ImageIO.write(img, "png", outPng.toFile())
        println("自检：窗口画面已存到 $outPng（${img.width}x${img.height}）")
    } else {
        println("自检：没抓到窗口画面")
    }
    window.close()
    Thread.sleep(500)
    if (Files.exists(outPng)) println("自检通过") else println("自检失败：没有生成截图")
}