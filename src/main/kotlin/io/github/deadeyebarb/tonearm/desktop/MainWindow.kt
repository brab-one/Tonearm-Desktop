package io.github.deadeyebarb.tonearm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.github.deadeyebarb.tonearm.desktop.config.SavedWindow
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * The main window: on screen or hidden to the tray, and where it is (kept in the config so it opens
 * there next time). Use it on the UI thread.
 */
class MainWindow(private val app: DesktopApp) {
    private val placed = place(app.config.state.value.window, screens())
    val state: WindowState = WindowState(
        placement = if (placed.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (placed.x != null && placed.y != null) WindowPosition(placed.x.dp, placed.y.dp) else WindowPosition(Alignment.Center),
        size = DpSize(placed.width.dp, placed.height.dp),
    )
    var shown by mutableStateOf(true)
        private set
    /** Goes up with every [show], to bring the window to the front even when it's already open. */
    var raised by mutableIntStateOf(0)
        private set
    /** Ends the app (the application's exitApplication). */
    var exit: () -> Unit = {}

    private val openedAt = System.nanoTime()
    /**
     * How much smaller than asked the window came out. AWT on X11 guesses the window frame before the
     * window manager tells it, then keeps the content size: saved as it is, the window would shrink a
     * little with every start. Null until the window has settled, floating.
     */
    private var drift: DpSize? = null
    private var wasMaximized = placed.maximized
    /**
     * Where AWT said the window was when it stopped being maximized. On X11 that can be where it was
     * maximized, or any other stale place, until the window is moved; it's back where it was before.
     */
    private var unmaximizedAt: WindowPosition? = null

    /** Whether the window can be seen (any thread). */
    fun onScreen() = shown && !state.isMinimized

    fun show() {
        shown = true
        state.isMinimized = false
        raised++
    }

    fun hide() {
        save()
        shown = false
    }

    fun toggle() = if (onScreen()) hide() else show()

    fun quit() {
        save()
        app.shutdown()
        exit()
    }

    /** The window has had time to settle on screen: measures [drift]. */
    fun settled() {
        if (drift != null || state.placement != WindowPlacement.Floating || state.isMinimized) return
        drift = DpSize(placed.width.dp, placed.height.dp) - state.size
    }

    /** Remembers where the window is. While it's maximized, the size it goes back to stays the one from before. */
    fun save() {
        if (state.isMinimized) return
        if (System.nanoTime() - openedAt > SETTLE_NS) settled()
        val saved = app.config.state.value.window ?: SavedWindow()
        val next = when (state.placement) {
            WindowPlacement.Maximized -> {
                wasMaximized = true
                saved.copy(maximized = true)
            }
            WindowPlacement.Floating -> {
                if (wasMaximized) {
                    wasMaximized = false
                    unmaximizedAt = state.position
                }
                val position = (state.position as? WindowPosition.Absolute)?.takeIf { it != unmaximizedAt }
                val size = drift?.let { state.size + it }
                SavedWindow(
                    x = position?.x?.value?.roundToInt() ?: saved.x,
                    y = position?.y?.value?.roundToInt() ?: saved.y,
                    width = size?.width?.value?.takeIf { it.isFinite() }?.roundToInt() ?: saved.width,
                    height = size?.height?.value?.takeIf { it.isFinite() }?.roundToInt() ?: saved.height,
                )
            }
            else -> return
        }
        app.config.update { it.copy(window = next) }
    }

    companion object {
        /** How long a new window takes to settle (the window manager framing it). */
        const val SETTLE_MS = 2_000L
        private const val SETTLE_NS = SETTLE_MS * 1_000_000

        /** How much of the window's top must be on a screen to count as on it: enough to grab it there. */
        private const val GRAB_WIDTH = 100
        private const val GRAB_HEIGHT = 20

        private fun screens(): List<Rectangle> =
            runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }.getOrDefault(emptyList())

        /**
         * Where the window opens: where it was and at least its minimum size, or centered (and no bigger than
         * the largest screen) when that's off every screen now, say after a monitor was unplugged.
         */
        fun place(saved: SavedWindow?, screens: List<Rectangle>): SavedWindow {
            saved ?: return SavedWindow()
            val width = saved.width.coerceAtLeast(SavedWindow.MIN_WIDTH)
            val height = saved.height.coerceAtLeast(SavedWindow.MIN_HEIGHT)
            val x = saved.x
            val y = saved.y
            val top = if (x != null && y != null) Rectangle(x, y, width, GRAB_HEIGHT) else null
            if (top != null && screens.any { s -> s.intersection(top).let { it.width >= GRAB_WIDTH && it.height >= GRAB_HEIGHT / 2 } }) {
                return saved.copy(width = width, height = height)
            }
            val largest = screens.maxByOrNull { it.width.toLong() * it.height }
            return SavedWindow(
                width = width.coerceAtMost(largest?.width ?: width).coerceAtLeast(SavedWindow.MIN_WIDTH),
                height = height.coerceAtMost(largest?.height ?: height).coerceAtLeast(SavedWindow.MIN_HEIGHT),
                maximized = saved.maximized,
            )
        }
    }
}
