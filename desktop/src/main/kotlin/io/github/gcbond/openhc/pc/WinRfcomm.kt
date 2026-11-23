package io.github.gcbond.openhc.pc

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.UUID

/**
 * Windows 经典蓝牙 RFCOMM，走 Winsock 的 `AF_BTH`（Windows 10 起内置，不依赖 BlueCove 那套 JNI）。
 *
 * - 服务端：`socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)` → `bind`（服务类 UUID + 任意通道）→
 *   `WSASetServiceW` 登记 SDP 记录 → `listen` → `accept`；
 * - 客户端：`socket` → `connect`（通道号或按 UUID 让系统查 SDP）。
 *
 * 结构体按 **x64** 布局手工排（SOCKADDR_BTH 40 字节、WSAQUERYSETW 120 字节、CSADDR_INFO 40 字节），
 * 和 Windows SDK 里的定义一致。只在 64 位 JVM 上用；32 位要换另一份布局（本程序不提供）。
 */
internal class WinRfcommBackend : RfcommBackend {
    init {
        check(Native.POINTER_SIZE == 8) { "RFCOMM 实现只支持 64 位 JVM" }
        Ws2.startup()
    }

    override fun listen(uuid: UUID, needChannelNumber: Boolean, events: RfcommEvents): RfcommHandle {
        val server = Server(uuid, events)
        server.start()
        return server
    }

    override fun connect(address: String, uuid: UUID, channel: Int?, events: RfcommEvents): RfcommHandle =
        Client(address, uuid, channel, events).also { it.start() }
}

// ---------------------------------------------------------------- 常量

private const val AF_BTH = 32
private const val SOCK_STREAM = 1
private const val BTHPROTO_RFCOMM = 3
private const val BT_PORT_ANY = 0xFFFFFFFFL
private const val NS_BTH = 16
private const val RNRSERVICE_REGISTER = 0
private const val RNRSERVICE_DELETE = 1
private const val SERVICE_MULTIPLE = -1
private const val SOCKADDR_BTH_SIZE = 30
private const val WSAQUERYSET_SIZE = 120
private const val CSADDR_INFO_SIZE = 40
private const val INVALID_SOCKET = -1L

/** ws2_32 的 JNA 绑定。SOCKET 是 UINT_PTR，按 64 位长整型传。 */
private interface Ws2 : Library {
    fun WSAStartup(wVersionRequested: Int, lpWSAData: Pointer): Int
    fun WSACleanup(): Int
    fun socket(af: Int, type: Int, protocol: Int): Long
    fun bind(s: Long, name: Pointer, namelen: Int): Int
    fun listen(s: Long, backlog: Int): Int
    fun accept(s: Long, addr: Pointer, addrlen: IntByReference): Long
    fun connect(s: Long, name: Pointer, namelen: Int): Int
    fun recv(s: Long, buf: ByteArray, len: Int, flags: Int): Int
    fun send(s: Long, buf: ByteArray, len: Int, flags: Int): Int
    fun closesocket(s: Long): Int
    fun getsockname(s: Long, name: Pointer, namelen: IntByReference): Int
    fun getpeername(s: Long, name: Pointer, namelen: IntByReference): Int
    fun WSASetServiceW(lpqsRegInfo: Pointer, essoperation: Int, dwControlFlags: Int): Int
    fun WSAGetLastError(): Int

    companion object {
        val INSTANCE: Ws2 by lazy { Native.load("ws2_32", Ws2::class.java) }
        private var started = false

        fun startup() {
            if (started) return
            val data = Memory(400)
            val rc = INSTANCE.WSAStartup(0x202, data)
            check(rc == 0) { "WSAStartup 失败（$rc）" }
            started = true
        }

        fun lastError(): Int = INSTANCE.WSAGetLastError()
    }
}

// ---------------------------------------------------------------- 结构体构造

