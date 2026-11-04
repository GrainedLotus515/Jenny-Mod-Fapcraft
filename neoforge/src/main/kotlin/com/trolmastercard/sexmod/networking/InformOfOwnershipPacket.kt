package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.GirlSavedData
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** SERVER->CLIENT ownership-status notification for Galath/Manglelie.
 * Sent whenever the server-side ownership state of the player changes (owner
 * granted, removed, girl despawned, dimension change) so the client can mirror
 * it in [GirlSavedData.debugEnabled] — which gates the Galath coin UI and
 * "already owned" logic.
 *
 * **Handler.** CLIENT-side; writes [GirlSavedData.debugEnabled] directly
 * (static field, client thread).
 */
class InformOfOwnershipPacket(@JvmField val isOwned: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<InformOfOwnershipPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(this.isOwned)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<InformOfOwnershipPacket>(Main.rl("inform_of_ownership"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InformOfOwnershipPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> InformOfOwnershipPacket(buf.readBoolean()) })

        @JvmStatic
        fun handle(packet: InformOfOwnershipPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @InformOfOwnership :(")
                return
            }

            GirlSavedData.debugEnabled = packet.isOwned
        }
    }
}
