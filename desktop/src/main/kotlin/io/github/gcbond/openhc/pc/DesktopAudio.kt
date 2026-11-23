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
 * PCM 直接写；AAC 先用 [LiveAacDecoder] 解成 PCM 再写（认证时仍向手机声明 PCM 优先）。
 * 播放放在独立线程上，协议线程只把字节放进有界队列：队列满了就丢，绝不阻塞收包。
 */
internal class DesktopAudio(private val log: (String) -> Unit = {}) : AudioStreamListener, AutoCloseable {
    private val queue = LinkedBlockingQueue<ByteArray>(256)
    private var aac: LiveAacDecoder? = null
    private var aacKey: Pair<Int, Int>? = null

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
        if (!packet.playable) return
        val isAac = packet.codec == AudioCodec.AAC || info.codec == AudioCodec.AAC
        var rate = info.sampleRate
        var channels = info.channels
        val bytes = if (isAac) {
            val decoder = aacFor(info) ?: return
            val decoded = runCatching { decoder.decode(packet.data, packet.offset, packet.length) }.getOrElse {
                log("AAC 解码失败：${it.javaClass.simpleName}：${it.message}")
                return
            }
            if (decoded.isEmpty()) return
            rate = decoder.sampleRate
            channels = decoder.channelCount
            decoded
        } else {
            ByteArray(packet.length).also { System.arraycopy(packet.data, packet.offset, it, 0, packet.length) }
        }
        if (!queue.offer(bytes)) {
            dropped++
            if (dropped % 100 == 1L) log("声音队列满，丢弃 ${dropped} 包（电脑太慢或解码卡住）")
            return
        }
        val key = rate to channels.coerceIn(1, 2)
        if (formatKey != key) {
            formatKey = key
            queue.offer(BYTEARRAY_MARKER)
        }
    }

    /** 按会话参数建（或复用）AAC 解码器。 */
    private fun aacFor(info: io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo): LiveAacDecoder? {
        val channels = info.channels.coerceIn(1, 2)
        val key = info.sampleRate to channels
        if (aac != null && aacKey == key) return aac
        runCatching { aac?.close() }
        aac = runCatching { LiveAacDecoder(info.sampleRate, channels) }
            .onFailure { log("AAC 解码器建不起来：${it.message}") }
            .getOrNull()
        aacKey = key
        return aac
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
        runCatching { aac?.close() }
        aac = null
        closeLine()
    }

    private companion object {
        val BYTEARRAY_MARKER = ByteArray(0)
    }
}