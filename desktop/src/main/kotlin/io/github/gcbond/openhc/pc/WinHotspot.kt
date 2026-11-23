package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.session.WifiNetwork

/**
 * Windows「移动热点」：调 `NetworkOperatorTetheringManager`（WinRT，PowerShell 里调），
 * 不碰过时的 `netsh wlan hostednetwork`（这台机器的 Intel AX210 驱动已不支持）。
 *
 * 热点固定是 192.168.137.1/24（Windows ICS 的默认网关），BSSID 取 Wi-Fi Direct 虚拟网卡的 MAC。
 * 频率读不到真实值（Windows 不公开热点的信道），按 2.4 GHz 第 6 信道（2437）报给手机——先这样，
 * 手机连不上时再让用户用 `--freq` 指定。
 */
internal class WinHotspotBackend(
    private val ssid: String,
    private val password: String,
    private val frequencyMhz: Int = 2437,
    private val log: (String) -> Unit = {},
) : WifiNetworkBackend {
    override fun start(events: WifiNetworkEvents): WifiNetworkHandle {
        val worker = HotspotWorker(events)
        worker.start()
        return worker
    }

    private inner class HotspotWorker(private val events: WifiNetworkEvents) : WifiNetworkHandle {
        @Volatile private var closed = false

        fun start() {
            Thread({ runStart() }, "HiCarlink-Hotspot").apply { isDaemon = true }.start()
        }

        private fun runStart() {
            val output = try {
                WinShell.run("hotspot.ps1", listOf("-Action", "start", "-Ssid", ssid, "-Password", password))
            } catch (e: Exception) {
                events.onFailed("开热点失败：${e.javaClass.simpleName}：${e.message}")
                return
            }
            if (closed) return
            if (output.containsKey("ERROR")) {
                events.onFailed("Windows 不给开热点：${output["ERROR"]}")
                return
            }
            val status = output["start"]
            if (status != "Success") {
                events.onFailed("Windows 开热点返回 $status（$status 非 Success 时多半是网卡驱动不支持，或在设置里被关掉了）")
                return
            }
            val ip = output["ip"]?.takeIf { it.isNotBlank() }
            if (ip == null) {
                events.onFailed("热点开起来了，但没找到 192.168.137.x 这个网关地址")
                return
            }
            val mac = output["mac"].orEmpty().replace("-", ":").lowercase()
            val bssid = if (mac.count { it == ':' } == 5) mac else "02:00:00:00:00:00"
            val network = WifiNetwork(
                localIp = ip,
                ssid = output["ssid"] ?: ssid,
                bssid = bssid,
                passphrase = output["password"] ?: password,
                frequencyMhz = (output["freq"]?.toIntOrNull() ?: frequencyMhz).coerceAtLeast(2412),
            )
            log("热点已开好：$network（BSSID 取自 $mac）")
            events.onReady(network)
        }

        override fun close() {
            closed = true
            Thread({
                runCatching { WinShell.run("hotspot.ps1", listOf("-Action", "stop"), timeoutSeconds = 20) }
                    .onFailure { log("关热点失败：${it.message}") }
            }, "HiCarlink-Hotspot-Stop").apply { isDaemon = true }.start()
        }
    }

}