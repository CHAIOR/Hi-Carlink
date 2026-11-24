package io.github.gcbond.openhc.pc

import java.io.File

/**
 * 让手机「主动发现」这台电脑：调用随包的 `hicar-ble-adv.exe`，用 WinRT 发 HiCar 的 BLE 广播
 * （FDEE 服务数据，载荷和安卓车机一致：版本/能力/车型编号/序列号）。
 *
 * 为什么用独立进程：Windows 的 BLE 广播只能经 WinRT 调，Java 这边没有对应 API；
 * 这个 exe 是 C# 编的，主程序用 ProcessBuilder 起它，手机连上后就杀掉。
 *
 * 注意：Windows 发的是**不可连接**广播，且发送地址取决于蓝牙栈；能不能被手机识别、
 * 识别后能不能连上，都要真机验证。失败不影响扫码流程。
 */
internal class BleBeaconAdv(private val log: (String) -> Unit = {}) {
    private var process: Process? = null
    private var sequence = 0

    /** 找随包 exe：优先 app/ 目录（安装目录下），其次当前目录。 */
    private fun exeFile(): File? {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/hicar-ble-adv.exe"),
            File(System.getProperty("user.dir"), "hicar-ble-adv.exe"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** 开始广播；已经在广播就重启一次（序列号会变，手机会当成新的一次发现）。 */
    fun start(seconds: Int = 3600) {
        val exe = exeFile()
        if (exe == null) {
            log("BLE 广播：没找到 hicar-ble-adv.exe，跳过（不影响扫码）")
            return
        }
        stop()
        sequence = (sequence + 1) and 0xFF
        try {
            val p = ProcessBuilder(exe.absolutePath, seconds.toString(), sequence.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            process = p
            log("BLE 广播：已启动（序列号 $sequence，最长 ${seconds} 秒），手机 HiCar 列表里可能会直接看到这台车")
            Thread({
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                if (process === p) process = null
                if (code != 0) log("BLE 广播：退出码 $code（可能不支持 BLE 广播，扫码照常用）")
            }, "HiCarlink-BleAdv").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            log("BLE 广播：起不来（${e.javaClass.simpleName}：${e.message}），扫码照常用")
        }
    }

    fun stop() {
        process?.let { p ->
            runCatching { p.destroy() }
            process = null
        }
    }
}
