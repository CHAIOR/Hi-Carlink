package io.github.gcbond.openhc.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * 应用主窗口（只有一个窗口）：
 *
 * - 顶部：图标 + 标题 + 当前状态；
 * - 中间：没连接时显示二维码和 6 位连接码；投屏时显示手机画面；
 * - 底部：设置 / 日志 / 全屏 / 重新开始。
 *
 * 控制台窗口不再需要（用 javaw 启动），日志在窗口里按 **F12** 看，也会写到 `~\.hi-carlink\last-run.log`。
 * 画面按 [ScreenMapping] 的几何绘制和换算触摸：普通窗口等比适应（留黑边），真全屏（F11）裁切铺满。
 */
internal class VideoWindow(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val onTouch: (HiSightInput) -> Unit,
    /** 物理按键（鼠标右键=返回、中键=桌面）。 */
    private val onKey: (HiSightInput.Key) -> Unit = {},
    private val onClosed: () -> Unit,
    initialFullscreen: Boolean = false,
    private val onRestart: () -> Unit = {},
    /** 设置保存后回调（用在界面上提示「重新开始后生效」）。 */
    private val onSettingsSaved: (AppSettings) -> Unit = {},
) : AccessUnitSink, AutoCloseable {

    @Volatile private var image: BufferedImage? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var fullscreen = initialFullscreen
    @Volatile private var qr: BufferedImage? = null
    @Volatile private var pinCode = ""
    @Volatile private var status = "启动中…"
    @Volatile private var paintMillis = 0.0

    /** 近一秒解出并上屏的帧数（全屏左上角显示）。 */
    @Volatile private var decodeFps = 0
    @Volatile private var paintPending = false
    @Volatile private var touchDown = false

    /** 键鼠设置：设置对话框保存后立即生效，不用重启。 */
    @Volatile private var inputSettings = AppSettings.load()
    @Volatile private var running = true

    private val units = java.util.concurrent.ArrayBlockingQueue<ByteArray>(12)
    private var droppedUnits = 0L

    private val accent = Color(0x0A84FF)
    private val background = Color(0x12161C)
    private val panelColor = Color(0x1B2029)
    private val textColor = Color(0xE8EAED)

    // ---------------------------------------------------------------- 控件

    private val statusLabel = JLabel("启动中…").apply {
        foreground = Color(0x9AA4B2)
        font = font.deriveFont(13f)
    }

    private val titleLabel = JLabel("Hi Carlink").apply {
        foreground = textColor
        font = font.deriveFont(Font.BOLD, 20f)
    }

    private val iconLabel = JLabel(loadIcon()).apply {
        preferredSize = Dimension(40, 40)
    }

    private val logArea = JTextArea().apply {
        isEditable = false
        background = Color(0x0B0E12)
        foreground = Color(0xA8C7E8)
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
    }
    private val logPane = JScrollPane(logArea).apply {
        border = BorderFactory.createEmptyBorder()
    }

    private val videoPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            paintPending = false
            val started = System.nanoTime()
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.color = background
            g2.fillRect(0, 0, width, height)
            val img = image
            val geometry = ScreenMapping.geometry(width, height, videoWidth, videoHeight, fullscreen && !inputSettings.fullscreenFit)
            if (img != null && geometry != null) {
                val dw = (geometry.videoWidth * geometry.scale).toInt()
                val dh = (geometry.videoHeight * geometry.scale).toInt()
                g2.drawImage(img, geometry.offsetX.toInt(), geometry.offsetY.toInt(), dw, dh, null)
            } else {
                paintPairingCard(g2)
            }
            if (fullscreen && videoWidth > 0) {
                val text = "${videoWidth}x${videoHeight}  ${decodeFps}fps"
                g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
                val metrics = g2.fontMetrics
                val tw = metrics.stringWidth(text)
                val th = metrics.height
                g2.color = Color(0, 0, 0, 110)
                g2.fillRoundRect(12, 10, tw + 16, th + 2, 10, 10)
                g2.color = Color(0xE8EAED)
                g2.drawString(text, 20, 10 + th - 4)
            }
            val micros = (System.nanoTime() - started) / 1000.0
            paintMillis = if (paintMillis == 0.0) micros / 1000.0 else paintMillis * 0.8 + (micros / 1000.0) * 0.2
        }

        /** 待连接：白色圆角卡片 + 二维码 + 下面一个虚线框写连接码。 */
        private fun paintPairingCard(g2: Graphics2D) {
            val q = qr
            if (q == null) {
                g2.color = Color(0x9AA4B2)
                g2.font = g2.font.deriveFont(18f)
                val lines = status.split('\n')
                var yy = height / 2 - (lines.size - 1) * 14
                for (line in lines) {
                    val w = g2.fontMetrics.stringWidth(line)
                    g2.drawString(line, (width - w) / 2, yy)
                    yy += 30
                }
                return
            }
            val cardW = minOf(width - 120, 520)
            val qrSide = minOf(cardW - 120, height - 260).coerceAtLeast(180)
            val cardH = qrSide + 190
            val x = (width - cardW) / 2
            val y = (height - cardH) / 2
            // 卡片
            g2.color = Color(0xFFFFFF)
            g2.fillRoundRect(x, y, cardW, cardH, 28, 28)
            // 二维码
            val qx = x + (cardW - qrSide) / 2
            val qy = y + 40
            g2.drawImage(q, qx, qy, qrSide, qrSide, null)
            // 连接码（虚线框 + 数字）
            val codeY = qy + qrSide + 26
            val codeW = cardW - 160
            val codeH = 62
            val codeX = x + 80
            g2.color = Color(0x9AA4B2)
            g2.stroke = BasicStroke(
                1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 6f), 0f,
            )
            g2.drawRoundRect(codeX, codeY, codeW, codeH, 14, 14)
            g2.stroke = BasicStroke(1f)
            g2.color = Color(0x1B2029)
            g2.font = Font(Font.MONOSPACED, Font.BOLD, 30)
            val text = pinCode.ifEmpty { "------" }
            val tw = g2.fontMetrics.stringWidth(text)
            g2.drawString(text, codeX + (codeW - tw) / 2, codeY + codeH / 2 + 11)
            // 提示
            g2.color = Color(0x6B7280)
            g2.font = g2.font.deriveFont(13f)
            val hint = "手机用 HiCar 扫描上面的二维码"
            val hw = g2.fontMetrics.stringWidth(hint)
            g2.drawString(hint, x + (cardW - hw) / 2, y + cardH - 22)
        }
    }

    private val restartButton = JButton("重新开始").apply {
        isVisible = false
        addActionListener { onRestart() }
    }

    private val settingsButton = JButton("设置").apply {
        addActionListener { openSettings() }
    }
    private val logButton = JButton("日志 (F12)").apply {
        addActionListener { toggleLog() }
    }
    private val fullscreenButton = JButton("全屏 (F11)").apply {
        addActionListener { setFullscreen(!fullscreen) }
    }

    private val headerPanel = buildHeader()
    private val bottomPanel = buildBottomBar()

    private val cards = JPanel().apply { layout = java.awt.CardLayout() }
    private val window = JFrame("Hi Carlink").apply {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        isUndecorated = false
        size = Dimension(1100, 760)
        minimumSize = Dimension(900, 620)
        setLocationRelativeTo(null)
        contentPane.background = background
        contentPane.add(headerPanel, BorderLayout.NORTH)
        contentPane.add(cards, BorderLayout.CENTER)
        contentPane.add(bottomPanel, BorderLayout.SOUTH)
        iconImage = loadIcon()?.image
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                onClosed()
                close()
            }
        })
    }

    init {
        cards.add(videoPanel, "video")
        cards.add(logPane, "log")
        videoPanel.isOpaque = true
        videoPanel.background = background
        videoPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isShowingLog()) return
                when (e.button) {
                    MouseEvent.BUTTON3 -> keyFor(inputSettings.rightButton)?.let { sendKey(it) }
                    MouseEvent.BUTTON2 -> keyFor(inputSettings.middleButton)?.let { sendKey(it) }
                    else -> press(e.x.toFloat(), e.y.toFloat())
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) release(e.x.toFloat(), e.y.toFloat())
            }
        })
        videoPanel.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (!isShowingLog()) drag(e.x.toFloat(), e.y.toFloat())
            }
        })
        videoPanel.addMouseWheelListener { e ->
            if (!touchDown && !isShowingLog()) {
                // 默认和 Windows 一致：滚轮往下 = 内容往下走 = 手指往上滑；设置里可以反过来。
                val down = e.wheelRotation > 0
                val dir = if (inputSettings.invertWheel) (if (down) 1 else -1) else (if (down) -1 else 1)
                scroll(e.x.toFloat(), e.y.toFloat(), dir)
            }
        }
        videoPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = requestPaint()
        })
        bindKeys()
        startDecoder()
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        background = panelColor
        border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 12, 4)).apply {
            isOpaque = false
            add(iconLabel)
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(titleLabel)
                    add(Box.createVerticalStrut(2))
                    add(JLabel("HiCar 无线投屏").apply {
                        foreground = Color(0x7A8494)
                        font = font.deriveFont(12f)
                    })
                },
            )
        }
        add(left, BorderLayout.WEST)
        add(statusLabel, BorderLayout.EAST)
    }

    private fun buildBottomBar(): JPanel = JPanel(BorderLayout()).apply {
        background = panelColor
        border = BorderFactory.createEmptyBorder(10, 16, 12, 16)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            add(settingsButton)
            add(logButton)
            add(fullscreenButton)
            add(restartButton)
        }
        add(buttons, BorderLayout.EAST)
    }

    private fun bindKeys() {
        fun bind(key: Int, name: String, action: () -> Unit) {
            val act = object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
            }
            videoPanel.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(key, 0), name)
            videoPanel.actionMap.put(name, act)
        }
        bind(java.awt.event.KeyEvent.VK_F11, "toggle-fullscreen") { setFullscreen(!fullscreen) }
        bind(java.awt.event.KeyEvent.VK_F12, "toggle-log") { toggleLog() }
        bind(java.awt.event.KeyEvent.VK_ESCAPE, "exit-fullscreen") { if (fullscreen) setFullscreen(false) }
    }

    // ---------------------------------------------------------------- 对外接口

    fun show() = SwingUtilities.invokeLater { window.isVisible = true }

    fun lastPaintMillis(): Double = paintMillis

    /** 把当前窗口画面抓成图片（自检、截图用）。 */
    fun snapshot(): BufferedImage? {
        var out: BufferedImage? = null
        val done = java.util.concurrent.CountDownLatch(1)
        SwingUtilities.invokeLater {
            val w = window.contentPane.width
            val h = window.contentPane.height
            if (w > 0 && h > 0) {
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                window.contentPane.paint(g)
                g.dispose()
                out = img
            }
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    fun appendLog(line: String) {
        SwingUtilities.invokeLater {
            logArea.append(line + "\n")
            val length = logArea.document.length
            if (length > 200_000) logArea.replaceRange("", 0, length - 150_000)
            logArea.caretPosition = logArea.document.length
        }
    }

    fun showPairing(qrContent: String, code: String) = SwingUtilities.invokeLater {
        qr = qrImage(qrContent)
        pinCode = code
        setStatus("等待手机扫码连接")
        requestPaint()
    }

    fun clearQr() = SwingUtilities.invokeLater {
        qr = null
        requestPaint()
    }

    fun setStatus(text: String) = SwingUtilities.invokeLater {
        status = text
        statusLabel.text = text.replace("\n", " · ")
        requestPaint()
    }

    fun showProjecting() = SwingUtilities.invokeLater {
        setStatus("投屏中")
        requestPaint()
    }

    fun showEnd(text: String, allowRestart: Boolean) = SwingUtilities.invokeLater {
        setStatus(text)
        restartButton.isVisible = allowRestart
        window.contentPane.revalidate()
        requestPaint()
    }

    fun hideRestart() = SwingUtilities.invokeLater {
        restartButton.isVisible = false
        window.contentPane.revalidate()
    }

    private fun isShowingLog(): Boolean {
        // CardLayout 没有公开的「当前卡片」查询，用一个字段记住。
        return showingLog
    }

    @Volatile private var showingLog = false

    private fun toggleLog() {
        showingLog = !showingLog
        (cards.layout as java.awt.CardLayout).show(cards, if (showingLog) "log" else "video")
        requestPaint()
    }

    // ---------------------------------------------------------------- 设置

    private fun openSettings() {
        val current = AppSettings.load()
        val dialog = JDialog(window, "设置", true)
        dialog.layout = BorderLayout()
        val resolutions = JComboBox(VideoPresets.ALL.map { "${it.name}（${it.width}x${it.height}）" }.toTypedArray())
        resolutions.selectedIndex = VideoPresets.ALL.indexOfFirst { it.name == current.resolution }.coerceAtLeast(0)
        val fpsBox = JComboBox(AppSettings.FPS_CHOICES.map { "$it 帧" }.toTypedArray())
        fpsBox.selectedIndex = AppSettings.FPS_CHOICES.indexOf(current.fps).coerceAtLeast(0)
        val invertWheel = javax.swing.JCheckBox("滚轮方向反过来（默认：滚轮往下 = 内容往下）").apply {
            isSelected = current.invertWheel
        }
        val rightBox = JComboBox(arrayOf("返回", "桌面", "最近任务", "不设置")).apply {
            selectedIndex = listOf("back", "home", "recents", "none").indexOf(current.rightButton).coerceAtLeast(0)
        }
        val middleBox = JComboBox(arrayOf("返回", "桌面", "最近任务", "不设置")).apply {
            selectedIndex = listOf("back", "home", "recents", "none").indexOf(current.middleButton).coerceAtLeast(0)
        }
        val fullscreenFit = javax.swing.JCheckBox("全屏时完整显示（画面比例和屏幕不同时留黑边，不裁切）").apply {
            isSelected = current.fullscreenFit
        }
        val videoForm = JPanel(GridLayout(2, 2, 12, 12)).apply {
            border = BorderFactory.createEmptyBorder(16, 20, 4, 20)
            add(JLabel("分辨率"))
            add(resolutions)
            add(JLabel("帧率"))
            add(fpsBox)
        }
        val inputForm = JPanel(GridLayout(4, 2, 12, 12)).apply {
            border = BorderFactory.createEmptyBorder(4, 20, 8, 20)
            add(JLabel("鼠标右键"))
            add(rightBox)
            add(JLabel("鼠标中键"))
            add(middleBox)
            add(JLabel("滚轮"))
            add(invertWheel)
            add(JLabel("全屏"))
            add(fullscreenFit)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("取消").apply { addActionListener { dialog.dispose() } })
            add(
                JButton("保存").apply {
                    addActionListener {
                        val preset = VideoPresets.ALL[resolutions.selectedIndex]
                        val chosenFps = AppSettings.FPS_CHOICES[fpsBox.selectedIndex]
                        val settings = AppSettings(
                            resolution = preset.name,
                            fps = chosenFps,
                            invertWheel = invertWheel.isSelected,
                            rightButton = listOf("back", "home", "recents", "none")[rightBox.selectedIndex],
                            middleButton = listOf("back", "home", "recents", "none")[middleBox.selectedIndex],
                            fullscreenFit = fullscreenFit.isSelected,
                        )
                        AppSettings.save(settings)
                        inputSettings = settings
                        setStatus("已保存：${preset.name} ${chosenFps} 帧（重新开始后生效）")
                        onSettingsSaved(settings)
                        dialog.dispose()
                    }
                },
            )
        }
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("画质").apply { border = BorderFactory.createEmptyBorder(10, 20, 0, 0) })
            add(videoForm)
            add(JLabel("键鼠").apply { border = BorderFactory.createEmptyBorder(8, 20, 0, 0) })
            add(inputForm)
        }
        dialog.add(center, BorderLayout.CENTER)
        dialog.add(buttons, BorderLayout.SOUTH)
        dialog.pack()
        dialog.setLocationRelativeTo(window)
        dialog.isVisible = true
    }

    // ---------------------------------------------------------------- 视频

    override fun onAccessUnit(unit: AccessUnit) = feed(unit.data, unit.offset, unit.length)

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        val unit = data.copyOfRange(offset, offset + length)
        while (!units.offer(unit)) {
            units.poll() ?: break
            droppedUnits++
            if (droppedUnits % 100 == 1L) appendLog("[视频] 解码跟不上，已丢 $droppedUnits 帧（队列满）")
        }
    }

    private fun startDecoder() {
        Thread({
            val decoder = try {
                LiveH264Decoder()
            } catch (e: Exception) {
                appendLog("[视频] 解码器启动失败：${e.javaClass.simpleName}：${e.message}")
                setStatus("解码器启动失败：${e.message}")
                return@Thread
            }
            appendLog("[视频] 解码器已启动")
            var frames = 0
            var fpsFrames = 0
            var fpsAt = System.currentTimeMillis()
            while (running) {
                val unit = try {
                    units.take()
                } catch (e: InterruptedException) {
                    break
                }
                val images = try {
                    decoder.decode(unit)
                } catch (e: Exception) {
                    appendLog("[视频] 解码出错：${e.javaClass.simpleName}：${e.message}")
                    continue
                }
                for (img in images) {
                    image = img
                    frames++
                    fpsFrames++
                    if (frames == 1) appendLog("[视频] 第一帧画面 ${img.width}x${img.height}")
                    if (img.width != videoWidth || img.height != videoHeight) {
                        videoWidth = img.width
                        videoHeight = img.height
                        SwingUtilities.invokeLater { window.title = "Hi Carlink — ${img.width}x${img.height}" }
                    }
                }
                if (images.isNotEmpty()) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - fpsAt >= 1000) {
                        decodeFps = ((fpsFrames * 1000L) / (nowMs - fpsAt)).toInt()
                        fpsFrames = 0
                        fpsAt = nowMs
                    }
                    requestPaint()
                }
            }
            decoder.close()
        }, "HiCarlink-Decoder").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 触摸

    /** 设置里的按键名 → 安卓键码；none 返回 null。 */
    private fun keyFor(name: String): Int? = when (name) {
        "back" -> KEY_BACK
        "home" -> KEY_HOME
        "recents" -> 187
        else -> null
    }

    private fun sendKey(keyCode: Int) {
        onKey(HiSightInput.Key(HiSightInput.Key.Action.DOWN, keyCode, 0))
        onKey(HiSightInput.Key(HiSightInput.Key.Action.UP, keyCode, 0))
    }

    private fun scroll(x: Float, y: Float, direction: Int) {
        val (vx, vy) = toVideo(x, y) ?: return
        val dx = maxOf(40, videoWidth / 20)
        val from = vy.coerceIn(0, videoHeight - 1)
        val to = (from + direction * WHEEL_STEP_PX).coerceIn(0, videoHeight - 1)
        if (from == to) return
        touchDown = true
        val x2 = (vx + dx).coerceIn(0, videoWidth - 1)
        onTouch(HiSightInput.Touch(HiSightInput.Touch.Action.DOWN,
            listOf(HiSightInput.Point(0, vx, from), HiSightInput.Point(1, x2, from)), videoWidth, videoHeight))
        for (i in 1..3) {
            val cy = from + (to - from) * i / 3
            onTouch(HiSightInput.Touch(HiSightInput.Touch.Action.MOVE,
                listOf(HiSightInput.Point(0, vx, cy), HiSightInput.Point(1, x2, cy)), videoWidth, videoHeight))
        }
        onTouch(HiSightInput.Touch(HiSightInput.Touch.Action.UP,
            listOf(HiSightInput.Point(0, vx, to)), videoWidth, videoHeight))
        touchDown = false
    }

    /** 窗口坐标 → 视频像素，按设置翻转 X/Y。 */
    private fun toVideo(x: Float, y: Float): Pair<Int, Int>? {
        val geometry = ScreenMapping.geometry(
            videoPanel.width, videoPanel.height, videoWidth, videoHeight, fullscreen && !inputSettings.fullscreenFit,
        ) ?: return null
        return ScreenMapping.toVideo(x, y, geometry)
    }

    private fun send(action: HiSightInput.Touch.Action, x: Float, y: Float) {
        val (vx, vy) = toVideo(x, y) ?: return
        onTouch(HiSightInput.Touch(action, listOf(HiSightInput.Point(0, vx, vy)), videoWidth, videoHeight))
    }

    private fun press(x: Float, y: Float) {
        if (touchDown) return
        // 适应模式下点在黑边上不响应（否则会被钳到边缘，等于点了手机屏幕边上的按钮）。
        val geometry = ScreenMapping.geometry(
            videoPanel.width, videoPanel.height, videoWidth, videoHeight, fullscreen && !inputSettings.fullscreenFit,
        ) ?: return
        if (!fullscreen) {
            val insideX = x >= geometry.offsetX && x <= geometry.offsetX + videoWidth * geometry.scale
            val insideY = y >= geometry.offsetY && y <= geometry.offsetY + videoHeight * geometry.scale
            if (!insideX || !insideY) return
        }
        touchDown = true
        send(HiSightInput.Touch.Action.DOWN, x, y)
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

    private fun requestPaint() {
        if (paintPending) return
        paintPending = true
        videoPanel.repaint()
    }

    private fun setFullscreen(fill: Boolean) {
        if (fullscreen == fill) return
        fullscreen = fill
        SwingUtilities.invokeLater {
            try {
                val device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                headerPanel.isVisible = !fill
                bottomPanel.isVisible = !fill
                window.contentPane.revalidate()
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
                appendLog("[窗口] 切换全屏失败：${e.javaClass.simpleName}：${e.message}")
            }
            requestPaint()
        }
    }

    override fun close() {
        running = false
        SwingUtilities.invokeLater { window.dispose() }
        units.clear()
    }

    /**
     * 图标：优先用安装目录里外置的 `app-icon.png`（换图标不用重新打包），
     * 其次用打进包里的资源。
     */
    private fun loadIcon(): javax.swing.ImageIcon? = runCatching {
        val external = java.io.File(System.getProperty("hicarlink.icon") ?: "app-icon.png")
        val raw = when {
            external.isFile -> ImageIO.read(external)
            else -> javaClass.getResourceAsStream("/app-icon.png")?.use { ImageIO.read(it) }
        } ?: return null
        val scaled = raw.getScaledInstance(40, 40, java.awt.Image.SCALE_SMOOTH)
        javax.swing.ImageIcon(scaled)
    }.getOrNull()

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

    private companion object {
        const val KEY_BACK = 4
        const val KEY_HOME = 3
        const val WHEEL_STEP_PX = 220
    }
}