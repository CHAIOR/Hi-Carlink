package io.github.gcbond.openhc.pc

/**
 * 窗口像素 ↔ 视频像素的换算（纯逻辑，可单测）。
 *
 * - 适应模式：等比缩放到窗口里、居中，四周留黑边；
 * - 铺满模式：等比放大到覆盖整个窗口、居中裁切，不留黑边（只在真全屏时用）。
 *
 * 绘制和触摸必须用同一套几何，否则点哪偏哪。
 */
internal object ScreenMapping {
    data class Geometry(
        val scale: Double,
        val offsetX: Double,
        val offsetY: Double,
        val videoWidth: Int,
        val videoHeight: Int,
    )

    fun geometry(panelWidth: Int, panelHeight: Int, videoWidth: Int, videoHeight: Int, fill: Boolean): Geometry? {
        if (panelWidth <= 0 || panelHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null
        val scale = if (fill) {
            maxOf(panelWidth.toDouble() / videoWidth, panelHeight.toDouble() / videoHeight)
        } else {
            minOf(panelWidth.toDouble() / videoWidth, panelHeight.toDouble() / videoHeight)
        }
        val drawnWidth = videoWidth * scale
        val drawnHeight = videoHeight * scale
        return Geometry(
            scale = scale,
            offsetX = (panelWidth - drawnWidth) / 2.0,
            offsetY = (panelHeight - drawnHeight) / 2.0,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }

    /** 窗口坐标 → 视频像素；超出画面的部分钳到边缘像素。 */
    fun toVideo(x: Float, y: Float, g: Geometry): Pair<Int, Int> {
        val vx = ((x - g.offsetX) / g.scale).toInt().coerceIn(0, g.videoWidth - 1)
        val vy = ((y - g.offsetY) / g.scale).toInt().coerceIn(0, g.videoHeight - 1)
        return vx to vy
    }
}
