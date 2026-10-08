package io.github.deadeyebarb.tonearm.desktop.system

import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.messages.Error
import org.freedesktop.dbus.types.Variant

/** The desktop session's D-Bus (Linux). MPRIS and the tray icon share one connection. */
object SessionBus {
    /** Connects, or null without a session bus (or with one only reachable through an abstract socket). */
    fun open(): DBusConnection? = runCatching { DBusConnectionBuilder.forSessionBus().withShared(false).build() }.getOrNull()
}

/** Whether some program owns [name] on the bus. */
internal fun DBusConnection.hasOwner(name: String): Boolean =
    runCatching { getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).NameHasOwner(name) }.getOrDefault(false)

/** Calls [member] of [iface] on [destination] and waits for the answer; false when it failed or didn't come. */
internal fun DBusConnection.call(destination: String, path: String, iface: String, member: String, signature: String?, vararg args: Any): Boolean =
    runCatching {
        val call = messageFactory.createMethodCall(destination, path, iface, member, 0, signature, *args)
        sendMessage(call)
        call.getReply(5_000).let { it != null && it !is Error }
    }.getOrDefault(false)

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