/** 把 `AA:BB:CC:DD:EE:FF` 变成 SOCKADDR_BTH 里那个 48 位小端地址。 */
private fun btAddressToLong(address: String): Long {
    val parts = address.split(":", "-").filter { it.isNotEmpty() }
    require(parts.size == 6) { "蓝牙地址格式不对：$address" }
    // Windows 的 SOCKADDR_BTH 里，这个 48 位值按“文本顺序的大端整数”放，写进去时正好是反序字节。
    var v = 0L
    for (i in 0..5) v = v or (parts[i].toInt(16).toLong() shl (8 * (5 - i)))
    return v
}

private fun longToBtAddress(v: Long): String {
    val sb = StringBuilder()
    for (i in 5 downTo 0) {
        if (i < 5) sb.append(':')
        sb.append("%02X".format(((v shr (8 * i)) and 0xFF).toInt()))
    }
    return sb.toString()
}

/** 写一个 SOCKADDR_BTH（x64，40 字节）。serviceClassId 传 null 时为零 GUID。 */
private fun sockaddrBth(btAddr: Long, uuid: UUID?, port: Long): Memory {
    val m = Memory(SOCKADDR_BTH_SIZE.toLong())
    m.clear()
    // ws2bth.h 里是 pack(1)：family@0、btAddr@2、serviceClassId@10、port@26，共 30 字节。
    m.setShort(0, AF_BTH.toShort())
    m.setLong(2, btAddr)
    if (uuid != null) writeGuid(m, 10L, uuid)
    m.setInt(26, port.toInt())
    return m
}

/** UUID → Windows GUID 的字节序（前三个字段小端，Data4 原样）。 */
private fun writeGuid(m: Memory, offset: Long, uuid: UUID) {
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    m.setInt(offset, (msb ushr 32).toInt())
    m.setShort(offset + 4, ((msb ushr 16) and 0xFFFF).toShort())
    m.setShort(offset + 6, (msb and 0xFFFF).toShort())
    for (i in 0..7) m.setByte(offset + 8 + i, ((lsb ushr (8 * (7 - i))) and 0xFF).toByte())
}

/** 登记/撤销 SDP 记录：WSAQUERYSETW + CSADDR_INFO + SOCKADDR_BTH 三段内存。 */
private fun setService(uuid: UUID, channel: Int, localBt: Long, register: Boolean): Int {
    val qs = Memory(WSAQUERYSET_SIZE.toLong()).apply { clear() }
    qs.setInt(0, WSAQUERYSET_SIZE)
    qs.setPointer(16, Memory(16).also { writeGuid(it, 0L, uuid) }) // lpServiceClassId
    qs.setInt(40, NS_BTH) // dwNameSpace
    qs.setInt(88, 1) // dwNumberOfCsAddrs
    val addr = sockaddrBth(localBt, uuid, channel.toLong())
    val cs = Memory(CSADDR_INFO_SIZE.toLong()).apply { clear() }
    cs.setPointer(0, addr)
    cs.setInt(8, SOCKADDR_BTH_SIZE)
    cs.setPointer(16, Pointer.NULL) // RemoteAddr: 无
    cs.setInt(24, 0)
    cs.setInt(32, BTHPROTO_RFCOMM) // iProtocol
    cs.setInt(36, SERVICE_MULTIPLE) // iType：可多条连接（我们只接受一条）
    qs.setPointer(96, cs)
    val chars = "HiCarlink RFCOMM".toCharArray()
    val nameMem = Memory((chars.size + 1) * 2L)
    for (i in chars.indices) nameMem.setShort((i * 2).toLong(), chars[i].code.toShort())
    nameMem.setShort((chars.size * 2).toLong(), 0)
    qs.setPointer(8, nameMem)
    return Ws2.INSTANCE.WSASetServiceW(qs, if (register) RNRSERVICE_REGISTER else RNRSERVICE_DELETE, 0)
}

// ---------------------------------------------------------------- 服务端

