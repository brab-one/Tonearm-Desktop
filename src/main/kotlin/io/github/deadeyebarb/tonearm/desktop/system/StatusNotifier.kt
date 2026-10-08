package io.github.deadeyebarb.tonearm.desktop.system

import io.github.deadeyebarb.tonearm.desktop.player.DesktopPlayer
import io.github.deadeyebarb.tonearm.desktop.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.Error
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/** An icon in one size: ARGB32 pixels in network byte order. */
class Pixmap(@field:Position(0) val width: Int, @field:Position(1) val height: Int, @field:Position(2) val pixels: ByteArray) : Struct()

class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val icon: List<Pixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val text: String,
) : Struct()

internal interface PixmapList : TypeRef<List<Pixmap>>

@Suppress("FunctionName")
@DBusInterfaceName("org.kde.StatusNotifierItem")
@DBusProperty(name = "Category", type = String::class, access = Access.READ)
@DBusProperty(name = "Id", type = String::class, access = Access.READ)
@DBusProperty(name = "Title", type = String::class, access = Access.READ)
@DBusProperty(name = "Status", type = String::class, access = Access.READ)
@DBusProperty(name = "WindowId", type = Int::class, access = Access.READ)
@DBusProperty(name = "IconName", type = String::class, access = Access.READ)
@DBusProperty(name = "IconPixmap", type = PixmapList::class, access = Access.READ)
@DBusProperty(name = "OverlayIconName", type = String::class, access = Access.READ)
@DBusProperty(name = "OverlayIconPixmap", type = PixmapList::class, access = Access.READ)
@DBusProperty(name = "AttentionIconName", type = String::class, access = Access.READ)
@DBusProperty(name = "AttentionIconPixmap", type = PixmapList::class, access = Access.READ)
@DBusProperty(name = "AttentionMovieName", type = String::class, access = Access.READ)
@DBusProperty(name = "ToolTip", type = ToolTip::class, access = Access.READ)
@DBusProperty(name = "ItemIsMenu", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "Menu", type = DBusPath::class, access = Access.READ)
interface StatusNotifierItem : DBusInterface {
    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
}

/** A menu entry and the entries under it (com.canonical.dbusmenu's layout). */
class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

class MenuProperties(@field:Position(0) val id: Int, @field:Position(1) val properties: Map<String, Variant<*>>) : Struct()

class MenuEvent(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32,
) : Struct()

/** Two return values. */
class Pair2<A, B>(@field:Position(0) val first: A, @field:Position(1) val second: B) : Tuple()

@Suppress("FunctionName")
@DBusInterfaceName("com.canonical.dbusmenu")
@DBusProperty(name = "Version", type = UInt32::class, access = Access.READ)
@DBusProperty(name = "TextDirection", type = String::class, access = Access.READ)
@DBusProperty(name = "Status", type = String::class, access = Access.READ)
@DBusProperty(name = "IconThemePath", type = StringList::class, access = Access.READ)
interface DBusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Pair2<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): Pair2<List<Int>, List<Int>>
}

/**
 * The tray icon on Linux desktops with a StatusNotifier host (Plasma, GNOME with the AppIndicator
 * extension, most panels): a StatusNotifierItem with a dbusmenu menu. A left click shows or hides the
 * window, a middle click plays or pauses, and the tooltip names the song.
 */
