package io.github.gcbond.openhc.pc

import java.nio.file.Path

/** 命令行参数。第一版只做无线，所以没有 USB 相关项。 */
internal class PcOptions(
    /** 连接参数 JSON（openhc-identity.json）。 */
    val identity: Path,
    /** 信任库目录（首次配对时写入，之后免码回连）。 */
    val trustDir: Path,
    /** 本机经典蓝牙的公共地址：手机按二维码里的它连过来，必须真实。 */
    val bluetoothAddress: String,
    /** 电脑开的热点的名称和口令（要和 Windows「移动热点」里设置的一致）。 */
    val hotspotSsid: String,
    val hotspotPassword: String,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val dpi: Int = 160,
    val videoOut: Path? = null,
    val durationSeconds: Long? = null,
    /** 报给手机的热点频率（MHz）：Windows 读不到真实信道，先按 2.4G 第 6 信道。 */
    val frequencyMhz: Int = 2437,
) {
    companion object {
        val USAGE = """
            用法：hi-carlink-pc --identity <openhc-identity.json> --trust-dir <目录> --bt <AA:BB:CC:DD:EE:FF>
                                [--ssid <热点名> --password <热点口令>] [--bt auto] [--video-out <文件>] [--duration <秒>]

              --identity <文件>   连接参数（必填）
              --trust-dir <目录>  信任库目录，首次配对后写入（必填）
              --bt <地址|auto>    本机蓝牙地址，默认 auto（自动读系统里的）
              --ssid <名称>       要用的 Windows 移动热点名称（默认 HiCarlink）
              --password <口令>   热点口令，至少 8 位（默认 hicarlink123）
              --width/--height/--fps/--dpi  声明给手机的画布，默认 1280x720@30、dpi 160
              --freq <MHz>       报给手机的热点频率（Windows 读不到真实信道，默认 2437）
              --video-out <文件>  把 H.264 原样写到文件（调试用）
              --duration <秒>     运行这么久后自动结束
              --help              显示这段说明
        """.trimIndent()

        fun parse(args: Array<String>): PcOptions {
            var identity: Path? = null
            var trustDir: Path? = null
            var bt = "auto"
            var ssid = "HiCarlink"
            var password = "hicarlink123"
            var width = 1280
            var height = 720
            var fps = 30
            var dpi = 160
            var videoOut: Path? = null
            var duration: Long? = null
            var freq = 2437
            var i = 0
            fun value(name: String): String {
                if (i + 1 >= args.size) throw IllegalArgumentException("$name 后面要跟一个值")
                return args[++i]
            }
            while (i < args.size) {
                when (val a = args[i]) {
                    "--help", "-h" -> throw HelpRequested()
                    "--identity" -> identity = Path.of(value(a))
                    "--trust-dir" -> trustDir = Path.of(value(a))
                    "--bt" -> bt = value(a)
                    "--ssid" -> ssid = value(a)
                    "--password" -> password = value(a)
                    "--width" -> width = value(a).toIntOrNull() ?: throw IllegalArgumentException("--width 要是整数")
                    "--height" -> height = value(a).toIntOrNull() ?: throw IllegalArgumentException("--height 要是整数")
                    "--fps" -> fps = value(a).toIntOrNull() ?: throw IllegalArgumentException("--fps 要是整数")
                    "--dpi" -> dpi = value(a).toIntOrNull() ?: throw IllegalArgumentException("--dpi 要是整数")
                    "--video-out" -> videoOut = Path.of(value(a))
                    "--duration" -> duration = value(a).toLongOrNull() ?: throw IllegalArgumentException("--duration 要是秒数")
                    "--freq" -> freq = value(a).toIntOrNull() ?: throw IllegalArgumentException("--freq 要是 MHz 数")
                    else -> throw IllegalArgumentException("不认识的参数 $a")
                }
                i++
            }
            require(width > 0 && height > 0 && fps > 0) { "画布参数要是正数" }
            require(password.length >= 8) { "热点口令至少 8 位" }
            return PcOptions(
                identity = identity ?: throw IllegalArgumentException("缺少 --identity"),
                trustDir = trustDir ?: throw IllegalArgumentException("缺少 --trust-dir"),
                bluetoothAddress = bt,
                hotspotSsid = ssid,
                hotspotPassword = password,
                width = width,
                height = height,
                fps = fps,
                dpi = dpi,
                videoOut = videoOut,
                durationSeconds = duration,
                frequencyMhz = freq,
            )
        }
    }
}

internal class HelpRequested : RuntimeException()