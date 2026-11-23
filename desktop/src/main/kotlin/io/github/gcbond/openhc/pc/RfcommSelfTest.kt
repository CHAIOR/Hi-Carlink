package io.github.gcbond.openhc.pc

import java.util.UUID

/**
 * 自检：在这台电脑上开一个 RFCOMM 服务端（HiCar 控制通道的服务 UUID），打印系统分配的通道号，
 * 8 秒后关掉。用来确认「Windows 蓝牙能当服务端、能登记 SDP」——真连手机还要手机配合。
 */
fun main() {
    val uuid = UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb")
    println("== RFCOMM 自检（UUID $uuid）")
    val backend = WinRfcommBackend()
    val events = object : RfcommEvents {
        override fun onListening(port: Int) = println("监听已开好，系统分配通道号 $port")
        override fun onAccepted(remote: String) = println("有设备连上来了：$remote")
        override fun onData(bytes: ByteArray) = println("收到 ${bytes.size} 字节")
        override fun onClosed() = println("连接被对端关闭")
        override fun onError(message: String) = println("出错：$message")
    }
    val handle = try {
        backend.listen(uuid, true, events)
    } catch (e: RuntimeException) {
        println("启动失败：${e.javaClass.simpleName}：${e.message}")
        return
    }
    Thread.sleep(8_000)
    handle.close()
    println("== 自检结束")
}