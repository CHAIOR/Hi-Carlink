package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 声音出口：会话交出的 PCM 包直接塞给系统的 [SourceDataLine]（16 位小端、按手机报的采样率和声道数）。
 *
 * 第一版只放 PCM（认证时向手机声明 PCM 优先）；手机坚持发 AAC 时只记日志，暂时没有声音。
 * 播放放在独立线程上，协议线程只把字节放进有界队列：队列满了就丢，绝不阻塞收包。
 */
internal class DesktopAudio(private val log: (String) -> Unit = {}) : AudioStreamListener, AutoCloseable {
    private val queue = LinkedBlockingQueue<ByteArray>(256)
    @Volatile private var running = true
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var formatKey: Pair<Int, Int>? = null
    private var dropped = 0L

    private val worker = Thread({ playLoop() }, "HiCarlink-Audio").apply {
        isDaemon = true
        start()
    }

    override fun onAudioEvent(event: AudioEvent) {
        log("声音：$event")
    }

    override fun onAudioPacket(packet: AudioPacket) {
        if (!running) return
        val info = packet.stream
        if (packet.codec == AudioCodec.AAC || info.codec == AudioCodec.AAC) {
            // 第一版不接 AAC：手机上把编码设成 PCM 才有声音。
            return
        }
        if (!packet.playable) return
        val bytes = ByteArray(packet.length)
        System.arraycopy(packet.data, packet.offset, bytes, 0, packet.length)
        if (!queue.offer(bytes)) {
            dropped++
            if (dropped % 100 == 1L) log("声音队列满，丢弃 ${dropped} 包（电脑太慢或解码卡住）")
            return
        }
        val key = info.sampleRate to info.channels
        if (formatKey != key) {
            formatKey = key
            // 换采样参数：让播放线程下一轮重建 line。
            queue.offer(BYTEARRAY_MARKER)
        }
    }

    private fun playLoop() {
        while (running) {
            val first = try {
                queue.take()
            } catch (e: InterruptedException) {
                return
            }
            if (first.isEmpty()) {
                continue // 标记
            }
            val key = formatKey ?: continue
            val current = line ?: openLine(key.first, key.second) ?: continue
            try {
                current.write(first, 0, first.size)
            } catch (e: Exception) {
                log("写声音失败：${e.javaClass.simpleName}：${e.message}")
                closeLine()
            }
            // 连着取，攒一批再写，减少系统调用。
            while (true) {
                val next = queue.poll() ?: break
                if (next.isEmpty()) break
                try {
                    current.write(next, 0, next.size)
                } catch (e: Exception) {
                    break
                }
            }
        }
        closeLine()
    }

    private fun openLine(rate: Int, channels: Int): SourceDataLine? = try {
        val format = AudioFormat(
            rate.toFloat(),
            16,
            channels.coerceIn(1, 2),
            true,
            false, // 小端
        )
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val l = AudioSystem.getLine(info) as SourceDataLine
        l.open(format, rate / 10 * 4) // 约 100ms 的缓冲
        l.start()
        line = l
        log("声音输出：${rate}Hz ${channels} 声道")
        l
    } catch (e: Exception) {
        log("打不开声音输出（${rate}Hz/${channels}）：${e.javaClass.simpleName}：${e.message}")
        null
    }

    private fun closeLine() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
    }

    override fun close() {
        running = false
        worker.interrupt()
        closeLine()
    }

    private companion object {
        val BYTEARRAY_MARKER = ByteArray(0)
    }
}