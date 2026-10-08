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
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

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
    /** Ends the app: the application's exitApplication once it runs, the process before that. */
    var exit: () -> Unit = { exitProcess(0) }
    private var quitting = false

    private val openedAt = System.nanoTime()
    /**
     * How much smaller than asked the window came out. AWT on X11 guesses the window frame before the
     * window manager tells it, then keeps the content size: saved as it is, the window would shrink a
     * little with every start. Null until the window has settled.
     */
    private var drift: DpSize? = null
    /** Only a window that has stayed floating since it opened can tell [drift]; a maximized one can't. */
    private var floatingSinceOpen = !placed.maximized
    private var wasMaximized = placed.maximized
    /** Where AWT last said the window was while maximized. */
    private var maximizedAt: WindowPosition.Absolute? = null
    /**
     * Where AWT said the window was when it stopped being maximized, when that looked stale: on X11 it can
     * keep saying where the window was maximized, or its old place give or take the frame, until it's moved.
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
        // Quit can come twice (the tray and MPRIS, or another click while the first is still saying goodbye).
        if (quitting) return
        quitting = true
        save()
        app.shutdown()
        exit()
    }

    /** The window has had time to settle on screen: measures [drift]. */
    fun settled() {
        if (drift != null) return
        if (!floatingSinceOpen || state.isMinimized || state.placement != WindowPlacement.Floating) {
            drift = DpSize.Zero
            return
        }
        val d = DpSize(placed.width.dp, placed.height.dp) - state.size
        // A frame's worth at most; more than that is the window manager's doing (tiling, a size that didn't fit).
        drift = d.takeIf { abs(it.width.value) <= MAX_DRIFT && abs(it.height.value) <= MAX_DRIFT } ?: DpSize.Zero
    }

    /** Remembers where the window is. While it's maximized, the size it goes back to stays the one from before. */
    fun save() {
        if (state.isMinimized) return
        if (System.nanoTime() - openedAt > SETTLE_NS) settled()
        val saved = app.config.state.value.window ?: SavedWindow()
        val next = when (state.placement) {
            WindowPlacement.Maximized -> {
                floatingSinceOpen = false
                if (drift == null) drift = DpSize.Zero
                wasMaximized = true
                val at = state.position as? WindowPosition.Absolute
                maximizedAt = at
                // Moved to another screen while maximized: it goes back to a spot on that screen.
                val moved = at?.let { carry(saved, Rectangle(it.x.value.roundToInt(), it.y.value.roundToInt(), state.size.width.value.roundToInt(), state.size.height.value.roundToInt()), screens()) }
                saved.copy(x = moved?.x ?: saved.x, y = moved?.y ?: saved.y, maximized = true)
            }
            WindowPlacement.Floating -> {
                if (wasMaximized) {
                    wasMaximized = false
                    // Anywhere else, it was dragged out of maximized: that's where it is.
                    unmaximizedAt = (state.position as? WindowPosition.Absolute)?.takeIf { p ->
                        maximizedAt?.let { near(p, it.x.value, it.y.value) } == true ||
                            (saved.x != null && saved.y != null && near(p, saved.x.toFloat(), saved.y.toFloat()))
                    }
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

    /** A screen: all of it, and the part windows get (without panels and docks). */
    data class Screen(val bounds: Rectangle, val area: Rectangle = bounds)

    companion object {
        /** How long a new window takes to settle (the window manager framing it). */
        const val SETTLE_MS = 2_000L
        private const val SETTLE_NS = SETTLE_MS * 1_000_000
        /** About a window frame: a title bar and borders. */
        private const val MAX_DRIFT = 64f
        private const val STALE_SLACK = 64f

        /** How much of the window's top must be on a screen to count as on it: enough to grab it there. */
        private const val GRAB_WIDTH = 100
        private const val GRAB_HEIGHT = 20
        /** Room left round a window as big as the screen, or some window managers maximize it. */
        private const val FIT_MARGIN = 16

        private fun near(p: WindowPosition.Absolute, x: Float, y: Float) = abs(p.x.value - x) <= STALE_SLACK && abs(p.y.value - y) <= STALE_SLACK

        private fun screens(): List<Screen> = runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val bounds = device.defaultConfiguration.bounds
                val insets = toolkit.getScreenInsets(device.defaultConfiguration)
                Screen(bounds, Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom))
            }
        }.getOrDefault(emptyList())

        /**
         * Where the window opens: where it was, or centered when that's off every screen now (say after a
         * monitor was unplugged). No bigger than the screen it opens on and no smaller than its minimum.
         */
        fun place(saved: SavedWindow?, screens: List<Screen>): SavedWindow {
            saved ?: return SavedWindow()
            val x = saved.x
            val y = saved.y
            val top = if (x != null && y != null) Rectangle(x, y, saved.width.coerceAtLeast(SavedWindow.MIN_WIDTH), GRAB_HEIGHT) else null
            val on = top?.let { t -> screens.firstOrNull { s -> s.bounds.intersection(t).let { it.width >= GRAB_WIDTH && it.height >= GRAB_HEIGHT / 2 } } }
            val fit = (on ?: screens.maxByOrNull { it.area.width.toLong() * it.area.height })?.area
            val width = saved.width.coerceAtMost(fit?.let { it.width - FIT_MARGIN } ?: saved.width).coerceAtLeast(SavedWindow.MIN_WIDTH)
            val height = saved.height.coerceAtMost(fit?.let { it.height - FIT_MARGIN } ?: saved.height).coerceAtLeast(SavedWindow.MIN_HEIGHT)
            return if (on != null) saved.copy(width = width, height = height) else SavedWindow(width = width, height = height, maximized = saved.maximized)
        }

        /**
         * The floating spot of [saved] moved onto the screen a window maximized at [maximized] is on, keeping its
         * place on the screen; null when that's the screen it's on already, or there's no spot yet.
         */
        fun carry(saved: SavedWindow, maximized: Rectangle, screens: List<Screen>): Point? {
            val x = saved.x ?: return null
            val y = saved.y ?: return null
            val to = screens.firstOrNull { it.bounds.contains(maximized.centerX, maximized.centerY) }?.bounds ?: return null
            val from = screens.firstOrNull { it.bounds.contains(x, y) }?.bounds
            if (from == to) return null
            val movedX = if (from != null) x - from.x + to.x else x
            val movedY = if (from != null) y - from.y + to.y else y
            return Point(
                movedX.coerceIn(to.x, to.x + (to.width - GRAB_WIDTH).coerceAtLeast(0)),
                movedY.coerceIn(to.y, to.y + (to.height - GRAB_HEIGHT).coerceAtLeast(0)),
            )
        }
    }
}
