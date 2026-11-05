package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** Proximity chat for girls — a girl's line is broadcast to all
 * players within 40 blocks of her.
 *
 * CLIENT->SERVER: the sender is the girl's client (her speech line); the
 * SERVER-side handler re-broadcasts the message SERVER->CLIENT via
 * `sendToAllAround` centered on the girl's previous position with a 40-block
 * radius. CLIENT-side handler: prints the message into the receiving player's chat.
 *
 * **Note.** [channel] is repurposed as the [TargetPoint] dimension id
 * (`WorldUtils.getDimensionId`); keep it consistent with the dimension the girl
 * is in. [playerUUID] is the girl's UUID.
 */
class SendChatMessagePacket(
    @JvmField val message: String,
    @JvmField val channel: Int,
    @JvmField val playerUUID: UUID,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SendChatMessagePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(message, PacketHandler.MAX_STRING)
        buf.writeInt(channel)
        buf.writeUtf(playerUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SendChatMessagePacket>(Main.rl("send_chat_message"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SendChatMessagePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SendChatMessagePacket(buf.readUtf(PacketHandler.MAX_STRING), buf.readInt(), UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: SendChatMessagePacket, ctx: IPayloadContext) {
            if (ctx.flow().isClientbound) {
                ClientHooks.displayChat(packet.message)
            } else {
                val girls = BaseGirlEntity.girlList(packet.playerUUID)
                if (girls.isEmpty()) {
                    return
                }

                val pos = girls[0].getPreviousPosition()
                PacketHandler.networkWrapper.sendToAllAround(
                    SendChatMessagePacket(packet.message, packet.channel, packet.playerUUID),
                    TargetPoint(packet.channel, pos.x, pos.y, pos.z, 40.0)
                )
            }
        }
    }
}
