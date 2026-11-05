package com.trolmastercard.sexmod.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * **Role.** `/whitelistserver [confirm]` — client-side opt-in for a
 * server to push custom models: adds the current server IP to
 * `sexmod/custom_models/whitelisted_servers.txt` and requests the model
 * download. The two-step confirm is a security gate (only whitelist servers you
 * trust). CLIENT command (`RegisterClientCommandsEvent`).
 */
open class CommandWhitelistServer {
    open fun getName(): String = "whitelistserver"

    open fun getUsage(sender: CommandSourceStack): String = "/whitelistserver"

    open fun allowUsageWithoutPrefix(sender: CommandSourceStack, args: String): Boolean = false

    open fun checkPermission(sender: CommandSourceStack): Boolean = true

    open fun execute(sender: CommandSourceStack, args: Array<String>) {
        val key = ServerWhitelistManager.getCustomModelsKey()
        if (key == null) {
            sender.sendSystemMessage(Component.literal(ChatFormatting.YELLOW.toString() + "This is a multiplayer feature only"))
        } else if (ServerWhitelistManager.isModelWhitelisted(key)) {
            sender.sendSystemMessage(Component.literal(ChatFormatting.GREEN.toString() + "Server is already whitelisted :)"))
        } else {
            val confirmed = args.size > 0 && "confirm" == args[0]
            if (!confirmed) {
                sender.sendSystemMessage(
                    Component.literal(
                        ChatFormatting.YELLOW.toString() + "By whitelisting this server, you allow the server to send you the custom models that are used on it"
                    )
                )
                sender.sendSystemMessage(Component.literal(ChatFormatting.RED.toString() + "ONLY WHITELIST SERVERS, WHOSE SERVER OWNER YOU KNOW AND TRUST"))
                sender.sendSystemMessage(Component.literal(ChatFormatting.YELLOW.toString() + "to confirm your decision type:"))
                sender.sendSystemMessage(Component.literal(ChatFormatting.GREEN.toString() + "/whitelistserver confirm"))
            } else {
                ServerWhitelistManager.initWhitelistFile(key)
                sender.sendSystemMessage(Component.literal(ChatFormatting.GREEN.toString() + "confirmed :)"))
                ServerWhitelistManager.reloadCustomModels()
            }
        }
    }

    companion object {
        @JvmField
        val WHITELIST_SERVER_COMMAND: CommandWhitelistServer = CommandWhitelistServer()

        /** Brigadier registration: `/whitelistserver [args...]` (client command). */
        @JvmStatic
        fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
            dispatcher.register(
                Commands.literal(WHITELIST_SERVER_COMMAND.getName())
                    .executes { ctx ->
                        WHITELIST_SERVER_COMMAND.execute(ctx.source, arrayOf())
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { ctx ->
                                WHITELIST_SERVER_COMMAND.execute(ctx.source, CommandFuta.splitArgs(StringArgumentType.getString(ctx, "args")))
                                1
                            }
                    )
            )
        }
    }
}
