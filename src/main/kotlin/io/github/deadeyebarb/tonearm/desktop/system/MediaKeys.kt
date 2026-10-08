package io.github.deadeyebarb.tonearm.desktop.system

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer

/**
 * Media keys reach the player once. The window handles them while it has focus, and the desktop may
 * pass the same key press on through MPRIS (or a global hotkey on Windows) as well.
 */
object MediaKeys {
    const val PLAY_PAUSE = "play-pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val STOP = "stop"

    /** A key press seen both ways arrives twice within this. */
    private const val SAME_PRESS_NS = 400_000_000L

    private var lastAction: String? = null
    private var lastInWindow = false
    private var lastAt = 0L

    /** True when [action] should run: false when the same key press just ran it the other way. */
    @Synchronized
    fun take(action: String, inWindow: Boolean, now: Long = System.nanoTime()): Boolean {
        if (action == lastAction && inWindow != lastInWindow && now - lastAt < SAME_PRESS_NS) return false
        lastAction = action
        lastInWindow = inWindow
        lastAt = now
        return true
    }

    fun run(player: DesktopPlayer, action: String, inWindow: Boolean) {
        if (!take(action, inWindow)) return
        when (action) {
            PLAY_PAUSE -> player.togglePlay()
            NEXT -> player.next()
            PREVIOUS -> player.previous()
            STOP -> player.stop()
        }
    }

    /**
     * Windows: the media keys as global hotkeys, so they work while another app has focus. They're taken
     * on a thread of their own that waits for the hotkey messages; keys another app holds are left alone.
     */
    fun startWindowsHotkeys(player: DesktopPlayer) {
        val keys = mapOf(1 to (0xB3 to PLAY_PAUSE), 2 to (0xB0 to NEXT), 3 to (0xB1 to PREVIOUS), 4 to (0xB2 to STOP))
        Thread({
            runCatching {
                val user32 = User32.INSTANCE
                val taken = keys.filter { (id, key) -> user32.RegisterHotKey(null, id, WinUser.MOD_NOREPEAT, key.first) }
                if (taken.isEmpty()) return@runCatching
                val msg = WinUser.MSG()
                while (user32.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WinUser.WM_HOTKEY) taken[msg.wParam.toInt()]?.let { run(player, it.second, inWindow = false) }
                }
            }
        }, "media-keys").apply { isDaemon = true }.start()
    }
}
