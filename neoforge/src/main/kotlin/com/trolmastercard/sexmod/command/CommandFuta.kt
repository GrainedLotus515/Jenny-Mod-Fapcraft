package com.trolmastercard.sexmod.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import java.io.BufferedReader
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.ConcurrentModificationException

/**
 * **Role.** Client-side toggle for the Galath futa feature: `/futa
 * true|false` persists the flag to `sexmod/futa` (read back in the
 * constructor) and, when enabled, spawns dragon-breath particles at the
 * `cockParticles` bone of every local Galath. Registered by
 * `ClientProxy` (`RegisterClientCommandsEvent`).
 *
 * Port note: mentions no client types, so [ENABLED] is safe to read from
 * common code.
 */
open class CommandFuta {
    init {
        var line = ""

        try {
            BufferedReader(FileReader("sexmod/futa")).use { reader -> line = reader.readLine().lowercase() }
        } catch (e: Exception) {
            // ignored: sexmod/futa is optional; the command keeps its default state
        }

        if ("" != line) {
            if ("true" == line) {
                ENABLED = true
            }

            if ("false" == line) {
                ENABLED = false
            }
        }
    }

    open fun getName(): String = "futa"

    open fun getUsage(sender: CommandSourceStack): String = "/futa <true|false>"

    open fun execute(sender: CommandSourceStack, args: Array<String>) {
        if (args.size < 1) {
            this.executeFuta(sender)
        } else {
            val arg = args[0].lowercase()
            if ("true" == arg) {
                ENABLED = true
            } else {
                if ("false" != arg) {
                    this.executeFuta(sender)
                    return
                }

                ENABLED = false
            }

            try {
                val writer = FileWriter("sexmod/futa")
                writer.write(arg)
                writer.close()
            } catch (ioe: IOException) {
                Main.LOGGER.error("could not write the futa setting", ioe)
            }

            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    if (girl.isAlive && girl.level().isClientSide && girl is GalathEntity) {
                        val bonePos = girl.getCachedBoneOffset("cockParticles").add(girl.position())
                        val random = girl.random

                        for (i in 0 until 10) {
                            girl.level().addParticle(
                                ParticleTypes.DRAGON_BREATH,
                                bonePos.x,
                                bonePos.y,
                                bonePos.z,
                                (random.nextFloat() * 0.025f * ThreadNames.randomSign()).toDouble(),
                                (random.nextFloat() * 0.025f * ThreadNames.randomSign()).toDouble(),
                                (random.nextFloat() * 0.025f * ThreadNames.randomSign()).toDouble()
                            )
                        }
                    }
                }
            } catch (cme: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }
        }
    }

    open fun executeFuta(sender: CommandSourceStack) {
        sender.sendSystemMessage(
            Component.literal(
                String.format(
                    "%sYou can either do %s/futa true %sor %s/futa false",
                    ChatFormatting.YELLOW, ChatFormatting.GRAY, ChatFormatting.YELLOW, ChatFormatting.GRAY
                )
            )
        )
    }

    open fun allowUsageWithoutPrefix(sender: CommandSourceStack, args: String): Boolean = false

    companion object {
        const val CONFIG_FILE_PATH: String = "sexmod/futa"
        const val PARTICLE_COUNT: Int = 10
        const val PARTICLE_SPREAD: Float = 0.025f

        @JvmField
        var ENABLED: Boolean = true

        @JvmField
        val FUTA_COMMAND: CommandFuta = CommandFuta()

        /** Brigadier registration: `/futa [args...]` (client command). */
        @JvmStatic
        fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
            dispatcher.register(
                Commands.literal(FUTA_COMMAND.getName())
                    .executes { ctx ->
                        FUTA_COMMAND.execute(ctx.source, arrayOf())
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { ctx ->
                                FUTA_COMMAND.execute(ctx.source, splitArgs(StringArgumentType.getString(ctx, "args")))
                                1
                            }
                    )
            )
        }

        /** 1.12 `CommandHandler` argument splitting (on spaces). */
        @JvmStatic
        fun splitArgs(args: String): Array<String> = args.split(" ").filter { it.isNotEmpty() }.toTypedArray()
    }
}
