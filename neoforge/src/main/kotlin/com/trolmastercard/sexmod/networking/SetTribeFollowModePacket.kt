package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.KoboldManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER toggle of the tribe "follow/alerted" mode from the
 * dragon-staff UI.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Resolves the
 * sender's tribe and flips [KoboldManager.setTribeFollowMode]; the flag is read by
 * [KoboldManager.isTribeAlerted] and the kobold AI (follow the master vs.
 * idle). No tribe -> no-op.
 */
class SetTribeFollowModePacket(@JvmField val followMode: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SetTribeFollowModePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(this.followMode)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetTribeFollowModePacket>(Main.rl("set_tribe_follow_mode"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetTribeFollowModePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SetTribeFollowModePacket(buf.readBoolean()) },
        )

        @JvmStatic
        fun handle(packet: SetTribeFollowModePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @SetTribeFollowMode :(")
                return
            }

            val player = ctx.player() as ServerPlayer
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
            if (tribeUuid != null) {
                KoboldManager.setTribeFollowMode(tribeUuid, packet.followMode)
            }
        }
    }
}
