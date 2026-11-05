package com.trolmastercard.sexmod.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UnknownPacket
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * **Role.** `/reloadcustommodels` (op level 2) — reloads the server's
 * custom-model registry and pushes the fresh model scales to every connected
 * player via [UnknownPacket] so client-side rendering matches the new
 * registry without a rejoin.
 */
open class CommandReloadCustomModels {
    open fun getName(): String = "reloadcustommodels"

    open fun getUsage(sender: CommandSourceStack): String = "/reloadcustommodels"

    open fun getRequiredPermissionLevel(): Int = 2

    open fun execute(sender: CommandSourceStack, args: Array<String>) {
        val server = sender.server
        ServerWhitelistManager.getModelCount(false)

        for (player in server.playerList.players) {
            server.execute { PacketHandler.networkWrapper.sendTo(UnknownPacket(ServerWhitelistManager.getModelScales()), player) }
        }
    }

    companion object {
        @JvmField
        val RELOAD_CUSTOM_MODELS_COMMAND: CommandReloadCustomModels = CommandReloadCustomModels()

        /** Brigadier registration: `/reloadcustommodels [args...]`, requires permission level 2. */
        @JvmStatic
        fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
            dispatcher.register(
                Commands.literal(RELOAD_CUSTOM_MODELS_COMMAND.getName())
                    .requires { it.hasPermission(RELOAD_CUSTOM_MODELS_COMMAND.getRequiredPermissionLevel()) }
                    .executes { ctx ->
                        RELOAD_CUSTOM_MODELS_COMMAND.execute(ctx.source, arrayOf())
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { ctx ->
                                RELOAD_CUSTOM_MODELS_COMMAND.execute(ctx.source, CommandFuta.splitArgs(StringArgumentType.getString(ctx, "args")))
                                1
                            }
                    )
            )
        }
    }
}
