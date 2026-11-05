package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.GuiHandler
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BeeEntity
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER request to open the bee-girl's chest GUI. Sent when
 * the player clicks "open chest" on a horny [BeeEntity].
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Looks up the girl by
 * UUID in [BaseGirlEntity.girlList]; only if she is a [BeeEntity] with
 * the `HORNY_FLAG` data-manager flag set, opens GUI id 1 at her position for
 * the requesting player (the bee's inventory screen). If the flag is not set the
 * request is silently dropped — the horny flag gates access to the chest.
 */
class BeeOpenChestPacket(@JvmField val girlUUID: UUID, @JvmField val playerUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BeeOpenChestPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(playerUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<BeeOpenChestPacket>(Main.rl("bee_open_chest"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, BeeOpenChestPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> BeeOpenChestPacket(UUID.fromString(buf.readUtf()), UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: BeeOpenChestPacket, ctx: IPayloadContext) {
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (!girl.level().isClientSide && girl is BeeEntity) {
                    if (girl.getEntityData().get(BeeEntity.HORNY_FLAG)) {
                        val player = girl.level().getPlayerByUUID(packet.playerUUID) as ServerPlayer?
                        if (player != null) {
                            val pos = girl.blockPosition()
                            GuiHandler.openGui(player, 1, girl.level(), pos.x, pos.y, pos.z)
                            return
                        }
                    }
                }
            }
        }
    }
}
