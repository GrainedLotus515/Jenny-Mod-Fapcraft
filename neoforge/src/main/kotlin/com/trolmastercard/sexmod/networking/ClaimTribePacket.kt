package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.util.KoboldManager
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER request to claim (form) a kobold tribe. Sent from
 * the dragon-staff UI when a player who found an unclaimed tribe enters a name.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. For every kobold of
 * the tribe that has no master yet it writes `MASTER` (player UUID) and the
 * tribe name (`TRIBE_NAME`) into the data manager; then it announces the tribe
 * formation in chat to all players and enables follow mode +
 * [KoboldManager.assignMaster]. `CURRENT_ACTION` of the leader
 * (parsed as [EyeAndKoboldColor]) determines the chat color.
 *
 * <b>Ordering.</b> Must be sent only for a tribe that exists on the server
 * (i.e. after the tribe was spawned and synced); the handler no-ops on unknown
 * tribes because [KoboldManager] lookups return empty data.
 */
class ClaimTribePacket(
    @JvmField val girlUUID: UUID,
    @JvmField val playerUUID: UUID,
    @JvmField val tribeName: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClaimTribePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(this.girlUUID.toString())
        buf.writeUtf(this.playerUUID.toString())
        buf.writeUtf(this.tribeName)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ClaimTribePacket>(Main.rl("claim_tribe"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ClaimTribePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> ClaimTribePacket(UUID.fromString(buf.readUtf()), UUID.fromString(buf.readUtf()), buf.readUtf()) },
        )

        @JvmStatic
        fun handle(packet: ClaimTribePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @ClaimTribe :(")
                return
            }

            val sender = ctx.player() as ServerPlayer
            val members = KoboldManager.getTribeMembersList(packet.girlUUID)
            var color: EyeAndKoboldColor? = null

            for (kobold in members) {
                if (!kobold.hasMaster()) {
                    val dataManager = kobold.getEntityData()
                    dataManager.set(BaseGirlEntity.MASTER, packet.playerUUID.toString())
                    dataManager.set(KoboldEntity.TRIBE_NAME, packet.tribeName)
                    color = EyeAndKoboldColor.valueOf(dataManager.get(KoboldEntity.CURRENT_ACTION))
                }
            }

            if (color != null) {
                val playerList = sender.server.playerList
                val playerName = sender.gameProfile.name

                for (player in playerList.players) {
                    player.sendSystemMessage(
                        Component.literal(
                            String.format("%s formed the " + color.getTextColor() + "%s " + ChatFormatting.WHITE + "Tribe", playerName, packet.tribeName),
                        ),
                    )
                }

                KoboldManager.setTribeFollowMode(packet.girlUUID, true)
                KoboldManager.assignMaster(packet.girlUUID, sender.uuid)
            }
        }
    }
}
