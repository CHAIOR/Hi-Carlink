package io.github.gcbond.openhc.pc

import io.github.gcbond.openhc.core.session.WifiNetwork
import io.github.gcbond.openhc.core.session.WirelessAction
import io.github.gcbond.openhc.core.session.WirelessChannel
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.core.session.WirelessOutput
import java.util.UUID

/** 平台结果交回无线会话的入口（生产实现由 PcMain 经 SocketRunner.submit 排到协议线程上）。 */
internal interface PlatformResults {
    fun onListening(id: Int, port: Int)
    fun onAccepted(id: Int, remote: String)
    fun onConnected(id: Int)
    fun onData(id: Int, data: ByteArray)
    fun onClosed(id: Int)
    fun onIoError(id: Int, message: String)
    fun onWifiNetworkReady(id: Int, network: WifiNetwork)
}

/** 一条 RFCOMM 连接（或监听接受下来的那条）上的回调，平台自己的线程上调用。 */
internal interface RfcommEvents {
    /** 监听开好了：[port] 是系统分配的通道号（1～30），拿不到交 0。 */
    fun onListening(port: Int)

    /** 手机连上来了：[remote] 是对端蓝牙地址 `AA:BB:CC:DD:EE:FF`。 */
    fun onAccepted(remote: String)
    fun onData(bytes: ByteArray)
    fun onClosed()
    fun onError(message: String)
}

/**
 * 一条 RFCOMM 监听 + 它接受下来的连接。
 *
 * **关监听不能把关掉的连接一起关掉**：会话在手机连上认证子通道后会先关掉两个监听（控制通道监听、认证子通道监听），
 * 这时控制通道和认证通道的连接还得继续用。安卓的 `BluetoothServerSocket.close()` 就只撤服务记录，不影响已接受的 socket。
 */
internal interface RfcommHandle {
    fun write(bytes: ByteArray)

    /** 只关监听：撤掉 SDP 记录、不再接受新连接；已经接受的连接照常收发。 */
    fun closeListener()

    /** 只关这条连接。 */
    fun closeConnection()

    /** 两个都关（收尾兜底）。 */
    fun close() {
        closeListener()
        closeConnection()
    }
}

/** 热点（或直连组）的回调。 */
internal interface WifiNetworkEvents {
    fun onReady(network: WifiNetwork)
    fun onLost()
    fun onFailed(message: String)
}

internal interface WifiNetworkHandle {
    fun close()
}

/**
 * 把无线编排器的平台动作接到本机：蓝牙 RFCOMM、热点。
 * 和安卓版 `WirelessPlatformExecutor` 同一套路：动作在协议线程上进来，平台层在自己的线程上做完，
 * 结果经 [results]（SocketRunner.submit）按 id 交回会话。
 */
