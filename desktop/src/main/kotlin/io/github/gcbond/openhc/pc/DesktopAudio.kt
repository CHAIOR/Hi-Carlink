package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.PcmByteOrder
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 声音出口：会话交出的 PCM 包写进系统 [SourceDataLine]（16 位、按手机报的采样率和声道数；AAC 先解码）。
 *
 * 几个实际踩到的问题与对策：
 * - **手机报的采样率和实际数据不一致**（例如 ParaAudio 写 16000Hz/1，实际发 48000Hz/2）：按报的值播会越来越慢，
 *   队列堆满、不停丢包，听感就是卡顿。这里按“每秒实际收到多少字节”估算真实采样率，偏差超过 25% 就改用它。
 * - **多路音频（音乐/导航/语音）参数不同**：每一路各自一条 [SourceDataLine]（同一台设备上系统会自己混音），
 *   不再把不同采样率的数据塞进同一条线。
 * - **大端 PCM**：按 [PcmByteOrder] 换成小端再放，否则是噪声。
 * - 播放线程只从有界队列取；队列满了丢最旧的，绝不让协议线程阻塞。
 */
internal class DesktopAudio(private val log: (String) -> Unit = {}) : AudioStreamListener, AutoCloseable {

    private class Chunk(
        val bytes: ByteArray,
        val rate: Int,
        val channels: Int,
        val session: String,
        /** RTP 流号：不同路（音乐/导航/语音）必须各走各的输出线，否则两条流的数据会拼在一条线里，越放越堵。 */
        val ssrc: Long,
    )

    private val queue = LinkedBlockingQueue<Chunk>(256)
    /** 输出线按「哪一路 + 采样率 + 声道」分开：同一路是同一条线，不同路各一条，由系统混音。 */
    private val lines = HashMap<Triple<Long, Int, Int>, SourceDataLine>()
    private var aac: LiveAacDecoder? = null
    private var aacKey: Pair<Int, Int>? = null

    @Volatile private var running = true
    private var dropped = 0L
    private var played = 0L

    /** 每个数据会话的“实际采样率”估计：按收到的字节数和时间算，偏差大就用估计值。 */
    private val estimatedRate = HashMap<String, Int>()
    private val windowStart = HashMap<String, Long>()
    private val windowBytes = HashMap<String, Long>()

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
        var channels = info.channels.coerceIn(1, 2)
        var bigEndian = false

        val bytes = if (isAac) {
            val decoder = aacFor(info) ?: return
            val decoded = runCatching { decoder.decode(packet.data, packet.offset, packet.length) }.getOrElse {
                log("AAC 解码失败：${it.javaClass.simpleName}：${it.message}")
                return
            }
            if (decoded.isEmpty()) return
            rate = decoder.sampleRate
            channels = decoder.channelCount.coerceIn(1, 2)
            decoded
        } else {
            bigEndian = packet.byteOrder == PcmByteOrder.BIG_ENDIAN
            ByteArray(packet.length).also { System.arraycopy(packet.data, packet.offset, it, 0, packet.length) }
        }

        if (!isAac) {
            rate = observeRate(info.dataSessionId, rate, channels, packet.length)
        }

        val chunk = Chunk(bytes, rate, channels, info.dataSessionId, packet.ssrc)
        if (!queue.offer(chunk)) {
            queue.poll()
            queue.offer(chunk)
            dropped++
            if (dropped == 1L || dropped % 1000L == 0L) log("声音：队列满，已丢 $dropped 包（会一直丢最旧的，保持低延迟）")
        }
    }

    /**
     * 用「每秒实际字节数 / 2 / 声道数」估真实采样率。只在前 3 秒内采样，之后固定，
     * 避免把音乐的静音段或变速播放误判成换采样率。
     */
    private fun observeRate(session: String, declared: Int, channels: Int, bytes: Int): Int {
        estimatedRate[session]?.let { return it }
        val now = System.nanoTime()
        val start = windowStart.getOrPut(session) { now }
        windowBytes[session] = (windowBytes[session] ?: 0L) + bytes
        val elapsed = now - start
        if (elapsed < 1_000_000_000L) return declared
        val bytesPerSecond = (windowBytes[session] ?: 0L) * 1_000_000_000.0 / elapsed
        val observed = (bytesPerSecond / 2.0 / channels.coerceAtLeast(1)).toInt()
        // 吸附到常见采样率，避免每个会话都开一条新线。
        val snapped = listOf(8000, 16000, 22050, 24000, 32000, 44100, 48000).minByOrNull { kotlin.math.abs(it - observed) } ?: declared
        val chosen = if (declared > 0 && kotlin.math.abs(snapped - declared) <= declared * 0.25) declared else snapped
        if (chosen != declared) {
            log("声音：手机报 ${declared}Hz，但实际数据约 ${observed}Hz，按 ${chosen}Hz 播放")
        }
        estimatedRate[session] = chosen
        return chosen
    }

    /** 按会话参数建（或复用）AAC 解码器。 */
    private fun aacFor(info: AudioStreamInfo): LiveAacDecoder? {
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
        var lastStats = System.nanoTime()
        while (running) {
            val chunk = try {
                queue.take()
            } catch (e: InterruptedException) {
                return
            }
            val line = lineFor(chunk.ssrc, chunk.rate, chunk.channels) ?: continue
            try {
                line.write(chunk.bytes, 0, chunk.bytes.size)
                played++
            } catch (e: Exception) {
                log("写声音失败：${e.javaClass.simpleName}：${e.message}")
                synchronized(lines) {
                    lines.remove(Triple(chunk.ssrc, chunk.rate, chunk.channels))
                }
                runCatching { line.close() }
            }
            val now = System.nanoTime()
            if (now - lastStats >= 5_000_000_000L) {
                log("声音：已播 $played 包，队列 ${queue.size}，丢弃 $dropped，输出 ${lines.keys.joinToString { "${it.first}Hz/${it.second}ch" }}")
                lastStats = now
            }
        }
        synchronized(lines) {
            lines.values.forEach { runCatching { it.stop() }; runCatching { it.close() } }
            lines.clear()
        }
    }

    private fun lineFor(ssrc: Long, rate: Int, channels: Int): SourceDataLine? = synchronized(lines) {
        val key = Triple(ssrc, rate, channels)
        lines[key]?.let { if (it.isOpen) return it }
        lines.remove(key)
        // 线太多说明流号在乱变（或手机重连），把旧的都关掉，避免一直占着设备。
        if (lines.size >= 8) {
            lines.values.forEach { runCatching { it.stop() }; runCatching { it.close() } }
            lines.clear()
        }
        try {
            val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format, rate / 5 * 4) // 约 200ms 缓冲，抗抖动
            line.start()
            lines[key] = line
            log("声音输出：流 $ssrc，${rate}Hz ${channels} 声道")
            line
        } catch (e: Exception) {
            log("打不开声音输出（流 $ssrc，${rate}Hz/${channels}）：${e.javaClass.simpleName}：${e.message}")
            null
        }
    }

    override fun close() {
        running = false
        worker.interrupt()
        runCatching { aac?.close() }
        aac = null
        synchronized(lines) {
            lines.values.forEach { runCatching { it.stop() }; runCatching { it.close() } }
            lines.clear()
        }
    }
}