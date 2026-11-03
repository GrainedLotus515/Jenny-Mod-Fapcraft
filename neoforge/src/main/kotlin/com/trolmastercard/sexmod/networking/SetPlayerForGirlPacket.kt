package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.JennyEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER "start a scene with this girl" request. Sent from
 * the interaction menu when the player picks an action; binds the player to the
 * girl as her interaction partner.
 *
 * **Handler.** SERVER-side. For Jenny additionally sets her `snapInPending`
 * flag (scene-ready). Sets the girl's interaction-player UUID — the
 * data-manager sync then makes the girl follow that player; the actual scene
 * entry is triggered by the follow-up packets
 * ([KoboldStatePacket]/[ChangeDataParameterPacket] flow).
 */
class SetPlayerForGirlPacket(@JvmField val girlUUID: UUID, @JvmField val playerUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SetPlayerForGirlPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(playerUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetPlayerForGirlPacket>(Main.rl("set_player_for_girl"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetPlayerForGirlPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SetPlayerForGirlPacket(UUID.fromString(buf.readUtf()), UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: SetPlayerForGirlPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @SetPlayerForGirl :(")
                return
            }
            val server = ServerLifecycleHooks.getCurrentServer() ?: return
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                val playerList = server.playerList

                if (playerList.getPlayer(packet.playerUUID) == null) {
                    Main.LOGGER.warn("couldn't find player with UUID: " + packet.playerUUID)
                    Main.LOGGER.info("could only find players with thsese UUID's:")

                    for (player in playerList.players) {
                        Main.LOGGER.info(player.gameProfile.name + " " + player.uuid)
                    }
                    continue
                }

                if (girl is JennyEntity) {
                    girl.snapInPending = true
                }

                girl.setInteractionPlayerUUID(packet.playerUUID)
            }
        }
    }
}