internal class WindowsPlatform(
    private val rfcomm: RfcommBackend,
    private val wifi: WifiNetworkBackend,
    private val results: PlatformResults,
    private val onEvent: (WirelessEvent) -> Unit,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    private val handles = HashMap<Int, RfcommHandle>()
    private val listeners = HashMap<Int, RfcommHandle>()
    private val networks = HashMap<Int, WifiNetworkHandle>()

    private var writesLogged = 0

    fun execute(output: WirelessOutput) {
        // 蓝牙/热点动作记一行，方便对照协议走到哪一步（写字节只记前几次）。
        when (output) {
            is WirelessAction.RfcommWrite -> if (writesLogged++ < 6) log("发送蓝牙数据 ${output.bytes.size} 字节")
            is WirelessAction -> log("动作：${output::class.simpleName} ${output}")
            else -> Unit
        }
        when (output) {
            is WirelessAction.ListenRfcomm -> listen(output)
            is WirelessAction.ConnectRfcomm -> dial(output)
            is WirelessAction.RfcommWrite -> write(output)
            is WirelessAction.RfcommClose -> closeRfcomm(output.id)
            is WirelessAction.StartWifiNetwork -> startNetwork(output.id)
            is WirelessAction.StopWifiNetwork -> stopNetwork(output.id)
            is WirelessEvent -> onEvent(output)
            is WirelessOutput.Common -> log("平台层收到了 TCP 输出，忽略：$output")
        }
    }

    private fun listen(a: WirelessAction.ListenRfcomm) {
        log("开蓝牙监听（${a.channel.label}，服务 ${a.uuid}）")
        val events = object : RfcommEvents {
            override fun onListening(port: Int) = results.onListening(a.id, port)
            override fun onAccepted(remote: String) = results.onAccepted(a.acceptId, remote)
            override fun onData(bytes: ByteArray) = results.onData(a.acceptId, bytes)
            override fun onClosed() = results.onClosed(a.acceptId)
            override fun onError(message: String) = results.onIoError(a.id, message)
            override fun toString() = "listen(${a.id}/${a.acceptId})"
        }
        val handle = try {
            rfcomm.listen(a.uuid, a.needChannelNumber, events)
        } catch (e: RuntimeException) {
            results.onIoError(a.id, "${e.javaClass.simpleName}：${e.message}")
            return
        }
        synchronized(lock) {
            listeners[a.id] = handle
            handles[a.acceptId] = handle
        }
    }

    private fun dial(a: WirelessAction.ConnectRfcomm) {
        log("连手机的蓝牙服务（${a.channel.label}）")
        val events = object : RfcommEvents {
            override fun onListening(port: Int) = Unit
            override fun onAccepted(remote: String) = Unit
            override fun onData(bytes: ByteArray) = results.onData(a.id, bytes)
            override fun onClosed() = results.onClosed(a.id)
            override fun onError(message: String) = results.onIoError(a.id, message)
        }
        val handle = try {
            rfcomm.connect(a.address, a.uuid, a.rfcommChannel, events)
        } catch (e: RuntimeException) {
            results.onIoError(a.id, "${e.javaClass.simpleName}：${e.message}")
            return
        }
        synchronized(lock) { handles[a.id] = handle }
    }

    private fun write(a: WirelessAction.RfcommWrite) {
        val handle = synchronized(lock) { handles[a.id] } ?: return
        try {
            handle.write(a.bytes)
        } catch (e: RuntimeException) {
            results.onIoError(a.id, "写蓝牙连接失败：${e.javaClass.simpleName}：${e.message}")
        }
    }

    private fun closeRfcomm(id: Int) {
        // 会话给的 id 可能是监听 id，也可能是接受连接的 id：两者对应同一个句柄，但只该关对应的那一半。
        val listener = synchronized(lock) { listeners.remove(id) }
        if (listener != null) {
            runCatching { listener.closeListener() }
            return
        }
        val connection = synchronized(lock) { handles.remove(id) }
        connection?.let { runCatching { it.closeConnection() } }
    }

    private fun startNetwork(id: Int) {
        log("开 WiFi 网络（热点）")
        val events = object : WifiNetworkEvents {
            override fun onReady(network: WifiNetwork) = results.onWifiNetworkReady(id, network)
            override fun onLost() = results.onClosed(id)
            override fun onFailed(message: String) = results.onIoError(id, message)
        }
        val handle = try {
            wifi.start(events)
        } catch (e: RuntimeException) {
            results.onIoError(id, "${e.javaClass.simpleName}：${e.message}")
            return
        }
        synchronized(lock) { networks[id] = handle }
    }

    private fun stopNetwork(id: Int) {
        val handle = synchronized(lock) { networks.remove(id) } ?: return
        runCatching { handle.close() }
    }

    /** 会话没能按顺序收尾时的兜底。 */
    fun closeAll() {
        val (h, n) = synchronized(lock) {
            val h = (listeners.values + handles.values).distinct()
            val n = networks.values.toList()
            listeners.clear(); handles.clear(); networks.clear()
            h to n
        }
        h.forEach { runCatching { it.close() } }
        n.forEach { runCatching { it.close() } }
    }
}

internal interface RfcommBackend {
    /** 开一个带 SDP 记录的 RFCOMM 服务端（UUID = [uuid]，不加密、不认证），只接受一条连接。 */
    fun listen(uuid: UUID, needChannelNumber: Boolean, events: RfcommEvents): RfcommHandle

    /** 连手机的 RFCOMM 服务；[channel] 为 null 时按 SDP 查。 */
    fun connect(address: String, uuid: UUID, channel: Int?, events: RfcommEvents): RfcommHandle
}

internal interface WifiNetworkBackend {
    fun start(events: WifiNetworkEvents): WifiNetworkHandle
}

/** 还没接上 Windows 蓝牙时的占位：立刻报错，让界面把原因说清楚，而不是卡住。 */
internal object UnavailableRfcomm : RfcommBackend {
    override fun listen(uuid: UUID, needChannelNumber: Boolean, events: RfcommEvents): RfcommHandle {
        events.onError("Windows 蓝牙 RFCOMM 还没接上（占位实现）")
        return object : RfcommHandle {
            override fun write(bytes: ByteArray) = Unit
            override fun closeListener() = Unit
            override fun closeConnection() = Unit
        }
    }

    override fun connect(address: String, uuid: UUID, channel: Int?, events: RfcommEvents): RfcommHandle {
        events.onError("Windows 蓝牙 RFCOMM 还没接上（占位实现）")
        return object : RfcommHandle {
            override fun write(bytes: ByteArray) = Unit
            override fun closeListener() = Unit
            override fun closeConnection() = Unit
        }
    }
}

/** 还没接上移动热点时的占位。 */
internal object UnavailableWifi : WifiNetworkBackend {
    override fun start(events: WifiNetworkEvents): WifiNetworkHandle {
        events.onFailed("Windows 移动热点还没接上（占位实现）")
        return object : WifiNetworkHandle {
            override fun close() = Unit
        }
    }
}