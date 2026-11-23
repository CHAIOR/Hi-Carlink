package io.github.gcbond.openhc.pc

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin

/**
 * 声音输出自检：不连手机，直接往系统默认输出设备写 1 秒 440Hz 正弦（16 位小端），
 * 确认 Java Sound 这条链路（SourceDataLine）在这台电脑上能出声。
 */
fun main() {
    val rate = 48000
    val channels = 2
    val seconds = 1.0
    val frames = (rate * seconds).toInt()
    val pcm = ByteArray(frames * channels * 2)
    var i = 0
    for (n in 0 until frames) {
        val v = (sin(2 * PI * 440.0 * n / rate) * 8000).toInt()
        for (c in 0 until channels) {
            pcm[i++] = (v and 0xFF).toByte()
            pcm[i++] = ((v shr 8) and 0xFF).toByte()
        }
    }
    val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
    try {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format, rate / 10 * channels * 2)
        line.start()
        line.write(pcm, 0, pcm.size)
        line.drain()
        line.stop()
        line.close()
        println("声音输出自检通过（${rate}Hz ${channels} 声道，1 秒 440Hz）")
    } catch (e: Exception) {
        println("声音输出自检失败：${e.javaClass.simpleName}：${e.message}")
    }
}