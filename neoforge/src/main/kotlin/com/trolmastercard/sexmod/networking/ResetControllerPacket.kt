package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * **Role.** Two-way animation-controller reset for a girl.
 *
 * CLIENT->SERVER: the interacting player leaves a scene / disconnects; the
 * SERVER-side handler resets the girl's action tick counters and forwards the
 * packet to every *other* player within 100 blocks so their client-side
 * animation controller is reset too (the girl's client animation must restart
 * identically for all observers).
 *
 * CLIENT-side handler: calls `BaseGirlEntity.resetAnimationControllerTicks()`
 * on the local client entity to restart her animation cycle.
 */
class ResetControllerPacket(@JvmField val girlUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ResetControllerPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ResetControllerPacket>(Main.rl("reset_controller"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ResetControllerPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> ResetControllerPacket(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: ResetControllerPacket, ctx: IPayloadContext) {
            if (ctx.flow().isServerbound) {
                val serverGirl = BaseGirlEntity.getServerGirlEntity(packet.girlUUID) ?: return

                val playerUuid = ctx.player().uuid
                serverGirl.getCurrentAction().ticksPlaying = intArrayOf(0, 0)

                val server = ServerLifecycleHooks.getCurrentServer() ?: return
                for (otherPlayer in server.playerList.players) {
                    if (playerUuid != otherPlayer.uuid && otherPlayer.distanceTo(serverGirl) < 100.0f) {
                        PacketHandler.networkWrapper.sendTo(ResetControllerPacket(packet.girlUUID), otherPlayer)
                    }
                }
            } else {
                val clientGirl = BaseGirlEntity.getClientGirlEntity(packet.girlUUID)
                clientGirl?.resetAnimationControllerTicks()
            }
        }
    }
}
