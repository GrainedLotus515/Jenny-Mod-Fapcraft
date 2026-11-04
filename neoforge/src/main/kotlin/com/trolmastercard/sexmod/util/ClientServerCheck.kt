package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * **Role.** Best-effort CLIENT/SERVER thread detector, used by
 * [ThreadNames.createDaemonThread] to name daemon threads. Decides by
 * thread name first ("server"/"client"/"render" substring); falls back to
 * the server's `isSameThread` with a warning when the name is ambiguous.
 */
object ClientServerCheck {
    /** @return `true` if the calling thread is a server thread */
    @JvmStatic
    fun getInstance(): Boolean {
        val threadName = Thread.currentThread().name.lowercase()
        // "entity-deserializer" is the 1.21 server thread that loads saved entities.
        if (threadName.contains("server") || threadName.contains("entity-deserializer") || FMLEnvironment.dist.isDedicatedServer) {
            return true
        }

        // 1.21 renamed the client main thread from "Client thread" to "Render thread".
        if (threadName.contains("client") || threadName.contains("render")) {
            return false
        }

        val server = ServerLifecycleHooks.getCurrentServer() ?: return false

        val onServerThread = server.isSameThread
        Main.LOGGER.warn("couldn't clarify if is running on a server or client thread. Came to the solution onServer=$onServerThread")
        return onServerThread
    }
}
