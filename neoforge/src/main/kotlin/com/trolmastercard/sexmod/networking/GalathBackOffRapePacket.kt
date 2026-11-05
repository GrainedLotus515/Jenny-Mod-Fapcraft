package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** CLIENT->SERVER input for the Galath rape scene — the player pressed
 * the "back off" key during the pounce/dash phase.
 *
 * **Handler.** SERVER-side, on the main thread. Resolves the girl
 * currently in a scene with the sending player
 * ([BaseGirlEntity.getGirlByUUID] (playerId, true)) and, if she is a
 * [GalathEntity], calls `handleRapeState()` to abort the pounce and
 * transition the scene state. No girl in scene -> no-op.
 */
class GalathBackOffRapePacket() : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<GalathBackOffRapePacket> = TYPE

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<GalathBackOffRapePacket>(Main.rl("galath_back_off_rape"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, GalathBackOffRapePacket> = StreamCodec.of(
            { _, _ -> },
            { GalathBackOffRapePacket() })

        @JvmStatic
        fun handle(packet: GalathBackOffRapePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid Message @GalathBackOffRape :(")
                return
            }

            val girl = BaseGirlEntity.getGirlByUUID(ctx.player().uuid, true)
            if (girl is GalathEntity) {
                girl.handleRapeState()
            }
        }
    }
}
