package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.HornyMeterHud
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** SERVER->CLIENT movement lock while a girl scene is active. The
 * server freezes the player (velocity zeroed, input locked via
 * [HandlePlayerMovement.setMovementLock]) so the player cannot walk away
 * mid-scene; on scene end the server sends the unlock state.
 *
 * **Handler.** CLIENT-side. Locks/unlocks movement and zeroes the local
 * player's velocity; when [isSprinting] is true also hides the horny meter HUD
 * ([HornyMeterHud.hideHornyMeter]).
 */
class SetPlayerMovementPacket(@JvmField val isSprinting: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SetPlayerMovementPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(isSprinting)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetPlayerMovementPacket>(Main.rl("set_player_movement"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetPlayerMovementPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SetPlayerMovementPacket(buf.readBoolean()) })

        /** Client-bound only (§2.3 Rule B: static client calls with common signatures). */
        @JvmStatic
        fun handle(packet: SetPlayerMovementPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @SetPlayerMovement :(")
                return
            }
            HandlePlayerMovement.setMovementLock(packet.isSprinting)

            try {
                ClientHooks.localPlayer()?.setDeltaMovement(0.0, 0.0, 0.0)
            } catch (exception: Exception) {
                // ignored: the client player can be gone by the time the packet lands
            }

            if (packet.isSprinting) {
                HornyMeterHud.hideHornyMeter()
            }
        }
    }
}
