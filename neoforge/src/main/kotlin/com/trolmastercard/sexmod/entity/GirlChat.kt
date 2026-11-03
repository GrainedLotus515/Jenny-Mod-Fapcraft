package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendChatMessagePacket
import com.trolmastercard.sexmod.networking.TargetPoint
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.network.chat.Component

/**
 * Chat output for a girl: local player messages, packeted server chat and the
 * "message to tracking players" broadcast.
 *
 * Extracted verbatim from [BaseGirlEntity]; the entity keeps its
 * methods as delegates. Overridable hooks (`sendGirlChatMessage`,
 * `isControlledByLocalPlayer`) are invoked through the girl instance so
 * subclass overrides still dispatch.
 */
object GirlChat {
    @JvmStatic
    fun sendMessageToTrackingPlayers(girl: BaseGirlEntity, message: String) {
        for (player in WorldUtils.getNearbyPlayers(girl)) {
            player.sendSystemMessage(Component.literal(message))
        }
    }

    /** Broadcasts "&lt;name&gt; message" chat to nearby players (both sides). */
    @JvmStatic
    fun sendGirlChatMessage(girl: BaseGirlEntity, message: String) {
        val dimension = WorldUtils.getDimensionId(girl.level())
        if (!girl.level().isClientSide) {
            PacketHandler.networkWrapper.sendToAllAround(
                SendChatMessagePacket(String.format("<%s> %s", girl.getEffectiveDisplayName(), message), dimension, girl.getGirlId()),
                TargetPoint(dimension, girl.x, girl.y, girl.z, 40.0),
            )
        } else if (girl.isControlledByLocalPlayer()) {
            PacketHandler.networkWrapper.sendToServer(
                SendChatMessagePacket(String.format("<%s> %s", girl.getEffectiveDisplayName(), message), dimension, girl.getGirlId()),
            )
        }
    }

    /** Sends the given message as the girl; if not local, additionally calls sendGirlChatMessage. */
    @JvmStatic
    fun broadcastChatAround(girl: BaseGirlEntity, message: String, toAll: Boolean) {
        if (!toAll) {
            girl.sendGirlChatMessage(message)
        }

        val dimension = WorldUtils.getDimensionId(girl.level())
        if (!girl.level().isClientSide) {
            PacketHandler.networkWrapper.sendToAllAround(
                SendChatMessagePacket(message, dimension, girl.getGirlId()),
                TargetPoint(dimension, girl.x, girl.y, girl.z, 40.0),
            )
        } else {
            if (girl.isControlledByLocalPlayer()) {
                PacketHandler.networkWrapper.sendToServer(SendChatMessagePacket(message, dimension, girl.getGirlId()))
            }
        }
    }

    /** CLIENT: shows "&lt;name&gt; message" in the local chat. */
    @JvmStatic
    fun sendChatMessage(girl: BaseGirlEntity, message: String) {
        if (girl.level().isClientSide) {
            ClientHooks.displayChat(String.format("<%s> %s", girl.getEffectiveDisplayName(), message))
        }
    }

    @JvmStatic
    fun sendChatMessageToPlayer(girl: BaseGirlEntity, uuid: UUID, message: String) {
        val player = girl.level().getPlayerByUUID(uuid)
        if (player == null) {
            Main.LOGGER.warn("Player with UUID $uuid not found")
        } else {
            if (girl.level().isClientSide) {
                ClientHooks.displayChat("<" + player.gameProfile.name + "> " + message)
            }
        }
    }
}
