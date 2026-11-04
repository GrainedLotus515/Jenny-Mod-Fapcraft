package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.GirlSavedData
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** CLIENT->SERVER request to ride the player's owned Galath
 * (controlled flight).
 *
 * **Handler.** SERVER-side. Looks up the girl owned by the sender
 * ([GirlSavedData.getOwnerOf]) and, if she exists, mounts the
 * player on her, switches her to [Action.CONTROLLED_FLIGHT], sets the
 * interaction player and gives her an upward velocity.
 *
 * **Pitfall — side effects.** In 1.12 the girl was removed from her chunk
 * (`world.getChunk(...).removeEntity(girl)`) so she was not ticked/render-
 * synced as a normal entity while being ridden; the riding player was the
 * authority for her position. There is no reverse packet — dismounting
 * restored her via the normal entity/chunk re-add path.
 */
class RequestRidingPacket() : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RequestRidingPacket> = TYPE

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<RequestRidingPacket>(Main.rl("request_riding"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, RequestRidingPacket> = StreamCodec.of(
            { _, _ -> },
            { RequestRidingPacket() })

        @JvmStatic
        fun handle(packet: RequestRidingPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @RequestRiding :(")
                return
            }

            val player = ctx.player() as ServerPlayer
            val ownerUuid = GirlSavedData.getOwnerOf(player)
            val girl = BaseGirlEntity.getServerGirlEntity(ownerUuid) ?: return
            player.startRiding(girl, true)
            girl.setCurrentAction(Action.CONTROLLED_FLIGHT)
            girl.setInteractionPlayer(player)
            girl.setDeltaMovement(girl.deltaMovement.x, 0.25, girl.deltaMovement.z)
            // PORT-GAP: 1.12 `world.getChunk(pos).removeEntity(girl)` has no 1.21 equivalent (chunks no longer
            // own entity lists; entity sections are managed by PersistentEntitySectionManager). The girl stays
            // tracked normally while ridden.
        }
    }
}
