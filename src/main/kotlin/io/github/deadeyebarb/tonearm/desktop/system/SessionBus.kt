package io.github.deadeyebarb.tonearm.desktop.system

import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.connections.IDisconnectCallback
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.types.Variant
import java.io.IOException

/** The desktop session's D-Bus (Linux). MPRIS and the tray icon share one connection. */
object SessionBus {
    /**
     * Connects, or null without a session bus (or with one only reachable through an abstract socket). One
     * try: an address left over from an old session (ssh, tmux) doesn't hold up the window. [onLost] runs
     * when the bus drops the connection later (on one of its threads).
     */
    fun open(onLost: () -> Unit): DBusConnection? = runCatching {
        DBusConnectionBuilder.forSessionBus().withShared(false)
            .transportConfig().withTimeout(500).back()
            .withDisconnectCallback(object : IDisconnectCallback {
                override fun disconnectOnError(e: IOException) = onLost()
                override fun requestedDisconnect(connectionId: Int?) = onLost()
            })
            .build()
    }.getOrNull()
}

/** A string the bus takes: dbus-daemon drops the connection of anyone who sends a NUL in one. */
internal fun String.busSafe() = replace('\u0000', ' ').trim()

/** Whether some program owns [name] on the bus. */
internal fun DBusConnection.hasOwner(name: String): Boolean =
    runCatching { getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).NameHasOwner(name) }.getOrDefault(false)

/** Calls [member] of [iface] on [destination] and waits for the answer (an Error when it failed); null when none came. */
internal fun DBusConnection.call(destination: String, path: String, iface: String, member: String, signature: String?, vararg args: Any): Message? =
    runCatching {
        val call = messageFactory.createMethodCall(destination, path, iface, member, 0, signature, *args)
        sendMessage(call)
        call.getReply(5_000)
    }.getOrNull()

/** Sends the signal [member] of [iface] from the object at [path]; [signature] describes [args]. */
internal fun DBusConnection.signal(path: String, iface: String, member: String, signature: String?, vararg args: Any) {
    runCatching { sendMessage(messageFactory.createSignal(null, path, iface, member, signature, *args)) }
}

/** Tells listeners that [changed] properties of [iface] have new values. */
internal fun DBusConnection.propertiesChanged(path: String, iface: String, changed: Map<String, Variant<*>>) {
    if (changed.isEmpty()) return
    signal(path, "org.freedesktop.DBus.Properties", "PropertiesChanged", "sa{sv}as", iface, changed, emptyList<String>())
}

/** Property types for introspection. */
internal interface StringList : TypeRef<List<String>>
internal interface VariantMap : TypeRef<Map<String, Variant<*>>>
