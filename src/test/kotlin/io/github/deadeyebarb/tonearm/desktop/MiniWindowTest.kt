package io.github.deadeyebarb.tonearm.desktop

import io.github.deadeyebarb.tonearm.desktop.MiniWindow.AfterClose
import io.github.deadeyebarb.tonearm.desktop.config.DesktopConfig
import io.github.deadeyebarb.tonearm.desktop.config.SavedMini
import kotlinx.serialization.json.Json
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniWindowTest {
    private val w = MiniWindow.WIDTH
    private val h = MiniWindow.HEIGHT
    // A laptop with a panel at the bottom, and a bigger screen to its right.
    private val laptop = MainWindow.Screen(Rectangle(0, 0, 1920, 1080), area = Rectangle(0, 0, 1920, 1040))
    private val right = MainWindow.Screen(Rectangle(1920, 0, 2560, 1440))

    @Test
    fun `it opens where it was while that's on a screen`() {
        assertEquals(Point(120, 140), MiniWindow.place(SavedMini(120, 140), listOf(laptop), w, h))
        assertEquals(Point(3000, 900), MiniWindow.place(SavedMini(3000, 900), listOf(laptop, right), w, h))
    }

    @Test
    fun `half on a screen it's pulled onto it, panels left out`() {
        // Over the right edge by a third.
        assertEquals(Point(1920 - w, 300), MiniWindow.place(SavedMini(1920 - w + w / 3, 300), listOf(laptop), w, h))
        // Over the panel.
        assertEquals(Point(500, 1040 - h), MiniWindow.place(SavedMini(500, 1040 - h + 30), listOf(laptop), w, h))
    }

    @Test
    fun `nothing saved, or off every screen, it goes to the largest screen's bottom right corner`() {
        val corner = Point(1920 + 2560 - w - 24, 1440 - h - 24)
        assertEquals(corner, MiniWindow.place(null, listOf(laptop, right), w, h))
        assertEquals(corner, MiniWindow.place(SavedMini(5000, 40), listOf(laptop, right), w, h))
        // Mostly off the edge: not pulled back, but to the corner.
        assertEquals(corner, MiniWindow.place(SavedMini(4480 - w / 4, 40), listOf(laptop, right), w, h))
        // On the screen that was unplugged: the corner of the one left, above its panel.
        assertEquals(Point(1920 - w - 24, 1040 - h - 24), MiniWindow.place(SavedMini(3000, 900), listOf(laptop), w, h))
    }

    @Test
    fun `without screens the window system places it`() {
        assertNull(MiniWindow.place(SavedMini(120, 140), emptyList(), w, h))
        assertNull(MiniWindow.place(null, emptyList(), w, h))
    }

    @Test
    fun `closing it brings the main window back unless it's on screen or in the tray`() {
        for (tray in listOf(true, false)) for (closeToTray in listOf(true, false)) {
            assertEquals(AfterClose.NOTHING, MiniWindow.afterClose(mainShown = true, trayShown = tray, closeToTray = closeToTray))
        }
        assertEquals(AfterClose.STAY_IN_TRAY, MiniWindow.afterClose(mainShown = false, trayShown = true, closeToTray = true))
        // Close to tray turned off meanwhile, or the tray gone: the main window comes back.
        assertEquals(AfterClose.SHOW_MAIN, MiniWindow.afterClose(mainShown = false, trayShown = true, closeToTray = false))
        assertEquals(AfterClose.SHOW_MAIN, MiniWindow.afterClose(mainShown = false, trayShown = false, closeToTray = true))
        assertEquals(AfterClose.SHOW_MAIN, MiniWindow.afterClose(mainShown = false, trayShown = false, closeToTray = false))
    }

    @Test
    fun `the config keeps where it was, and an older config has nothing`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val config = DesktopConfig(deviceId = "desktop-x", deviceName = "here", mini = SavedMini(120, 140))
        val text = json.encodeToString(DesktopConfig.serializer(), config)
        assertEquals(SavedMini(120, 140), json.decodeFromString(DesktopConfig.serializer(), text).mini)
        assertNull(json.decodeFromString(DesktopConfig.serializer(), """{"deviceId":"desktop-x","volume":60}""").mini)
    }
}
