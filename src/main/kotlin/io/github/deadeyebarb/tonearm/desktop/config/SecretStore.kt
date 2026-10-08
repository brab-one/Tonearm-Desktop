package io.github.deadeyebarb.tonearm.desktop.config

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
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
        changes++
        return sealed
    }

    private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Goes up whenever a secret is replaced, so whatever was built with the old one can be rebuilt. */
    @Volatile var changes = 0
        private set

    /** The secret behind [sealed], or null when the keyring didn't answer: not remembered, so it's asked again next time. */
    fun read(sealed: String): String? = opened[sealed] ?: unseal(sealed)?.also { opened[sealed] = it }

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
        if (sealed.startsWith("keyring:") && !keyringOff) {
            runCatching { run("secret-tool", "clear", "application", "tonearm", "name", sealed.removePrefix("keyring:")) }
        }
    }

    private fun storeInKeyring(name: String, secret: String): Boolean = runCatching {
        val process = ProcessBuilder("secret-tool", "store", "--label=Tonearm: $name", "application", "tonearm", "name", name).start()
        process.outputStream.use { it.write(secret.encodeToByteArray()) }
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && lookup(name) == secret
    }.getOrDefault(false)

    /** A few tries: right after login, or while the keyring is being updated, it may not answer yet. */
    private fun lookup(name: String): String? {
        repeat(3) { attempt ->
            val result = runCatching { run("secret-tool", "lookup", "application", "tonearm", "name", name) }
            result.getOrNull()?.let { return it }
            // One that didn't answer at all (an unlock prompt nobody answers) isn't asked again right away.
            if (result.exceptionOrNull() is TimeoutException) return null
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
