package io.github.gcbond.openhc.platform.wifi

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.SystemClock
import java.lang.reflect.Method
import kotlin.random.Random

/** 旧版热点接口开出来的一次热点：关的时候要用同一个配置。 */
class LegacyHotspotLease(
    /** 不带引号的网络名。 */
    val ssid: String,
    /** 不带引号的 WPA2 口令。 */
    val passphrase: String,
    internal val configuration: WifiConfiguration?,
)

/**
 * 安卓 6.0～7.1（API 23～25）上开热点。
 *
 * 这些系统没有公开的「本地热点」接口（`startLocalOnlyHotspot` 是安卓 8.0 才有的），只能调隐藏接口：
 * `WifiManager.setWifiApEnabled` / `getWifiApConfiguration` / `getWifiApState`。它们不在公开 SDK 里，
 * 各家车机 ROM 的实现也不完全一样（有的要系统签名才算数、有的直接抛异常），所以每一步都可能失败：
 * 失败一律按「热点没开成」报给界面，不抛到会话里。
 *
 * 网络名固定（`OpenHC-xxxx`，存在 SharedPreferences 里）：手机的「已保存网络」按网络名认车机，
 * 名字每次换的话，免码回连、自动连接都失效。车机原来的热点配置已经是 WPA2 口令时优先用它，
 * 这样车主在车机设置里看到的还是原来那个网络。口令固定 8 位，避免有的 ROM 对短口令的处理不一致。
 */
internal object LegacyHotspot {
    const val MIN_SDK = 23
    const val MAX_SDK = 25

    private const val AP_STATE_ENABLED = 13
    private const val AP_STATE_ENABLING = 12
    private const val AP_STATE_FAILED = 15

    private const val POLL_MS = 200L
    private const val ENABLE_TIMEOUT_MS = 12_000L

    private const val PREFS = "openhc-legacy-hotspot"
    private const val KEY_SSID = "ssid"
    private const val KEY_PASS = "passphrase"

    /** 这个系统版本要走旧版热点接口。 */
    fun supported(): Boolean = android.os.Build.VERSION.SDK_INT in MIN_SDK..MAX_SDK

    sealed class EnableResult {
        class Ok(val lease: LegacyHotspotLease) : EnableResult()

        class Failed(val message: String) : EnableResult()
    }

    /** 热点现在是不是开着（掉了的时候用来报「热点被系统关了」）。 */
    fun isEnabled(wifi: WifiManager?): Boolean = try {
        wifi != null && apState(wifi) == AP_STATE_ENABLED
    } catch (e: SecurityException) {
        // 读状态也要 ACCESS_WIFI_STATE，读不到就当还开着，别误报。
        true
    } catch (e: RuntimeException) {
        true
    }

