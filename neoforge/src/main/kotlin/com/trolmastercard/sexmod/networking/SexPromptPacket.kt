package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.GenderSwapScreen
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** Sex-prompt routing between a girl's owner (female) and the male
 * player for girl-girl pair scenes. The action name travels with the prompt so
 * the recipient's [GenderSwapScreen] can render the accept/decline buttons.
 *
 * CLIENT->SERVER: either player's answer to a prompt. SERVER-side handler
 * validates that both players exist and forwards the prompt to the *other*
 * participant — `accepted=true` is sent to the female (owner),
 * `accepted=false` to the male.
 *
 * CLIENT-side handler: feeds the button click into `GenderSwapScreen.onButtonClicked`.
 */
class SexPromptPacket(
    @JvmField val actionName: String,
    @JvmField val playerUUID: UUID,
    @JvmField val girlUUID: UUID,
    @JvmField val accepted: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SexPromptPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(actionName)
        buf.writeUtf(playerUUID.toString())
        buf.writeUtf(girlUUID.toString())
        buf.writeBoolean(accepted)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SexPromptPacket>(Main.rl("sex_prompt"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SexPromptPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SexPromptPacket(buf.readUtf(), UUID.fromString(buf.readUtf()), UUID.fromString(buf.readUtf()), buf.readBoolean()) })

        @JvmStatic
        fun handle(packet: SexPromptPacket, ctx: IPayloadContext) {
            if (ctx.flow().isClientbound) {
                Client.onPrompt(packet)
            } else {
                val world = ctx.player().level()
                val girlPlayer = world.getPlayerByUUID(packet.girlUUID)
                val player = world.getPlayerByUUID(packet.playerUUID)
                if (girlPlayer == null) {
                    Main.LOGGER.warn("Sex prompt invalid -> female player not found")
                } else if (player == null) {
                    Main.LOGGER.warn("Sex prompt invalid -> male player not found")
                } else {
                    PacketHandler.networkWrapper.sendTo(
                        SexPromptPacket(packet.actionName, packet.playerUUID, packet.girlUUID, packet.accepted),
                        (if (packet.accepted) girlPlayer else player) as ServerPlayer
                    )
                }
            }
        }
    }

    /** Client-only half (§2.3 Rule C). */
    private object Client {
        fun onPrompt(packet: SexPromptPacket) {
            GenderSwapScreen.instance?.onButtonClicked(
                GenderSwapScreen.SwapButton(packet.actionName, packet.playerUUID, packet.girlUUID, packet.accepted)
            )
        }
    }
}
