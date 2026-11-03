package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * **Role.** Best-effort CLIENT/SERVER thread detector, used by
 * [ThreadNames.createDaemonThread] to name daemon threads. Decides by
 * thread name first ("server"/"client" substring); falls back to
 * the server's `isSameThread` with a warning when the name is ambiguous.
 */
object ClientServerCheck {
    /** @return `true` if the calling thread is a server thread */
    @JvmStatic
    fun getInstance(): Boolean {
        val threadName = Thread.currentThread().name.lowercase()
        if (threadName.contains("server")) {
            return true
        }

        if (threadName.contains("client")) {
            return false
        }

        val server = ServerLifecycleHooks.getCurrentServer() ?: return false

        val onServerThread = server.isSameThread
        Main.LOGGER.warn("couldn't clarify if is running on a server or client thread. Came to the solution onServer=$onServerThread")
        return onServerThread
    }
}
