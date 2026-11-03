package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER owner command for a transformed player-girl —
 * starts a standing sex animation between the girl's owner and the given player.
 *
 * **Handler.** SERVER-side. Resolves the [AbstractPlayerGirlEntity] by the
 * owner's UUID (on an integrated server it re-scans the girl list because the
 * owner girl may not be the first match), then calls
 * `handleOwnerCommand(animation, playerUUID)` which runs the scene server-side.
 * Ordering: the player must be registered as interaction partner first (see
 * [SetPlayerForGirlPacket]).
 */
class StartStandingSexAnimationPacket(
    @JvmField val girlUUID: UUID,
    @JvmField val playerUUID: UUID,
    @JvmField val animation: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StartStandingSexAnimationPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(playerUUID.toString())
        buf.writeUtf(animation)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<StartStandingSexAnimationPacket>(Main.rl("start_standing_sex_animation"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StartStandingSexAnimationPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> StartStandingSexAnimationPacket(UUID.fromString(buf.readUtf()), UUID.fromString(buf.readUtf()), buf.readUtf()) })

        @JvmStatic
        fun handle(packet: StartStandingSexAnimationPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @StartStandingSexAnimation :(")
                return
            }
            var playerGirl: AbstractPlayerGirlEntity = AbstractPlayerGirlEntity.getPlayerGirlByUUID(packet.girlUUID) ?: return
            val server = ServerLifecycleHooks.getCurrentServer()
            if (server != null && !server.isDedicatedServer) {
                try {
                    for (girl in BaseGirlEntity.getGirlEntityList()) {
                        if (girl is AbstractPlayerGirlEntity) {
                            playerGirl = girl
                            if (!playerGirl.level().isClientSide && playerGirl.getOwnerUserUUID() == packet.girlUUID) {
                                break
                            }
                        }
                    }
                } catch (exception: Exception) {
                    // ignored: entity list mutated during iteration; retried on the next tick
                }
            }

            playerGirl.handleOwnerCommand(packet.animation, packet.playerUUID)
        }
    }
}
