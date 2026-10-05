package io.github.deadeyebarb.tonearm.desktop.config

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Keeps passwords and API keys out of the config file: in the desktop keyring through the Secret
 * Service (`secret-tool`: KWallet, GNOME Keyring) on Linux, encrypted with DPAPI on Windows. Where
 * neither is available the secret stays in the config file, which only the user can read.
 *
 * The config holds a sealed reference: `keyring:<name>`, `dpapi:<base64>` or `file:<base64>`.
 */
object SecretStore {
    private val secretTool: Boolean by lazy {
        !AppDirs.isWindows && runCatching { ProcessBuilder("secret-tool", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    fun seal(name: String, secret: String): String {
        if (secret.isEmpty()) return ""
        if (AppDirs.isWindows) return "dpapi:" + b64(Crypt32Util.cryptProtectData(secret.encodeToByteArray()))
        if (secretTool && storeInKeyring(name, secret)) return "keyring:$name"
        return "file:" + b64(secret.encodeToByteArray())
    }

    private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun open(sealed: String): String = opened.getOrPut(sealed) { unseal(sealed) }

    private fun unseal(sealed: String): String = when {
        sealed.isEmpty() -> ""
        sealed.startsWith("dpapi:") -> Crypt32Util.cryptUnprotectData(unb64(sealed.removePrefix("dpapi:"))).decodeToString()
        sealed.startsWith("keyring:") -> lookup(sealed.removePrefix("keyring:")).orEmpty()
        sealed.startsWith("file:") -> unb64(sealed.removePrefix("file:")).decodeToString()
        else -> ""
    }

    fun forget(sealed: String) {
        opened.remove(sealed)
        if (sealed.startsWith("keyring:")) {
            runCatching { run("secret-tool", "clear", "application", "tonearm", "name", sealed.removePrefix("keyring:")) }
        }
    }

    private fun storeInKeyring(name: String, secret: String): Boolean = runCatching {
        val process = ProcessBuilder("secret-tool", "store", "--label=Tonearm: $name", "application", "tonearm", "name", name).start()
        process.outputStream.use { it.write(secret.encodeToByteArray()) }
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && lookup(name) == secret
    }.getOrDefault(false)

    private fun lookup(name: String): String? = runCatching { run("secret-tool", "lookup", "application", "tonearm", "name", name) }.getOrNull()

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command[0]} failed" }
        return out
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}
