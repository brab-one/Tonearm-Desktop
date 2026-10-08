package io.github.deadeyebarb.tonearm.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.config.SavedWindow
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.desktop.player.MpvMissingException
import io.github.deadeyebarb.tonearm.desktop.system.MediaKeys
import io.github.deadeyebarb.tonearm.desktop.system.Mpris
import io.github.deadeyebarb.tonearm.desktop.system.SessionBus
import io.github.deadeyebarb.tonearm.desktop.system.StatusNotifier
import io.github.deadeyebarb.tonearm.desktop.ui.Navigator
import io.github.deadeyebarb.tonearm.desktop.ui.TonearmApp
import io.github.deadeyebarb.tonearm.desktop.ui.TonearmTheme
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.net.forImages
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import okhttp3.Call
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Image
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JOptionPane
import kotlin.system.exitProcess

@OptIn(FlowPreview::class)
fun main() {
    System.setProperty("jna.encoding", "UTF-8")
    val app = try {
        DesktopApp()
    } catch (e: MpvMissingException) {
        JOptionPane.showMessageDialog(null, "Tonearm needs libmpv to play music.\n\n${e.message}", "Tonearm", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val iconPng = requireNotNull(DesktopApp::class.java.getResourceAsStream("/icon.png")).readBytes()
    val icon = BitmapPainter(Image.makeFromEncoded(iconPng).toComposeImageBitmap())
    val main = MainWindow(app)

    // Linux: MPRIS for the media keys and the desktop's media controls, and the tray icon over D-Bus when
    // the desktop has a StatusNotifier host. Windows: the media keys as global hotkeys.
    val bus = if (AppDirs.isLinux) SessionBus.open() else null
    if (bus != null) Mpris.start(bus, app, raise = { onUi(main::show) }, quit = { onUi(main::quit) })
    val statusNotifier = bus?.let {
        StatusNotifier.start(it, app.player, app.scope, iconPng, main::onScreen, toggleWindow = { onUi(main::toggle) }, quit = { onUi(main::quit) })
    }
    if (AppDirs.isWindows) MediaKeys.startWindowsHotkeys(app.player)
    // Elsewhere (Windows, macOS, Linux without a StatusNotifier host) the tray is AWT's, when there is one.
    val awtTray = statusNotifier == null && isTraySupported

    application {
        main.exit = ::exitApplication
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageCalls(app) })) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
                .diskCache { DiskCache.Builder().directory(AppDirs.cache.resolve("images").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
                .crossfade(true)
                .build()
        }
        val nav = remember { Navigator() }
        // The mouse's back and forward buttons go back and forth like in a browser. AWT numbers them
        // 4/5 on Windows and macOS, 6/7 on X11 (where 4–7 are taken by the scroll wheel).
        DisposableEffect(nav) {
            val listener = AWTEventListener { event ->
                if (event is MouseEvent && event !is MouseWheelEvent && event.id == MouseEvent.MOUSE_PRESSED) {
                    when (event.button) {
                        4, 6 -> nav.back()
                        5, 7 -> nav.forward()
                    }
                }
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
            onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
        }
        if (awtTray) {
            val playing by remember { app.player.state.map { it.current to it.playing }.distinctUntilChanged() }.collectAsState(null to false)
            val (song, isPlaying) = playing
            Tray(
                icon,
                tooltip = song?.let { listOfNotNull(it.title, it.artist).joinToString(" · ") } ?: "Tonearm",
                onAction = main::toggle,
                menu = {
                    Item(if (main.onScreen()) "Hide Tonearm" else "Show Tonearm", onClick = main::toggle)
                    Item(if (isPlaying) "Pause" else "Play", enabled = song != null, onClick = app.player::togglePlay)
                    Item("Next", enabled = song != null, onClick = app.player::next)
                    Item("Previous", enabled = song != null, onClick = app.player::previous)
                    Separator()
                    Item("Quit", onClick = main::quit)
                },
            )
        }
        // The tray menu's Show/Hide follows the window.
        val onScreen = main.onScreen()
        LaunchedEffect(onScreen) { statusNotifier?.menuChanged() }
        LaunchedEffect(main) {
            snapshotFlow { listOf(main.state.placement, main.state.position, main.state.size, main.state.isMinimized) }
                .drop(1).debounce(500).collect { main.save() }
        }
        Window(
            onCloseRequest = {
                // With a tray icon to come back from, closing the window leaves the music playing.
                if (app.config.state.value.closeToTray && (statusNotifier != null || awtTray)) main.hide() else main.quit()
            },
            visible = main.shown,
            title = "Tonearm",
            icon = icon,
            state = main.state,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                when {
                    event.key == Key.MediaPlayPause -> MediaKeys.run(app.player, MediaKeys.PLAY_PAUSE, inWindow = true)
                    event.key == Key.MediaNext -> MediaKeys.run(app.player, MediaKeys.NEXT, inWindow = true)
                    event.key == Key.MediaPrevious -> MediaKeys.run(app.player, MediaKeys.PREVIOUS, inWindow = true)
                    event.isCtrlPressed && event.key == Key.P -> app.player.togglePlay()
                    event.isCtrlPressed && event.key == Key.DirectionRight -> app.player.next()
                    event.isCtrlPressed && event.key == Key.DirectionLeft -> app.player.previous()
                    event.isAltPressed && event.key == Key.DirectionLeft -> nav.back()
                    event.isAltPressed && event.key == Key.DirectionRight -> nav.forward()
                    event.key == Key.Back -> nav.back()
                    event.key == Key.Forward -> nav.forward()
                    else -> return@Window false
                }
                true
            },
        ) {
            window.minimumSize = Dimension(SavedWindow.MIN_WIDTH, SavedWindow.MIN_HEIGHT)
            LaunchedEffect(Unit) {
                delay(MainWindow.SETTLE_MS)
                main.settled()
            }
            LaunchedEffect(main.raised) {
                if (main.raised == 0) return@LaunchedEffect
                window.toFront()
                window.requestFocus()
            }
            TonearmTheme { TonearmApp(app, nav) }
        }
    }
    // Background work (Connect's polling, HTTP threads) would otherwise keep the process alive without a window.
    exitProcess(0)
}

/** Runs [block] on the UI thread (tray, MPRIS and hotkey calls come on threads of their own). */
private fun onUi(block: () -> Unit) = EventQueue.invokeLater(block)

/** Covers from the music server (through its client certificate) and Lidarr's posters (with its API key, or through the Tonearm server). */
private fun imageCalls(app: DesktopApp) = Call.Factory { request ->
    val lidarr = app.lidarr.current.value
    if (lidarr != null && LidarrClient.isCoverUrl(lidarr.url, request.url)) {
        val client = if (lidarr.useServerTls) app.sessions.current()?.client ?: app.baseClient else app.baseClient
        client.forImages().newCall(request.newBuilder().header("X-Api-Key", if (lidarr.viaServer) "" else SecretStore.open(lidarr.keyEnc)).build())
    } else {
        val session = app.sessions.current()
        (if (session != null && session.matches(request.url)) session.client else app.baseClient).forImages().newCall(request)
    }
}