private class Server(
    private val uuid: UUID,
    private val events: RfcommEvents,
) : RfcommHandle {
    private var listenSocket: Long = INVALID_SOCKET
    private var connSocket: Long = INVALID_SOCKET
    private var channel = 0
    private var localBt = 0L
    private var closed = false
    private val writeLock = Any()

    fun start() {
        val fd = Ws2.INSTANCE.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (fd == INVALID_SOCKET) {
            events.onError("开蓝牙 socket 失败（WSA ${Ws2.lastError()}）：这台电脑没有可用的蓝牙射频？")
            return
        }
        listenSocket = fd
        val bindAddr = sockaddrBth(0, uuid, BT_PORT_ANY)
        val bindRc = Ws2.INSTANCE.bind(fd, bindAddr, SOCKADDR_BTH_SIZE)
        if (bindRc != 0) {
            val err = Ws2.lastError()
            val winErr = Native.getLastError()
            closeSocket()
            events.onError("绑定蓝牙服务失败（bind=$bindRc WSA=$err GetLastError=$winErr）：蓝牙没打开或驱动不支持 RFCOMM 服务端")
            return
        }
        val local = Memory(SOCKADDR_BTH_SIZE.toLong()).apply { clear() }
        val len = IntByReference(SOCKADDR_BTH_SIZE)
        if (Ws2.INSTANCE.getsockname(fd, local, len) != 0) {
            val err = Ws2.lastError()
            closeSocket()
            events.onError("读本地蓝牙地址失败（WSA $err）")
            return
        }
        localBt = local.getLong(2)
        channel = local.getInt(26) and 0xFF
        if (System.getenv("OPENHC_DEBUG") != null) {
            val hex = StringBuilder()
            for (i in 0 until SOCKADDR_BTH_SIZE) hex.append("%02X ".format(local.getByte(i.toLong())))
            println("getsockname raw: $hex channel=$channel addr=${longToBtAddress(localBt)}")
        }
        if (setService(uuid, channel, localBt, register = true) != 0) {
            val err = Ws2.lastError()
            closeSocket()
            events.onError("登记蓝牙服务（SDP）失败（WSA $err）")
            return
        }
        if (Ws2.INSTANCE.listen(fd, 1) != 0) {
            val err = Ws2.lastError()
            setService(uuid, channel, localBt, register = false)
            closeSocket()
            events.onError("监听蓝牙端口失败（WSA $err）")
            return
        }
        events.onListening(channel)
        Thread({ acceptLoop() }, "HiCarlink-Rfcomm-Server").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        val addr = Memory(SOCKADDR_BTH_SIZE.toLong()).apply { clear() }
        val len = IntByReference(SOCKADDR_BTH_SIZE)
        val fd = Ws2.INSTANCE.accept(listenSocket, addr, len)
        if (fd == INVALID_SOCKET) {
            if (!closed) events.onError("接受蓝牙连接失败（WSA ${Ws2.lastError()}）")
            return
        }
        synchronized(writeLock) { connSocket = fd }
        val peer = longToBtAddress(addr.getLong(2))
        events.onAccepted(peer)
        readLoop(fd)
    }

    private fun readLoop(fd: Long) {
        val buf = ByteArray(16384)
        try {
            while (!closed) {
                val n = Ws2.INSTANCE.recv(fd, buf, buf.size, 0)
                when {
                    n > 0 -> events.onData(buf.copyOf(n))
                    n == 0 -> {
                        events.onClosed()
                        return
                    }
                    else -> {
                        if (!closed) events.onError("读蓝牙连接失败（WSA ${Ws2.lastError()}）")
                        return
                    }
                }
            }
        } catch (e: RuntimeException) {
            if (!closed) events.onError("读蓝牙连接出错：${e.javaClass.simpleName}：${e.message}")
        }
    }

    override fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            val fd = connSocket
            if (fd == INVALID_SOCKET) return
            var off = 0
            while (off < bytes.size) {
                val n = Ws2.INSTANCE.send(fd, bytes.copyOfRange(off, bytes.size), bytes.size - off, 0)
                if (n <= 0) throw IllegalStateException("蓝牙写失败（WSA ${Ws2.lastError()}）")
                off += n
            }
        }
    }

    override fun close() {
        closed = true
        synchronized(writeLock) {
            if (connSocket != INVALID_SOCKET) {
                Ws2.INSTANCE.closesocket(connSocket)
                connSocket = INVALID_SOCKET
            }
        }
        if (listenSocket != INVALID_SOCKET) {
            if (channel in 1..30) setService(uuid, channel, localBt, register = false)
            Ws2.INSTANCE.closesocket(listenSocket)
            listenSocket = INVALID_SOCKET
        }
    }

    private fun closeSocket() {
        if (listenSocket != INVALID_SOCKET) {
            Ws2.INSTANCE.closesocket(listenSocket)
            listenSocket = INVALID_SOCKET
        }
    }
}

