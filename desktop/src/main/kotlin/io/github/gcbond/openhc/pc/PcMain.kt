package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.session.AudioSettings
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.session.DisplaySettings
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.SocketRunner
import io.github.gcbond.openhc.core.session.WifiNetwork
import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.core.session.WirelessReceiverConfig
import io.github.gcbond.openhc.core.session.WirelessReceiverSession
import io.github.gcbond.openhc.core.session.WirelessRunnerBridge
import io.github.gcbond.openhc.core.session.WirelessTimeouts
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Windows 控制台默认 GBK，中文会变问号：统一按 UTF-8 输出（终端用 Windows Terminal 或 chcp 65001）。
    runCatching {
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    }
    val options = try {
        PcOptions.parse(args)
    } catch (e: HelpRequested) {
        println(PcOptions.USAGE)
        return
    } catch (e: IllegalArgumentException) {
        System.err.println("参数错误：${e.message}")
        System.err.println()
        System.err.println(PcOptions.USAGE)
        exitProcess(1)
    }
    // TCP/UDP 动作日志默认开着：排查「一分钟内掉线」要看 UDP 保活。
    System.setProperty("openhc.debug.actions", "1")
    options.dumpBtDir?.let { dir ->
        java.io.File(dir).mkdirs()
        System.setProperty("openhc.dumpBt", dir)
    }
    // 只允许一个实例：两个实例会抢同一个蓝牙监听和热点，谁也连不上。
    val lock = try {
        val dir = java.io.File(System.getProperty("user.home"), ".hi-carlink").apply { mkdirs() }
        java.nio.channels.FileChannel.open(
            java.io.File(dir, "hicarlink.lock").toPath(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).tryLock()
    } catch (e: Exception) {
        null
    }
    if (lock == null) {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Hi Carlink 已经在运行了。\n请切换到已经打开的窗口，或先把它关掉再启动。",
            "Hi Carlink",
            javax.swing.JOptionPane.INFORMATION_MESSAGE,
        )
        return
    }
    try {
        exitProcess(PcApp(options).run())
    } finally {
        runCatching { lock.release() }
    }
}

/**
 * 一次无线会话：蓝牙握手/认证 → 开热点 → 手机连 TCP → 投屏 → 窗口上屏 + 鼠标触摸回传 + 声音。
 */
internal class PcApp(private val options: PcOptions) {
    private companion object {
        /** 断线后自动回连的间隔。 */
        const val AUTO_RECONNECT_DELAY_MS = 3_000L
    }
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile private var ended = false
    @Volatile private var windowClosed = false
    @Volatile private var restartRequested: java.util.concurrent.CountDownLatch? = null
    @Volatile private var endReason: WirelessEndReason? = null

    /** 当前阶段（给 20 秒一次的「当前阶段」日志用）。 */
    @Volatile private var stage = "等手机扫码"

    /** 首次配对的 6 位连接码。 */
    @Volatile private var pin = ""

    /** 上次连成功的手机：存在 ~/.hi-carlink/phone.properties，下次启动先主动回连，不用扫码。 */
    private val phoneFile: java.io.File? = runCatching {
        val dir = java.io.File(System.getProperty("user.home"), ".hi-carlink").apply { mkdirs() }
        java.io.File(dir, "phone.properties")
    }.getOrNull()

    /** 同一份日志也写进文件，出问题时直接把文件发出来就行。 */
    private val logFile: java.io.File? = runCatching {
        val dir = java.io.File(System.getProperty("user.home"), ".hi-carlink").apply { mkdirs() }
        java.io.File(dir, "last-run.log")
    }.getOrNull()

    private lateinit var window: VideoWindow
    private lateinit var runner: SocketRunner
    private lateinit var bridge: WirelessRunnerBridge
    private lateinit var platform: WindowsPlatform

