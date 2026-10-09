package io.github.deadeyebarb.tonearm.desktop.config

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Keeps passwords and API keys out of the config file: in the desktop keyring through the Secret
 * Service (`secret-tool`: KWallet, GNOME Keyring) on Linux, encrypted with DPAPI on Windows. Where
 * neither is available the secret stays in the config file, which only the user can read.
 *
 * The config holds a sealed reference: `keyring:<name>`, `dpapi:<base64>` or `file:<base64>`.
 * TONEARM_KEYRING=off leaves the keyring alone (test setups: its entries are shared by every Tonearm on the account).
 */
object SecretStore {
    private val keyringOff = System.getenv("TONEARM_KEYRING")?.lowercase() == "off"

    private val secretTool: Boolean by lazy {
        !AppDirs.isWindows && !keyringOff && runCatching { ProcessBuilder("secret-tool", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    fun seal(name: String, secret: String): String {
        if (secret.isEmpty()) return ""
        val sealed = when {
            AppDirs.isWindows -> "dpapi:" + b64(Crypt32Util.cryptProtectData(secret.encodeToByteArray()))
            secretTool && storeInKeyring(name, secret) -> "keyring:$name"
            else -> "file:" + b64(secret.encodeToByteArray())
        }
        // The keyring entry keeps its name, so what was read from it before is stale now.
        opened[sealed] = secret
        failed.remove(sealed)
        changes++
        return sealed
    }

    private val opened = ConcurrentHashMap<String, String>()

    /** Goes up whenever a secret is replaced, so whatever was built with the old one can be rebuilt. */
    @Volatile var changes = 0
        private set

    /** Looking a secret up failed [times] in a row: the keyring is left alone [until] then. */
    private class Failed(val times: Int, val until: Long)
    private val failed = ConcurrentHashMap<String, Failed>()
    private val lookups = ConcurrentHashMap<String, Any>()

    /**
     * The secret behind [sealed], or null when the keyring didn't answer. Each lookup may show an unlock prompt, so
     * there's one at a time per secret (the others wait for its answer), and after a failure the keyring is left
     * alone for a minute, then for longer, up to five minutes.
     */
    fun read(sealed: String): String? {
        opened[sealed]?.let { return it }
        return synchronized(lookups.computeIfAbsent(sealed) { Any() }) {
            opened[sealed] ?: run {
                val last = failed[sealed]
                if (last != null && System.currentTimeMillis() < last.until) return@run null
                unseal(sealed).also { secret ->
                    if (secret != null) {
                        opened[sealed] = secret
                        failed.remove(sealed)
                    } else {
                        val times = (last?.times ?: 0) + 1
                        failed[sealed] = Failed(times, System.currentTimeMillis() + minOf(60_000L shl minOf(times - 1, 3), 300_000L))
                    }
                }
            }
        }
    }

    /** The keyring gets asked again right away, failures or not (the user asked for it). */
    fun retry() = failed.clear()

    /** Like [read], with "" when the keyring didn't answer. */
    fun open(sealed: String): String = read(sealed).orEmpty()

    private fun unseal(sealed: String): String? = when {
        sealed.isEmpty() -> ""
        sealed.startsWith("dpapi:") -> Crypt32Util.cryptUnprotectData(unb64(sealed.removePrefix("dpapi:"))).decodeToString()
        sealed.startsWith("keyring:") -> if (keyringOff) null else lookup(sealed.removePrefix("keyring:"))
        sealed.startsWith("file:") -> unb64(sealed.removePrefix("file:")).decodeToString()
        else -> ""
    }

    fun forget(sealed: String) {
        opened.remove(sealed)
        failed.remove(sealed)
        if (sealed.startsWith("keyring:") && !keyringOff) {
            runCatching { run("secret-tool", "clear", "application", "tonearm", "name", sealed.removePrefix("keyring:")) }
        }
    }

    private fun storeInKeyring(name: String, secret: String): Boolean = runCatching {
        val process = ProcessBuilder("secret-tool", "store", "--label=Tonearm: $name", "application", "tonearm", "name", name).start()
        process.outputStream.use { it.write(secret.encodeToByteArray()) }
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && lookup(name) == secret
    }.getOrDefault(false)

    /**
     * A few tries when the keyring said no at once: right after login, or while it's being updated, it may not
     * answer yet. One that took its time showed an unlock prompt, dismissed or not answered: asking again would
     * only show another one.
     */
    private fun lookup(name: String): String? {
        repeat(3) { attempt ->
            val started = System.nanoTime()
            runCatching { run("secret-tool", "lookup", "application", "tonearm", "name", name) }.getOrNull()?.let { return it }
            if (System.nanoTime() - started > 2_000_000_000L) return null
            if (attempt < 2) Thread.sleep(400)
        }
        return null
    }

    /** Runs [command] and returns what it printed, giving up after 30 s (a keyring that never answers). */
    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).start()
        process.outputStream.close()
        // Waited for before reading: reading first would block for as long as the keyring does. What
        // secret-tool prints fits in the pipe, so it can't stall on a full one.
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw TimeoutException("${command[0]} didn't answer")
        }
        check(process.exitValue() == 0) { "${command[0]} failed" }
        return process.inputStream.readBytes().decodeToString()
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}
