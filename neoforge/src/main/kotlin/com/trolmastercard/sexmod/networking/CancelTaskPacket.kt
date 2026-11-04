package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.KoboldManager
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER request to cancel a tribe mining task. Sent by the
 * dragon-staff UI when the player erases a highlighted mining target (block
 * marker) — the server responds with a [SendBlocksPacket] to un-highlight
 * the removed blocks on the client.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Resolves the
 * sender's tribe via [KoboldManager.getTribeUUID], removes the task that
 * contained `taskPos` ([KoboldManager.removeMiningTargetsFor]) and replies to
 * the sender with the removed blocks so the markers disappear
 * (`SendBlocksPacket` with `isBreaking=false`).
 */
class CancelTaskPacket(@JvmField val taskPos: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CancelTaskPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeInt(this.taskPos.x)
        buf.writeInt(this.taskPos.y)
        buf.writeInt(this.taskPos.z)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<CancelTaskPacket>(Main.rl("cancel_task"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CancelTaskPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> CancelTaskPacket(BlockPos(buf.readInt(), buf.readInt(), buf.readInt())) },
        )

        @JvmStatic
        fun handle(packet: CancelTaskPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid Message @CancelTask :(")
                return
            }

            val player = ctx.player() as ServerPlayer
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
            if (tribeUuid != null) {
                val targets = KoboldManager.removeMiningTargetsFor(tribeUuid, packet.taskPos)
                if (targets.isNotEmpty()) {
                    PacketHandler.networkWrapper.sendTo(SendBlocksPacket(targets, false), player)
                }
            }
        }
    }
}
