package io.github.gcbond.openhc.pc

import java.io.File
import java.util.Properties

/** 一个画质档位：分辨率和配套的 dpi / density（保持手机界面按 1280x720 dp 排）。 */
internal data class VideoPreset(val name: String, val width: Int, val height: Int, val dpi: Int, val density: Double)

internal object VideoPresets {
    val FOUR_K = VideoPreset("4K", 3840, 2160, 480, 3.0)
    val TWO_K = VideoPreset("2K", 2560, 1440, 320, 2.0)
    val P1080 = VideoPreset("1080P", 1920, 1080, 240, 1.5)
    val ALL = listOf(FOUR_K, TWO_K, P1080)

    fun byName(name: String): VideoPreset? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** 按宽高猜档位（命令行直接给了 --width/--height 时用）。 */
    fun bySize(width: Int, height: Int): VideoPreset? = ALL.firstOrNull { it.width == width && it.height == height }
}

/** 应用设置（保存在 ~/.hi-carlink/settings.properties，下次启动生效）。 */
internal data class AppSettings(
    val resolution: String = "2K",
    val fps: Int = 60,
    /** 滚轮方向反过来（有人习惯「滚轮往下 = 内容往上」）。 */
    val invertWheel: Boolean = false,
    /** 鼠标右键做什么：back 返回 / home 桌面 / none 不用。 */
    val rightButton: String = "back",
    /** 鼠标中键做什么。 */
    val middleButton: String = "home",
    /** 触摸坐标左右翻转（画面和手指方向对不上时用）。 */
    val invertX: Boolean = false,
    /** 触摸坐标上下翻转。 */
    val invertY: Boolean = false,
) {
    val preset: VideoPreset get() = VideoPresets.byName(resolution) ?: VideoPresets.TWO_K

    companion object {
        val FPS_CHOICES = listOf(30, 60)

        fun file(): File = File(System.getProperty("user.home"), ".hi-carlink/settings.properties")

        fun load(): AppSettings {
            val f = file()
            if (!f.isFile) return AppSettings()
            return runCatching {
                val p = Properties().apply { f.inputStream().use { load(it) } }
                val resolution = p.getProperty("resolution") ?: "2K"
                val fps = p.getProperty("fps")?.toIntOrNull() ?: 60
                AppSettings(
                    resolution = resolution,
                    fps = if (fps in FPS_CHOICES) fps else 60,
                    invertWheel = p.getProperty("invertWheel")?.toBoolean() ?: false,
                    rightButton = p.getProperty("rightButton") ?: "back",
                    middleButton = p.getProperty("middleButton") ?: "home",
                    invertX = p.getProperty("invertX")?.toBoolean() ?: false,
                    invertY = p.getProperty("invertY")?.toBoolean() ?: false,
                )
            }.getOrDefault(AppSettings())
        }

        fun save(settings: AppSettings) {
            runCatching {
                val f = file()
                f.parentFile?.mkdirs()
                Properties().apply {
                    setProperty("resolution", settings.resolution)
                    setProperty("fps", settings.fps.toString())
                    setProperty("invertWheel", settings.invertWheel.toString())
                    setProperty("rightButton", settings.rightButton)
                    setProperty("middleButton", settings.middleButton)
                    setProperty("invertX", settings.invertX.toString())
                    setProperty("invertY", settings.invertY.toString())
                }.store(f.outputStream(), "Hi Carlink PC settings")
            }
        }
    }
}