    fun run(): Int {
        runCatching { logFile?.writeText("", Charsets.UTF_8) }
        log("Hi Carlink PC 0.2.0（实验）· 基于 OpenHC (GPL-3.0)")
        val identity = try {
            IdentityAssets.parse(Files.readString(options.identity))
        } catch (e: IOException) {
            log("读不了连接参数 ${options.identity}：${e.message}")
            return 1
        } catch (e: IllegalArgumentException) {
            log("连接参数不合法：${e.message}")
            return 1
        }
        log("连接参数：${options.identity}")

        val trust = try {
            FileTrustStore.open(options.trustDir)
        } catch (e: IOException) {
            log("信任库不可用：${e.message}")
            return 1
        }
        log("信任库：${trust.file}（已配对 ${trust.peerCount} 台）")

        val btAddress = if (options.bluetoothAddress.equals("auto", ignoreCase = true)) {
            try {
                localBluetoothAddress().also { log("本机蓝牙地址：$it") }
            } catch (e: Exception) {
                log("读不到本机蓝牙地址：${e.message}；请用 --bt 手工指定（AA:BB:CC:DD:EE:FF）")
                return 1
            }
        } else {
            options.bluetoothAddress
        }

        // 画质：命令行显式给了就听命令行的，否则用界面里保存的设置（默认 2K60）。
        val settings = AppSettings.load()
        val preset = settings.preset
        val screenSized = if (options.explicitVideo) null else if (settings.matchScreenAspect) AppSettings.sizeForScreen(preset) else null
        val width = if (options.explicitVideo) options.width else (screenSized?.first ?: preset.width)
        val height = if (options.explicitVideo) options.height else (screenSized?.second ?: preset.height)
        val fps = if (options.explicitVideo) options.fps else settings.fps
        val dpi = if (options.explicitVideo) options.dpi else (screenSized?.let { (it.second * 160) / 720 } ?: preset.dpi)
        val density = if (options.explicitVideo) options.density else (screenSized?.let { it.second / 720.0 } ?: preset.density)
        log("画质：${width}x$height@$fps（dpi $dpi / density $density）")
        val display = DisplaySettings(width, height, fps, dpi, density)
        val config = try {
            WirelessReceiverConfig(
                identity = identity,
                bluetoothAddress = btAddress,
                display = display,
                trustStore = trust,
                supportsUsb = false,
                touchInput = true,
                // 鼠标右键/中键要发安卓的返回/桌面键。
                keyInput = true,
                // 第一版只放 PCM：手机按声明优先发 PCM，Windows 上直接用 Java Sound 放。
                audio = AudioSettings(codec = AudioCodec.PCM),
                timeouts = WirelessTimeouts(networkStartMillis = 20_000),
            )
        } catch (e: IllegalArgumentException) {
            log("配置不合法：${e.message}")
            return 1
        }

        window = VideoWindow(
            width,
            height,
            ::sendTouch,
            ::sendKey,
            {
                log("窗口被关掉，结束会话")
                windowClosed = true
                // 如果正停在「重新开始」的等待上，也要把它放出来，否则进程会在窗口消失后继续挂着。
                restartRequested?.countDown()
                if (this::runner.isInitialized) runner.stop()
            },
            initialFullscreen = options.fullscreen,
            onRestart = { restartRequested?.countDown() },
            onSettingsSaved = { saved ->
                log("设置已保存：${saved.resolution} ${saved.fps} 帧（点「重新开始」后生效）")
            },
        )
        window.show()

        val saved = loadPhone()
        if (saved != null) log("上次连过的手机：${saved.first}（断开时用它自动回连）")
        var qrMode = true
        var autoAttempts = 0
        while (!windowClosed) {
            val target: Pair<String, String>? = if (qrMode) null else saved
            val reconnectFailed = runSession(config, target)
            if (windowClosed) break
            val reasonText = endReason?.toString() ?: ""
            val networkDrop = reasonText.contains("网络错误") || reasonText.contains("被手机关闭") ||
                reasonText.contains("连接被关闭")
            if (!qrMode && reconnectFailed) {
                log("主动回连没成，改成出二维码")
                qrMode = true
                continue
            }
            if (qrMode && networkDrop && saved != null && autoAttempts < 2) {
                autoAttempts++
                log("连接断了（$reasonText），${AUTO_RECONNECT_DELAY_MS / 1000} 秒后自动回连（第 $autoAttempts 次）")
                Thread.sleep(AUTO_RECONNECT_DELAY_MS)
                qrMode = false
                continue
            }
            autoAttempts = 0
            window.showEnd("会话结束：${reasonText.ifEmpty { "未知" }}", allowRestart = true)
            val latch = java.util.concurrent.CountDownLatch(1)
            restartRequested = latch
            val restart = latch.await(30, java.util.concurrent.TimeUnit.MINUTES)
            restartRequested = null
            if (windowClosed || !restart) break
            log("用户点了重新开始")
            window.hideRestart()
            qrMode = true
        }
        window.close()
        log("程序退出")
        return 0
    }

