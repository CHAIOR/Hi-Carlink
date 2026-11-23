package io.github.gcbond.openhc.pc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenMappingTest {
    @Test
    fun fitModeLeavesBarsAndMapsCenter() {
        // 1920x1080 的视频放进 1000x1000 的窗口：等比到 1000x562，上下各留 219。
        val g = ScreenMapping.geometry(1000, 1000, 1920, 1080, fill = false)!!
        assertEquals(1000.0 / 1920.0, g.scale, 1e-9)
        assertEquals(0.0, g.offsetX, 1e-9)
        assertEquals(218.75, g.offsetY, 1e-6)
        assertEquals(960 to 540, ScreenMapping.toVideo(500f, 500f, g))
    }

    @Test
    fun fitModeClampsOutsideVideo() {
        val g = ScreenMapping.geometry(1000, 1000, 1920, 1080, fill = false)!!
        // 点在顶部黑边上：钳到第 0 行。
        assertEquals(0, ScreenMapping.toVideo(500f, 10f, g).second)
        // 点在右下黑边外：钳到最后一列/行。
        assertEquals(1918 to 1079, ScreenMapping.toVideo(999f, 999f, g))
    }

    @Test
    fun fillModeCropsHorizontally() {
        // 1920x1080 铺满 1000x1000 的窗口：放大到 1778x1000，左右各裁 389。
        val g = ScreenMapping.geometry(1000, 1000, 1920, 1080, fill = true)!!
        assertEquals(1000.0 / 1080.0, g.scale, 1e-9)
        assertEquals(-388.888888, g.offsetX, 1e-6)
        assertEquals(0.0, g.offsetY, 1e-9)
        // 窗口中心 → 视频中心。
        assertEquals(960 to 540, ScreenMapping.toVideo(500f, 500f, g))
        // 窗口最左缘 → 视频第 420 列左右（被裁掉的部分到不了）。
        val left = ScreenMapping.toVideo(0f, 500f, g).first
        assertEquals(true, left > 400 && left < 430)
    }

    @Test
    fun sameAspectHasNoBarsOrCrop() {
        val fit = ScreenMapping.geometry(1280, 720, 1280, 720, fill = false)!!
        assertEquals(0.0, fit.offsetX, 1e-9)
        assertEquals(0.0, fit.offsetY, 1e-9)
        val fill = ScreenMapping.geometry(1280, 720, 1280, 720, fill = true)
        assertEquals(fit, fill)
    }

    @Test
    fun clickFollowsNormalOrientation() {
        // 回归：单击不能上下/左右颠倒——左上角点 → 视频 (0,0)，右下角点 → 最后一行一列。
        val g = ScreenMapping.geometry(1920, 1080, 2560, 1440, fill = false)!!
        assertEquals(1 to 1, ScreenMapping.toVideo(1f, 1f, g))
        assertEquals(2558 to 1438, ScreenMapping.toVideo(1919f, 1079f, g))
        // 正中间就是视频正中间。
        assertEquals(1280 to 720, ScreenMapping.toVideo(960f, 540f, g))
    }

    @Test
    fun invalidGeometryReturnsNull() {
        assertNull(ScreenMapping.geometry(0, 720, 1280, 720, fill = false))
        assertNull(ScreenMapping.geometry(1280, 720, 0, 0, fill = true))
    }
}
