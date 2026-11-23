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
    exitProcess(PcApp(options).run())
}

/**
 * 一次无线会话：蓝牙握手/认证 → 开热点 → 手机连 TCP → 投屏 → 窗口上屏 + 鼠标触摸回传 + 声音。
 */
internal class PcApp(private val options: PcOptions) {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile private var ended = false
    @Volatile private var endReason: WirelessEndReason? = null

    private lateinit var window: VideoWindow
    private lateinit var runner: SocketRunner
    private lateinit var bridge: WirelessRunnerBridge
    private lateinit var platform: WindowsPlatform

    fun run(): Int {
        log("Hi Carlink PC（实验）")
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
                WinShell.localBluetoothAddress().also { log("本机蓝牙地址：$it") }
            } catch (e: Exception) {
                log("读不到本机蓝牙地址：${e.message}；请用 --bt 手工指定")
                return 1
            }
        } else {
            options.bluetoothAddress
        }

        val display = DisplaySettings(options.width, options.height, options.fps, options.dpi, 1.0)
        val config = try {
            WirelessReceiverConfig(
                identity = identity,
                bluetoothAddress = btAddress,
                display = display,
                trustStore = trust,
                supportsUsb = false,
                touchInput = true,
                // 第一版只放 PCM：手机按声明优先发 PCM，Windows 上直接用 Java Sound 放。
                audio = AudioSettings(codec = AudioCodec.PCM),
                timeouts = WirelessTimeouts(networkStartMillis = 20_000),
            )
        } catch (e: IllegalArgumentException) {
            log("配置不合法：${e.message}")
            return 1
        }

        window = VideoWindow(options.width, options.height, ::sendTouch) {
            log("窗口被关掉，结束会话")
            runner.stop()
        }
        window.show()
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
        bridge = WirelessRunnerBridge(session, session::start) { output -> platform.execute(output) }
        runner = SocketRunner(bridge, ::onSessionEvent, SocketRunner.Options(threadName = "HiCarlink-PC", log = { log("[执行器] $it") }))

        Runtime.getRuntime().addShutdownHook(thread(start = false, name = "pc-shutdown") {
            if (!ended) {
                log("收到退出信号，按协议结束会话")
                runner.stop()
                runner.awaitTermination(5_000)
            }
            runCatching { fileOut?.close() }
        })
        runner.start()
        options.durationSeconds?.let { seconds ->
            thread(isDaemon = true) {
                if (!runner.awaitTermination(java.util.concurrent.TimeUnit.SECONDS.toMillis(seconds))) {
                    log("已运行 $seconds 秒，结束会话")
                    runner.stop()
                }
            }
        }
        while (!runner.awaitTermination(60_000)) {
            // 等会话结束。
        }
        platform.closeAll()
        window.close()
        audio.close()
        runCatching { fileOut?.close() }
        log("结束：${endReason ?: "未知"}")
        return 0
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
                log("蓝牙监听已开好，连接码 ${event.pinCode}")
                window.showQr(event.qrCode)
                window.setStatus("连接码 ${event.pinCode}\n手机用 HiCar 扫上面的二维码")
            }
            is WirelessEvent.PhoneBluetoothConnected -> {
                log("手机蓝牙连上来了")
                window.setStatus("手机蓝牙已连接，正在握手…")
            }
            is WirelessEvent.BluetoothHandshakeCompleted -> {
                log("蓝牙握手完成（加密=${event.encrypted}）")
                window.setStatus("正在认证…（首次连接手机会要连接码）")
            }
            is WirelessEvent.DataChannelNegotiated -> {
                log("数据通道协商完成：${event.frequencyMhz} MHz")
                window.setStatus("正在开热点，等手机加入 WiFi…")
            }
            is WirelessEvent.WifiNetworkReady -> {
                log("WiFi 网络已开好：${event.frequencyMhz} MHz")
                window.setStatus("热点已开好，等手机连上来…")
            }
            is WirelessEvent.ConnectionInfoSent -> {
                log("连接串已发给手机，端口 ${event.port}，等手机连上来")
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

    private fun log(message: String) = println("${LocalTime.now().format(time)}  $message")

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun isWindows64() = isWindows() && com.sun.jna.Native.POINTER_SIZE == 8
}