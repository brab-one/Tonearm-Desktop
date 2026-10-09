package io.github.deadeyebarb.tonearm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.github.deadeyebarb.tonearm.desktop.config.SavedMini
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * The mini player: a small window on top of the others with the song and its controls. It opens where it was last
 * time (else at the bottom right), and opened from the main window it gets that out of the way. Use it on the UI thread.
 */
class MiniWindow(private val app: DesktopApp, private val main: MainWindow, private val trayShown: () -> Boolean) {
    var open by mutableStateOf(false)
        private set
    /** Where it is; placed anew each time it opens. */
    var state by mutableStateOf(WindowState(size = DpSize(WIDTH.dp, HEIGHT.dp)))
        private set

    fun open() {
        if (open) return
        val at = place(app.config.state.value.mini, MainWindow.screens(), WIDTH, HEIGHT)
        state = WindowState(position = at?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.BottomEnd), size = DpSize(WIDTH.dp, HEIGHT.dp))
        open = true
    }

    /** From the main window: the mini player opens, and the main window goes to the tray when closing goes there, else down to the taskbar. */
    fun switchFromMain() {
        open()
        if (trayShown() && app.config.state.value.closeToTray) main.hide() else main.minimize()
    }

    /** Back to the main window, without the mini player. */
    fun expand() {
        close()
        main.show()
    }

    /** ✕: the mini player goes, and the main window comes back unless it's on screen or waiting in the tray. */
    fun close() {
        if (!open) return
        save()
        open = false
        if (afterClose(main.onScreen(), trayShown(), app.config.state.value.closeToTray) == AfterClose.SHOW_MAIN) main.show()
    }

    /** The player bar's button: opens it from the main window, or closes it. */
    fun toggle() = if (open) close() else switchFromMain()

    /** Remembers where it is. */
    fun save() {
        if (!open) return
        val at = state.position as? WindowPosition.Absolute ?: return
        app.config.update { it.copy(mini = SavedMini(at.x.value.roundToInt(), at.y.value.roundToInt())) }
    }

    /** What closing the mini player does to the main window. */
    enum class AfterClose { NOTHING, STAY_IN_TRAY, SHOW_MAIN }

    companion object {
        const val WIDTH = 380
        const val HEIGHT = 124
        /** Room left to the screen's edges in the corner it opens in. */
        private const val MARGIN = 24

        /**
         * Where it opens: where it was when that's on a screen still, pulled onto the screen when half of it is, else in
         * the bottom right corner of the largest screen (panels left out). Null without screens (headless).
         */
        fun place(saved: SavedMini?, screens: List<MainWindow.Screen>, width: Int, height: Int): Point? {
            if (screens.isEmpty()) return null
            if (saved != null) {
                val box = Rectangle(saved.x, saved.y, width, height)
                if (screens.any { it.area.contains(box) }) return Point(saved.x, saved.y)
                val mostly = screens.firstOrNull { s -> s.area.intersection(box).let { !it.isEmpty && 2L * it.width * it.height >= width.toLong() * height } }
                if (mostly != null) {
                    val a = mostly.area
                    return Point(saved.x.coerceAtMost(a.x + a.width - width).coerceAtLeast(a.x), saved.y.coerceAtMost(a.y + a.height - height).coerceAtLeast(a.y))
                }
            }
            val a = screens.maxBy { it.area.width.toLong() * it.area.height }.area
            return Point(a.x + a.width - width - MARGIN, a.y + a.height - height - MARGIN)
        }

        /**
         * After the mini player closes: a main window on screen stays as it is, and one out of sight stays in the tray
         * when there is one and closing goes there. Otherwise it comes back, so Tonearm can't vanish.
         */
        fun afterClose(mainShown: Boolean, trayShown: Boolean, closeToTray: Boolean): AfterClose = when {
            mainShown -> AfterClose.NOTHING
            trayShown && closeToTray -> AfterClose.STAY_IN_TRAY
            else -> AfterClose.SHOW_MAIN
        }
    }
}
