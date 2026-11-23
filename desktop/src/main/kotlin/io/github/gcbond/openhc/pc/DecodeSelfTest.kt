package io.github.gcbond.openhc.pc

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 解码器自检：现场编码一段 H.264，再用 FFmpegFrameGrabber 从内存流解，打印解出多少帧。 */
fun main() {
    val width = 1280
    val height = 720
    val out = ByteArrayOutputStream(4 shl 20)
    val rec = FFmpegFrameRecorder(out, width, height, 0)
    rec.format = "h264"
    rec.frameRate = 30.0
    rec.videoBitrate = 2_000_000
    rec.gopSize = 15
    rec.start()
    val conv = Java2DFrameConverter()
    for (i in 0 until 60) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.color = Color(0x10, 0x40, 0x80)
        g.fillRect(0, 0, width, height)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
        g.drawString("Hi Carlink PC", 120, 300)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 64)
        g.drawString("frame %03d".format(i), 120, 420)
        g.dispose()
        rec.record(conv.convert(img))
    }
    rec.stop()
    rec.release()
    val clip = out.toByteArray()
    println("编码完成：${clip.size} 字节")

    val grabber = FFmpegFrameGrabber(ByteArrayInputStream(clip))
    grabber.format = "h264"
    grabber.start()
    println("解码器启动：${grabber.imageWidth}x${grabber.imageHeight}，长度 ${grabber.lengthInFrames}")
    var frames = 0
    while (true) {
        val frame = grabber.grabImage() ?: break
        frames++
        if (frames <= 2) println("第 $frames 帧：${frame.imageWidth}x${frame.imageHeight}")
    }
    grabber.stop()
    grabber.release()
    println("解出 $frames 帧")
    if (frames > 0) println("解码器自检通过") else println("解码器自检失败")
}