package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

/**
 * **Role.** The whitelist file backing [ServerWhitelistManager]:
 * `sexmod/custom_models/whitelisted_servers.txt`, one server address per
 * line. Reads it into a set, answers membership tests and rewrites it with one
 * more server appended.
 *
 * **Pitfalls.** Every read recreates the file when it is missing, so even a
 * membership test touches the disk. Lines are taken verbatim — no trimming, no
 * blank-line filter — so a stored line must match the server address exactly.
 */
object ServerWhitelistFile {
    @JvmStatic
    fun contains(serverName: String): Boolean = read().contains(serverName)

    @JvmStatic
    fun read(): HashSet<String> {
        val file = File(ServerWhitelistManager.WHITELIST_FILE)

        try {
            file.parentFile?.mkdirs()
            file.createNewFile()
        } catch (exception: Exception) {
            Main.LOGGER.error("could not create the whitelisted servers file", exception)
        }

        val servers = HashSet<String>()

        try {
            val reader = BufferedReader(FileReader(file))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    servers.add(line)
                }
            } finally {
                reader.close()
            }

            return servers
        } catch (ioException: IOException) {
            Main.LOGGER.error("could not read the whitelisted servers file", ioException)
            return HashSet()
        }
    }

    @JvmStatic
    fun addServer(serverName: String) {
        var file = File(ServerWhitelistManager.WHITELIST_FILE)
        // 1.12 quirk kept: mkdirs() on the file path itself (the empty directory is deleted below)
        file.mkdirs()
        var whitelist = HashSet<String>()
        if (file.exists()) {
            whitelist = read()
        }

        whitelist.add(serverName)
        file.delete()
        file = File(ServerWhitelistManager.WHITELIST_FILE)

        try {
            FileWriter(file).use { writer ->
                for (server in whitelist) {
                    writer.write(server + "\n")
                }
            }
        } catch (ioException: IOException) {
            Main.LOGGER.error("could not close the whitelisted servers file", ioException)
        }
    }
}