    /** 跑一次无线会话（窗口一直留着）。[reconnect] 不为 null 时走主动回连；返回「回连是否失败」。 */
    private fun runSession(config: WirelessReceiverConfig, reconnect: Pair<String, String>? = null): Boolean {
        ended = false
        endReason = null
        stage = "等手机扫码"
        window.hideRestart()
        val fileOut = options.videoOut?.let { out ->
            try {
                BufferedOutputStream(Files.newOutputStream(out), 1 shl 20)
            } catch (e: IOException) {
                log("打不开视频输出文件 $out：${e.message}")
                null
            }
        }
        val sink = object : AccessUnitSink {
            var frames = 0L
            override fun onAccessUnit(unit: AccessUnit) {
                frames++
                fileOut?.write(unit.data, unit.offset, unit.length)
                window.onAccessUnit(unit)
            }
        }

        val audio = DesktopAudio { log(it) }
        val session = WirelessReceiverSession(config, sink, audio)
        val results = object : PlatformResults {
            override fun onListening(id: Int, port: Int) = runner.submit { now -> bridge.onListening(id, port, now) }
            override fun onAccepted(id: Int, remote: String) = runner.submit { now -> bridge.onAccepted(id, remote, now) }
            override fun onConnected(id: Int) = runner.submit { now -> bridge.onConnected(id, now) }
            override fun onData(id: Int, data: ByteArray) = runner.submit { now -> bridge.onData(id, data, 0, data.size, now) }
            override fun onClosed(id: Int) = runner.submit { now -> bridge.onClosed(id, now) }
            override fun onIoError(id: Int, message: String) = runner.submit { now -> bridge.onIoError(id, message, now) }
            override fun onWifiNetworkReady(id: Int, network: WifiNetwork) =
                runner.submit { now -> bridge.onWifiNetworkReady(id, network, now) }
        }
        val rfcomm = if (isWindows64()) WinRfcommBackend() else UnavailableRfcomm
        val wifi = if (isWindows()) {
            WinHotspotBackend(options.hotspotSsid, options.hotspotPassword, options.frequencyMhz) { log(it) }
        } else {
            UnavailableWifi
        }
        platform = WindowsPlatform(rfcomm, wifi, results, ::onWirelessEvent) { log(it) }
        val begin: (Long) -> List<io.github.gcbond.openhc.core.session.WirelessOutput> = if (reconnect != null) {
            { now -> session.startReconnect(reconnect.first, reconnect.second, now) }
        } else {
            session::start
        }
        bridge = WirelessRunnerBridge(session, begin) { output -> platform.execute(output) }
        runner = SocketRunner(bridge, ::onSessionEvent, SocketRunner.Options(threadName = "HiCarlink-PC", log = { log("[执行器] $it") }))
        // 必须先把桥接连上执行器再 start：平台回调（蓝牙连上、数据到达）都经桥接排回协议线程。
        bridge.attach(runner)

        Runtime.getRuntime().addShutdownHook(thread(start = false, name = "pc-shutdown") {
            if (!ended) {
                log("收到退出信号，按协议结束会话")
                runner.stop()
                runner.awaitTermination(5_000)
            }
            runCatching { fileOut?.close() }
        })
        runner.start()
        // 每 20 秒报一次当前阶段：车主看不到细节时，一眼知道卡在哪。
        thread(isDaemon = true, name = "HiCarlink-Stage") {
            while (!ended) {
                Thread.sleep(20_000)
                if (!ended) log("当前阶段：$stage")
            }
        }
        options.durationSeconds?.let { seconds ->
            thread(isDaemon = true) {
                if (!runner.awaitTermination(java.util.concurrent.TimeUnit.SECONDS.toMillis(seconds))) {
                    log("已运行 $seconds 秒，结束会话")
                    runner.stop()
                }
            }
        }
        while (!runner.awaitTermination(60_000)) {
            if (windowClosed) break
        }
        platform.closeAll()
        audio.close()
        runCatching { fileOut?.close() }
        log("结束：${endReason ?: "未知"}")
        val reasonText = endReason?.toString() ?: ""
        return reconnect != null && reasonText.contains("主动回连")
    }

    /** 鼠标右键/中键 → 安卓按键，同样投到协议线程。 */
    private fun sendKey(key: HiSightInput.Key) {
        if (!ended) {
            runner.submit {
                try {
                    bridge.sendInput(key)
                } catch (e: RuntimeException) {
                    log("按键没发出去：${e.javaClass.simpleName}：${e.message}")
                    emptyList()
                }
            }
        }
    }