@Suppress("FunctionName")
class StatusNotifier private constructor(
    private val bus: DBusConnection,
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
    /** The watcher's bus name, which is also its interface's name. */
    private val watcher: String,
    /** Our own bus name, the one the watcher knows the icon by. */
    private val name: String,
    private val icon: List<Pixmap>,
    /** Whether the window is on screen (for the menu's Show/Hide). */
    private val windowShown: () -> Boolean,
    private val toggleWindow: () -> Unit,
    private val quit: () -> Unit,
    /** The tray went away for good (not just restarting) and nothing shows the icon anymore. */
    private val trayGone: () -> Unit,
) : StatusNotifierItem, Properties {
    private var revision = 1
    /** A host (the panel's tray) shows the watcher's icons. A watcher can run without one: then there's no tray. */
    @Volatile private var hostPresent = false

    /** Whether the icon is in a tray, so the window can hide to it. */
    val trayShown: Boolean get() = hostPresent && bus.isConnected

    override fun getObjectPath() = ITEM_PATH

    override fun ContextMenu(x: Int, y: Int) = Unit
    override fun Activate(x: Int, y: Int) = toggleWindow()
    override fun SecondaryActivate(x: Int, y: Int) = player.togglePlay()
    override fun Scroll(delta: Int, orientation: String) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        throw PropertyReadOnly(propertyName)
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
        "Category" to Variant("ApplicationStatus"),
        "Id" to Variant("tonearm"),
        "Title" to Variant("Tonearm"),
        "Status" to Variant("Active"),
        "WindowId" to Variant(0),
        "IconName" to Variant(""),
        "IconPixmap" to Variant(icon, "a(iiay)"),
        "OverlayIconName" to Variant(""),
        "OverlayIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
        "AttentionIconName" to Variant(""),
        "AttentionIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
        "AttentionMovieName" to Variant(""),
        "ToolTip" to Variant(toolTip(player.state.value), "(sa(iiay)ss)"),
        "ItemIsMenu" to Variant(false),
        "Menu" to Variant(DBusPath(MENU_PATH)),
    )

    private fun toolTip(s: PlayerState): ToolTip {
        val song = s.current ?: return ToolTip("", emptyList(), "Tonearm", "")
        return ToolTip("", emptyList(), song.title.busSafe(), listOfNotNull(song.artist?.busSafe(), "paused".takeIf { !s.playing }).joinToString(" · "))
    }

    /** Puts the icon in the watcher's list and finds out whether a tray shows it. It waits for answers: not on the UI thread. */
    private fun register() {
        val reply = bus.call(watcher, WATCHER_PATH, watcher, "RegisterStatusNotifierItem", "s", name)
        // No answer isn't a no: the watcher may just be slow, and its host is asked next.
        if (reply is Error) hostChanged(false) else readHost()
    }

    /** Asks the watcher whether a host is registered; one without that property is taken to have one. */
    private fun readHost() {
        val present = runCatching {
            val value: Any? = bus.getRemoteObject(watcher, WATCHER_PATH, Properties::class.java).Get(watcher, "IsStatusNotifierHostRegistered")
            ((value as? Variant<*>)?.value ?: value) != false
        }.getOrDefault(true)
        hostChanged(present)
    }

    private fun hostChanged(present: Boolean) {
        val was = hostPresent
        hostPresent = present
        if (was && !present) {
            scope.launch {
                // A tray that restarts (plasmashell) is back within moments.
                delay(3_000)
                if (!hostPresent) trayGone()
            }
        }
    }

    /** The menu has changed (the window was shown or hidden, playback started or stopped): hosts fetch it again. */
    @Synchronized
    fun menuChanged() {
        revision++
        bus.signal(MENU_PATH, MENU, "LayoutUpdated", "ui", UInt32(revision.toLong()), 0)
    }

    /** The tray menu (com.canonical.dbusmenu) at [MENU_PATH]. */
    private val menu = object : DBusMenu, Properties {
        override fun getObjectPath() = MENU_PATH

        /** The entries by id; 0 is the root. */
        private fun items(): List<Pair<Int, Map<String, Variant<*>>>> {
            val s = player.state.value
            val shown = windowShown()
            fun item(label: String, icon: String? = null, enabled: Boolean = true) = buildMap<String, Variant<*>> {
                put("label", Variant(label))
                put("enabled", Variant(enabled))
                icon?.let { put("icon-name", Variant(it)) }
            }
            return listOf(
                SHOW to item(if (shown) "Hide Tonearm" else "Show Tonearm"),
                PLAY to if (s.playing) item("Pause", "media-playback-pause") else item("Play", "media-playback-start", enabled = s.current != null),
                NEXT to item("Next", "media-skip-forward", enabled = s.current != null),
                PREVIOUS to item("Previous", "media-skip-backward", enabled = s.current != null),
                SEPARATOR to mapOf("type" to Variant("separator")),
                QUIT to item("Quit", "application-exit"),
            )
        }

        private fun layout(id: Int, depth: Int): MenuLayout {
            val items = items()
            if (id != ROOT_ID) return MenuLayout(id, items.firstOrNull { it.first == id }?.second.orEmpty(), emptyList())
            val children = if (depth == 0) emptyList() else items.map { (child, props) -> Variant(MenuLayout(child, props, emptyList()), "(ia{sv}av)") }
            return MenuLayout(ROOT_ID, mapOf("children-display" to Variant("submenu")), children)
        }

        @Synchronized
        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>) =
            Pair2(UInt32(revision.toLong()), layout(parentId, recursionDepth))

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuProperties> =
            items().filter { ids.isEmpty() || it.first in ids }.map { MenuProperties(it.first, it.second) }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            items().firstOrNull { it.first == id }?.second?.get(name) ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                SHOW -> toggleWindow()
                PLAY -> player.togglePlay()
                NEXT -> player.next()
                PREVIOUS -> player.previous()
                QUIT -> quit()
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            val known = items().map { it.first }.toSet() + ROOT_ID
            events.filter { it.id in known }.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return events.map { it.id }.filter { it !in known }
        }

        override fun AboutToShow(id: Int) = false

        override fun AboutToShowGroup(ids: List<Int>) = Pair2(emptyList<Int>(), emptyList<Int>())

        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            throw PropertyReadOnly(propertyName)
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )
    }

    companion object {
        private const val ITEM_PATH = "/StatusNotifierItem"
        private const val MENU_PATH = "/MenuBar"
        private const val WATCHER_PATH = "/StatusNotifierWatcher"
        private const val ITEM = "org.kde.StatusNotifierItem"
        private const val MENU = "com.canonical.dbusmenu"
        private val WATCHERS = listOf("org.kde.StatusNotifierWatcher", "org.freedesktop.StatusNotifierWatcher")
        private const val ROOT_ID = 0
        private const val SHOW = 1
        private const val PLAY = 2
        private const val NEXT = 3
        private const val PREVIOUS = 4
        private const val SEPARATOR = 5
        private const val QUIT = 6

        /**
         * Puts the icon up when the desktop has a StatusNotifier watcher; null without one. Whether a tray
         * actually shows it is found out in the background ([trayShown]). The callbacks come on D-Bus's threads.
         */
        fun start(
            bus: DBusConnection,
            player: DesktopPlayer,
            scope: CoroutineScope,
            iconPng: ByteArray,
            windowShown: () -> Boolean,
            toggleWindow: () -> Unit,
            quit: () -> Unit,
            trayGone: () -> Unit,
        ): StatusNotifier? = runCatching {
            val watcher = WATCHERS.firstOrNull(bus::hasOwner) ?: return null
            val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            val item = StatusNotifier(bus, player, scope, watcher, name, pixmaps(iconPng), windowShown, toggleWindow, quit, trayGone)
            bus.exportObject(ITEM_PATH, item)
            bus.exportObject(MENU_PATH, item.menu)
            bus.requestBusName(name)
            // A tray that restarts (Plasma does) forgets its icons: register again when its watcher is back.
            bus.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                if (signal.name != watcher) return@addSigHandler
                if (signal.newOwner.isEmpty()) item.hostChanged(false) else scope.launch(Dispatchers.IO) { item.register() }
            }
            // Trays (hosts) come and go while the watcher stays, e.g. the panel's tray removed or plasmashell restarting.
            val hosts = DBusMatchRuleBuilder.create().withType("signal").withInterface(watcher).withPath(WATCHER_PATH).build()
            bus.addGenericSigHandler(hosts) { signal ->
                if (signal.name == "StatusNotifierHostRegistered" || signal.name == "StatusNotifierHostUnregistered") {
                    scope.launch(Dispatchers.IO) { item.readHost() }
                }
            }
            scope.launch(Dispatchers.IO) { item.register() }
            scope.launch {
                player.state.map { Triple(it.current?.title, it.current?.artist, it.playing) }.distinctUntilChanged().collect {
                    bus.signal(ITEM_PATH, ITEM, "NewToolTip", null)
                }
            }
            scope.launch {
                player.state.map { it.playing to (it.current != null) }.distinctUntilChanged().collect { item.menuChanged() }
            }
            item
        }.getOrNull()

        /** The app icon in the sizes panels ask for. */
        private fun pixmaps(png: ByteArray): List<Pixmap> {
            val image = ImageIO.read(ByteArrayInputStream(png))
            return listOf(16, 22, 24, 32, 48, 64).map { size ->
                val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                scaled.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    drawImage(image, 0, 0, size, size, null)
                    dispose()
                }
                val pixels = ByteBuffer.allocate(size * size * 4)
                for (y in 0 until size) for (x in 0 until size) pixels.putInt(scaled.getRGB(x, y))
                Pixmap(size, size, pixels.array())
            }
        }
    }
}