    /**
     * 开热点：先读车机原来的热点配置，能用就用，不能就换成自己的；然后调隐藏接口打开，
     * 再轮询状态直到开起来（最多 [ENABLE_TIMEOUT_MS]）。回调在 [handler] 的线程上，只回调一次。
     */
    fun enableAsync(
        wifi: WifiManager?,
        context: Context?,
        handler: Handler,
        onResult: (EnableResult) -> Unit,
    ) {
        if (wifi == null) return onResult(EnableResult.Failed("拿不到系统的 WiFi 服务"))
        var done = false
        fun finish(result: EnableResult) {
            if (done) return
            done = true
            onResult(result)
        }

        val prefs = preferences(context)
        val existing = readConfiguration(wifi)
        val picked = pickIdentity(existing, prefs)
        // 现有配置就是我们要的网络名和口令时直接用它（车机原来那个热点配置），否则按我们的建一份。
        val config = if (existing != null && matches(existing, picked.first, picked.second)) {
            existing
        } else {
            buildConfiguration(picked.first, picked.second)
        }
        val calledAt = SystemClock.elapsedRealtime()
        val opened = try {
            setEnabled(wifi, config, true)
        } catch (e: SecurityException) {
            finish(
                EnableResult.Failed(
                    "系统不让开热点（${e.cause?.message ?: e.message}）：旧版热点接口要 CHANGE_WIFI_STATE 权限，" +
                        "有的车机 ROM 只让系统自己开",
                ),
            )
            return
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            finish(EnableResult.Failed("调旧版热点接口出错：${e.javaClass.simpleName}：${e.cause?.message ?: e.message}"))
            return
        }
        if (!opened) {
            finish(EnableResult.Failed("系统拒绝了开热点的请求（车机可能开着个人热点、在共享网络，或这个 ROM 关了旧版接口）"))
            return
        }
        val deadline = calledAt + ENABLE_TIMEOUT_MS
        lateinit var poll: Runnable
        poll = Runnable {
            val state = try {
                apState(wifi)
            } catch (e: SecurityException) {
                finish(EnableResult.Failed("读不到热点状态（${e.message}）"))
                return@Runnable
            } catch (e: RuntimeException) {
                finish(EnableResult.Failed("读不到热点状态（${e.javaClass.simpleName}）"))
                return@Runnable
            }
            when {
                state == AP_STATE_ENABLED -> {
                    // 系统可能改写过配置（有的 ROM 会把口令换掉、或者读回来是掩码），以能用的为准。
                    val actual = readConfiguration(wifi)
                    val ssid = LocalHotspotRules.networkName(actual?.SSID) ?: picked.first
                    val readBack = unquote(actual?.preSharedKey)
                    val pass = if (readBack != null && readBack.length >= 8 && readBack != picked.second) {
                        // 读回来的口令能用就用（ROM 改过）；掩码（"*"）之类的一律不用。
                        readBack
                    } else {
                        picked.second
                    }
                    if (prefs != null) prefs.edit().putString(KEY_SSID, ssid).putString(KEY_PASS, pass).apply()
                    finish(EnableResult.Ok(LegacyHotspotLease(ssid, pass, actual ?: config)))
                }
                state == AP_STATE_FAILED ->
                    finish(EnableResult.Failed("系统开热点失败（热点被别的应用占着，或这个 ROM 的热点不可用）"))
                SystemClock.elapsedRealtime() >= deadline ->
                    finish(EnableResult.Failed("系统 ${ENABLE_TIMEOUT_MS / 1000} 秒内没有把热点打开"))
                else -> handler.postDelayed(poll, POLL_MS)
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }

    /** 关热点（不等手机离开）。 */
    fun disable(wifi: WifiManager?, lease: LegacyHotspotLease?) {
        try {
            setEnabled(wifi, lease?.configuration, false)
        } catch (e: RuntimeException) {
            // 关不掉也无妨：系统在进程退出、或车主在车机设置里关热点时会收回。
        }
    }

    // ---------------------------------------------------------------- 配置

    private fun preferences(context: Context?): SharedPreferences? = try {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (e: RuntimeException) {
        null
    }

    /** 现有配置能用（WPA2、有网络名和口令）就沿用，否则用存下来的；都没有就生成一组并记下。 */
    private fun pickIdentity(existing: WifiConfiguration?, prefs: SharedPreferences?): Pair<String, String> {
        val existingSsid = LocalHotspotRules.networkName(existing?.SSID)
        val existingPass = unquote(existing?.preSharedKey)
        if (existingSsid != null && existingPass != null && existingPass.length >= 8 &&
            existing?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.WPA_PSK) == true
        ) {
            return existingSsid to existingPass
        }
        val savedSsid = prefs?.getString(KEY_SSID, null)?.takeIf { LocalHotspotRules.networkName(it) != null }
        val savedPass = prefs?.getString(KEY_PASS, null)?.takeIf { it.length >= 8 }
        if (savedSsid != null && savedPass != null) return savedSsid to savedPass
        val ssid = "OpenHC-" + Random.nextInt(0x1000, 0xFFFF).toString(16).uppercase().take(4)
        val pass = buildString {
            val charset = "0123456789abcdefghijklmnopqrstuvwxyz"
            repeat(10) { append(charset[Random.nextInt(charset.length)]) }
        }
        prefs?.edit()?.putString(KEY_SSID, ssid)?.putString(KEY_PASS, pass)?.apply()
        return ssid to pass
    }

    private fun matches(config: WifiConfiguration, ssid: String, passphrase: String): Boolean =
        LocalHotspotRules.networkName(config.SSID) == ssid && unquote(config.preSharedKey) == passphrase

    private fun buildConfiguration(ssid: String, passphrase: String) = WifiConfiguration().apply {
        SSID = quote(ssid)
        preSharedKey = quote(passphrase)
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
        allowedProtocols.set(WifiConfiguration.Protocol.RSN)
    }

    private fun quote(text: String): String = if (text.startsWith('"') && text.endsWith('"')) text else "\"$text\""

    private fun unquote(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        return if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1) else text
    }

    // ---------------------------------------------------------------- 隐藏接口

    private fun method(name: String, vararg types: Class<*>): Method? = try {
        WifiManager::class.java.getMethod(name, *types)
    } catch (e: NoSuchMethodException) {
        null
    }

    private fun readConfiguration(wifi: WifiManager): WifiConfiguration? = try {
        method("getWifiApConfiguration")?.invoke(wifi) as? WifiConfiguration
    } catch (e: RuntimeException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun setEnabled(wifi: WifiManager?, config: WifiConfiguration?, enabled: Boolean): Boolean {
        if (wifi == null) return false
        val m = method("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType!!)
            ?: throw NoSuchMethodException("这个系统没有 setWifiApEnabled")
        return m.invoke(wifi, config, enabled) as? Boolean ?: false
    }

    private fun apState(wifi: WifiManager): Int {
        val m = method("getWifiApState") ?: throw NoSuchMethodException("这个系统没有 getWifiApState")
        return (m.invoke(wifi) as? Int) ?: -1
    }
}