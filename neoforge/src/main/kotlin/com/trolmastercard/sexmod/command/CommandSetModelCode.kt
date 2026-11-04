package com.trolmastercard.sexmod.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadModelStringPacket
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * **Role.** `/setmodelcode` — client-side upload of a custom model code
 * (and optional `$`-separated part-id list) for the targeted girl; without
 * a valid girl target it applies to the sender's own player-girl. The payload is
 * sent via [UploadModelStringPacket] to the server for validation and
 * persistence. CLIENT command (`RegisterClientCommandsEvent`); CLIENT-ONLY class.
 */
open class CommandSetModelCode {
    open fun allowUsageWithoutPrefix(sender: CommandSourceStack, args: String): Boolean = false

    open fun getName(): String = "setmodelcode"

    open fun getUsage(sender: CommandSourceStack): String = "/setmodelcode"

    open fun checkPermission(sender: CommandSourceStack): Boolean = true

    open fun execute(sender: CommandSourceStack, args: Array<String>) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        var modelCode = ""
        var parts = ""
        if (args.size > 0) {
            val partsArr = args[0].split("$")
            modelCode = partsArr[0]
            if (partsArr.size > 1) {
                parts = partsArr[1]
            }
        }

        val rayTrace = Minecraft.getInstance().hitResult
        val target = this.getTargetGirl(rayTrace)
        if (target == null) {
            player.displayClientMessage(Component.literal("You gotta transform into the girl you want to apply the model-code to"), true)
        } else if ("" == parts) {
            PacketHandler.networkWrapper.sendToServer(UploadModelStringPacket(modelCode, target.getGirlId()))
            player.displayClientMessage(Component.literal(this.getModelCodeText(target)), true)
        } else {
            PacketHandler.networkWrapper.sendToServer(UploadModelStringPacket(modelCode, target.getGirlId(), BaseGirlEntity.decodePartIdList(parts)))
            player.displayClientMessage(Component.literal(this.getModelCodeText(target)), true)
        }
    }

    open fun getModelCodeText(girl: BaseGirlEntity): String {
        return if (girl is AbstractPlayerGirlEntity)
            ChatFormatting.YELLOW.toString() + "applied model code to your player-" + ThreadNames.capitalizeFirst(NpcType.getNpcType(girl).toString())
        else
            ChatFormatting.YELLOW.toString() + "applied model code to this " + girl.getDisplayNameText()
    }

    /** 1.12 `objectMouseOver` (null) / `entityHit` -> 1.21 [HitResult] / [EntityHitResult]. */
    open fun getTargetGirl(target: HitResult?): BaseGirlEntity? {
        val player = Minecraft.getInstance().player ?: return null
        if (target == null) {
            return AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
        } else {
            val entityHit = (target as? EntityHitResult)?.entity
            return if (BaseGirlEntity.isValidGirl(entityHit))
                entityHit as BaseGirlEntity
            else
                AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
        }
    }

    companion object {
        @JvmField
        val SET_MODEL_CODE_COMMAND: CommandSetModelCode = CommandSetModelCode()

        /** Brigadier registration: `/setmodelcode [args...]` (client command). */
        @JvmStatic
        fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
            dispatcher.register(
                Commands.literal(SET_MODEL_CODE_COMMAND.getName())
                    .executes { ctx ->
                        SET_MODEL_CODE_COMMAND.execute(ctx.source, arrayOf())
                        1
                    }
                    .then(
                        Commands.argument("args", StringArgumentType.greedyString())
                            .executes { ctx ->
                                SET_MODEL_CODE_COMMAND.execute(ctx.source, CommandFuta.splitArgs(StringArgumentType.getString(ctx, "args")))
                                1
                            }
                    )
            )
        }
    }
}