    /** 鼠标触摸（Swing 线程）→ 投到协议线程，经桥接发给手机。 */
    private fun sendTouch(input: HiSightInput) {
        if (!ended) {
            runner.submit {
                try {
                    bridge.sendInput(input)
                } catch (e: IllegalStateException) {
                    log("触摸状态对不上，丢弃：${e.message}")
                    emptyList()
                } catch (e: IllegalArgumentException) {
                    log("触摸不合法，丢弃：${e.message}")
                    emptyList()
                }
            }
        }
    }

    private fun onWirelessEvent(event: WirelessEvent) {
        when (event) {
            is WirelessEvent.PairingReady -> {
                pin = event.pinCode
                stage = "出二维码，等手机扫码"
                log("蓝牙监听已开好，连接码 ${event.pinCode}（首次配对要在手机上输入这 6 位）")
                window.showPairing(event.qrCode, event.pinCode)
                window.setStatus(
                    "连接码 ${event.pinCode}  手机用 HiCar 扫上面的二维码\n" +
                        "（连上以后要把 Windows「移动热点」打开：名字 ${options.hotspotSsid}，密码 ${options.hotspotPassword}）",
                )
            }
            is WirelessEvent.PhoneBluetoothConnected -> {
                log("手机蓝牙连上来了（${event.address}）")
                savePhone(event.address)
                stage = "手机蓝牙已连上，等握手"
                window.setStatus("手机蓝牙已连接，正在握手…")
            }
            is WirelessEvent.BluetoothHandshakeCompleted -> {
                log("蓝牙握手完成（加密=${event.encrypted}）")
                stage = "握手完成，等手机输连接码 $pin 并补认证"
                window.setStatus("正在认证…（首次连接手机会要连接码）")
            }
            is WirelessEvent.DataChannelNegotiated -> {
                log("数据通道协商完成：${event.frequencyMhz} MHz")
                stage = "协商完成，要开热点"
                window.setStatus("正在开热点，等手机加入 WiFi…")
            }
            is WirelessEvent.WifiNetworkReady -> {
                log("WiFi 网络已开好：${event.frequencyMhz} MHz")
                stage = "热点已就绪（${options.hotspotSsid}），等手机加入"
                window.setStatus("热点已开好，等手机连上来…")
            }
            is WirelessEvent.ConnectionInfoSent -> {
                log("连接串已发给手机，端口 ${event.port}，等手机连上来")
                stage = "连接串已发，等手机连 TCP ${event.port}"
                window.setStatus("等手机加入 WiFi（${options.hotspotSsid}）…")
            }
            is WirelessEvent.ReconnectAwaitingPhone -> log("等手机确认（状态 ${event.status}）")
            is WirelessEvent.Ended -> {
                ended = true
                endReason = event.reason
                log("会话结束：${event.reason}")
                window.setStatus("会话结束：${event.reason}")
            }
        }
    }

    private fun onSessionEvent(event: SessionEvent) {
        log("会话事件：$event")
        when (event) {
            is SessionEvent.VideoStarted -> window.clearQr()
            is SessionEvent.VideoNegotiated -> window.setStatus("正在投屏…")
            else -> Unit
        }
    }

    private fun log(message: String) {
        val line = "${LocalTime.now().format(time)}  $message"
        println(line)
        if (this::window.isInitialized) window.appendLog(line)
        runCatching { logFile?.appendText(line + "\n", Charsets.UTF_8) }
    }

    private fun loadPhone(): Pair<String, String>? = runCatching {
        val f = phoneFile ?: return@runCatching null
        if (!f.isFile) return@runCatching null
        val props = java.util.Properties().apply { f.inputStream().use { load(it) } }
        val address = props.getProperty("address")?.trim().orEmpty()
        if (address.isEmpty()) null else address to "HiCar Phone"
    }.getOrNull()

    private fun savePhone(address: String) = runCatching {
        val f = phoneFile ?: return@runCatching
        val props = java.util.Properties().apply {
            setProperty("address", address)
            setProperty("name", "HiCar Phone")
        }
        f.outputStream().use { props.store(it, "last paired phone") }
    }.let { }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun isWindows64() = isWindows() && com.sun.jna.Native.POINTER_SIZE == 8
}