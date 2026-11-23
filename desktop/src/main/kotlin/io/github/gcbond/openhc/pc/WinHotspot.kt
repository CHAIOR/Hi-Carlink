package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.session.WifiNetwork
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Windows「移动热点」。
 *
 * **不依赖 PowerShell**：有的机器（包括作者这台和用户那台）上 Java 起 powershell.exe 会被系统策略挡住
 * （`CreateProcess error=5 拒绝访问`），所以这里的主路径是「等热点出现」：
 *
 * 1. 已经在 192.168.137.x 上开了热点 → 直接拿来用；
 * 2. 否则试一次自动开（PowerShell + WinRT `NetworkOperatorTetheringManager`，能开就省事，开不了不报错）；
 * 3. 然后最多等 [WAIT_MS]，等车主在「设置 → 网络和 Internet → 移动热点」里打开它（窗口里会提示要设的名字和口令）。
 *
 * 热点网关固定是 `192.168.137.1`（Windows ICS 的约定），BSSID 取热点网卡的 MAC。
 * 频率读不到真实值，按 2.4 GHz 第 6 信道报给手机（见 [frequencyMhz]）。
 */
internal class WinHotspotBackend(
    private val ssid: String,
    private val password: String,
    private val frequencyMhz: Int = 2437,
    private val log: (String) -> Unit = {},
) : WifiNetworkBackend {
    override fun start(events: WifiNetworkEvents): WifiNetworkHandle {
        val worker = Worker(events)
        worker.start()
        return worker
    }

    private inner class Worker(private val events: WifiNetworkEvents) : WifiNetworkHandle {
        @Volatile private var closed = false
        @Volatile private var done = false

        fun start() {
            Thread({ run() }, "HiCarlink-Hotspot").apply { isDaemon = true }.start()
        }

        private fun run() {
            findHotspot()?.let {
                ready(it)
                return
            }
            // 自动开一次：能开就开，开不了（被策略挡住、驱动不支持）就转人工。
            Thread({
                val auto = runCatching {
                    WinShell.run("hotspot.ps1", listOf("-Action", "start", "-Ssid", ssid, "-Password", password), 30)
                }
                if (auto.isSuccess) {
                    val out = auto.getOrThrow()
                    log("尝试自动开热点：${out["start"] ?: out["ERROR"] ?: "没有回话"}")
                } else {
                    log("自动开热点不可用（${auto.exceptionOrNull()?.message}），改成等车主手动开")
                }
            }, "HiCarlink-Hotspot-Auto").apply { isDaemon = true }.start()

            val deadline = System.currentTimeMillis() + WAIT_MS
            while (!closed && !done && System.currentTimeMillis() < deadline) {
                Thread.sleep(400)
                findHotspot()?.let {
                    ready(it)
                    return
                }
            }
            if (!closed && !done) {
                events.onFailed(
                    "没等到热点。请打开「设置 → 网络和 Internet → 移动热点」，名字设成 $ssid、密码设成 $password，" +
                        "打开后程序会自动接上（也可以先把热点开好再启动）",
                )
            }
        }

        private fun ready(found: Hotspot) {
            if (closed || done) return
            done = true
            val net = WifiNetwork(
                localIp = found.ip,
                ssid = ssid,
                bssid = found.mac,
                passphrase = password,
                frequencyMhz = frequencyMhz,
            )
            log("热点就绪：$net（网卡 ${found.iface}，${found.prefix} 位前缀）")
            events.onReady(net)
        }

        override fun close() {
            closed = true
            // 热点是车主开的就不去关它；我们自己开起来的也不关：Windows 移动热点在关掉后要等一会儿才能再开。
        }
    }

    private class Hotspot(val ip: String, val mac: String, val iface: String, val prefix: Int)

    /**
     * 找 Windows 移动热点的那块网卡：IPv4 在 `192.168.137.0/24` 上。
     * 不按网卡名找——中文系统上叫「本地连接* 11」之类的名字，随机器而变。
     */
    private fun findHotspot(): Hotspot? {
        return try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (!ip.startsWith("192.168.137.")) continue
                    val mac = nif.hardwareAddress?.joinToString(":") { "%02x".format(it) }
                    if (mac == null || mac.count { it == ':' } != 5 || mac == "00:00:00:00:00:00") {
                        // 有的虚拟网卡读不到 MAC；BSSID 只是给手机定位用，占位也行。
                        return Hotspot(ip, "02:00:00:00:00:00", nif.name, 24)
                    }
                    return Hotspot(ip, mac, nif.name, 24)
                }
            }
            null
        } catch (e: Exception) {
            log("枚举网卡失败：${e.javaClass.simpleName}：${e.message}")
            null
        }
    }

    private companion object {
        /** 等车主手动开热点的上限。 */
        const val WAIT_MS = 120_000L
    }
}