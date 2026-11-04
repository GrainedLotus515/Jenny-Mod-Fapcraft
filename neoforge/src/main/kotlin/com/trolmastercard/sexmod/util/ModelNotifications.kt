package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.fml.loading.FMLEnvironment
import org.apache.logging.log4j.Level

/**
 * **Role.** Where custom-model diagnostics go: on a client they become
 * coloured chat lines for the local player, on a server they go to the logger.
 * Also renders the "your Fapcraft is outdated" chat notice.
 *
 * **Pitfalls.** On the client a present player means the message is *not*
 * logged at all, so a DEBUG reload reads as chat spam and leaves no log trail.
 * The update notice must stay one literal component — splitting it
 * loses the `§`-colour prefix per line.
 *
 * Port notes: COMMON object (used by the server-side model loader). The 1.12
 * `Main.proxy instanceof ClientProxy` test is `FMLEnvironment.dist.isClient`
 * (equivalent; avoids mentioning the client-only proxy), and the chat output
 * lives in the nested client-only [Client] object (PORTING.md §2.3 rule C).
 */
object ModelNotifications {
    @JvmStatic
    fun logError(level: Level, message: String) {
        if (FMLEnvironment.dist.isClient) {
            logInfo(level, message)
        } else {
            Main.LOGGER.log(level, message)
        }
    }

    @JvmStatic
    fun logInfo(level: Level, message: String) {
        Client.logInfo(level, message)
    }

    @JvmStatic
    fun buildOutdatedNotice(localBuild: Int, latest: String, change: String?): String {
        val msg = StringBuilder()
        msg.append("§eYour Fapcraft is outdated! (installed build ").append(localBuild).append(", latest ").append(latest).append(")")
        msg.append("§7\nDownload: §bhttps://github.com/ReverseEngineeringEnthusiasts/Jenny-Mod-Fapcraft/releases")
        if (change != null && change.isNotEmpty()) {
            msg.append("\n§7What's new in ").append(latest).append(":\n").append(change)
        }

        return msg.toString()
    }

    private object Client {
        fun logInfo(level: Level, message: String) {
            val player = Minecraft.getInstance().player
            if (player == null) {
                Main.LOGGER.log(level, message)
            } else {
                val color: ChatFormatting = if (Level.DEBUG == level) {
                    ChatFormatting.DARK_GREEN
                } else if (Level.ERROR == level) {
                    ChatFormatting.RED
                } else {
                    ChatFormatting.WHITE
                }

                player.sendSystemMessage(Component.literal(color.toString() + message))
            }
        }
    }
}
