package io.github.gcbond.openhc.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * 投屏窗口：接收 H.264 访问单元 → ffmpeg（JavaCV）解码 → Swing 面板上屏；
 * 鼠标事件按 [VideoViewport] 换算成手机画面像素，经 [TouchMapper] 变成 [HiSightInput]，再交给会话发送。
 *
 * 解码在独立线程：协议线程只往管道里写字节（[onAccessUnit]），不阻塞收包。
 */
internal class VideoWindow(
    private val displayWidth: Int,
    private val displayHeight: Int,
    /** 触摸事件（可能一次多个）。在 Swing 的 EDT 上调用。 */
    private val onTouch: (HiSightInput) -> Unit,
    /** 用户关窗口。 */
    private val onClosed: () -> Unit,
) : AccessUnitSink, AutoCloseable {

    @Volatile private var image: BufferedImage? = null
    @Volatile private var status: String = "等待手机扫码…"
    @Volatile private var qr: BufferedImage? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0

    private val mapper = TouchMapper()
    private var viewport: VideoViewport? = null
    private val fingers = HashMap<Int, IntArray>() // 鼠标只有一根指针；以后接触摸屏再用

    /** 待解码的访问单元：协议线程放，解码线程取；最多压 60 帧，满了丢最旧的，绝不阻塞收包。 */
    private val units = java.util.concurrent.ArrayBlockingQueue<ByteArray>(60)
    private var running = true

    private val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val vp = viewport
            val img = image
            if (vp != null && img != null) {
                g2.drawImage(img, vp.left, vp.top, vp.width, vp.height, null)
            } else {
                g2.color = Color(0x20, 0x20, 0x20)
                g2.fillRect(0, 0, width, height)
                val q = qr
                if (q != null) {
                    val side = minOf(width, height) - 80
                    val x = (width - side) / 2
                    val y = (height - side) / 2
                    g2.drawImage(q, x, y, side, side, null)
                } else {
                    g2.color = Color.WHITE
                    g2.font = g2.font.deriveFont(20f)
                    val lines = status.split('\n')
                    var yy = height / 2 - (lines.size - 1) * 14
                    for (line in lines) {
                        val w = g2.fontMetrics.stringWidth(line)
                        g2.drawString(line, (width - w) / 2, yy)
                        yy += 28
                    }
                }
            }
        }
    }

    private val window = JFrame("Hi Carlink").apply {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        contentPane.add(panel)
        size = Dimension(1280, 760)
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                onClosed()
                close()
            }
        })
    }

    init {
        panel.isOpaque = true
        panel.background = Color(0x20, 0x20, 0x20)
        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = press(e.x.toFloat(), e.y.toFloat())
            override fun mouseReleased(e: MouseEvent) = release(e.x.toFloat(), e.y.toFloat())
        })
        panel.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) = drag(e.x.toFloat(), e.y.toFloat())
        })
        panel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = refreshViewport()
        })
        startDecoder()
    }

    fun show() = SwingUtilities.invokeLater { window.isVisible = true }

    /** 把当前窗口画面抓成图片（自检、截图用）。 */
    fun snapshot(): BufferedImage? {
        var out: BufferedImage? = null
        val done = java.util.concurrent.CountDownLatch(1)
        SwingUtilities.invokeLater {
            val w = panel.width
            val h = panel.height
            if (w > 0 && h > 0) {
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                panel.paint(g)
                g.dispose()
                out = img
            }
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    fun setStatus(text: String) = SwingUtilities.invokeLater {
        status = text
        panel.repaint()
    }

    fun showQr(content: String) = SwingUtilities.invokeLater {
        qr = qrImage(content)
        status = "手机用 HiCar 扫这个二维码"
        panel.repaint()
    }

    fun clearQr() = SwingUtilities.invokeLater {
        qr = null
        panel.repaint()
    }

    // ---------------------------------------------------------------- 视频

    override fun onAccessUnit(unit: AccessUnit) = feed(unit.data, unit.offset, unit.length)

    /** 喂一个 Annex-B 访问单元（一帧）。队列满了就丢最旧的。 */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        val unit = data.copyOfRange(offset, offset + length)
        while (!units.offer(unit)) {
            units.poll() ?: break
        }
    }

    private fun startDecoder() {
        Thread({
            val decoder = try {
                LiveH264Decoder()
            } catch (e: Exception) {
                println("[视频] 解码器启动失败：${e.javaClass.simpleName}：${e.message}")
                setStatus("解码器启动失败：${e.message}")
                return@Thread
            }
            println("[视频] 解码器已启动")
            var frames = 0
            while (running) {
                val unit = try {
                    units.take()
                } catch (e: InterruptedException) {
                    break
                }
                val images = try {
                    decoder.decode(unit)
                } catch (e: Exception) {
                    println("[视频] 解码出错：${e.javaClass.simpleName}：${e.message}")
                    continue
                }
                for (img in images) {
                    image = img
                    frames++
                    if (frames == 1) println("[视频] 第一帧画面 ${img.width}x${img.height}")
                    if (img.width != videoWidth || img.height != videoHeight) {
                        videoWidth = img.width
                        videoHeight = img.height
                        SwingUtilities.invokeLater { refreshViewport() }
                    }
                }
                if (images.isNotEmpty()) panel.repaint()
            }
            decoder.close()
        }, "HiCarlink-Decoder").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 触摸

    private fun refreshViewport() {
        val vw = panel.width
        val vh = panel.height
        val newViewport = if (vw > 0 && vh > 0 && videoWidth > 0 && videoHeight > 0) {
            VideoViewport(vw, vh, videoWidth, videoHeight)
        } else {
            null
        }
        viewport = newViewport
        mapper.setViewport(newViewport)?.forEach(onTouch)
        panel.repaint()
    }

    private fun press(x: Float, y: Float) {
        var handled = false
        mapper.onEvent(TouchMapper.Action.DOWN, 0, 1, intArrayOf(0), floatArrayOf(x), floatArrayOf(y)).forEach {
            handled = true
            onTouch(it)
        }
        if (!handled) { /* 点在画面外，忽略 */ }
    }

    private fun drag(x: Float, y: Float) {
        mapper.onEvent(TouchMapper.Action.MOVE, 0, 1, intArrayOf(0), floatArrayOf(x), floatArrayOf(y)).forEach(onTouch)
    }

    private fun release(x: Float, y: Float) {
        mapper.onEvent(TouchMapper.Action.UP, 0, 1, intArrayOf(0), floatArrayOf(x), floatArrayOf(y)).forEach(onTouch)
    }

    override fun close() {
        running = false
        SwingUtilities.invokeLater { window.dispose() }
        units.clear()
    }

    private fun qrImage(text: String): BufferedImage {
        val size = 600
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        return img
    }
}