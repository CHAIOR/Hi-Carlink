package io.github.gcbond.openhc.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
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
 * 投屏窗口：接收 H.264 访问单元 → avcodec 解码 → Swing 面板上屏。
 *
 * - **普通窗口**：等比适应窗口、居中留黑边（默认布局，和以前一样，放大窗口也不裁画面）；
 * - **全屏（F11 / --fullscreen）**：独占屏幕、任务栏让开，按屏幕比例裁切铺满、不留黑边。
 *
 * 鼠标触摸按 [ScreenMapping] 换算成手机画面像素，绘制和触摸共用同一套几何。
 * 解码在独立线程：协议线程只把访问单元放进有界队列，不阻塞收包。
 */
internal class VideoWindow(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onTouch: (HiSightInput) -> Unit,
    /** 物理按键（鼠标右键=返回、中键=桌面）。 */
    private val onKey: (HiSightInput.Key) -> Unit = {},
    private val onClosed: () -> Unit,
    /** 启动就进全屏（--fullscreen）。 */
    initialFullscreen: Boolean = false,
    /** 会话结束后点「重新开始」（出新二维码）。 */
    private val onRestart: () -> Unit = {},
) : AccessUnitSink, AutoCloseable {

    @Volatile private var image: BufferedImage? = null
    @Volatile private var status: String = "等待手机扫码…"
    @Volatile private var qr: BufferedImage? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0

    /** 真全屏状态（F11 / --fullscreen）。 */
    @Volatile private var fullscreen = initialFullscreen

    @Volatile private var paintMillis = 0.0
    @Volatile private var paintPending = false
    @Volatile private var touchDown = false

    /** 待解码的访问单元：最多压 12 帧（2K30 约 0.4 秒），满了丢最旧的，避免越积越慢。 */
    private val units = java.util.concurrent.ArrayBlockingQueue<ByteArray>(12)
    private var droppedUnits = 0L

    private val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            paintPending = false
            val started = System.nanoTime()
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val img = image
            val panelW = width
            val panelH = height
            val geometry = ScreenMapping.geometry(panelW, panelH, videoWidth, videoHeight, fullscreen)
            if (img != null && geometry != null) {
                val dw = (geometry.videoWidth * geometry.scale).toInt()
                val dh = (geometry.videoHeight * geometry.scale).toInt()
                g2.drawImage(img, geometry.offsetX.toInt(), geometry.offsetY.toInt(), dw, dh, null)
            } else {
                g2.color = Color(0x20, 0x20, 0x20)
                g2.fillRect(0, 0, panelW, panelH)
                val q = qr
                if (q != null) {
                    val side = minOf(panelW, panelH) - 80
                    val x = (panelW - side) / 2
                    val y = (panelH - side) / 2
                    g2.drawImage(q, x, y, side, side, null)
                } else {
                    g2.color = Color.WHITE
                    g2.font = g2.font.deriveFont(20f)
                    val lines = status.split('\n')
                    var yy = panelH / 2 - (lines.size - 1) * 14
                    for (line in lines) {
                        val w = g2.fontMetrics.stringWidth(line)
                        g2.drawString(line, (panelW - w) / 2, yy)
                        yy += 28
                    }
                }
            }
            val micros = (System.nanoTime() - started) / 1000.0
            paintMillis = if (paintMillis == 0.0) micros / 1000.0 else paintMillis * 0.8 + (micros / 1000.0) * 0.2
        }
    }

    /** 滚轮一次转动的步长（视频像素）。 */
    private val wheelStepPx = 160

    private val restartButton = javax.swing.JButton("重新开始（出新二维码）").apply {
        isVisible = false
        addActionListener { onRestart() }
    }
    private val bottomBar = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        isVisible = false
        add(restartButton, java.awt.BorderLayout.CENTER)
    }

    private val window = JFrame("Hi Carlink").apply {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        contentPane.add(panel, java.awt.BorderLayout.CENTER)
        contentPane.add(bottomBar, java.awt.BorderLayout.SOUTH)
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
            override fun mousePressed(e: MouseEvent) {
                when (e.button) {
                    MouseEvent.BUTTON3 -> sendKey(KEY_BACK)
                    MouseEvent.BUTTON2 -> sendKey(KEY_HOME)
                    else -> press(e.x.toFloat(), e.y.toFloat())
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) release(e.x.toFloat(), e.y.toFloat())
            }
        })
        panel.addMouseWheelListener { e ->
            // 滚轮 → 两根手指上下滑：向下滚相当于把内容往上推（手指往下滑）。
            if (!touchDown) scroll(e.x.toFloat(), e.y.toFloat(), if (e.wheelRotation > 0) 1 else -1)
        }
        panel.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) = drag(e.x.toFloat(), e.y.toFloat())
        })
        panel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = requestPaint()
        })
        val toggle = object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                setFullscreen(!fullscreen)
                println("[窗口] 全屏：" + if (fullscreen) "开（独占屏幕、画面裁切铺满）" else "关（恢复窗口、留黑边）")
            }
        }
        panel.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW)
            .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F11, 0), "toggle-fullscreen")
        panel.actionMap.put("toggle-fullscreen", toggle)
        startDecoder()
    }

    fun show() = SwingUtilities.invokeLater { window.isVisible = true }

    /** 最近一次绘制耗时（毫秒，滑动平均），自检用。 */
    fun lastPaintMillis(): Double = paintMillis

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

    /** 会话结束：显示结束原因，并放出「重新开始」。 */
    fun showEnd(text: String, allowRestart: Boolean) = SwingUtilities.invokeLater {
        setStatus(text)
        restartButton.isVisible = allowRestart
        bottomBar.isVisible = allowRestart
        window.contentPane.revalidate()
        requestPaint()
    }

    fun hideRestart() = SwingUtilities.invokeLater {
        restartButton.isVisible = false
        bottomBar.isVisible = false
        window.contentPane.revalidate()
    }

    fun setStatus(text: String) = SwingUtilities.invokeLater {
        status = text
        requestPaint()
    }

    fun showQr(content: String) = SwingUtilities.invokeLater {
        qr = qrImage(content)
        status = "手机用 HiCar 扫这个二维码"
        requestPaint()
    }

    fun clearQr() = SwingUtilities.invokeLater {
        qr = null
        requestPaint()
    }

    /** 请求重绘（合并：已经排着队就跳过，画面再快也每轮只画最新一帧）。 */
    private fun requestPaint() {
        if (paintPending) return
        paintPending = true
        panel.repaint()
    }

    /** 全屏开关：真全屏独占屏幕（任务栏让开），铺满裁切；退出恢复普通窗口。 */
    private fun setFullscreen(fill: Boolean) {
        if (fullscreen == fill) return
        fullscreen = fill
        SwingUtilities.invokeLater {
            try {
                val device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                if (fill) {
                    if (device.isFullScreenSupported) {
                        device.fullScreenWindow = window
                    } else {
                        window.extendedState = Frame.MAXIMIZED_BOTH
                        window.isAlwaysOnTop = true
                    }
                } else {
                    if (device.fullScreenWindow === window) device.fullScreenWindow = null
                    window.isAlwaysOnTop = false
                }
            } catch (e: Exception) {
                println("[窗口] 切换全屏失败：${e.javaClass.simpleName}：${e.message}")
            }
            requestPaint()
        }
    }

    // ---------------------------------------------------------------- 视频

    override fun onAccessUnit(unit: AccessUnit) = feed(unit.data, unit.offset, unit.length)

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        val unit = data.copyOfRange(offset, offset + length)
        while (!units.offer(unit)) {
            units.poll() ?: break
            droppedUnits++
            if (droppedUnits % 100 == 1L) println("[视频] 解码跟不上，已丢 $droppedUnits 帧（队列满）")
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
            var windowFrames = 0
            var windowStartedAt = System.currentTimeMillis()
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
                        SwingUtilities.invokeLater { requestPaint() }
                    }
                }
                if (images.isNotEmpty()) {
                    windowFrames++
                    val now = System.currentTimeMillis()
                    if (now - windowStartedAt >= 1000) {
                        val fps = windowFrames * 1000 / (now - windowStartedAt)
                        val w0 = videoWidth
                        val h0 = videoHeight
                        SwingUtilities.invokeLater {
                            window.title = "Hi Carlink — ${w0}x${h0}（解码 $fps 帧/秒，绘制 ${"%.1f".format(paintMillis)} ms）"
                        }
                        windowFrames = 0
                        windowStartedAt = now
                    }
                    requestPaint()
                }
            }
            decoder.close()
        }, "HiCarlink-Decoder").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 触摸

    private fun sendKey(keyCode: Int) {
        val key = HiSightInput.Key(HiSightInput.Key.Action.DOWN, keyCode, 0)
        val up = HiSightInput.Key(HiSightInput.Key.Action.UP, keyCode, 0)
        onKey(key)
        onKey(up)
    }

    /**
     * 用两根手指的上下滑动模拟滚轮（协议里没有滚轮事件）。[direction] 为 1 表示手指往下滑：
     * 一根手指起步就被手机当成单击，所以必须两根一起动。
     */
    private fun scroll(x: Float, y: Float, direction: Int) {
        val geometry = ScreenMapping.geometry(panel.width, panel.height, videoWidth, videoHeight, fullscreen) ?: return
        val (vx, vy) = ScreenMapping.toVideo(x, y, geometry)
        val dx = maxOf(40, videoWidth / 20)
        val from = vy.coerceIn(0, videoHeight - 1)
        val to = (from + direction * wheelStepPx).coerceIn(0, videoHeight - 1)
        if (from == to) return
        touchDown = true
        onTouch(
            HiSightInput.Touch(
                HiSightInput.Touch.Action.DOWN,
                listOf(HiSightInput.Point(0, vx, from), HiSightInput.Point(1, (vx + dx).coerceIn(0, videoWidth - 1), from)),
                videoWidth,
                videoHeight,
            ),
        )
        val steps = 3
        for (i in 1..steps) {
            val cy = from + (to - from) * i / steps
            onTouch(
                HiSightInput.Touch(
                    HiSightInput.Touch.Action.MOVE,
                    listOf(HiSightInput.Point(0, vx, cy), HiSightInput.Point(1, (vx + dx).coerceIn(0, videoWidth - 1), cy)),
                    videoWidth,
                    videoHeight,
                ),
            )
        }
        // 结束手势：手机只把「带 1 个点的 UP」当成抬起。
        onTouch(
            HiSightInput.Touch(
                HiSightInput.Touch.Action.UP,
                listOf(HiSightInput.Point(0, vx, to)),
                videoWidth,
                videoHeight,
            ),
        )
        touchDown = false
    }

    private fun send(action: HiSightInput.Touch.Action, x: Float, y: Float) {
        val geometry = ScreenMapping.geometry(panel.width, panel.height, videoWidth, videoHeight, fullscreen) ?: return
        val (vx, vy) = ScreenMapping.toVideo(x, y, geometry)
        onTouch(HiSightInput.Touch(action, listOf(HiSightInput.Point(0, vx, vy)), videoWidth, videoHeight))
    }

    private fun press(x: Float, y: Float) {
        if (!touchDown) {
            touchDown = true
            send(HiSightInput.Touch.Action.DOWN, x, y)
        }
    }

    private fun drag(x: Float, y: Float) {
        if (touchDown) send(HiSightInput.Touch.Action.MOVE, x, y)
    }

    private fun release(x: Float, y: Float) {
        if (touchDown) {
            touchDown = false
            send(HiSightInput.Touch.Action.UP, x, y)
        }
    }

    override fun close() {
        running = false
        SwingUtilities.invokeLater { window.dispose() }
        units.clear()
    }

    @Volatile private var running = true

    private companion object {
        /** 安卓键码：返回、桌面。 */
        const val KEY_BACK = 4
        const val KEY_HOME = 3
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
