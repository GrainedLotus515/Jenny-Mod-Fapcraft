package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** CLIENT->SERVER input for the Galath rape scene — the player chose
 * to pounce (or not) when prompted by `handleRapeState`.
 *
 * **Handler.** SERVER-side, on the main thread. Resolves the active
 * scene girl for the sender via
 * [BaseGirlEntity.getActiveSceneInfo] (playerId) and calls
 * [GalathEntity.handleRapeAction] with the pounce decision; the
 * girl picks the corresponding scene branch server-side. Ordering constraint:
 * only valid while a Galath rape scene is active for that player.
 */
class GalathRapePouncePacket(@JvmField val isPounce: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<GalathRapePouncePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(this.isPounce)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<GalathRapePouncePacket>(Main.rl("galath_rape_pounce"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, GalathRapePouncePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> GalathRapePouncePacket(buf.readBoolean()) })

        @JvmStatic
        fun handle(packet: GalathRapePouncePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @GalathRapePounce :(")
                return
            }

            val girl = BaseGirlEntity.getActiveSceneInfo(ctx.player().uuid)
            if (girl is GalathEntity) {
                girl.handleRapeAction(packet.isPounce)
            }
        }
    }
}
