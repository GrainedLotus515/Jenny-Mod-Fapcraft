package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.GuiHandler
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER request to open a girl's interaction GUI (id 0).
 * Sent when the player interacts with a girl (e.g. Jenny).
 *
 * **Handler.** SERVER-side. Finds the girl by UUID in the global girl list and
 * opens GUI id 0 at her position for the requesting player
 * ([GuiHandler.openGui]). Id 0 maps to the girl equipment menu.
 */
class PlayerActionPacket(@JvmField val girlUUID: UUID, @JvmField val playerUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PlayerActionPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(playerUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<PlayerActionPacket>(Main.rl("player_action"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, PlayerActionPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> PlayerActionPacket(UUID.fromString(buf.readUtf()), UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: PlayerActionPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                return
            }
            for (girl in ArrayList(BaseGirlEntity.getGirlEntityList())) {
                if (!girl.level().isClientSide && girl.getGirlId() == packet.girlUUID) {
                    val player = girl.level().getPlayerByUUID(packet.playerUUID) as ServerPlayer? ?: continue
                    val pos = girl.blockPosition()
                    GuiHandler.openGui(player, 0, girl.level(), pos.x, pos.y, pos.z)
                }
            }
        }
    }
}
