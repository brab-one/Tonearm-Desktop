package io.github.deadeyebarb.tonearm.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.deadeyebarb.tonearm.desktop.config.AppDirs
import io.github.deadeyebarb.tonearm.desktop.config.SecretStore
import io.github.deadeyebarb.tonearm.desktop.player.MpvMissingException
import io.github.deadeyebarb.tonearm.desktop.ui.Navigator
import io.github.deadeyebarb.tonearm.desktop.ui.TonearmApp
import io.github.deadeyebarb.tonearm.desktop.ui.TonearmTheme
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.net.forImages
import okhttp3.Call
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Image
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    System.setProperty("jna.encoding", "UTF-8")
    val app = try {
        DesktopApp()
    } catch (e: MpvMissingException) {
        JOptionPane.showMessageDialog(null, "Tonearm needs libmpv to play music.\n\n${e.message}", "Tonearm", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val icon = BitmapPainter(Image.makeFromEncoded(requireNotNull(DesktopApp::class.java.getResourceAsStream("/icon.png")).readBytes()).toComposeImageBitmap())

    application {
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
        Window(
            onCloseRequest = {
                app.shutdown()
                exitApplication()
            },
            title = "Tonearm",
            icon = icon,
            state = rememberWindowState(width = 1360.dp, height = 860.dp),
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                when {
                    event.key == Key.MediaPlayPause || (event.isCtrlPressed && event.key == Key.P) -> app.player.togglePlay()
                    event.key == Key.MediaNext || (event.isCtrlPressed && event.key == Key.DirectionRight) -> app.player.next()
                    event.key == Key.MediaPrevious || (event.isCtrlPressed && event.key == Key.DirectionLeft) -> app.player.previous()
                    event.isAltPressed && event.key == Key.DirectionLeft -> nav.back()
                    event.isAltPressed && event.key == Key.DirectionRight -> nav.forward()
                    event.key == Key.Back -> nav.back()
                    event.key == Key.Forward -> nav.forward()
                    else -> return@Window false
                }
                true
            },
        ) {
            window.minimumSize = Dimension(1000, 640)
            TonearmTheme { TonearmApp(app, nav) }
        }
    }
}

/** Covers from the music server (through its client certificate) and Lidarr's posters (with its API key). */
private fun imageCalls(app: DesktopApp) = Call.Factory { request ->
    val lidarr = app.config.state.value.lidarr
    if (lidarr != null && LidarrClient.isCoverUrl(lidarr.url, request.url)) {
        val client = if (lidarr.useServerTls) app.sessions.current()?.client ?: app.baseClient else app.baseClient
        client.forImages().newCall(request.newBuilder().header("X-Api-Key", SecretStore.open(lidarr.keyEnc)).build())
    } else {
        val session = app.sessions.current()
        (if (session != null && session.matches(request.url)) session.client else app.baseClient).forImages().newCall(request)
    }
}
