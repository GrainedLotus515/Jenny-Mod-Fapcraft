package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** SERVER->CLIENT forced sync of a transformed player-girl's
 * appearance. Sent by the server when the girl's outfit/action changed in a way
 * the data-manager sync alone would not cover (e.g. after re-login or a forced
 * model swap).
 *
 * **Handler.** CLIENT-side; it only touches the entity's synced data. Looks up
 * the [AbstractPlayerGirlEntity] by UUID and writes `CUR_ACTION` and
 * `OUTFIT_INDEX`, which drive her model/action on the render side.
 */
class ForcePlayerGirlUpdatePacket(
    @JvmField val girlUUID: UUID,
    @JvmField val modelVersion: Int,
    @JvmField val action: Action,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ForcePlayerGirlUpdatePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeInt(modelVersion)
        buf.writeUtf(action.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ForcePlayerGirlUpdatePacket>(Main.rl("force_player_girl_update"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ForcePlayerGirlUpdatePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> ForcePlayerGirlUpdatePacket(UUID.fromString(buf.readUtf()), buf.readInt(), Action.valueOf(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: ForcePlayerGirlUpdatePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @ForcePlayerGirlUpdate :(")
                return
            }
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(packet.girlUUID) ?: return

            playerGirl.getEntityData().set(BaseGirlEntity.CUR_ACTION, packet.action.toString())
            playerGirl.getEntityData().set(BaseGirlEntity.OUTFIT_INDEX, packet.modelVersion)
        }
    }
}
