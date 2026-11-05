package com.trolmastercard.sexmod.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.ConcurrentModificationException

/**
 * **Role.** `/locatenearestgoblinlair` — finds the closest goblin lair
 * (a [GoblinEntity] flagged `isQueen`) to the sender and prints its
 * coordinates; rejects the Nether/End outright. Registered by
 * `Main` (`RegisterCommandsEvent`).
 */
open class CommandLocateGoblinLair {
    open fun getName(): String = "locatenearestgoblinlair"

    open fun getUsage(sender: CommandSourceStack): String = "/locatenearestgoblinlair"

    open fun execute(sender: CommandSourceStack, args: Array<String>) {
        val target = sender.entity
        val dimension = if (target != null) WorldUtils.getDimensionId(target.level()) else 0
        if (target != null && dimension != 0) {
            sender.sendSystemMessage(
                Component.literal(
                    ChatFormatting.YELLOW.toString() +
                        "goblin lairs don't exist in the " +
                        (if (dimension == -1) ChatFormatting.RED.toString() + "Nether" else ChatFormatting.DARK_PURPLE.toString() + "End")
                )
            )
        } else {
            var lair: GoblinEntity? = null
            val senderCorner = Vec3.atLowerCornerOf(BlockPos.containing(sender.position))

            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    if (girl is GoblinEntity) {
                        val goblin = girl
                        if (goblin.isQueen) {
                            if (lair == null) {
                                lair = goblin
                            } else if (goblin.distanceToSqr(senderCorner) < lair.distanceToSqr(senderCorner)) {
                                lair = goblin
                            }
                        }
                    }
                }
            } catch (e: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }

            if (lair == null) {
                sender.sendSystemMessage(Component.literal(ChatFormatting.RED.toString() + "No nearby goblin lair found uwu"))
            } else {
                val pos = lair.blockPosition()
                sender.sendSystemMessage(
                    Component.literal(
                        String.format(
                            "%sgoblin lair found at %s%s %s%s %s%s",
                            ChatFormatting.YELLOW,
                            ChatFormatting.RED,
                            pos.x,
                            ChatFormatting.GREEN,
                            pos.y,
                            ChatFormatting.BLUE,
                            pos.z
                        )
                    )
                )
            }
        }
    }

    companion object {
        @JvmField
        val LOCATE_GOBLIN_LAIR_COMMAND: CommandLocateGoblinLair = CommandLocateGoblinLair()

        /** Brigadier registration: `/locatenearestgoblinlair [args...]` (server command, any permission level). */
        @JvmStatic
        fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
            dispatcher.register(
                Commands.literal(LOCATE_GOBLIN_LAIR_COMMAND.getName())
                    .executes { ctx ->
                        LOCATE_GOBLIN_LAIR_COMMAND.execute(ctx.source, arrayOf())
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { ctx ->
                                LOCATE_GOBLIN_LAIR_COMMAND.execute(ctx.source, CommandFuta.splitArgs(StringArgumentType.getString(ctx, "args")))
                                1
                            }
                    )
            )
        }
    }
}
