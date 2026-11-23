package io.github.gcbond.openhc.pc

import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 直播解码器自检：现场编码 90 帧 H.264，按访问单元喂给 [LiveH264Decoder]，
 * 统计帧数与平均耗时（判断能不能跟上 30fps），并把第一帧存成 PNG 验收画面。
 */
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
    for (i in 0 until 90) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.color = Color(0x10, 0x40, 0x80)
        g.fillRect(0, 0, width, height)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
        g.drawString("Hi Carlink PC", 120, 300)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 72)
        g.drawString("frame %03d".format(i), 120, 430)
        g.dispose()
        rec.record(conv.convert(img))
    }
    rec.stop()
    rec.release()
    val clip = out.toByteArray()
    println("编码完成：${clip.size} 字节")

    val units = splitAccessUnits(clip)
    println("切成 ${units.size} 个访问单元")
    LiveH264Decoder().use { dec ->
        var frames = 0
        var first: BufferedImage? = null
        val times = ArrayList<Long>()
        for (au in units) {
            val t0 = System.nanoTime()
            val imgs = dec.decode(au)
            times += (System.nanoTime() - t0) / 1_000_000
            for (img in imgs) {
                frames++
                if (first == null) first = img
            }
        }
        val avg = if (times.isEmpty()) 0.0 else times.average()
        val max = times.maxOrNull() ?: 0
        println("解出 $frames 帧；平均 ${"%.1f".format(avg)} 毫秒/单元，最慢 $max 毫秒")
        first?.let {
            ImageIO.write(it, "png", File("avcodec-selftest.png"))
            println("第一帧已存到 avcodec-selftest.png（${it.width}x${it.height}）")
        }
        if (frames >= units.size - 5 && avg < 33) println("解码器自检通过（跟得上 30fps）")
        else if (frames > 0) println("解码器能出画面，但性能/完整度要再看看")
        else println("解码器自检失败")
    }
}
/** 按 Annex-B 起始码把 NAL 切开，再按「每个片（slice）一帧、SPS/PPS/AUD 起新帧」合成访问单元。 */
internal fun splitAccessUnits(data: ByteArray): List<ByteArray> {
    val nals = ArrayList<IntArray>()
    var i = 0
    var start = -1
    while (i + 3 <= data.size) {
        val isStart = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
        if (isStart) {
            if (start >= 0) nals += intArrayOf(start, i)
            start = i
            i += 3
        } else {
            i++
        }
    }
    if (start >= 0 && start < data.size) nals += intArrayOf(start, data.size)

    val units = ArrayList<ByteArray>()
    val cur = java.io.ByteArrayOutputStream()
    var hasSlice = false
    for ((a, b) in nals) {
        val header = a + 3
        if (header >= data.size) continue
        val type = data[header].toInt() and 0x1F
        val startsNew = type == 7 || type == 8 || type == 9 || (type in 1..5 && hasSlice)
        if (startsNew && cur.size() > 0) {
            units += cur.toByteArray()
            cur.reset()
            hasSlice = false
        }
        cur.write(data, a, b - a)
        if (type in 1..5) hasSlice = true
    }
    if (cur.size() > 0) units += cur.toByteArray()
    return units
}