// ---------------------------------------------------------------- 客户端

private class Client(
    private val address: String,
    private val uuid: UUID,
    private val channel: Int?,
    private val events: RfcommEvents,
) : RfcommHandle {
    private var socket: Long = INVALID_SOCKET
    private var closed = false
    private val writeLock = Any()

    fun start() {
        Thread({ run() }, "HiCarlink-Rfcomm-Client").apply { isDaemon = true }.start()
    }

    private fun run() {
        val fd = Ws2.INSTANCE.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (fd == INVALID_SOCKET) {
            events.onError("开蓝牙 socket 失败（WSA ${Ws2.lastError()}）")
            return
        }
        socket = fd
        val port = (channel ?: 0).toLong()
        val addr = sockaddrBth(btAddressToLong(address), uuid, port)
        if (Ws2.INSTANCE.connect(fd, addr, SOCKADDR_BTH_SIZE) != 0) {
            val err = Ws2.lastError()
            Ws2.INSTANCE.closesocket(fd)
            socket = INVALID_SOCKET
            events.onError("连手机蓝牙服务失败（WSA $err）：手机不在附近、没配对，或蓝牙关着")
            return
        }
        events.onListening(0)
        val buf = ByteArray(16384)
        while (!closed) {
            val n = Ws2.INSTANCE.recv(fd, buf, buf.size, 0)
            when {
                n > 0 -> events.onData(buf.copyOf(n))
                n == 0 -> {
                    events.onClosed()
                    return
                }
                else -> {
                    if (!closed) events.onError("读蓝牙连接失败（WSA ${Ws2.lastError()}）")
                    return
                }
            }
        }
    }

    override fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            val fd = socket
            if (fd == INVALID_SOCKET) return
            var off = 0
            while (off < bytes.size) {
                val n = Ws2.INSTANCE.send(fd, bytes.copyOfRange(off, bytes.size), bytes.size - off, 0)
                if (n <= 0) throw IllegalStateException("蓝牙写失败（WSA ${Ws2.lastError()}）")
                off += n
            }
        }
    }

    override fun close() {
        closed = true
        synchronized(writeLock) {
            if (socket != INVALID_SOCKET) {
                Ws2.INSTANCE.closesocket(socket)
                socket = INVALID_SOCKET
            }
        }
    }
}

/** 诊断：确认 WSAGetLastError 这条链路能拿到错误码，并试试几种 bind 参数。 */
internal fun rfcommDebug(): String {
    Ws2.startup()
    val sb = StringBuilder()
    val bad = Ws2.INSTANCE.socket(999, SOCK_STREAM, 0)
    val badErr = Native.getLastError()
    sb.append("invalid-af socket=$bad GetLastError(immediate)=").append(badErr).append('\n')
    val fd = Ws2.INSTANCE.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
    sb.append("AF_BTH socket=$fd GetLastError=").append(Native.getLastError()).append('\n')
    if (fd != INVALID_SOCKET) {
        for (len in intArrayOf(SOCKADDR_BTH_SIZE, 32, 30)) {
            val a = sockaddrBth(0, UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"), BT_PORT_ANY)
            val rc = Ws2.INSTANCE.bind(fd, a, len)
            val wsa = Native.getLastError()
            sb.append("bind(len=").append(len).append(")=").append(rc).append(" err=").append(wsa).append('\n')
            if (rc == 0) {
                Ws2.INSTANCE.closesocket(fd)
                break
            }
        }
    }
    return sb.toString()
}
