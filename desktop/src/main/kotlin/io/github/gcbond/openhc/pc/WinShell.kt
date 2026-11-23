package io.github.gcbond.openhc.pc

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 跑安装包里带的 PowerShell 脚本（WinRT 调用只能这么走），把 `key=value` 行收成 map。 */
internal object WinShell {
    private val dir: File by lazy {
        Files.createTempDirectory("hi-carlink-pc").toFile().also { it.deleteOnExit() }
    }
    private val extracted = HashMap<String, File>()

    fun script(name: String): File = extracted.getOrPut(name) {
        val file = File(dir, name)
        val stream = WinShell::class.java.getResourceAsStream("/$name")
            ?: throw IllegalStateException("安装包里缺少 $name")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        file.deleteOnExit()
        file
    }

    fun run(name: String, args: List<String> = emptyList(), timeoutSeconds: Long = 40): Map<String, String> {
        // 用绝对路径：PATH 里的 MSYS 目录优先，按名字调可能命中一个 Windows 起不来的壳。
        val windir = System.getenv("SystemRoot") ?: "C:\\Windows"
        val candidates = listOf(
            File(windir, "System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
            File(windir, "System32\\WindowsPowerShell\\v1.0\\pwsh.exe"),
        )
        val file = script(name).absolutePath
        var process: Process? = null
        var lastError: Exception? = null
        for (ps in candidates) {
            if (!ps.isFile) continue
            val cmd = mutableListOf(
                ps.absolutePath, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file,
            )
            cmd += args
            try {
                process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        val started = process ?: throw IllegalStateException(
            "启动不了 PowerShell（${lastError?.message ?: "找不到 powershell.exe"}）；" +
                "可以自己先打开 Windows「移动热点」，把名字和口令设成程序里那组，程序会直接用",
        )
        val text = started.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            started.destroyForcibly()
            throw IllegalStateException("PowerShell 超时（$name）：${text.takeLast(300)}")
        }
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("ERROR:", ignoreCase = true)) {
                result["ERROR"] = t.substringAfter(':').trim()
                continue
            }
            val idx = t.indexOf('=')
            if (idx in 1 until t.length - 1) result[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
        }
        if (started.exitValue() != 0 && result["ERROR"] == null && result.isEmpty()) {
            throw IllegalStateException("PowerShell 退出码 ${started.exitValue()}（$name）：${text.takeLast(300)}")
        }
        return result
    }

    /** 本机经典蓝牙地址 `AA:BB:CC:DD:EE:FF`；蓝牙关着或没有蓝牙时抛异常。 */
    fun localBluetoothAddress(): String {
        val out = run("btaddr.ps1")
        out["ERROR"]?.let { throw IllegalStateException(it) }
        return out["bt"] ?: throw IllegalStateException("读不到本机蓝牙地址")
    }
}